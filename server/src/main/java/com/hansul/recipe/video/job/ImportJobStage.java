package com.hansul.recipe.video.job;

import com.hansul.recipe.video.VideoImportStage;

public enum ImportJobStage {
    QUEUED,
    FETCHING_METADATA,
    UPLOADING_TO_AI,
    WATCHING_VIDEO,
    WRITING_RECIPE,
    SAVING,
    DONE;

    /** The pipeline reports its stages under the job stage names of the same moments. */
    static ImportJobStage of(VideoImportStage stage) {
        return valueOf(stage.name());
    }
}
