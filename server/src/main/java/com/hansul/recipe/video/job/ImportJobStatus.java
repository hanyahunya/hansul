package com.hansul.recipe.video.job;

import java.util.Set;

public enum ImportJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    /** The statuses covered by the partial unique index uq_recipe_import_job_in_flight. */
    static final Set<ImportJobStatus> IN_FLIGHT = Set.of(QUEUED, RUNNING);
}
