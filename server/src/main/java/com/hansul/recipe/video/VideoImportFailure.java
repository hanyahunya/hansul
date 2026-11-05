package com.hansul.recipe.video;

/** An import that cannot produce a recipe; {@link #code()} is the job {@code errorCode}. */
public class VideoImportFailure extends RuntimeException {

    public static final String VIDEO_UNAVAILABLE = "VIDEO_UNAVAILABLE";
    public static final String VIDEO_TOO_LONG = "VIDEO_TOO_LONG";
    public static final String NOT_A_RECIPE_VIDEO = "NOT_A_RECIPE_VIDEO";
    public static final String RECIPE_EXTRACTION_FAILED = "RECIPE_EXTRACTION_FAILED";
    public static final String AI_QUOTA_EXCEEDED = "AI_QUOTA_EXCEEDED";
    public static final String AI_BLOCKED = "AI_BLOCKED";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";

    private final String code;

    public VideoImportFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public VideoImportFailure(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
