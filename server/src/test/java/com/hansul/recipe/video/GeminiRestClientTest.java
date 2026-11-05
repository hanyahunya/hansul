package com.hansul.recipe.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hansul.recipe.video.GeminiClient.Media;
import com.hansul.recipe.video.GeminiClient.Request;
import com.hansul.recipe.video.GeminiClient.UploadedFile;
import com.hansul.recipe.video.GeminiException.Kind;
import com.hansul.recipe.video.StubHttpServer.Reply;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class GeminiRestClientTest {

    private static final String GENERATE_PATH = "/v1beta/models/gemini-test:generateContent";
    private static final Map<String, Object> SCHEMA = VideoJson.objectSchema("ok", Map.of("type", "boolean"));

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final StubHttpServer server = StubHttpServer.start();
    private final List<Duration> sleeps = new ArrayList<>();

    @TempDir
    private Path tempDir;

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void sendsTheVideoBeforeTheTaskWithTheSchemaAndConfiguredTuning() {
        server.on("POST", GENERATE_PATH, Reply.json(200, """
                {"candidates": [{"content": {"role": "model", "parts": [
                    {"text": "먼저 영상을 살펴보면", "thought": true},
                    {"text": "```json\\n{\\"ok\\": "},
                    {"text": "true}\\n```"}
                  ]}, "finishReason": "STOP"}],
                 "usageMetadata": {"promptTokenCount": 1200, "candidatesTokenCount": 300, "thoughtsTokenCount": 50}}
                """));

        var result = client("low", "low").generateContent(new Request(
                "전사 지시", Media.youTube(VideoFixtures.WATCH_URL), "받아 적어 주세요.", SCHEMA, 32768));

        assertThat(result.json().path("ok").booleanValue()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(1200);
        assertThat(result.outputTokens()).isEqualTo(350);

        var request = server.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(GENERATE_PATH);
        assertThat(request.header("x-goog-api-key")).isEqualTo("test-key");
        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        var body = mapper.readTree(request.bodyText());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").stringValue())
                .isEqualTo("전사 지시");
        var content = body.path("contents").get(0);
        assertThat(content.path("role").stringValue()).isEqualTo("user");
        var fileData = content.path("parts").get(0).path("fileData");
        assertThat(fileData.path("fileUri").stringValue()).isEqualTo(VideoFixtures.WATCH_URL);
        assertThat(fileData.has("mimeType")).isFalse();
        assertThat(content.path("parts").get(1).path("text").stringValue()).isEqualTo("받아 적어 주세요.");
        var config = body.path("generationConfig");
        assertThat(config.path("responseMimeType").stringValue()).isEqualTo("application/json");
        assertThat(config.path("responseJsonSchema")).isEqualTo(mapper.valueToTree(SCHEMA));
        assertThat(config.path("maxOutputTokens").intValue()).isEqualTo(32768);
        assertThat(config.path("thinkingConfig").path("thinkingLevel").stringValue()).isEqualTo("LOW");
        assertThat(config.path("mediaResolution").stringValue()).isEqualTo("MEDIA_RESOLUTION_LOW");
    }

    @Test
    void leavesOutUnconfiguredTuningAndNamesTheMimeTypeOfUploadedFiles() {
        server.on("POST", GENERATE_PATH, Reply.json(200, answer("{\"ok\": true}", "STOP")));
        var client = client("", "");

        client.generateContent(new Request("지시", new Media("https://example.test/files/abc", "video/mp4"),
                "작업", SCHEMA, 16384));
        client.generateContent(new Request("지시", null, "{\"segments\": []}", SCHEMA, 16384));

        var withFile = mapper.readTree(server.requests().get(0).bodyText());
        assertThat(withFile.path("contents").get(0).path("parts").get(0).path("fileData").path("mimeType")
                .stringValue()).isEqualTo("video/mp4");
        assertThat(withFile.path("generationConfig").has("thinkingConfig")).isFalse();
        assertThat(withFile.path("generationConfig").has("mediaResolution")).isFalse();
        var textOnly = mapper.readTree(server.requests().get(1).bodyText());
        assertThat(textOnly.path("contents").get(0).path("parts")).hasSize(1);
        assertThat(textOnly.path("contents").get(0).path("parts").get(0).path("text").stringValue())
                .isEqualTo("{\"segments\": []}");
    }

    @ParameterizedTest
    @CsvSource({
            "MAX_TOKENS, TRUNCATED",
            "RECITATION, RECITATION",
            "SAFETY, BLOCKED",
            "PROHIBITED_CONTENT, BLOCKED",
            "BLOCKLIST, BLOCKED",
            "SPII, BLOCKED",
            "IMAGE_SAFETY, BLOCKED",
            "LANGUAGE, MALFORMED",
            "OTHER, MALFORMED"
    })
    void mapsFinishReasonsAndKeepsTheBilledTokens(String finishReason, Kind expected) {
        server.on("POST", GENERATE_PATH, Reply.json(200, answer("{\"ok\": true}", finishReason)));

        assertThatThrownBy(() -> generate(client("", "")))
                .isInstanceOfSatisfying(GeminiException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(expected);
                    assertThat(exception.inputTokens()).isEqualTo(10);
                    assertThat(exception.outputTokens()).isEqualTo(5);
                });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void blockedPromptsMissingCandidatesAndNonObjectOutputAreFailures() {
        server.on("POST", GENERATE_PATH,
                Reply.json(200, "{\"promptFeedback\": {\"blockReason\": \"SAFETY\"}}"),
                Reply.json(200, "{\"candidates\": []}"),
                Reply.json(200, answer("양파를 채 썰어 주세요", "STOP")),
                Reply.json(200, answer("[1, 2]", "STOP")),
                Reply.json(200, "not json"));
        var client = client("", "");

        assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.BLOCKED);
        assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.MALFORMED);
        assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.MALFORMED);
        assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.MALFORMED);
        assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.MALFORMED);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "429 | {\"error\": {\"code\": 429, \"message\": \"quota\", \"status\": \"RESOURCE_EXHAUSTED\"}} | QUOTA",
            "400 | {\"error\": {\"code\": 400, \"message\": \"quota\", \"status\": \"RESOURCE_EXHAUSTED\"}} | QUOTA",
            "400 | {\"error\": {\"code\": 400, \"message\": \"bad video\", \"status\": \"INVALID_ARGUMENT\"}} | INVALID_ARGUMENT",
            "400 | {\"error\": {\"code\": 400, \"status\": \"INVALID_ARGUMENT\", \"details\": [{\"reason\": \"API_KEY_INVALID\"}]}} | CONFIGURATION",
            "400 | {\"error\": {\"code\": 400, \"status\": \"FAILED_PRECONDITION\"}} | CONFIGURATION",
            "401 | {\"error\": {\"code\": 401, \"status\": \"UNAUTHENTICATED\"}} | CONFIGURATION",
            "403 | {\"error\": {\"code\": 403, \"status\": \"PERMISSION_DENIED\"}} | CONFIGURATION",
            "404 | {\"error\": {\"code\": 404, \"status\": \"NOT_FOUND\"}} | CONFIGURATION"
    })
    void mapsHttpErrorsWithoutRetryingThem(int status, String body, Kind expected) {
        server.on("POST", GENERATE_PATH, Reply.json(status, body));

        assertThatThrownBy(() -> generate(client("", "")))
                .isInstanceOfSatisfying(GeminiException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(expected);
                    assertThat(exception.getMessage()).startsWith("Gemini returned HTTP " + status);
                });
        assertThat(server.requests()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesAServerErrorOnceAfterThreeSeconds() {
        server.on("POST", GENERATE_PATH,
                Reply.json(503, "{\"error\": {\"code\": 503, \"status\": \"UNAVAILABLE\"}}"),
                Reply.json(200, answer("{\"ok\": true}", "STOP")));

        var result = generate(client("", ""));

        assertThat(result.json().path("ok").booleanValue()).isTrue();
        assertThat(server.requests()).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void givesUpAfterTheSecondServerError() {
        server.on("POST", GENERATE_PATH, Reply.json(500, "{\"error\": {\"code\": 500, \"status\": \"INTERNAL\"}}"));

        assertThatThrownBy(() -> generate(client("", "")))
                .hasFieldOrPropertyWithValue("kind", Kind.TRANSIENT);
        assertThat(server.requests()).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void retriesADroppedConnectionOnce() {
        server.on("POST", GENERATE_PATH, Reply.dropConnection(), Reply.json(200, answer("{\"ok\": true}", "STOP")));

        assertThat(generate(client("", "")).json().path("ok").booleanValue()).isTrue();
        assertThat(server.requests()).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void doesNotRetryARequestThatTimedOut() {
        server.on("POST", GENERATE_PATH,
                Reply.json(200, answer("{\"ok\": true}", "STOP")).delayedBy(Duration.ofSeconds(3)));
        var settings = new VideoImportProperties.Gemini(
                server.baseUrl(), "test-key", "gemini-test", "", "", 0, 0, Duration.ofMillis(300), false);
        var client = new GeminiRestClient(mapper, HttpClient.newHttpClient(), settings, sleeps::add);

        assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.TRANSIENT);
        assertThat(server.requests()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void refusesToCallGeminiWithoutKeyOrValidModel() {
        for (var settings : List.of(
                new VideoImportProperties.Gemini(server.baseUrl(), "", "gemini-test", "", "", 0, 0, null, false),
                new VideoImportProperties.Gemini(server.baseUrl(), "key", "models/x?y", "", "", 0, 0, null, false))) {
            var client = new GeminiRestClient(mapper, HttpClient.newHttpClient(), settings, sleeps::add);

            assertThatThrownBy(() -> generate(client)).hasFieldOrPropertyWithValue("kind", Kind.CONFIGURATION);
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void uploadsThroughAResumableSessionAndPollsUntilTheFileIsActive() throws Exception {
        var video = tempDir.resolve("video.mp4");
        var bytes = new byte[200_000];
        for (var index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        Files.write(video, bytes);
        server.on("POST", "/upload/v1beta/files", Reply.json(200, "{}")
                        .withHeader("X-Goog-Upload-URL", server.baseUrl() + "/upload-session?upload_id=42"))
                .on("POST", "/upload-session", Reply.json(200, """
                        {"file": {"name": "files/abc123", "uri": "https://generativelanguage.googleapis.com/v1beta/files/abc123",
                                  "mimeType": "video/mp4", "state": "PROCESSING"}}"""))
                .on("GET", "/v1beta/files/abc123",
                        Reply.json(200, fileJson("PROCESSING")),
                        Reply.json(200, fileJson("ACTIVE")));
        var client = client("", "");

        var uploaded = client.uploadFile(video, "video/mp4", "hansul-upload-0b9c5ae4-1f0f-4c39-9d9c-0f7d2b2a1e10");
        var active = client.awaitActive(uploaded);

        assertThat(uploaded.state()).isEqualTo("PROCESSING");
        assertThat(active).isEqualTo(new UploadedFile("files/abc123",
                "https://generativelanguage.googleapis.com/v1beta/files/abc123", "video/mp4", "ACTIVE"));

        var start = server.requests("POST", "/upload/v1beta/files").getFirst();
        assertThat(start.header("x-goog-api-key")).isEqualTo("test-key");
        assertThat(start.header("X-Goog-Upload-Protocol")).isEqualTo("resumable");
        assertThat(start.header("X-Goog-Upload-Command")).isEqualTo("start");
        assertThat(start.header("X-Goog-Upload-Header-Content-Length")).isEqualTo("200000");
        assertThat(start.header("X-Goog-Upload-Header-Content-Type")).isEqualTo("video/mp4");
        assertThat(start.header("Content-Type")).isEqualTo("application/json");
        assertThat(mapper.readTree(start.bodyText()).path("file").path("display_name").stringValue())
                .isEqualTo("hansul-upload-0b9c5ae4-1f0f-4c39-9d9c-0f7d2b2a1e10");

        var upload = server.requests("POST", "/upload-session").getFirst();
        assertThat(upload.query()).isEqualTo("upload_id=42");
        assertThat(upload.header("x-goog-api-key")).isEqualTo("test-key");
        assertThat(upload.header("X-Goog-Upload-Offset")).isEqualTo("0");
        assertThat(upload.header("X-Goog-Upload-Command")).isEqualTo("upload, finalize");
        assertThat(upload.body()).isEqualTo(bytes);

        var polls = server.requests("GET", "/v1beta/files/abc123");
        assertThat(polls).hasSize(2).allSatisfy(poll -> assertThat(poll.header("x-goog-api-key")).isEqualTo("test-key"));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @Test
    void readsTheLengthGeminiMeasuredForAnUploadedVideo() {
        server.on("GET", "/v1beta/files/abc123", Reply.json(200, """
                {"name": "files/abc123", "uri": "https://generativelanguage.googleapis.com/v1beta/files/abc123",
                 "mimeType": "video/mp4", "state": "ACTIVE", "videoMetadata": {"videoDuration": "753.400s"}}"""));
        server.on("GET", "/v1beta/files/odd", Reply.json(200, """
                {"name": "files/odd", "uri": "u", "state": "ACTIVE", "videoMetadata": {"videoDuration": "soon"}}"""));
        var client = client("", "");

        var measured = client.awaitActive(new UploadedFile("files/abc123", "u", "video/mp4", "PROCESSING"));
        var unreadable = client.awaitActive(new UploadedFile("files/odd", "u", "video/mp4", "PROCESSING"));

        assertThat(measured.durationSeconds()).isEqualTo(754);
        assertThat(unreadable.durationSeconds()).isNull();
        assertThat(new UploadedFile("files/x", "u", "video/mp4", "ACTIVE").durationSeconds()).isNull();
    }

    @Test
    void aFailedFileAndAFileThatNeverFinishesAreFailures() {
        server.on("GET", "/v1beta/files/failed", Reply.json(200, "{\"name\": \"files/failed\", \"uri\": \"u\", \"state\": \"FAILED\"}"))
                .on("GET", "/v1beta/files/slow", Reply.json(200, "{\"name\": \"files/slow\", \"uri\": \"u\", \"state\": \"PROCESSING\"}"));
        var client = client("", "");

        assertThatThrownBy(() -> client.awaitActive(new UploadedFile("files/failed", "u", "video/mp4", "PROCESSING")))
                .hasFieldOrPropertyWithValue("kind", Kind.FILE_PROCESSING_FAILED);
        sleeps.clear();
        assertThatThrownBy(() -> client.awaitActive(new UploadedFile("files/slow", "u", "video/mp4", "PROCESSING")))
                .hasFieldOrPropertyWithValue("kind", Kind.TRANSIENT);
        assertThat(server.requests("GET", "/v1beta/files/slow")).hasSize(100);
        assertThat(sleeps.stream().reduce(Duration.ZERO, Duration::plus)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void neverSendsTheKeyToAnUploadUrlOutsideTheApiHost() throws Exception {
        var video = Files.write(tempDir.resolve("video.mp4"), new byte[] {1, 2, 3});
        server.on("POST", "/upload/v1beta/files",
                Reply.json(200, "{}").withHeader("X-Goog-Upload-URL", "https://upload.example.com/session"));

        assertThatThrownBy(() -> client("", "").uploadFile(video, "video/mp4", "hansul-upload-x"))
                .hasFieldOrPropertyWithValue("kind", Kind.MALFORMED);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void deletesFilesBestEffort() {
        server.on("DELETE", "/v1beta/files/abc123", Reply.status(500));
        var client = client("", "");

        client.deleteFile("files/abc123");
        client.deleteFile("../models/gemini-test");
        client.deleteFile(null);

        assertThat(server.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/v1beta/files/abc123");
            assertThat(request.header("x-goog-api-key")).isEqualTo("test-key");
        });
    }

    private GeminiRestClient client(String thinkingLevel, String mediaResolution) {
        var settings = new VideoImportProperties.Gemini(server.baseUrl(), "test-key", "gemini-test", thinkingLevel,
                mediaResolution, 0, 0, Duration.ofSeconds(5), false);
        return new GeminiRestClient(mapper, HttpClient.newHttpClient(), settings, sleeps::add);
    }

    private static GeminiClient.Result generate(GeminiRestClient client) {
        return client.generateContent(new Request("지시", null, "작업", SCHEMA, 1024));
    }

    private String answer(String text, String finishReason) {
        return mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", text))),
                        "finishReason", finishReason)),
                "usageMetadata", Map.of("promptTokenCount", 10, "candidatesTokenCount", 5)));
    }

    private static String fileJson(String state) {
        return """
                {"name": "files/abc123", "uri": "https://generativelanguage.googleapis.com/v1beta/files/abc123",
                 "mimeType": "video/mp4", "state": "%s"}""".formatted(state);
    }
}
