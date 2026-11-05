package com.hansul.recipe.video;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Turns a cooking video into a recipe: Gemini first writes down the video's speech and on-screen text, then
 * writes a recipe from that transcript, and every quote and number of it is checked against the transcript.
 * Expected failures are {@link VideoImportFailure}s carrying the job error code.
 */
public interface VideoRecipeImportPipeline {

    String YOUTUBE_PARSER_VERSION = "youtube-gemini-v1";
    String UPLOAD_PARSER_VERSION = "upload-gemini-v1";

    /** Reports FETCHING_METADATA, WATCHING_VIDEO and WRITING_RECIPE. */
    VideoImportResult importYouTube(String videoId, StageListener listener);

    /**
     * Reports UPLOADING_TO_AI, WATCHING_VIDEO and WRITING_RECIPE. The file is copied to Gemini for the
     * transcript and that copy is deleted again whatever happens; the local file is left alone.
     */
    VideoImportResult importUpload(
            UUID uploadId,
            Path file,
            String contentType,
            Integer durationSeconds,
            StageListener listener
    );
}
