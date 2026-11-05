package com.hansul.cooking.ai;

import com.hansul.cooking.CookingPlan;
import com.hansul.cooking.CookingState;
import com.hansul.recipe.api.RecipeImportResponse;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;

/**
 * Everything the model may use for one turn; no hidden chat history is treated as cooking state.
 * {@code recipeSourceKind} is the recipe's {@code sourceKind}: {@code VIDEO_AI} recipes were written by AI from a video.
 */
public record AgentTurnContext(
        String recipeTitle,
        List<SourceStep> sourceSteps,
        CookingPlan plan,
        CookingState state,
        String transcript,
        List<SourceIngredient> ingredients,
        BigDecimal baseServings,
        BigDecimal targetServings,
        String recipeSourceKind) {

    public AgentTurnContext {
        Objects.requireNonNull(recipeTitle, "recipeTitle");
        sourceSteps = List.copyOf(Objects.requireNonNull(sourceSteps, "sourceSteps"));
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(transcript, "transcript");
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        recipeSourceKind = recipeSourceKind == null ? RecipeImportResponse.SOURCE_KIND_WEB_PAGE : recipeSourceKind;
    }

    public AgentTurnContext(String recipeTitle, List<SourceStep> sourceSteps, CookingPlan plan,
            CookingState state, String transcript, List<SourceIngredient> ingredients,
            BigDecimal baseServings, BigDecimal targetServings) {
        this(recipeTitle, sourceSteps, plan, state, transcript, ingredients, baseServings, targetServings, null);
    }

    public AgentTurnContext(String recipeTitle, List<SourceStep> sourceSteps, CookingPlan plan,
            CookingState state, String transcript) {
        this(recipeTitle, sourceSteps, plan, state, transcript, List.of(), null, null, null);
    }

    public record SourceIngredient(String name, String originalQuantityText,
            String proportionalQuantityText, String scalingStatus) {}

    public record SourceStep(int number, String text, boolean hasPhoto) {
        public SourceStep {
            if (number < 1) {
                throw new IllegalArgumentException("Source step number must be positive");
            }
            Objects.requireNonNull(text, "text");
        }
    }
}
