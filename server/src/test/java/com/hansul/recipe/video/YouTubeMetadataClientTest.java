package com.hansul.recipe.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hansul.recipe.video.StubHttpServer.Reply;
import java.net.http.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class YouTubeMetadataClientTest {

    private static final String VIDEO_ID = VideoFixtures.VIDEO_ID;
    private static final String FALLBACK_THUMBNAIL = "https://i.ytimg.com/vi/" + VIDEO_ID + "/hqdefault.jpg";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final StubHttpServer server = StubHttpServer.start();

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void readsTitleChannelDescriptionDurationAndTheBestThumbnailFromTheDataApi() {
        server.on("GET", "/youtube/v3/videos", Reply.json(200, video("public", "none", "PT12M34S", true, """
                {"default": {"url": "https://i.ytimg.com/vi/AbCdEfGhIjK/default.jpg"},
                 "high": {"url": "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg"},
                 "maxres": {"url": "https://i.ytimg.com/vi/AbCdEfGhIjK/maxresdefault.jpg"}}""")));

        var metadata = client("yt-key").fetch(VIDEO_ID);

        assertThat(metadata).isEqualTo(new VideoMetadata(
                VIDEO_ID,
                "자취생 초간단 김치볶음밥",
                "한술키친",
                "[재료] 2인분\n신김치 1컵",
                754,
                "https://i.ytimg.com/vi/AbCdEfGhIjK/maxresdefault.jpg",
                true
        ));
        var request = server.requests().getFirst();
        assertThat(request.path()).isEqualTo("/youtube/v3/videos");
        assertThat(request.query()).isEqualTo("part=snippet,contentDetails,status&id=" + VIDEO_ID + "&key=yt-key");
        assertThat(server.requests("GET", "/oembed")).isEmpty();
    }

    @Test
    void acceptsOnlyHttpsThumbnailsOnYouTubeImageHosts() {
        server.on("GET", "/youtube/v3/videos",
                Reply.json(200, video("public", "none", "PT1M", false, """
                        {"maxres": {"url": "https://evil.example/maxres.jpg"},
                         "standard": {"url": "http://i.ytimg.com/vi/AbCdEfGhIjK/sddefault.jpg"},
                         "high": {"url": "https://i9.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg"}}""")),
                Reply.json(200, video("public", "none", "PT1M", false, """
                        {"maxres": {"url": "https://ytimg.com.evil.example/maxres.jpg"},
                         "default": {"url": "https://user@i.ytimg.com/vi/AbCdEfGhIjK/default.jpg"}}""")));
        var client = client("yt-key");

        var subdomain = client.fetch(VIDEO_ID);
        var foreign = client.fetch(VIDEO_ID);

        assertThat(subdomain.thumbnailUrl()).isEqualTo("https://i9.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg");
        assertThat(subdomain.embeddable()).isFalse();
        assertThat(foreign.thumbnailUrl()).isEqualTo(FALLBACK_THUMBNAIL);
    }

    @ParameterizedTest
    @CsvSource({
            "private, none",
            "unlisted, none",
            "public, live",
            "public, upcoming"
    })
    void privateUnlistedAndLiveVideosCannotBeAnalysed(String privacy, String broadcast) {
        server.on("GET", "/youtube/v3/videos", Reply.json(200, video(privacy, broadcast, "PT10M", true, "{}")));

        assertThatThrownBy(() -> client("yt-key").fetch(VIDEO_ID))
                .isInstanceOfSatisfying(VideoImportFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("VIDEO_UNAVAILABLE"));
    }

    @Test
    void anUnknownVideoIsUnavailable() {
        server.on("GET", "/youtube/v3/videos", Reply.json(200, "{\"kind\": \"youtube#videoListResponse\", \"items\": []}"));

        assertThatThrownBy(() -> client("yt-key").fetch(VIDEO_ID))
                .isInstanceOfSatisfying(VideoImportFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("VIDEO_UNAVAILABLE"));
    }

    @Test
    void rejectsVideosOverTheLimitBeforeAnyAnalysis() {
        server.on("GET", "/youtube/v3/videos",
                Reply.json(200, video("public", "none", "PT30M", true, "{}")),
                Reply.json(200, video("public", "none", "PT30M1S", true, "{}")));
        var client = client("yt-key");

        assertThat(client.fetch(VIDEO_ID).durationSeconds()).isEqualTo(1800);
        assertThatThrownBy(() -> client.fetch(VIDEO_ID))
                .isInstanceOfSatisfying(VideoImportFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("VIDEO_TOO_LONG"));
    }

    @Test
    void anUnknownDurationDoesNotStopTheImport() {
        server.on("GET", "/youtube/v3/videos",
                Reply.json(200, video("public", "none", "P0D", true, "{}")),
                Reply.json(200, video("public", "none", "P1W", true, "{}")));
        var client = client("yt-key");

        assertThat(client.fetch(VIDEO_ID).durationSeconds()).isNull();
        assertThat(client.fetch(VIDEO_ID).durationSeconds()).isNull();
    }

    @Test
    void continuesWithMinimalMetadataWhenTheDataApiFails() {
        server.on("GET", "/youtube/v3/videos",
                Reply.json(403, "{\"error\": {\"code\": 403, \"message\": \"quotaExceeded\"}}"),
                Reply.json(500, "{}"),
                Reply.json(200, "<html>"),
                Reply.dropConnection());
        var client = client("yt-key");

        for (var attempt = 0; attempt < 4; attempt++) {
            assertThat(client.fetch(VIDEO_ID))
                    .isEqualTo(new VideoMetadata(VIDEO_ID, null, null, null, null, FALLBACK_THUMBNAIL, true));
        }
    }

    @Test
    void usesOembedWithoutAnApiKey() {
        server.on("GET", "/oembed", Reply.json(200, """
                {"title": "자취생 초간단 김치볶음밥", "author_name": "한술키친", "type": "video",
                 "thumbnail_url": "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg"}"""));

        var metadata = client("").fetch(VIDEO_ID);

        assertThat(metadata).isEqualTo(new VideoMetadata(VIDEO_ID, "자취생 초간단 김치볶음밥", "한술키친", null, null,
                "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg", true));
        assertThat(server.requests()).singleElement().satisfies(request -> assertThat(request.query())
                .isEqualTo("url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D" + VIDEO_ID + "&format=json"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void oembedRefusalMeansTheVideoMayNotBeEmbedded(int status) {
        server.on("GET", "/oembed", Reply.status(status));

        assertThat(client("").fetch(VIDEO_ID))
                .isEqualTo(new VideoMetadata(VIDEO_ID, null, null, null, null, FALLBACK_THUMBNAIL, false));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404})
    void oembedNotFoundMeansTheVideoIsUnavailable(int status) {
        server.on("GET", "/oembed", Reply.status(status));

        assertThatThrownBy(() -> client("").fetch(VIDEO_ID))
                .isInstanceOfSatisfying(VideoImportFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("VIDEO_UNAVAILABLE"));
    }

    @Test
    void oembedOutagesAndForeignThumbnailsFallBack() {
        server.on("GET", "/oembed",
                Reply.json(503, "{}"),
                Reply.json(200, "{\"title\": \"김치볶음밥\", \"thumbnail_url\": \"https://cdn.example/thumb.jpg\"}"));
        var client = client("");

        assertThat(client.fetch(VIDEO_ID))
                .isEqualTo(new VideoMetadata(VIDEO_ID, null, null, null, null, FALLBACK_THUMBNAIL, true));
        assertThat(client.fetch(VIDEO_ID).thumbnailUrl()).isEqualTo(FALLBACK_THUMBNAIL);
    }

    @Test
    void rejectsAnInvalidVideoIdBeforeAnyRequest() {
        assertThatThrownBy(() -> client("").fetch("not-an-id")).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.requests()).isEmpty();
    }

    private YouTubeMetadataClient client(String apiKey) {
        var properties = new VideoImportProperties(
                null,
                new VideoImportProperties.YouTube(server.baseUrl(), server.baseUrl(), apiKey),
                1800,
                0,
                0,
                null
        );
        return new YouTubeMetadataClient(mapper, HttpClient.newHttpClient(), properties);
    }

    private static String video(String privacy, String broadcast, String duration, boolean embeddable, String thumbnails) {
        return """
                {"items": [{"id": "AbCdEfGhIjK",
                  "snippet": {"title": "자취생 초간단 김치볶음밥", "channelTitle": "한술키친",
                              "description": "[재료] 2인분\\n신김치 1컵", "liveBroadcastContent": "%s",
                              "thumbnails": %s},
                  "contentDetails": {"duration": "%s"},
                  "status": {"privacyStatus": "%s", "embeddable": %s}}]}"""
                .formatted(broadcast, thumbnails, duration, privacy, embeddable);
    }
}
