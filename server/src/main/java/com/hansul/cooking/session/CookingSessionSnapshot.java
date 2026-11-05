package com.hansul.cooking.session;

import com.hansul.cooking.CookingPlan;
import com.hansul.cooking.CookingState;
import com.hansul.cooking.ai.RecipePlanBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CookingSessionSnapshot(
        UUID id,
        String ownerSubject,
        UUID recipeId,
        BigDecimal targetServings,
        CookingPlan plan,
        RecipePlanBuilder.PlanSource planSource,
        List<String> planWarnings,
        CookingState state,
        Instant createdAt,
        Instant updatedAt) {
    public CookingSessionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerSubject, "ownerSubject");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(targetServings, "targetServings");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(planSource, "planSource");
        planWarnings = List.copyOf(planWarnings);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
