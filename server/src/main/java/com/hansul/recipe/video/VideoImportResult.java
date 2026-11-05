package com.hansul.recipe.video;

import com.hansul.recipe.domain.ParsedRecipe;
import java.util.Objects;

/** Token counts cover every Gemini call of the import, retries included. */
public record VideoImportResult(
        ParsedRecipe recipe,
        String parserVersion,
        long inputTokens,
        long outputTokens
) {
    public VideoImportResult {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(parserVersion, "parserVersion");
    }
}
