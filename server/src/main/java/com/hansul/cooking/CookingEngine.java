package com.hansul.cooking;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic state transitions; neither model text nor speech playback can directly mutate the session. */
public final class CookingEngine {
    private CookingEngine() {}

    public static Outcome applyTurn(
            CookingPlan plan,
            CookingState state,
            String clientTurnId,
            String transcript,
            TurnDecision decision) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        requireCommandId(clientTurnId);
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(decision, "decision");
        validateState(plan, state);

        String commandId = "turn:" + clientTurnId;
        if (state.processedCommandIds().contains(commandId)) {
            return new Outcome(state, List.of(), plan.availableActionIds(state), true);
        }
        validateDecision(plan, state, transcript, decision);

        Set<String> completed = new HashSet<>(state.completedActionIds());
        Set<String> observed = new HashSet<>(state.observedConditionIds());
        Set<String> offeredTimers = new HashSet<>(state.offeredTimerCueIds());
        Set<String> requestedTimers = new HashSet<>(state.requestedTimerCueIds());
        Set<String> runningTimers = new HashSet<>(state.runningTimerCueIds());
        List<Event> events = new ArrayList<>();
        Map<String, IngredientAdjustment> ingredients = new LinkedHashMap<>();
        state.ingredientAdjustments().forEach(report -> ingredients.put(report.ingredientName(), report));
        Set<Integer> completedSteps = new HashSet<>(state.completedSourceStepNumbers());
        Set<Integer> skippedSteps = new HashSet<>(state.skippedSourceStepNumbers());

        if (decision.addressing() == TurnDecision.Addressing.DIRECT) {
            for (IngredientAdjustment report : decision.ingredientReports()) {
                ingredients.put(report.ingredientName(), report);
                events.add(new Event(EventType.INGREDIENT_REPORTED, report.ingredientName()));
            }
            for (TurnDecision.UserReport report : decision.reports()) {
                switch (report.kind()) {
                    case ACTION_DONE -> {
                        change(completed, report.targetId(), true, EventType.ACTION_DONE, events);
                        skippedSteps.removeAll(plan.actions().get(report.targetId()).sourceStepNumbers());
                    }
                    case ACTION_RETRACTED -> change(completed, report.targetId(), false, EventType.ACTION_RETRACTED, events);
                    case CONDITION_OBSERVED -> change(observed, report.targetId(), true, EventType.CONDITION_OBSERVED, events);
                    case CONDITION_RETRACTED -> change(observed, report.targetId(), false, EventType.CONDITION_RETRACTED, events);
                }
            }
            for (String timerCueId : decision.timerOfferIds()) {
                if (!requestedTimers.contains(timerCueId) && !runningTimers.contains(timerCueId)) {
                    change(offeredTimers, timerCueId, true, EventType.TIMER_OFFERED, events);
                }
            }
            for (String timerCueId : decision.timerAcceptanceIds()) {
                if (offeredTimers.remove(timerCueId)) {
                    events.add(new Event(EventType.TIMER_OFFER_CLEARED, timerCueId));
                }
                change(requestedTimers, timerCueId, true, EventType.TIMER_START_REQUESTED, events);
            }
        }

        Set<String> processed = new HashSet<>(state.processedCommandIds());
        processed.add(commandId);
        Set<Integer> reportedActionSteps = decision.reports().stream()
                .filter(report -> report.kind() == TurnDecision.ReportKind.ACTION_DONE
                        || report.kind() == TurnDecision.ReportKind.ACTION_RETRACTED)
                .flatMap(report -> plan.actions().get(report.targetId()).sourceStepNumbers().stream())
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> retractedActionSteps = decision.reports().stream()
                .filter(report -> report.kind() == TurnDecision.ReportKind.ACTION_RETRACTED)
                .flatMap(report -> plan.actions().get(report.targetId()).sourceStepNumbers().stream())
                .collect(java.util.stream.Collectors.toSet());
        synchronizeCompletedSteps(plan, completed, completedSteps, reportedActionSteps, retractedActionSteps);
        CookingState updated = new CookingState(
                state.version() + 1,
                completed,
                observed,
                offeredTimers,
                requestedTimers,
                runningTimers,
                processed,
                state.focusedSourceStepNumber(),
                List.copyOf(ingredients.values()),
                completedSteps, skippedSteps);
        return new Outcome(updated, events, plan.availableActionIds(updated), false);
    }

    /** Explicit UI consent can request a source-bound timer without waiting for a model offer. */
    public static Outcome requestTimerStart(CookingPlan plan, CookingState state, String clientCommandId,
            String timerCueId) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        requireCommandId(clientCommandId);
        validateState(plan, state);
        if (!plan.timerCues().containsKey(timerCueId)) {
            throw new IllegalArgumentException("Unknown timer cue: " + timerCueId);
        }
        String commandId = "timerRequest:" + clientCommandId;
        if (state.processedCommandIds().contains(commandId)) {
            return new Outcome(state, List.of(), plan.availableActionIds(state), true);
        }
        if (state.requestedTimerCueIds().contains(timerCueId) || state.runningTimerCueIds().contains(timerCueId)) {
            throw new IllegalStateException("Timer is already requested or running: " + timerCueId);
        }
        Set<String> offered = new HashSet<>(state.offeredTimerCueIds());
        Set<String> requested = new HashSet<>(state.requestedTimerCueIds());
        List<Event> events = new ArrayList<>();
        if (offered.remove(timerCueId)) {
            events.add(new Event(EventType.TIMER_OFFER_CLEARED, timerCueId));
        }
        requested.add(timerCueId);
        events.add(new Event(EventType.TIMER_START_REQUESTED, timerCueId));
        Set<String> processed = new HashSet<>(state.processedCommandIds());
        processed.add(commandId);
        CookingState updated = new CookingState(state.version() + 1, state.completedActionIds(),
                state.observedConditionIds(), offered, requested, state.runningTimerCueIds(), processed,
                state.focusedSourceStepNumber(), state.ingredientAdjustments(),
                state.completedSourceStepNumbers(), state.skippedSourceStepNumbers());
        return new Outcome(updated, events, plan.availableActionIds(updated), false);
    }

    /** The device has confirmed that it actually scheduled the timer. */
    public static Outcome acknowledgeTimerStart(
            CookingPlan plan, CookingState state, String clientAckId, String timerCueId) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        requireCommandId(clientAckId);
        validateState(plan, state);
        if (!plan.timerCues().containsKey(timerCueId)) {
            throw new IllegalArgumentException("Unknown timer cue: " + timerCueId);
        }
        String commandId = "timerAck:" + clientAckId;
        if (state.processedCommandIds().contains(commandId)) {
            return new Outcome(state, List.of(), plan.availableActionIds(state), true);
        }
        if (!state.requestedTimerCueIds().contains(timerCueId)) {
            throw new IllegalArgumentException("Timer was not requested: " + timerCueId);
        }
        Set<String> requested = new HashSet<>(state.requestedTimerCueIds());
        Set<String> running = new HashSet<>(state.runningTimerCueIds());
        Set<String> processed = new HashSet<>(state.processedCommandIds());
        requested.remove(timerCueId);
        running.add(timerCueId);
        processed.add(commandId);
        CookingState updated = new CookingState(
                state.version() + 1,
                state.completedActionIds(),
                state.observedConditionIds(),
                state.offeredTimerCueIds(),
                requested,
                running,
                processed,
                state.focusedSourceStepNumber(),
                state.ingredientAdjustments(),
                state.completedSourceStepNumbers(), state.skippedSourceStepNumbers());
        return new Outcome(
                updated,
                List.of(new Event(EventType.TIMER_STARTED, timerCueId)),
                plan.availableActionIds(updated),
                false);
    }

    /** Time passage is a server event, never a model inference or a user completion report. */
    public static Outcome expireTimers(CookingPlan plan, CookingState state, Set<String> expiredTimerCueIds) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiredTimerCueIds, "expiredTimerCueIds");
        validateState(plan, state);
        if (!plan.timerCues().keySet().containsAll(expiredTimerCueIds)) {
            throw new IllegalArgumentException("Unknown expired timer cue");
        }
        Set<String> running = new HashSet<>(state.runningTimerCueIds());
        List<Event> events = new ArrayList<>();
        for (String timerCueId : expiredTimerCueIds.stream().sorted().toList()) {
            change(running, timerCueId, false, EventType.TIMER_ELAPSED, events);
        }
        if (events.isEmpty()) {
            return new Outcome(state, List.of(), plan.availableActionIds(state), true);
        }
        CookingState updated = new CookingState(
                state.version() + 1,
                state.completedActionIds(),
                state.observedConditionIds(),
                state.offeredTimerCueIds(),
                state.requestedTimerCueIds(),
                running,
                state.processedCommandIds(),
                state.focusedSourceStepNumber(),
                state.ingredientAdjustments(),
                state.completedSourceStepNumbers(), state.skippedSourceStepNumbers());
        return new Outcome(updated, events, plan.availableActionIds(updated), false);
    }

    private static void validateDecision(
            CookingPlan plan, CookingState state, String transcript, TurnDecision decision) {
        if (!plan.sourceStepNumbers().containsAll(decision.citedSourceSteps())
                || !plan.sourceStepNumbers().containsAll(decision.photoSourceSteps())) {
            throw new IllegalArgumentException("Decision refers to an unknown source step");
        }
        if (!decision.citedSourceSteps().containsAll(decision.photoSourceSteps())) {
            throw new IllegalArgumentException("Photo source steps must be cited in the decision");
        }
        boolean hasMutations = !decision.reports().isEmpty()
                || !decision.ingredientReports().isEmpty()
                || !decision.timerOfferIds().isEmpty()
                || !decision.timerAcceptanceIds().isEmpty();
        if (decision.addressing() != TurnDecision.Addressing.DIRECT && hasMutations) {
            throw new IllegalArgumentException("Unrelated or uncertain speech cannot change state");
        }
        if ((decision.speechAct() == TurnDecision.SpeechAct.QUESTION_ONLY
                || decision.speechAct() == TurnDecision.SpeechAct.OTHER)
                && (!decision.reports().isEmpty() || !decision.ingredientReports().isEmpty())) {
            throw new IllegalArgumentException("A question or non-report cannot assert a completed action");
        }
        for (IngredientAdjustment report : decision.ingredientReports()) {
            if (!UserEvidencePolicy.containsExactSpan(transcript, report.evidence())
                    || !report.evidence().contains(report.ingredientName())
                    || (!report.replacementName().isEmpty()
                        && !report.evidence().contains(report.replacementName()))
                    || (!report.quantityText().isEmpty()
                        && !report.evidence().contains(report.quantityText()))) {
                throw new IllegalArgumentException("Ingredient change must be grounded in the user's latest statement");
            }
            if (!UserEvidencePolicy.permitsReport(transcript, report.evidence(),
                    UserEvidencePolicy.Purpose.valueOf(report.kind().name()))) {
                throw new UncertainReportEvidenceException();
            }
        }
        if ((decision.speechAct() == TurnDecision.SpeechAct.QUESTION_ONLY
                || decision.speechAct() == TurnDecision.SpeechAct.OTHER)
                && !decision.timerAcceptanceIds().isEmpty()) {
            throw new IllegalArgumentException("A question or non-command cannot accept a timer");
        }
        for (TurnDecision.UserReport report : decision.reports()) {
            boolean actionReport = report.kind() == TurnDecision.ReportKind.ACTION_DONE
                    || report.kind() == TurnDecision.ReportKind.ACTION_RETRACTED;
            if (actionReport && !plan.actions().containsKey(report.targetId())) {
                throw new IllegalArgumentException("Unknown reported action: " + report.targetId());
            }
            if (!actionReport && !plan.conditions().containsKey(report.targetId())) {
                throw new IllegalArgumentException("Unknown reported condition: " + report.targetId());
            }
            if (!UserEvidencePolicy.containsExactSpan(transcript, report.evidence())) {
                throw new IllegalArgumentException("Report evidence is not a complete statement span in the latest transcript");
            }
            if (!UserEvidencePolicy.permitsReport(transcript, report.evidence(),
                    UserEvidencePolicy.Purpose.valueOf(report.kind().name()))) {
                throw new UncertainReportEvidenceException();
            }
        }
        for (String timerCueId : decision.timerOfferIds()) {
            if (!plan.timerCues().containsKey(timerCueId)) {
                throw new IllegalArgumentException("Timer has no explicit recipe source: " + timerCueId);
            }
        }
        for (String timerCueId : decision.timerAcceptanceIds()) {
            if (!plan.timerCues().containsKey(timerCueId)) {
                throw new IllegalArgumentException("Unknown timer cue: " + timerCueId);
            }
            if (!state.offeredTimerCueIds().contains(timerCueId)) {
                throw new IllegalArgumentException("Timer was not offered: " + timerCueId);
            }
            if (state.offeredTimerCueIds().size() != 1 || decision.timerAcceptanceIds().size() != 1
                    || !UserEvidencePolicy.permitsTimerConsent(transcript)) {
                throw new UncertainReportEvidenceException();
            }
        }
    }

    /** Grounded words were present, but their polarity or current factual meaning is ambiguous. */
    public static final class UncertainReportEvidenceException extends IllegalArgumentException {
        private UncertainReportEvidenceException() {
            super("User report or timer consent needs clarification");
        }
    }

    private static void validateState(CookingPlan plan, CookingState state) {
        if (!plan.actions().keySet().containsAll(state.completedActionIds())
                || !plan.conditions().keySet().containsAll(state.observedConditionIds())
                || !plan.timerCues().keySet().containsAll(state.offeredTimerCueIds())
                || !plan.timerCues().keySet().containsAll(state.requestedTimerCueIds())
                || !plan.timerCues().keySet().containsAll(state.runningTimerCueIds())
                || !plan.sourceStepNumbers().containsAll(state.completedSourceStepNumbers())
                || !plan.sourceStepNumbers().containsAll(state.skippedSourceStepNumbers())
                || (state.focusedSourceStepNumber() != null
                    && !plan.sourceStepNumbers().contains(state.focusedSourceStepNumber()))) {
            throw new IllegalArgumentException("Cooking state does not belong to this plan");
        }
    }

    private static void change(Set<String> target, String id, boolean add, EventType type, List<Event> events) {
        boolean changed = add ? target.add(id) : target.remove(id);
        if (changed) {
            events.add(new Event(type, id));
        }
    }

    private static void requireCommandId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Client command id must be nonempty");
        }
    }

    public enum EventType {
        SOURCE_STEP_VIEWED,
        SOURCE_STEP_COMPLETED,
        SOURCE_STEP_SKIPPED,
        SOURCE_STEP_PENDING,
        INGREDIENT_REPORTED,
        ACTION_DONE,
        ACTION_RETRACTED,
        CONDITION_OBSERVED,
        CONDITION_RETRACTED,
        TIMER_OFFERED,
        TIMER_OFFER_CLEARED,
        TIMER_START_REQUESTED,
        TIMER_STARTED,
        TIMER_ELAPSED
    }

    /** Viewing a step is allowed in any order and does not assert completion of earlier steps. */
    public static Outcome navigate(CookingPlan plan, CookingState state, String clientNavigationId,
            int sourceStepNumber) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        requireCommandId(clientNavigationId);
        validateState(plan, state);
        if (!plan.sourceStepNumbers().contains(sourceStepNumber)) {
            throw new IllegalArgumentException("Unknown source step: " + sourceStepNumber);
        }
        String commandId = "navigation:" + clientNavigationId;
        if (state.processedCommandIds().contains(commandId)) {
            return new Outcome(state, List.of(), plan.availableActionIds(state), true);
        }
        Set<String> processed = new HashSet<>(state.processedCommandIds());
        processed.add(commandId);
        CookingState updated = new CookingState(state.version() + 1, state.completedActionIds(),
                state.observedConditionIds(), state.offeredTimerCueIds(), state.requestedTimerCueIds(),
                state.runningTimerCueIds(), processed, sourceStepNumber, state.ingredientAdjustments(),
                state.completedSourceStepNumbers(), state.skippedSourceStepNumbers());
        return new Outcome(updated,
                List.of(new Event(EventType.SOURCE_STEP_VIEWED, Integer.toString(sourceStepNumber))),
                plan.availableActionIds(updated), false);
    }

    public enum StepProgressStatus { COMPLETED, SKIPPED, PENDING }

    /** An explicit user button action. Skipping records a choice, never satisfaction of a prerequisite. */
    public static Outcome setStepProgress(CookingPlan plan, CookingState state, String clientCommandId,
            int sourceStepNumber, StepProgressStatus status) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(status, "status");
        requireCommandId(clientCommandId);
        validateState(plan, state);
        if (!plan.sourceStepNumbers().contains(sourceStepNumber)) {
            throw new IllegalArgumentException("Unknown source step: " + sourceStepNumber);
        }
        String commandId = "stepProgress:" + clientCommandId;
        if (state.processedCommandIds().contains(commandId)) {
            return new Outcome(state, List.of(), plan.availableActionIds(state), true);
        }
        Set<Integer> completedSteps = new HashSet<>(state.completedSourceStepNumbers());
        Set<Integer> skippedSteps = new HashSet<>(state.skippedSourceStepNumbers());
        Set<String> completedActions = new HashSet<>(state.completedActionIds());
        List<Event> events = new ArrayList<>();
        switch (status) {
            case COMPLETED -> {
                completedSteps.add(sourceStepNumber);
                skippedSteps.remove(sourceStepNumber);
                for (CookingPlan.Action action : plan.actions().values()) {
                    if (action.sourceStepNumbers().contains(sourceStepNumber)
                            && completedSteps.containsAll(action.sourceStepNumbers())) {
                        change(completedActions, action.id(), true, EventType.ACTION_DONE, events);
                    }
                }
                events.add(new Event(EventType.SOURCE_STEP_COMPLETED, Integer.toString(sourceStepNumber)));
            }
            case SKIPPED, PENDING -> {
                completedSteps.remove(sourceStepNumber);
                if (status == StepProgressStatus.SKIPPED) {
                    skippedSteps.add(sourceStepNumber);
                } else {
                    skippedSteps.remove(sourceStepNumber);
                }
                for (CookingPlan.Action action : plan.actions().values()) {
                    if (action.sourceStepNumbers().contains(sourceStepNumber)) {
                        change(completedActions, action.id(), false, EventType.ACTION_RETRACTED, events);
                    }
                }
                events.add(new Event(status == StepProgressStatus.SKIPPED
                        ? EventType.SOURCE_STEP_SKIPPED : EventType.SOURCE_STEP_PENDING,
                        Integer.toString(sourceStepNumber)));
            }
        }
        Set<String> processed = new HashSet<>(state.processedCommandIds());
        processed.add(commandId);
        CookingState updated = new CookingState(state.version() + 1, completedActions,
                state.observedConditionIds(), state.offeredTimerCueIds(), state.requestedTimerCueIds(),
                state.runningTimerCueIds(), processed, state.focusedSourceStepNumber(),
                state.ingredientAdjustments(), completedSteps, skippedSteps);
        return new Outcome(updated, events, plan.availableActionIds(updated), false);
    }

    private static void synchronizeCompletedSteps(CookingPlan plan, Set<String> completedActions,
            Set<Integer> completedSteps, Set<Integer> changedSourceSteps, Set<Integer> retractedSourceSteps) {
        for (Integer sourceStep : changedSourceSteps) {
            Set<String> actionIds = plan.actions().values().stream()
                    .filter(action -> action.sourceStepNumbers().contains(sourceStep))
                    .map(CookingPlan.Action::id).collect(java.util.stream.Collectors.toSet());
            if (!actionIds.isEmpty()) {
                if (completedActions.containsAll(actionIds)) {
                    completedSteps.add(sourceStep);
                } else if (retractedSourceSteps.contains(sourceStep)) {
                    completedSteps.remove(sourceStep);
                }
            }
        }
    }

    public record Event(EventType type, String targetId) {
        public Event {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    public record Outcome(CookingState state, List<Event> events, List<String> availableActionIds, boolean duplicate) {
        public Outcome {
            Objects.requireNonNull(state, "state");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            availableActionIds = List.copyOf(Objects.requireNonNull(availableActionIds, "availableActionIds"));
        }
    }
}
