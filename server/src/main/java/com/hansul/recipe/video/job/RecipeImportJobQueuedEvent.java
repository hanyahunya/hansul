package com.hansul.recipe.video.job;

import java.util.UUID;

/** Published once a QUEUED job row is committed; the worker picks it up from there. */
public record RecipeImportJobQueuedEvent(UUID jobId) {
}
