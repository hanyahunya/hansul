package com.hansul.recipe.infrastructure.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class YouTubeUrlPolicy {

    public static final String PROVIDER = "youtube";
    private static final String SHORT_LINK_HOST = "youtu.be";
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            SHORT_LINK_HOST,
            "www.youtube-nocookie.com"
    );
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern WATCH_PATH = Pattern.compile("^/watch/?$");
    private static final Pattern VIDEO_PATH = Pattern.compile("^/(shorts|live|embed)/([^/]*)/?$");
    private static final Pattern SHORT_LINK_PATH = Pattern.compile("^/([^/]*)/?$");
    // Playlist and channel-live players use these 11-character words in place of a video id; no URL shape may
    // pass them off as a video.
    private static final Set<String> RESERVED_IDS = Set.of("videoseries", "live_stream");

    public CanonicalRecipeUrl canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRecipeUrlException("Video URL is required");
        }

        final URI input;
        try {
            input = new URI(rawUrl.trim());
        } catch (URISyntaxException exception) {
            throw new InvalidRecipeUrlException("Video URL is malformed");
        }

        var scheme = input.getScheme();
        var host = input.getHost();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidRecipeUrlException("Only HTTPS video URLs are supported");
        }
        if (!isYouTubeHost(host)) {
            throw new InvalidRecipeUrlException("Only YouTube video URLs are supported");
        }
        if (input.getUserInfo() != null || input.getPort() != -1) {
            throw new InvalidRecipeUrlException("User information and explicit ports are not allowed");
        }

        var videoId = videoIdFromPath(host.toLowerCase(Locale.ROOT), input);
        if (!isValidVideoId(videoId)) {
            throw new InvalidRecipeUrlException(
                    "Expected a YouTube video URL (/watch?v=, /shorts/, /live/, /embed/ or youtu.be/)"
                            + " with an 11-character video id"
            );
        }
        return forVideoId(videoId);
    }

    public static CanonicalRecipeUrl forVideoId(String videoId) {
        return new CanonicalRecipeUrl(PROVIDER, videoId, URI.create(watchUrl(videoId)));
    }

    public static String watchUrl(String videoId) {
        if (!isValidVideoId(videoId)) {
            throw new IllegalArgumentException("Invalid YouTube video id");
        }
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    public static boolean isValidVideoId(String value) {
        return value != null && VIDEO_ID.matcher(value).matches() && !RESERVED_IDS.contains(value);
    }

    public static boolean isYouTubeHost(String host) {
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the video id of an embedded player URL such as {@code https://www.youtube.com/embed/{id}?rel=0}.
     * Only the id is kept, so http and protocol-relative embeds found in page markup are accepted too.
     */
    public static Optional<String> videoIdFromEmbed(String embedUrl) {
        if (embedUrl == null || embedUrl.isBlank()) {
            return Optional.empty();
        }
        var value = embedUrl.trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        }

        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
        var scheme = uri.getScheme();
        if (scheme == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || !isYouTubeHost(uri.getHost())
                || SHORT_LINK_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawPath() == null) {
            return Optional.empty();
        }

        var matcher = VIDEO_PATH.matcher(uri.getRawPath());
        if (!matcher.matches() || !"embed".equals(matcher.group(1))) {
            return Optional.empty();
        }
        var videoId = matcher.group(2);
        return isValidVideoId(videoId) ? Optional.of(videoId) : Optional.empty();
    }

    private static String videoIdFromPath(String host, URI input) {
        var path = input.getRawPath() == null ? "" : input.getRawPath();
        if (SHORT_LINK_HOST.equals(host)) {
            var matcher = SHORT_LINK_PATH.matcher(path);
            return matcher.matches() ? matcher.group(1) : null;
        }
        if (WATCH_PATH.matcher(path).matches()) {
            return queryParameter(input.getRawQuery(), "v");
        }
        var matcher = VIDEO_PATH.matcher(path);
        return matcher.matches() ? matcher.group(2) : null;
    }

    // Raw (still percent-encoded) values: an encoded id is rejected instead of being decoded.
    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (var pair : rawQuery.split("&")) {
            var separator = pair.indexOf('=');
            var key = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equals(key)) {
                return separator < 0 ? "" : pair.substring(separator + 1);
            }
        }
        return null;
    }
}
