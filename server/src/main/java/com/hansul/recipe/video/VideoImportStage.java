package com.hansul.recipe.video;

/** Pipeline progress. The names are the import job {@code stage} values for the same moments. */
public enum VideoImportStage {
    FETCHING_METADATA,
    UPLOADING_TO_AI,
    WATCHING_VIDEO,
    WRITING_RECIPE
}
