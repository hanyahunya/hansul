package com.hansul.recipe.domain;

import java.math.BigDecimal;

public record ParsedIngredient(
        int position,
        String rawText,
        String name,
        String quantityText,
        BigDecimal normalizedAmount,
        String normalizedUnit,
        String normalizationWarning,
        SourceEvidence evidence
) {
}
