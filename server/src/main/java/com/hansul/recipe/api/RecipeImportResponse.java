package com.hansul.recipe.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecipeImportResponse(
        UUID id,
        String sourceUrl,
        String title,
        BigDecimal baseServings,
        List<Ingredient> ingredients,
        List<Step> steps,
        String baseServingsText,
        String description,
        String totalTimeText,
        String primaryImageUrl,
        String parserVersion,
        Instant importedAt,
        List<String> warnings,
        String sourceKind,
        Video video
) {
    public static final String SOURCE_KIND_WEB_PAGE = "WEB_PAGE";
    public static final String SOURCE_KIND_VIDEO_AI = "VIDEO_AI";

    /** A web page recipe without a video, the only kind that existed before video imports. */
    public RecipeImportResponse(
            UUID id,
            String sourceUrl,
            String title,
            BigDecimal baseServings,
            List<Ingredient> ingredients,
            List<Step> steps,
            String baseServingsText,
            String description,
            String totalTimeText,
            String primaryImageUrl,
            String parserVersion,
            Instant importedAt,
            List<String> warnings
    ) {
        this(id, sourceUrl, title, baseServings, ingredients, steps, baseServingsText, description,
                totalTimeText, primaryImageUrl, parserVersion, importedAt, warnings, SOURCE_KIND_WEB_PAGE, null);
    }

    public record Ingredient(
            String name,
            String quantityText,
            String originalText,
            BigDecimal normalizedAmount,
            String normalizedUnit,
            String normalizationWarning,
            Evidence evidence
    ) {
    }

    public record Step(
            int number,
            String text,
            String imageUrl,
            String originalText,
            Evidence evidence,
            Integer videoStartSeconds,
            Integer videoEndSeconds,
            Integer keyFrameSeconds
    ) {
        public Step(int number, String text, String imageUrl, String originalText, Evidence evidence) {
            this(number, text, imageUrl, originalText, evidence, null, null, null);
        }
    }

    public record Evidence(
            String sourceType,
            String locator
    ) {
    }

    public record Video(
            String provider,
            String videoId,
            String pageUrl,
            Integer durationSeconds,
            String channel,
            boolean embeddable
    ) {
    }
}
