package com.hansul.recipe.video;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Gemini generateContent and the Files API. Every failure is a {@link GeminiException}. */
public interface GeminiClient {

    /** One structured-output call; {@code media} is null for text-only requests. */
    Result generateContent(Request request);

    /** Starts a resumable upload and sends the whole file; the result may still be PROCESSING. */
    UploadedFile uploadFile(Path file, String mimeType, String displayName);

    /** Polls until the uploaded file is ACTIVE. */
    UploadedFile awaitActive(UploadedFile file);

    /** Best effort, never throws: Gemini also expires uploaded files on its own. */
    void deleteFile(String name);

    record Media(String fileUri, String mimeType) {
        public Media {
            Objects.requireNonNull(fileUri, "fileUri");
        }

        /** Gemini reads public YouTube videos by their watch URL, without a MIME type. */
        public static Media youTube(String watchUrl) {
            return new Media(watchUrl, null);
        }
    }

    record Request(
            String systemInstruction,
            Media media,
            String text,
            Map<String, Object> responseJsonSchema,
            int maxOutputTokens
    ) {
        public Request {
            Objects.requireNonNull(systemInstruction, "systemInstruction");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(responseJsonSchema, "responseJsonSchema");
        }
    }

    /** {@code json} is always an object; output tokens include thinking tokens. */
    record Result(JsonNode json, long inputTokens, long outputTokens) {
    }

    /** {@code durationSeconds} is the length Gemini measured once the video is processed, rounded up; else null. */
    record UploadedFile(String name, String uri, String mimeType, String state, Integer durationSeconds) {
        public UploadedFile(String name, String uri, String mimeType, String state) {
            this(name, uri, mimeType, state, null);
        }

        public boolean isActive() {
            return "ACTIVE".equals(state);
        }
    }
}
