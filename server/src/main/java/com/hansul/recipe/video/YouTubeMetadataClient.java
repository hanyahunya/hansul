package com.hansul.recipe.video;

import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Title, channel and thumbnail from the YouTube Data API when a key is configured (which also gives the
 * description, duration and visibility), otherwise from oEmbed. Only a definite "this video cannot be
 * analysed" stops the import; an unreachable YouTube just leaves the metadata minimal.
 */
@Component
public class YouTubeMetadataClient implements VideoMetadataSource {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> THUMBNAIL_SIZES = List.of("maxres", "standard", "high", "medium", "default");

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final VideoImportProperties.YouTube settings;
    private final int maxVideoSeconds;

    @Autowired
    public YouTubeMetadataClient(JsonMapper jsonMapper, VideoImportProperties properties) {
        this(jsonMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), properties);
    }

    YouTubeMetadataClient(JsonMapper jsonMapper, HttpClient httpClient, VideoImportProperties properties) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.settings = properties.youtube();
        this.maxVideoSeconds = properties.maxVideoSeconds();
    }

    @Override
    public VideoMetadata fetch(String videoId) {
        var watchUrl = YouTubeUrlPolicy.watchUrl(videoId);
        var metadata = settings.hasApiKey() ? fromDataApi(videoId) : fromOembed(videoId, watchUrl);
        if (metadata.durationSeconds() != null && metadata.durationSeconds() > maxVideoSeconds) {
            throw new VideoImportFailure(
                    VideoImportFailure.VIDEO_TOO_LONG,
                    "The video is longer than " + maxVideoSeconds + " seconds"
            );
        }
        return metadata;
    }

    /** The fallback thumbnail every public video has. */
    static String fallbackThumbnail(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    static boolean isYouTubeImage(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            var uri = new URI(url.trim());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && host.endsWith(".ytimg.com");
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private VideoMetadata fromDataApi(String videoId) {
        var response = get(URI.create(settings.apiBaseUrl()
                + "/youtube/v3/videos?part=snippet,contentDetails,status&id=" + videoId
                + "&key=" + URLEncoder.encode(settings.apiKey(), StandardCharsets.UTF_8)));
        if (response == null || response.statusCode() != 200) {
            return minimal(videoId, true);
        }
        var items = parse(response.body()).path("items");
        if (!items.isArray()) {
            return minimal(videoId, true);
        }
        if (items.isEmpty()) {
            throw unavailable("YouTube does not know this video");
        }
        var item = items.get(0);
        var snippet = item.path("snippet");
        var status = item.path("status");
        // Gemini only reads public videos.
        if (!"public".equals(VideoJson.text(status, "privacyStatus"))) {
            throw unavailable("The YouTube video is not public");
        }
        var broadcast = VideoJson.text(snippet, "liveBroadcastContent");
        if ("live".equals(broadcast) || "upcoming".equals(broadcast)) {
            throw unavailable("Live and upcoming streams cannot be analysed");
        }
        return new VideoMetadata(
                videoId,
                present(VideoJson.text(snippet, "title")),
                present(VideoJson.text(snippet, "channelTitle")),
                present(VideoJson.text(snippet, "description")),
                duration(VideoJson.text(item.path("contentDetails"), "duration")),
                thumbnail(videoId, snippet.path("thumbnails")),
                VideoJson.isTrue(status, "embeddable")
        );
    }

    private VideoMetadata fromOembed(String videoId, String watchUrl) {
        var response = get(URI.create(settings.oembedBaseUrl()
                + "/oembed?url=" + URLEncoder.encode(watchUrl, StandardCharsets.UTF_8) + "&format=json"));
        if (response == null) {
            return minimal(videoId, true);
        }
        var status = response.statusCode();
        // oEmbed answers 401 when the owner disabled embedding; the video itself can still be analysed.
        if (status == 401 || status == 403) {
            return minimal(videoId, false);
        }
        if (status == 400 || status == 404) {
            throw unavailable("YouTube does not know this video or it is private");
        }
        var root = status == 200 ? parse(response.body()) : MissingNode.getInstance();
        if (!root.isObject()) {
            return minimal(videoId, true);
        }
        var thumbnail = VideoJson.text(root, "thumbnail_url");
        return new VideoMetadata(
                videoId,
                present(VideoJson.text(root, "title")),
                present(VideoJson.text(root, "author_name")),
                null,
                null,
                isYouTubeImage(thumbnail) ? thumbnail.trim() : fallbackThumbnail(videoId),
                true
        );
    }

    // Timeouts and network errors only cost the metadata; the analysis itself can still run.
    private HttpResponse<String> get(URI uri) {
        var request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private JsonNode parse(String body) {
        try {
            return jsonMapper.readTree(body == null ? "" : body);
        } catch (JacksonException exception) {
            return MissingNode.getInstance();
        }
    }

    private static VideoMetadata minimal(String videoId, boolean embeddable) {
        return new VideoMetadata(videoId, null, null, null, null, fallbackThumbnail(videoId), embeddable);
    }

    private static String thumbnail(String videoId, JsonNode thumbnails) {
        for (var size : THUMBNAIL_SIZES) {
            var url = VideoJson.text(thumbnails.path(size), "url");
            if (isYouTubeImage(url)) {
                return url.trim();
            }
        }
        return fallbackThumbnail(videoId);
    }

    // P0D marks live or premiere placeholders: an unknown duration rather than an empty video.
    private static Integer duration(String isoDuration) {
        if (isoDuration.isBlank()) {
            return null;
        }
        try {
            var seconds = Duration.parse(isoDuration).toSeconds();
            return seconds <= 0 ? null : (int) Math.min(seconds, Integer.MAX_VALUE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String present(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static VideoImportFailure unavailable(String message) {
        return new VideoImportFailure(VideoImportFailure.VIDEO_UNAVAILABLE, message);
    }
}
