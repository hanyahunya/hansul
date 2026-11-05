package com.hansul.recipe.application;

import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotEntity;
import org.springframework.stereotype.Component;

@Component
class RecipeResponseMapper {

    RecipeImportResponse toResponse(RecipeSnapshotEntity entity) {
        var ingredients = entity.getIngredients().stream()
                .map(ingredient -> new RecipeImportResponse.Ingredient(
                        ingredient.getName(),
                        ingredient.getQuantityText(),
                        ingredient.getRawText(),
                        ingredient.getNormalizedAmount(),
                        ingredient.getNormalizedUnit(),
                        ingredient.getNormalizationWarning(),
                        new RecipeImportResponse.Evidence(
                                ingredient.getSourceType(),
                                ingredient.getSourceLocator()
                        )
                ))
                .toList();
        var steps = entity.getSteps().stream()
                .map(step -> new RecipeImportResponse.Step(
                        step.getStepNumber(),
                        step.getNormalizedText(),
                        step.getImageUrl(),
                        step.getRawText(),
                        new RecipeImportResponse.Evidence(
                                step.getSourceType(),
                                step.getSourceLocator()
                        ),
                        step.getVideoStartSeconds(),
                        step.getVideoEndSeconds(),
                        step.getKeyFrameSeconds()
                ))
                .toList();

        return new RecipeImportResponse(
                entity.getId(),
                entity.getSourceUrl(),
                entity.getTitle(),
                entity.getBaseServings(),
                ingredients,
                steps,
                entity.getBaseServingsText(),
                entity.getDescription(),
                entity.getTotalTimeText(),
                entity.getPrimaryImageUrl(),
                entity.getParserVersion(),
                entity.getImportedAt(),
                entity.getWarnings(),
                ParsedRecipe.VIDEO_AI_SOURCE_FORMAT.equals(entity.getSourceFormat())
                        ? RecipeImportResponse.SOURCE_KIND_VIDEO_AI
                        : RecipeImportResponse.SOURCE_KIND_WEB_PAGE,
                video(entity)
        );
    }

    private RecipeImportResponse.Video video(RecipeSnapshotEntity entity) {
        if (entity.getVideoProvider() == null) {
            return null;
        }
        return new RecipeImportResponse.Video(
                entity.getVideoProvider(),
                entity.getVideoId(),
                entity.getVideoPageUrl(),
                entity.getVideoDurationSeconds(),
                entity.getVideoChannel(),
                Boolean.TRUE.equals(entity.getVideoEmbeddable())
        );
    }
}
