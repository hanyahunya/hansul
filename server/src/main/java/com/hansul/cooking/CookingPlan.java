package com.hansul.cooking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A recipe-derived plan. Identifiers and relationships come from the imported recipe, not recipe-specific code. */
public record CookingPlan(
        Map<String, Action> actions,
        Map<String, Condition> conditions,
        Map<String, TimerCue> timerCues,
        Set<Integer> sourceStepNumbers) {

    public CookingPlan {
        actions = immutableOrderedMap(actions, "actions");
        conditions = immutableOrderedMap(conditions, "conditions");
        timerCues = immutableOrderedMap(timerCues, "timerCues");
        sourceStepNumbers = Set.copyOf(Objects.requireNonNull(sourceStepNumbers, "sourceStepNumbers"));
        if (sourceStepNumbers.isEmpty() || sourceStepNumbers.stream().anyMatch(number -> number == null || number < 1)) {
            throw new IllegalArgumentException("Source steps must be positive and nonempty");
        }

        for (Map.Entry<String, Action> entry : actions.entrySet()) {
            String key = entry.getKey();
            Action action = entry.getValue();
            if (!key.equals(action.id())) {
                throw new IllegalArgumentException("Action map key differs from action id: " + key);
            }
            requireSourceSteps(action.sourceStepNumbers(), sourceStepNumbers);
            if (!actions.keySet().containsAll(action.prerequisiteActionIds())) {
                throw new IllegalArgumentException("Unknown action prerequisite: " + action.id());
            }
            if (!conditions.keySet().containsAll(action.requiredConditionIds())) {
                throw new IllegalArgumentException("Unknown required condition: " + action.id());
            }
        }
        for (Map.Entry<String, Condition> entry : conditions.entrySet()) {
            String key = entry.getKey();
            Condition condition = entry.getValue();
            if (!key.equals(condition.id())) {
                throw new IllegalArgumentException("Condition map key differs from condition id: " + key);
            }
            requireSourceSteps(condition.sourceStepNumbers(), sourceStepNumbers);
        }
        for (Map.Entry<String, TimerCue> entry : timerCues.entrySet()) {
            String key = entry.getKey();
            TimerCue timerCue = entry.getValue();
            if (!key.equals(timerCue.id())) {
                throw new IllegalArgumentException("Timer map key differs from timer id: " + key);
            }
            if (!sourceStepNumbers.contains(timerCue.sourceStepNumber())) {
                throw new IllegalArgumentException("Timer has no source step: " + key);
            }
        }
        for (String id : actions.keySet()) {
            checkAcyclic(id, actions, new HashSet<>(), new HashSet<>());
        }
    }

    private static <T> Map<String, T> immutableOrderedMap(Map<String, T> source, String name) {
        Objects.requireNonNull(source, name);
        for (Map.Entry<String, T> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException(name + " cannot contain nulls");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void requireSourceSteps(Set<Integer> referenced, Set<Integer> available) {
        if (referenced.isEmpty() || !available.containsAll(referenced)) {
            throw new IllegalArgumentException("Unknown or absent source step reference");
        }
    }

    private static void checkAcyclic(
            String id,
            Map<String, Action> actions,
            Set<String> visiting,
            Set<String> checked) {
        if (checked.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Action prerequisites contain a cycle at " + id);
        }
        for (String prerequisite : actions.get(id).prerequisiteActionIds()) {
            checkAcyclic(prerequisite, actions, visiting, checked);
        }
        visiting.remove(id);
        checked.add(id);
    }

    public List<String> availableActionIds(CookingState state) {
        Objects.requireNonNull(state, "state");
        List<String> result = new ArrayList<>();
        for (Action action : actions.values()) {
            if (!state.completedActionIds().contains(action.id())
                    && state.completedActionIds().containsAll(action.prerequisiteActionIds())
                    && state.observedConditionIds().containsAll(action.requiredConditionIds())) {
                result.add(action.id());
            }
        }
        return List.copyOf(result);
    }

    public record Action(
            String id,
            Set<Integer> sourceStepNumbers,
            Set<String> prerequisiteActionIds,
            Set<String> requiredConditionIds) {
        public Action {
            id = requireId(id);
            sourceStepNumbers = Set.copyOf(Objects.requireNonNull(sourceStepNumbers, "sourceStepNumbers"));
            prerequisiteActionIds = Set.copyOf(Objects.requireNonNull(prerequisiteActionIds, "prerequisiteActionIds"));
            requiredConditionIds = Set.copyOf(Objects.requireNonNull(requiredConditionIds, "requiredConditionIds"));
            if (prerequisiteActionIds.contains(id)) {
                throw new IllegalArgumentException("Action cannot require itself");
            }
        }
    }

    public record Condition(String id, Set<Integer> sourceStepNumbers) {
        public Condition {
            id = requireId(id);
            sourceStepNumbers = Set.copyOf(Objects.requireNonNull(sourceStepNumbers, "sourceStepNumbers"));
        }
    }

    public record TimerCue(String id, Duration duration, int sourceStepNumber, Boolean approximate) {
        public TimerCue {
            id = requireId(id);
            Objects.requireNonNull(duration, "duration");
            approximate = Boolean.TRUE.equals(approximate);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Timer duration must be positive");
            }
            if (sourceStepNumber < 1) {
                throw new IllegalArgumentException("Timer source step must be positive");
            }
        }

        public TimerCue(String id, Duration duration, int sourceStepNumber) {
            this(id, duration, sourceStepNumber, Boolean.FALSE);
        }
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Identifier must be nonempty");
        }
        return value;
    }
}
