package com.hansul.recipe.video;

import java.util.List;

/** Pass 2 output exactly as the model wrote it; nothing here is trusted until {@link VideoRecipeValidator}. */
record VideoRecipeDraft(
        String title,
        Servings servings,
        List<Ingredient> ingredients,
        List<Step> steps
) {
    VideoRecipeDraft {
        servings = servings == null ? Servings.NONE : servings;
        ingredients = List.copyOf(ingredients);
        steps = List.copyOf(steps);
    }

    record Servings(String text, String source, String ref, String quote) {
        static final Servings NONE = new Servings("", "NONE", "", "");
    }

    record Ingredient(String name, String quantityText, String ref, String quote) {
    }

    /** Times are null when the model left them out or wrote something that is not a finite number. */
    record Step(
            String text,
            List<String> segmentIds,
            Double startSeconds,
            Double endSeconds,
            Double keyFrameSeconds,
            String quote
    ) {
        Step {
            segmentIds = List.copyOf(segmentIds);
        }
    }
}
