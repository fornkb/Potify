package app.potify.offline;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Downloads a YouTube audio stream the way YouTube's own player does: in byte-range chunks
 * requested with the {@code range} URL parameter, several chunks at a time. A single long request
 * gets throttled to roughly playback speed; chunked requests don't.
 */
final class ChunkedDownload {
    static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;
    private static final byte[] POST_BODY = {0x78, 0};

    interface Progress {
        void update(long done, long total);
    }

    /** Thrown when YouTube refuses the stream URL (expired or blocked); fetching a new URL may help. */
    static final class Refused extends IOException {
        final int code;

        Refused(int code) {
            super("Audio request refused (" + code + ")");
            this.code = code;
        }
    }

    private final OkHttpClient http;
    private final ExecutorService pool;
    private final BooleanSupplier stopped;
    private final AtomicInteger requestNumber = new AtomicInteger();
    private final int chunkSize;

    ChunkedDownload(OkHttpClient http, ExecutorService pool, BooleanSupplier stopped, int chunkSize) {
        this.http = http;
        this.pool = pool;
        this.stopped = stopped;
        this.chunkSize = chunkSize;
    }

    /**
     * Saves the stream at {@code url} into {@code out}. {@code length} is the total size in bytes,
     * or a value <= 0 when unknown (then chunks are fetched one after another until the end).
     * Returns the number of bytes written.
     */
    long fetch(String url, long length, Map<String, String> headers, File out, int parallel, Progress progress)
            throws IOException, InterruptedException {
        try (RandomAccessFile file = new RandomAccessFile(out, "rw"); FileChannel channel = file.getChannel()) {
            if (length <= 0) return fetchSequential(url, headers, channel, progress);
            file.setLength(length);
            int chunks = (int) ((length + chunkSize - 1) / chunkSize);
            AtomicInteger next = new AtomicInteger();
            AtomicLong done = new AtomicLong();
            int workers = Math.max(1, Math.min(parallel, chunks));
            List<Future<?>> running = new ArrayList<>(workers);
            for (int w = 0; w < workers; w++) {
                running.add(pool.submit(() -> {
                    int index;
                    while ((index = next.getAndIncrement()) < chunks) {
                        long start = (long) index * chunkSize;
                        long end = Math.min(length, start + chunkSize) - 1;
                        fetchRange(url, headers, start, end, channel, done, length, progress);
                    }
                    return null;
                }));
            }
            try {
                for (Future<?> task : running) task.get();
            } catch (ExecutionException e) {
                for (Future<?> task : running) task.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IOException(cause);
            }
            return done.get();
        }
    }

    private long fetchSequential(String url, Map<String, String> headers, FileChannel channel, Progress progress)
            throws IOException {
        AtomicLong done = new AtomicLong();
        long start = 0;
        while (true) {
            long end = start + chunkSize - 1;
            long got = fetchRange(url, headers, start, end, channel, done, -1, progress);
            if (got < chunkSize) return done.get();
            start += got;
        }
    }

    /** Fetches one chunk, retrying twice on network errors. Returns the bytes written. */
    private long fetchRange(String url, Map<String, String> headers, long start, long end, FileChannel channel,
                            AtomicLong done, long total, Progress progress) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            checkRunning();
            AtomicLong attemptBytes = new AtomicLong();
            try {
                long written = fetchOnce(url, headers, start, end, channel, done, attemptBytes, total, progress);
                if (total > 0 && written != end - start + 1) throw new IOException("Chunk ended early");
                return written;
            } catch (Refused | InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                done.addAndGet(-attemptBytes.get());
                last = e;
                try {
                    Thread.sleep(400L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Downloads paused.");
                }
            }
        }
        throw last;
    }

    private long fetchOnce(String url, Map<String, String> headers, long start, long end, FileChannel channel,
                           AtomicLong done, AtomicLong attemptBytes, long total, Progress progress)
            throws IOException {
        String ranged = url + "&range=" + start + "-" + end + "&rn=" + requestNumber.incrementAndGet();
        var builder = new Request.Builder().url(ranged).post(RequestBody.create(POST_BODY, null));
        headers.forEach(builder::header);
        builder.header("Accept-Encoding", "identity");
        try (Response response = http.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code == 401 || code == 403 || code == 410) throw new Refused(code);
            if ((code != 200 && code != 206) || response.body() == null) {
                throw new IOException("Audio request failed (" + code + ")");
            }
            long position = start;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = response.body().byteStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkRunning();
                    if (position + read > end + 1) read = (int) (end + 1 - position);
                    ByteBuffer slice = ByteBuffer.wrap(buffer, 0, read);
                    while (slice.hasRemaining()) position += channel.write(slice, position);
                    attemptBytes.addAndGet(read);
                    long all = done.addAndGet(read);
                    progress.update(all, total);
                    if (position > end) break;
                }
            }
            return position - start;
        }
    }

    private void checkRunning() throws InterruptedIOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Downloads paused.");
        }
    }
}
