package app.potify.offline;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import okhttp3.OkHttpClient;

/**
 * Works through the download queue: reads queued playlists, then downloads tracks with several
 * workers pulling from the queue, each fetching its track in parallel byte-range chunks.
 */
final class DownloadBatch {
    /** Shared with Spotify matching and previews: it also sets up NewPipe, which is global. */
    static final YouTubeSource SOURCE = new YouTubeSource();
    private static final ExecutorService BATCHES = Executors.newSingleThreadExecutor();
    /** Tracks downloading at the same time. */
    private static final int TRACK_WORKERS = 3;
    /** Chunks of one track downloading at the same time. */
    private static final int CHUNK_WORKERS = 2;

    private final Context context;
    private final Library library;
    private final IntConsumer progress;
    private final Runnable finished;
    private final AtomicInteger saved = new AtomicInteger();
    private final AtomicLong batchBytes = new AtomicLong();
    private final AtomicInteger claims = new AtomicInteger();
    private final AtomicInteger busy = new AtomicInteger();
    private final int chunkSize = ChunkedDownload.DEFAULT_CHUNK_SIZE;
    private volatile boolean stopped;
    private final int trackWorkers = TRACK_WORKERS;
    private final int chunkWorkers = CHUNK_WORKERS;
    private ExecutorService chunks;
    private OkHttpClient streamClient;

    DownloadBatch(Context context, IntConsumer progress, Runnable finished) {
        this.context = context.getApplicationContext();
        library = Library.get(context);
        this.progress = progress;
        this.finished = finished;
    }

    void start() {
        BATCHES.execute(this::run);
    }

    void stop() {
        stopped = true;
        synchronized (SOURCE) {
            if (activeBatch == this) SOURCE.http.dispatcher().cancelAll();
        }
    }

    private static DownloadBatch activeBatch;

    private void run() {
        ExecutorService workers = Executors.newFixedThreadPool(trackWorkers);
        chunks = Executors.newFixedThreadPool(trackWorkers * chunkWorkers);
        streamClient = SOURCE.http.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.MINUTES)
                .build();
        synchronized (SOURCE) {
            activeBatch = this;
        }
        long started = System.nanoTime();
        try {
            if (stopped) return;
            library.recoverInterrupted();
            while (!stopped && library.hasWork()) {
                int before = claims.get();
                var running = new ArrayList<Future<?>>(trackWorkers);
                for (int i = 0; i < trackWorkers; i++) running.add(workers.submit(this::work));
                for (Future<?> task : running) task.get();
                if (claims.get() == before) break; // Nothing claimable is left.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e("Potify", "Could not finish the download queue", e);
        } finally {
            workers.shutdownNow();
            chunks.shutdownNow();
            synchronized (SOURCE) {
                if (activeBatch == this) activeBatch = null;
            }
            double seconds = Math.max(0.001, (System.nanoTime() - started) / 1e9);
            Log.i("PotifyDownload", String.format(Locale.ROOT,
                    "batch tracks=%d workers=%dx%d chunk=%dKB total=%.2fMB time=%.1fs average=%.0fKB/s",
                    saved.get(), trackWorkers, chunkWorkers, chunkSize / 1024, batchBytes.get() / 1048576.0, seconds,
                    batchBytes.get() / 1024.0 / seconds));
            finished.run();
        }
    }

    /**
     * One worker: keeps taking the next playlist to read or track to download. When nothing is
     * queued it waits while other workers are still busy (they may queue more, e.g. after reading a
     * playlist), and stops once every worker is idle.
     */
    private void work() {
        while (!stopped) {
            busy.incrementAndGet(); // Counted as busy while claiming, so idle workers don't quit early.
            String playlist = library.claimNextPlaylist();
            Library.Track track = playlist == null ? library.claimNextTrack() : null;
            if (playlist == null && track == null) {
                if (busy.decrementAndGet() == 0) return;
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            claims.incrementAndGet();
            try {
                if (playlist != null) importPlaylist(playlist);
                else downloadTrack(track);
            } finally {
                busy.decrementAndGet();
            }
        }
    }

    private void importPlaylist(String id) {
        try {
            var playlist = SOURCE.loadPlaylist(id, () -> stopped);
            checkRunning();
            if (playlist.entries().isEmpty()) {
                throw new YouTubeSource.Unavailable("This playlist has no available videos.");
            }
            library.importPlaylist(id, playlist);
            Artwork.fetchMissing(library);
        } catch (Exception e) {
            library.playlistStatus(id, stopped ? Library.QUEUED : Library.FAILED,
                    stopped ? null : message(e, "Couldn’t read this playlist. Check that its link is accessible."));
        }
    }

    private void downloadTrack(Library.Track track) {
        try {
            String name = existingFile(track.id());
            if (name == null) {
                try {
                    name = saveAudio(track.id());
                } catch (IOException e) {
                    if (e instanceof YouTubeSource.Unavailable || e instanceof InterruptedIOException) throw e;
                    checkRunning();
                    Thread.sleep(1000);
                    name = saveAudio(track.id());
                }
            }
            library.trackStatus(track.id(), Library.SAVED, name, null);
            progress.accept(saved.incrementAndGet());
        } catch (Exception e) {
            if (!stopped) Log.w("PotifyDownload", track.id() + " failed", e);
            library.trackStatus(track.id(), stopped ? Library.QUEUED : Library.FAILED, null,
                    stopped ? null : message(e, "Audio unavailable. Try again later."));
        }
    }

    private String existingFile(String id) throws IOException {
        for (String suffix : new String[]{"m4a", "webm"}) {
            String name = id + "." + suffix;
            File file = library.audioFile(name);
            if (!file.isFile()) continue;
            if (hasAudioOnly(file)) return name;
            Files.delete(file.toPath());
        }
        return null;
    }

    private String saveAudio(String id) throws Exception {
        checkRunning();
        long started = System.nanoTime();
        boolean dataSaver = Prefs.dataSaver(context);
        YouTubeSource.Audio audio = SOURCE.audioFor(id, dataSaver);
        long extracted = System.nanoTime();
        library.fillTrackInfo(id, audio.title(), audio.artist(), audio.seconds());
        checkRunning();
        if (!audio.url().startsWith("https://")) {
            throw new YouTubeSource.Unavailable("A secure audio download isn’t available.");
        }
        // Create the folder first: Android reports 0 free bytes for a folder that doesn't exist
        // (it can disappear when it's emptied, e.g. by a phone cleaner).
        Files.createDirectories(library.audioDir.toPath());
        if (audio.length() > 0 && library.audioDir.getUsableSpace() < audio.length() + 16L * 1024 * 1024) {
            throw new YouTubeSource.Unavailable("There isn’t enough storage on this phone.");
        }
        File partial = library.audioFile(id + ".part");
        var download = new ChunkedDownload(streamClient, chunks, () -> stopped, chunkSize);
        ChunkedDownload.Progress report = (done, total) -> {
            if (total > 0) DownloadProgress.set(id, (float) done / total);
        };
        try {
            long copied;
            try {
                copied = download.fetch(audio.url(), audio.length(), YouTubeSource.streamHeaders(audio.url()),
                        partial, chunkWorkers, report);
            } catch (ChunkedDownload.Refused refused) {
                // Stream URLs expire or get refused now and then; a freshly extracted one usually works.
                checkRunning();
                audio = SOURCE.audioFor(id, dataSaver);
                Files.deleteIfExists(partial.toPath());
                try {
                    copied = download.fetch(audio.url(), audio.length(), YouTubeSource.streamHeaders(audio.url()),
                            partial, chunkWorkers, report);
                } catch (ChunkedDownload.Refused again) {
                    throw new YouTubeSource.Unavailable("YouTube didn’t make this audio available.");
                }
            }
            if (copied == 0 || (audio.length() > 0 && copied != audio.length())) {
                throw new IOException("Audio transfer ended early.");
            }
            batchBytes.addAndGet(copied);
            double seconds = Math.max(0.001, (System.nanoTime() - extracted) / 1e9);
            Log.i("PotifyDownload", String.format(Locale.ROOT,
                    "%s client=%s format=%s bitrate=%dk size=%.2fMB extract=%.2fs transfer=%.2fs speed=%.0fKB/s",
                    id, YouTubeSource.clientOf(audio.url()), audio.format(), audio.bitrate(), copied / 1048576.0,
                    (extracted - started) / 1e9, seconds, copied / 1024.0 / seconds));
            if (!hasAudioOnly(partial)) {
                throw new YouTubeSource.Unavailable("The download didn’t contain playable audio.");
            }
            checkRunning();
            String name = id + "." + audio.extension();
            Files.move(partial.toPath(), library.audioFile(name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return name;
        } finally {
            DownloadProgress.clear(id);
            Files.deleteIfExists(partial.toPath());
        }
    }

    private static boolean hasAudioOnly(File file) {
        var extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) return false;
                if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i;
            }
            if (audioTrack < 0) return false;
            extractor.selectTrack(audioTrack);
            return extractor.getSampleTime() >= 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            extractor.release();
        }
    }

    private void checkRunning() throws InterruptedIOException {
        if (stopped || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Downloads paused.");
        }
    }

    private static String message(Exception e, String fallback) {
        if (e instanceof YouTubeSource.Unavailable) return e.getMessage();
        if (e instanceof org.schabi.newpipe.extractor.exceptions.ReCaptchaException) {
            return "YouTube is limiting requests. Retry later.";
        }
        return fallback;
    }
}
