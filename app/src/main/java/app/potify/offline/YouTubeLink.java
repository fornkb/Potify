package app.potify.offline;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YouTubeLink {
    private static final Set<String> HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be");
    private static final Pattern SHARED_LINK = Pattern.compile("https?://[^\\s<>]+");
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,150}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** What a pasted link points at: a video, a playlist, or (when opened from a playlist) both. */
    record Parsed(String videoId, String playlistId) {}

    /** Finds the first YouTube video or playlist link in shared text. */
    static Parsed parse(String text) {
        Matcher links = SHARED_LINK.matcher(text);
        while (links.find()) {
            String link = links.group();
            URI uri = trustedUri(link);
            if (uri == null) continue;
            String playlist = parameter(uri, "list");
            if (playlist != null && !(PLAYLIST_ID.matcher(playlist).matches() && downloadable(playlist))) {
                playlist = null;
            }
            String video = videoId(link);
            if (video != null || playlist != null) return new Parsed(video, playlist);
        }
        throw new IllegalArgumentException("Paste a YouTube video or playlist link.");
    }

    /** Mixes (RD...) are generated per viewer, and Watch later / Liked videos are private. */
    private static boolean downloadable(String playlistId) {
        return !(playlistId.startsWith("RD") || playlistId.equals("WL") || playlistId.equals("LL"));
    }

    static String videoId(String url) {
        URI uri = trustedUri(url);
        if (uri == null) return null;
        String path = uri.getPath();
        String id;
        if ("youtu.be".equalsIgnoreCase(uri.getHost()) && path.length() > 1) {
            id = path.substring(1);
        } else if ("/watch".equals(path)) {
            id = parameter(uri, "v");
        } else if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
            id = path.substring(path.indexOf('/', 1) + 1);
        } else {
            return null;
        }
        return id != null && VIDEO_ID.matcher(id).matches() ? id : null;
    }

    private static URI trustedUri(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String parameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String field : query.split("&")) {
            int separator = field.indexOf('=');
            if (separator < 0 || !name.equals(field.substring(0, separator))) continue;
            return URLDecoder.decode(field.substring(separator + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
