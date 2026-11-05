package com.hansul.recipe.video;

import com.hansul.recipe.video.GeminiException.Kind;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini over plain HTTP: generateContent with a response JSON schema, and the resumable Files API. */
@Component
public class GeminiRestClient implements GeminiClient {

    static final Duration RETRY_DELAY = Duration.ofSeconds(3);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    static final Duration POLL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(GeminiRestClient.class);
    private static final Pattern FILE_NAME = Pattern.compile("files/[A-Za-z0-9_-]{1,128}");
    private static final Pattern ERROR_STATUS = Pattern.compile("[A-Z_]{1,40}");
    private static final Pattern FENCE_START = Pattern.compile("^```(?:json)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("([0-9]{1,12}(?:\\.[0-9]{1,9})?)s");
    private static final BigDecimal MAX_DURATION_SECONDS = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final Set<String> BLOCKED_FINISH_REASONS =
            Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY");
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(500, 502, 503, 504);

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final VideoImportProperties.Gemini settings;
    private final Sleeper sleeper;

    @Autowired
    public GeminiRestClient(JsonMapper jsonMapper, VideoImportProperties properties) {
        this(jsonMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                properties.gemini(),
                Thread::sleep);
    }

    GeminiRestClient(
            JsonMapper jsonMapper,
            HttpClient httpClient,
            VideoImportProperties.Gemini settings,
            Sleeper sleeper
    ) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public Result generateContent(Request request) {
        requireConfigured();
        var httpRequest = HttpRequest.newBuilder(uri("/v1beta/models/" + settings.model() + ":generateContent"))
                .timeout(settings.requestTimeout())
                .header("x-goog-api-key", settings.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(requestBody(request))))
                .build();
        return retryOnce(() -> generation(checked(send(httpRequest)).body()));
    }

    @Override
    public UploadedFile uploadFile(Path file, String mimeType, String displayName) {
        requireConfigured();
        final long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            throw new UncheckedIOException("The video file to upload is not readable", exception);
        }
        return retryOnce(() -> upload(startUpload(size, mimeType, displayName), file));
    }

    @Override
    public UploadedFile awaitActive(UploadedFile file) {
        requireConfigured();
        var current = file;
        var waited = Duration.ZERO;
        while (!current.isActive()) {
            if ("FAILED".equals(current.state())) {
                throw new GeminiException(Kind.FILE_PROCESSING_FAILED, "Gemini could not process the uploaded video");
            }
            if (waited.compareTo(POLL_TIMEOUT) >= 0) {
                throw new GeminiException(Kind.TRANSIENT, "Gemini did not finish processing the uploaded video in time");
            }
            pause(POLL_INTERVAL);
            waited = waited.plus(POLL_INTERVAL);
            var name = current.name();
            current = retryOnce(() -> fetchFile(name));
        }
        return current;
    }

    @Override
    public void deleteFile(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches() || !settings.isConfigured()) {
            return;
        }
        try {
            var request = HttpRequest.newBuilder(uri("/v1beta/" + name))
                    .timeout(SHORT_TIMEOUT)
                    .header("x-goog-api-key", settings.apiKey())
                    .DELETE()
                    .build();
            var status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if ((status < 200 || status >= 300) && status != 404) {
                log.warn("Gemini file {} was not deleted: HTTP {}", name, status);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Gemini file {} was not deleted: interrupted", name);
        } catch (IOException | RuntimeException exception) {
            log.warn("Gemini file {} was not deleted: {}", name, exception.getClass().getSimpleName());
        }
    }

    private Map<String, Object> requestBody(Request request) {
        var parts = new ArrayList<Map<String, Object>>();
        if (request.media() != null) {
            var fileData = new LinkedHashMap<String, Object>();
            fileData.put("fileUri", request.media().fileUri());
            if (request.media().mimeType() != null) {
                fileData.put("mimeType", request.media().mimeType());
            }
            parts.add(Map.of("fileData", fileData));
        }
        parts.add(Map.of("text", request.text()));

        var content = new LinkedHashMap<String, Object>();
        content.put("role", "user");
        content.put("parts", parts);

        var generationConfig = new LinkedHashMap<String, Object>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseJsonSchema", request.responseJsonSchema());
        generationConfig.put("maxOutputTokens", request.maxOutputTokens());
        if (!settings.thinkingLevel().isEmpty()) {
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", settings.thinkingLevel()));
        }
        if (!settings.mediaResolution().isEmpty()) {
            generationConfig.put("mediaResolution", settings.mediaResolution());
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        body.put("contents", List.of(content));
        body.put("generationConfig", generationConfig);
        return body;
    }

    private Result generation(String body) {
        var root = json(body);
        var usage = root.path("usageMetadata");
        var inputTokens = VideoJson.count(usage, "promptTokenCount");
        var outputTokens = VideoJson.count(usage, "candidatesTokenCount")
                + VideoJson.count(usage, "thoughtsTokenCount");

        var blockReason = VideoJson.text(root.path("promptFeedback"), "blockReason");
        if (!blockReason.isBlank()) {
            throw new GeminiException(Kind.BLOCKED, "Gemini blocked the prompt: " + safe(blockReason),
                    inputTokens, outputTokens);
        }
        var candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new GeminiException(Kind.MALFORMED, "Gemini returned no candidate", inputTokens, outputTokens);
        }
        var candidate = candidates.get(0);
        var finishReason = VideoJson.text(candidate, "finishReason");
        if (BLOCKED_FINISH_REASONS.contains(finishReason)) {
            throw new GeminiException(Kind.BLOCKED, "Gemini blocked the answer: " + finishReason,
                    inputTokens, outputTokens);
        }
        switch (finishReason) {
            case "", "STOP" -> {
            }
            case "MAX_TOKENS" -> throw new GeminiException(Kind.TRUNCATED, "Gemini output reached maxOutputTokens",
                    inputTokens, outputTokens);
            case "RECITATION" -> throw new GeminiException(Kind.RECITATION, "Gemini stopped the answer for recitation",
                    inputTokens, outputTokens);
            default -> throw new GeminiException(Kind.MALFORMED, "Gemini finished with " + safe(finishReason),
                    inputTokens, outputTokens);
        }

        var text = new StringBuilder();
        for (var part : candidate.path("content").path("parts")) {
            if (!VideoJson.isTrue(part, "thought")) {
                text.append(VideoJson.text(part, "text"));
            }
        }
        JsonNode output;
        try {
            output = jsonMapper.readTree(withoutFence(text.toString()));
        } catch (JacksonException exception) {
            output = null;
        }
        if (output == null || !output.isObject()) {
            throw new GeminiException(Kind.MALFORMED, "Gemini output was not a JSON object", inputTokens, outputTokens);
        }
        return new Result(output, inputTokens, outputTokens);
    }

    private URI startUpload(long size, String mimeType, String displayName) {
        var body = jsonMapper.writeValueAsString(Map.of("file", Map.of("display_name", displayName)));
        var request = HttpRequest.newBuilder(uri("/upload/v1beta/files"))
                .timeout(SHORT_TIMEOUT)
                .header("x-goog-api-key", settings.apiKey())
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", Long.toString(size))
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var uploadUrl = checked(send(request)).headers().firstValue("x-goog-upload-url")
                .orElseThrow(() -> new GeminiException(Kind.MALFORMED, "Gemini did not return an upload URL"));
        return onApiHost(uploadUrl);
    }

    private UploadedFile upload(URI uploadUri, Path file) {
        final HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.ofFile(file);
        } catch (FileNotFoundException exception) {
            throw new UncheckedIOException("The video file to upload is not readable", exception);
        }
        var request = HttpRequest.newBuilder(uploadUri)
                .timeout(settings.requestTimeout())
                .header("x-goog-api-key", settings.apiKey())
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .POST(body)
                .build();
        return uploadedFile(json(checked(send(request)).body()));
    }

    private UploadedFile fetchFile(String name) {
        if (!FILE_NAME.matcher(name).matches()) {
            throw new GeminiException(Kind.MALFORMED, "Gemini file name is not usable");
        }
        var request = HttpRequest.newBuilder(uri("/v1beta/" + name))
                .timeout(SHORT_TIMEOUT)
                .header("x-goog-api-key", settings.apiKey())
                .GET()
                .build();
        return uploadedFile(json(checked(send(request)).body()));
    }

    // The upload answer wraps the resource in "file"; a plain GET returns the resource itself.
    private UploadedFile uploadedFile(JsonNode root) {
        var file = root.path("file").isObject() ? root.path("file") : root;
        var name = VideoJson.text(file, "name");
        var fileUri = VideoJson.text(file, "uri");
        if (!FILE_NAME.matcher(name).matches() || fileUri.isBlank()) {
            throw new GeminiException(Kind.MALFORMED, "Gemini returned an unusable file resource");
        }
        return new UploadedFile(name, fileUri, VideoJson.text(file, "mimeType"), VideoJson.text(file, "state"),
                durationSeconds(VideoJson.text(file.path("videoMetadata"), "videoDuration")));
    }

    // A protobuf Duration in JSON: "754.400s".
    private static Integer durationSeconds(String duration) {
        var matcher = DURATION.matcher(duration);
        if (!matcher.matches()) {
            return null;
        }
        var seconds = new BigDecimal(matcher.group(1)).setScale(0, RoundingMode.CEILING);
        return seconds.compareTo(MAX_DURATION_SECONDS) > 0 ? Integer.MAX_VALUE : seconds.intValue();
    }

    // The upload URL arrives in a response header and receives the API key, so it must stay on the API host.
    private URI onApiHost(String uploadUrl) {
        try {
            var upload = new URI(uploadUrl.trim());
            var base = URI.create(settings.apiBaseUrl());
            if (base.getScheme() != null
                    && base.getScheme().equalsIgnoreCase(upload.getScheme())
                    && base.getHost() != null
                    && base.getHost().equalsIgnoreCase(upload.getHost())
                    && base.getPort() == upload.getPort()
                    && upload.getUserInfo() == null) {
                return upload;
            }
        } catch (URISyntaxException exception) {
            // Reported below like any other unusable upload URL.
        }
        throw new GeminiException(Kind.MALFORMED, "Gemini returned an upload URL outside the API host");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException exception) {
            throw new RetryableFailure("Gemini could not be reached in time", exception);
        } catch (HttpTimeoutException exception) {
            // Not retried: a second full wait would outlast the app's patience for the job.
            throw new GeminiException(Kind.TRANSIENT, "Gemini did not answer within the request timeout", exception);
        } catch (IOException exception) {
            throw new RetryableFailure("Gemini could not be reached", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GeminiException(Kind.TRANSIENT, "The Gemini request was interrupted", exception);
        }
    }

    private HttpResponse<String> checked(HttpResponse<String> response) {
        var status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response;
        }
        var error = ErrorBody.parse(jsonMapper, response.body());
        var message = "Gemini returned HTTP " + status + (error.status().isEmpty() ? "" : " " + error.status());
        if (status == 429 || "RESOURCE_EXHAUSTED".equals(error.status())) {
            throw new GeminiException(Kind.QUOTA, message);
        }
        if (status == 400) {
            // An invalid key or an unsupported region also answers 400; neither means the video is at fault.
            var configuration = error.reasons().contains("API_KEY_INVALID")
                    || "FAILED_PRECONDITION".equals(error.status());
            throw new GeminiException(configuration ? Kind.CONFIGURATION : Kind.INVALID_ARGUMENT, message);
        }
        if (status == 401 || status == 403 || status == 404) {
            throw new GeminiException(Kind.CONFIGURATION, message);
        }
        if (RETRYABLE_STATUSES.contains(status)) {
            throw new RetryableFailure(message, null);
        }
        throw new GeminiException(status >= 500 ? Kind.TRANSIENT : Kind.CONFIGURATION, message);
    }

    private <T> T retryOnce(Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RetryableFailure first) {
            pause(RETRY_DELAY);
            try {
                return attempt.get();
            } catch (RetryableFailure second) {
                throw new GeminiException(Kind.TRANSIENT, second.getMessage(), second.getCause());
            }
        }
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GeminiException(Kind.TRANSIENT, "The Gemini request was interrupted", exception);
        }
    }

    private JsonNode json(String body) {
        try {
            return jsonMapper.readTree(body == null ? "" : body);
        } catch (JacksonException exception) {
            throw new GeminiException(Kind.MALFORMED, "Gemini response was not JSON");
        }
    }

    private void requireConfigured() {
        if (!settings.isConfigured()) {
            throw new GeminiException(Kind.CONFIGURATION, "Gemini API key or model is not configured");
        }
    }

    private URI uri(String path) {
        return URI.create(settings.apiBaseUrl() + path);
    }

    private static String withoutFence(String text) {
        var trimmed = text.strip();
        var fence = FENCE_START.matcher(trimmed);
        if (!fence.find()) {
            return trimmed;
        }
        trimmed = trimmed.substring(fence.end());
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.strip();
    }

    private static String safe(String apiEnumValue) {
        return ERROR_STATUS.matcher(apiEnumValue).matches() ? apiEnumValue : "unknown";
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One more attempt is worth it; {@link #retryOnce} turns a second one into {@link Kind#TRANSIENT}. */
    private static final class RetryableFailure extends RuntimeException {
        RetryableFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record ErrorBody(String status, Set<String> reasons) {
        static ErrorBody parse(JsonMapper jsonMapper, String body) {
            try {
                var error = jsonMapper.readTree(body == null ? "" : body).path("error");
                var reasons = new HashSet<String>();
                for (var detail : error.path("details")) {
                    reasons.add(VideoJson.text(detail, "reason"));
                }
                var status = VideoJson.text(error, "status");
                return new ErrorBody(ERROR_STATUS.matcher(status).matches() ? status : "", reasons);
            } catch (JacksonException exception) {
                return new ErrorBody("", Set.of());
            }
        }
    }
}
