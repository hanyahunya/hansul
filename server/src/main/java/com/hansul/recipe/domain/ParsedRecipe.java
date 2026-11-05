package com.hansul.recipe.domain;

import java.math.BigDecimal;
import java.util.List;

public record ParsedRecipe(
        String title,
        BigDecimal baseServings,
        String baseServingsText,
        String description,
        String totalTimeText,
        String primaryImageUrl,
        List<ParsedIngredient> ingredients,
        List<ParsedStep> steps,
        String sourceFormat,
        String sourceEvidenceLocator,
        String rawStructuredData,
        List<String> warnings,
        ParsedVideo video
) {
    /** Recipes written by the video pipeline; the API reports them as {@code sourceKind = VIDEO_AI}. */
    public static final String VIDEO_AI_SOURCE_FORMAT = "VIDEO_AI_TRANSCRIPT";

    public ParsedRecipe {
        ingredients = List.copyOf(ingredients);
        steps = List.copyOf(steps);
        warnings = List.copyOf(warnings);
    }

    public ParsedRecipe(
            String title,
            BigDecimal baseServings,
            String baseServingsText,
            String description,
            String totalTimeText,
            String primaryImageUrl,
            List<ParsedIngredient> ingredients,
            List<ParsedStep> steps,
            String sourceFormat,
            String sourceEvidenceLocator,
            String rawStructuredData,
            List<String> warnings
    ) {
        this(title, baseServings, baseServingsText, description, totalTimeText, primaryImageUrl,
                ingredients, steps, sourceFormat, sourceEvidenceLocator, rawStructuredData, warnings, null);
    }
}
