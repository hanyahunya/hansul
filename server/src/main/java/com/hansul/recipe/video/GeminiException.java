package com.hansul.recipe.video;

/** Messages never contain the key, prompts or model output, so they are safe to store with a failed job. */
public class GeminiException extends RuntimeException {

    public enum Kind {
        /** HTTP 429 or RESOURCE_EXHAUSTED. */
        QUOTA,
        /** HTTP 400: for a media request this usually means Gemini could not open the video. */
        INVALID_ARGUMENT,
        /** Key, model or project problems (401, 403, 404, invalid key). */
        CONFIGURATION,
        /** 5xx after one retry, timeouts and network errors. */
        TRANSIENT,
        /** Prompt or candidate blocked (safety, prohibited content, ...). */
        BLOCKED,
        /** The model stopped because its output would recite source material. */
        RECITATION,
        /** The output hit maxOutputTokens. */
        TRUNCATED,
        /** No candidate, unknown finish reason or output that is not a JSON object. */
        MALFORMED,
        /** The Files API reported the uploaded video as FAILED. */
        FILE_PROCESSING_FAILED
    }

    private final Kind kind;
    private final long inputTokens;
    private final long outputTokens;

    public GeminiException(Kind kind, String message) {
        this(kind, message, null, 0, 0);
    }

    public GeminiException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, 0, 0);
    }

    /** For a response that was billed although it is unusable. */
    public GeminiException(Kind kind, String message, long inputTokens, long outputTokens) {
        this(kind, message, null, inputTokens, outputTokens);
    }

    private GeminiException(Kind kind, String message, Throwable cause, long inputTokens, long outputTokens) {
        super(message, cause);
        this.kind = kind;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public Kind kind() {
        return kind;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }
}
