package com.hansul.cooking;

import java.util.Objects;
import java.util.List;
import java.util.Set;

/** Persist this projection alongside an append-only event log. */
public record CookingState(
        long version,
        Set<String> completedActionIds,
        Set<String> observedConditionIds,
        Set<String> offeredTimerCueIds,
        Set<String> requestedTimerCueIds,
        Set<String> runningTimerCueIds,
        Set<String> processedCommandIds,
        Integer focusedSourceStepNumber,
        List<IngredientAdjustment> ingredientAdjustments,
        Set<Integer> completedSourceStepNumbers,
        Set<Integer> skippedSourceStepNumbers) {

    public CookingState {
        if (version < 0) {
            throw new IllegalArgumentException("Version cannot be negative");
        }
        completedActionIds = Set.copyOf(Objects.requireNonNull(completedActionIds, "completedActionIds"));
        observedConditionIds = Set.copyOf(Objects.requireNonNull(observedConditionIds, "observedConditionIds"));
        offeredTimerCueIds = Set.copyOf(Objects.requireNonNull(offeredTimerCueIds, "offeredTimerCueIds"));
        requestedTimerCueIds = Set.copyOf(Objects.requireNonNull(requestedTimerCueIds, "requestedTimerCueIds"));
        runningTimerCueIds = Set.copyOf(Objects.requireNonNull(runningTimerCueIds, "runningTimerCueIds"));
        processedCommandIds = Set.copyOf(Objects.requireNonNull(processedCommandIds, "processedCommandIds"));
        if (focusedSourceStepNumber != null && focusedSourceStepNumber < 1) {
            throw new IllegalArgumentException("Focused source step must be positive");
        }
        ingredientAdjustments = ingredientAdjustments == null ? List.of() : List.copyOf(ingredientAdjustments);
        completedSourceStepNumbers = completedSourceStepNumbers == null ? Set.of() : Set.copyOf(completedSourceStepNumbers);
        skippedSourceStepNumbers = skippedSourceStepNumbers == null ? Set.of() : Set.copyOf(skippedSourceStepNumbers);
        if (!java.util.Collections.disjoint(completedSourceStepNumbers, skippedSourceStepNumbers)) {
            throw new IllegalArgumentException("A source step cannot be completed and skipped at once");
        }
        if (ingredientAdjustments.size() > 50) {
            throw new IllegalArgumentException("Too many ingredient adjustments");
        }
        if (!java.util.Collections.disjoint(offeredTimerCueIds, requestedTimerCueIds)
                || !java.util.Collections.disjoint(offeredTimerCueIds, runningTimerCueIds)
                || !java.util.Collections.disjoint(requestedTimerCueIds, runningTimerCueIds)) {
            throw new IllegalArgumentException("A timer cue cannot be offered, requested, or running at once");
        }
    }

    /** Compatibility with existing persisted projections and callers. */
    public CookingState(long version, Set<String> completedActionIds, Set<String> observedConditionIds,
            Set<String> offeredTimerCueIds, Set<String> requestedTimerCueIds, Set<String> runningTimerCueIds,
            Set<String> processedCommandIds) {
        this(version, completedActionIds, observedConditionIds, offeredTimerCueIds, requestedTimerCueIds,
                runningTimerCueIds, processedCommandIds, null, List.of(), Set.of(), Set.of());
    }

    public static CookingState empty() {
        return new CookingState(0, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }
}
