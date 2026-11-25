package app.potify.offline;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

final class YouTubeSource {
    record Entry(String id, String title, String artist, long seconds) {}
    record Playlist(String title, List<Entry> entries) {}
    record Audio(String url, String extension, int bitrate, String format, long length,
                 String title, String artist, long seconds) {}

    /** The desktop browser identity NewPipe uses for its own requests. */
    static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

    final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build();

    YouTubeSource() {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(org.schabi.newpipe.extractor.downloader.Request request)
                    throws IOException, ReCaptchaException {
                var builder = new okhttp3.Request.Builder().url(request.url());
                request.headers().forEach((name, values) ->
                        values.forEach(value -> builder.addHeader(name, value)));
                if (request.headers().keySet().stream().noneMatch("User-Agent"::equalsIgnoreCase)) {
                    builder.header("User-Agent", DESKTOP_USER_AGENT);
                }
                byte[] bytes = request.dataToSend();
                RequestBody body = bytes == null ? null : RequestBody.create(bytes, null);
                try (okhttp3.Response response = http.newCall(
                        builder.method(request.httpMethod(), body).build()).execute()) {
                    if (response.code() == 429) {
                        throw new ReCaptchaException("YouTube is limiting requests.", request.url());
                    }
                    ResponseBody responseBody = response.body();
                    return new Response(response.code(), response.message(),
                            response.headers().toMultimap(),
                            responseBody == null ? "" : responseBody.string(),
                            response.request().url().toString());
                }
            }
        });
    }

    Playlist loadPlaylist(String id, BooleanSupplier stopped) throws Exception {
        PlaylistExtractor extractor = ServiceList.YouTube.getPlaylistExtractor(
                "https://www.youtube.com/playlist?list=" + id);
        extractor.fetchPage();
        var entries = new ArrayList<Entry>();
        InfoItemsPage<StreamInfoItem> page = extractor.getInitialPage();
        while (page != null) {
            if (stopped.getAsBoolean()) throw new InterruptedIOException("Playlist paused.");
            for (InfoItem info : page.getItems()) {
                if (!(info instanceof StreamInfoItem item)) continue;
                String video = YouTubeLink.videoId(item.getUrl());
                if (video != null) entries.add(new Entry(video, item.getName(), item.getUploaderName(), item.getDuration()));
            }
            Page next = page.getNextPage();
            if (!Page.isValid(next)) break;
            page = extractor.getPage(next);
        }
        return new Playlist(extractor.getName(), entries);
    }

    /**
     * Picks the audio-only stream to download. Best quality takes the highest bitrate (usually
     * Opus ~160 kbps); data saver takes the highest bitrate at or below ~96 kbps (usually Opus ~70 kbps).
     */
    Audio audioFor(String videoId, boolean dataSaver) throws Exception {
        StreamExtractor extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=" + videoId);
        extractor.fetchPage();
        if (extractor.getStreamType() == StreamType.LIVE_STREAM) {
            throw new Unavailable("This video is live.");
        }
        List<AudioStream> streams = extractor.getAudioStreams();
        if (streams == null) throw new Unavailable("No audio file is available for this video.");
        List<AudioStream> candidates = streams.stream()
                .filter(s -> s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP)
                .filter(s -> s.getFormat() == MediaFormat.M4A || s.getFormat() == MediaFormat.WEBMA
                        || s.getFormat() == MediaFormat.WEBMA_OPUS)
                .filter(s -> s.getAudioTrackType() == null
                        || s.getAudioTrackType() == AudioTrackType.ORIGINAL)
                .collect(java.util.stream.Collectors.toList());
        Comparator<AudioStream> quality = Comparator
                .comparing((AudioStream s) -> s.getAudioTrackType() == AudioTrackType.ORIGINAL)
                .thenComparingInt(AudioStream::getAverageBitrate);
        java.util.Optional<AudioStream> chosen = candidates.stream().max(quality);
        if (dataSaver) {
            var small = candidates.stream()
                    .filter(s -> s.getAverageBitrate() > 0 && s.getAverageBitrate() <= 96).max(quality);
            chosen = small.isPresent() ? small
                    : candidates.stream().min(Comparator.comparingInt(AudioStream::getAverageBitrate));
        }
        String title = extractor.getName();
        String artist = extractor.getUploaderName();
        long seconds = extractor.getLength();
        return chosen
                .map(s -> new Audio(s.getContent(), s.getFormat().getSuffix(), s.getAverageBitrate(),
                        s.getFormat().getName(), contentLength(s), title, artist, seconds))
                .orElseThrow(() -> new Unavailable("No original audio file is available for this video."));
    }

    private static long contentLength(AudioStream stream) {
        if (stream.getItagItem() != null && stream.getItagItem().getContentLength() > 0) {
            return stream.getItagItem().getContentLength();
        }
        var clen = java.util.regex.Pattern.compile("[?&]clen=([0-9]+)").matcher(stream.getContent());
        return clen.find() ? Long.parseLong(clen.group(1)) : -1;
    }

    /** Headers YouTube's servers expect for a stream URL, matching the client it was issued to. */
    static java.util.Map<String, String> streamHeaders(String url) {
        var headers = new java.util.HashMap<String, String>();
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            headers.put("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null));
        } else {
            headers.put("User-Agent", DESKTOP_USER_AGENT);
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            headers.put("Origin", "https://www.youtube.com");
            headers.put("Referer", "https://www.youtube.com/");
            headers.put("Sec-Fetch-Dest", "empty");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Site", "cross-site");
        }
        return headers;
    }

    /** Which YouTube client issued a stream URL (its {@code c} parameter), for logging. */
    static String clientOf(String url) {
        var client = java.util.regex.Pattern.compile("[?&]c=([A-Za-z0-9_]+)").matcher(url);
        return client.find() ? client.group(1) : "?";
    }

    static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
    }
}
