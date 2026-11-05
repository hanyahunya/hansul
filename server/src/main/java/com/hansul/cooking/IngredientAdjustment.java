package com.hansul.cooking;

import java.util.Objects;

/** A user-reported pantry/change fact, never an AI suggestion that was silently accepted. */
public record IngredientAdjustment(
        Kind kind,
        String ingredientName,
        String replacementName,
        String quantityText,
        String evidence) {
    public IngredientAdjustment {
        Objects.requireNonNull(kind, "kind");
        if (ingredientName == null || ingredientName.isBlank() || ingredientName.length() > 100) {
            throw new IllegalArgumentException("Ingredient name must be between 1 and 100 characters");
        }
        replacementName = Objects.requireNonNullElse(replacementName, "").strip();
        quantityText = Objects.requireNonNullElse(quantityText, "").strip();
        if (replacementName.length() > 100 || quantityText.length() > 100
                || evidence == null || evidence.isBlank() || evidence.length() > 3000) {
            throw new IllegalArgumentException("Ingredient report fields are invalid");
        }
        ingredientName = ingredientName.strip();
        if ((kind == Kind.REPLACED) != !replacementName.isEmpty()) {
            throw new IllegalArgumentException("Only a replacement report has a replacement ingredient");
        }
    }

    public enum Kind { MISSING, AVAILABLE, ADDED, REPLACED }
}
