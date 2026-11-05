package com.hansul.recipe.video.job;

import java.time.Instant;
import java.util.UUID;

public record RecipeImportJobResponse(
        UUID jobId,
        ImportJobStatus status,
        ImportJobStage stage,
        UUID recipeId,
        String errorCode,
        String errorMessage,
        ImportJobSourceKind sourceKind,
        String sourceUrl,
        Instant createdAt,
        Instant updatedAt
) {
    static RecipeImportJobResponse from(RecipeImportJobEntity job) {
        return new RecipeImportJobResponse(
                job.getId(),
                job.getStatus(),
                job.getStage(),
                job.getRecipeId(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getSourceKind(),
                job.getSourceUrl(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
