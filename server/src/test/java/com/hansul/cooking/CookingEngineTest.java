package com.hansul.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CookingEngineTest {

    private final CookingPlan plan = plan();

    @Test
    void questionCannotClaimCompletedAction() {
        TurnDecision invalid = decision(
                TurnDecision.SpeechAct.QUESTION_ONLY,
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "prepare", "재료 넣어?")),
                List.of(),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-1", "재료 넣어?", invalid));
        assertTrue(CookingState.empty().completedActionIds().isEmpty());
    }

    @Test
    void mixedReportAndQuestionOnlyRecordWhatUserReported() {
        TurnDecision mixed = decision(
                TurnDecision.SpeechAct.MIXED,
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "prepare", "재료 준비했어")),
                List.of(),
                List.of());

        CookingEngine.Outcome result = CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-2", "재료 준비했어. 물 끓으면 넣어?", mixed);

        assertTrue(result.state().completedActionIds().contains("prepare"));
        assertFalse(result.state().observedConditionIds().contains("boiling"));
        assertFalse(result.state().completedActionIds().contains("add"));
        assertEquals(List.of(CookingEngine.EventType.ACTION_DONE),
                result.events().stream().map(CookingEngine.Event::type).toList());
        assertTrue(result.availableActionIds().isEmpty());
    }

    @Test
    void reportedConditionUnlocksRelevantAction() {
        CookingState prepared = CookingEngine.applyTurn(
                plan,
                CookingState.empty(),
                "turn-1",
                "준비했어",
                decision(
                        TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                        List.of(new TurnDecision.UserReport(
                                TurnDecision.ReportKind.ACTION_DONE, "prepare", "준비했어")),
                        List.of(),
                        List.of())).state();

        CookingEngine.Outcome result = CookingEngine.applyTurn(
                plan,
                prepared,
                "turn-2",
                "물이 끓고 있어",
                decision(
                        TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                        List.of(new TurnDecision.UserReport(
                                TurnDecision.ReportKind.CONDITION_OBSERVED, "boiling", "물이 끓고 있어")),
                        List.of(),
                        List.of()));

        assertEquals(List.of("add"), result.availableActionIds());
    }

    @Test
    void timerMustComeFromRecipeAndBeOfferedBeforeAcceptance() {
        TurnDecision madeUpTimer = decision(
                TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of("unknown"), List.of());
        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-1", "타이머 맞춰줘", madeUpTimer));

        TurnDecision prematureAcceptance = decision(
                TurnDecision.SpeechAct.REPORT_OR_COMMAND, List.of(), List.of(), List.of("soak"));
        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-2", "응", prematureAcceptance));

        CookingState offered = CookingEngine.applyTurn(
                plan,
                CookingState.empty(),
                "turn-3",
                "얼마나 담가?",
                decision(TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of("soak"), List.of())).state();
        CookingEngine.Outcome accepted = CookingEngine.applyTurn(
                plan, offered, "turn-4", "응 맞춰줘", prematureAcceptance);

        assertTrue(accepted.state().requestedTimerCueIds().contains("soak"));
        assertFalse(accepted.state().runningTimerCueIds().contains("soak"));

        CookingEngine.Outcome acknowledged = CookingEngine.acknowledgeTimerStart(
                plan, accepted.state(), "ack-1", "soak");
        assertTrue(acknowledged.state().runningTimerCueIds().contains("soak"));
        assertTrue(acknowledged.state().requestedTimerCueIds().isEmpty());
    }

    @Test
    void explicitTimerTapRequestsSourceCueWithoutOfferAndStillNeedsDeviceAck() {
        CookingEngine.Outcome request = CookingEngine.requestTimerStart(plan, CookingState.empty(), "tap", "soak");
        assertEquals(Set.of("soak"), request.state().requestedTimerCueIds());
        assertTrue(request.state().runningTimerCueIds().isEmpty());
        assertTrue(request.state().completedActionIds().isEmpty());
        assertTrue(CookingEngine.requestTimerStart(plan, request.state(), "tap", "soak").duplicate());
        assertThrows(IllegalStateException.class,
                () -> CookingEngine.requestTimerStart(plan, request.state(), "other-tap", "soak"));
        assertThrows(IllegalArgumentException.class,
                () -> CookingEngine.requestTimerStart(plan, CookingState.empty(), "unknown-tap", "invented"));
        CookingState running = CookingEngine.acknowledgeTimerStart(plan, request.state(), "ack-tap", "soak").state();
        assertEquals(Set.of("soak"), running.runningTimerCueIds());
        assertThrows(IllegalStateException.class,
                () -> CookingEngine.requestTimerStart(plan, running, "running-tap", "soak"));

        CookingState offered = CookingEngine.applyTurn(plan, CookingState.empty(), "offer-tap", "몇 분?",
                decision(TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of("soak"), List.of())).state();
        CookingState accepted = CookingEngine.requestTimerStart(plan, offered, "accept-offer-tap", "soak").state();
        assertTrue(accepted.offeredTimerCueIds().isEmpty());
        assertEquals(Set.of("soak"), accepted.requestedTimerCueIds());
    }

    @Test
    void duplicateTurnAndTimerAckDoNotRepeatEvents() {
        TurnDecision offer = decision(
                TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of("soak"), List.of());
        CookingEngine.Outcome first = CookingEngine.applyTurn(
                plan, CookingState.empty(), "same-id", "타이머 필요해?", offer);
        CookingEngine.Outcome retry = CookingEngine.applyTurn(
                plan, first.state(), "same-id", "타이머 필요해?", offer);

        assertTrue(retry.duplicate());
        assertEquals(first.state(), retry.state());
        assertTrue(retry.events().isEmpty());

        CookingState requested = CookingEngine.applyTurn(
                plan,
                first.state(),
                "accept-id",
                "응",
                decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND, List.of(), List.of(), List.of("soak"))).state();
        CookingEngine.Outcome ack = CookingEngine.acknowledgeTimerStart(plan, requested, "ack-id", "soak");
        CookingEngine.Outcome ackRetry = CookingEngine.acknowledgeTimerStart(plan, ack.state(), "ack-id", "soak");
        assertTrue(ackRetry.duplicate());
        assertTrue(ackRetry.events().isEmpty());
    }

    @Test
    void rejectsReportWithInventedEvidence() {
        TurnDecision decision = decision(
                TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "prepare", "준비 완료")),
                List.of(),
                List.of());
        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-1", "아직 준비 중이야", decision));
    }

    @Test
    void questionMarkImmediatelyAfterReportEvidenceDoesNotCountAsCompletion() {
        TurnDecision mistaken = decision(
                TurnDecision.SpeechAct.MIXED,
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "add", "넣어")),
                List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-question", "지금 넣어?", mistaken));
    }

    @Test
    void incompleteWordEvidenceCannotTurnAnUnpunctuatedQuestionIntoProgress() {
        TurnDecision mistaken = decision(
                TurnDecision.SpeechAct.MIXED,
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "add", "넣어")),
                List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "turn-unpunctuated", "지금 넣어야 돼", mistaken));
    }

    @Test
    void bareYesCannotClaimAnArbitraryActionWithoutItsOwnReport() {
        TurnDecision mistaken = decision(
                TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "prepare", "응")),
                List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(
                plan, CookingState.empty(), "bare-yes", "응", mistaken));
    }

    @Test
    void elapsedTimerLeavesRunningStateOnlyOnce() {
        CookingState offered = CookingEngine.applyTurn(
                plan, CookingState.empty(), "offer", "타이머는?",
                decision(TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of("soak"), List.of())).state();
        CookingState requested = CookingEngine.applyTurn(
                plan, offered, "accept", "응 맞춰줘",
                decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND, List.of(), List.of(), List.of("soak"))).state();
        CookingState running = CookingEngine.acknowledgeTimerStart(plan, requested, "ack", "soak").state();

        CookingEngine.Outcome elapsed = CookingEngine.expireTimers(plan, running, Set.of("soak"));
        CookingEngine.Outcome duplicate = CookingEngine.expireTimers(plan, elapsed.state(), Set.of("soak"));

        assertTrue(elapsed.state().runningTimerCueIds().isEmpty());
        assertEquals(List.of(CookingEngine.EventType.TIMER_ELAPSED),
                elapsed.events().stream().map(CookingEngine.Event::type).toList());
        assertTrue(duplicate.duplicate());
        assertEquals(elapsed.state(), duplicate.state());
    }

    @Test
    void rejectsCyclicPlan() {
        Map<String, CookingPlan.Action> actions = Map.of(
                "a", new CookingPlan.Action("a", Set.of(1), Set.of("b"), Set.of()),
                "b", new CookingPlan.Action("b", Set.of(2), Set.of("a"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CookingPlan(
                actions, Map.of(), Map.of(), Set.of(1, 2)));
    }

    @Test
    void viewingLaterStepDoesNotCompleteEarlierActionsOrObserveConditions() {
        CookingEngine.Outcome result = CookingEngine.navigate(plan, CookingState.empty(), "view-2", 2);

        assertEquals(2, result.state().focusedSourceStepNumber());
        assertTrue(result.state().completedActionIds().isEmpty());
        assertTrue(result.state().completedSourceStepNumbers().isEmpty());
        assertTrue(result.state().observedConditionIds().isEmpty());
        assertEquals(List.of("prepare"), result.availableActionIds());
        assertTrue(CookingEngine.navigate(plan, result.state(), "view-2", 2).duplicate());
        assertThrows(IllegalArgumentException.class,
                () -> CookingEngine.navigate(plan, result.state(), "view-99", 99));
    }

    @Test
    void skipDoesNotSatisfyPrerequisitesAndCompleteThenUndoDoesNotInventBoiling() {
        CookingState skipped = CookingEngine.setStepProgress(plan, CookingState.empty(), "skip-1", 1,
                CookingEngine.StepProgressStatus.SKIPPED).state();
        assertEquals(Set.of(1), skipped.skippedSourceStepNumbers());
        assertTrue(skipped.completedActionIds().isEmpty());
        assertFalse(plan.availableActionIds(skipped).contains("add"));

        CookingState completed = CookingEngine.setStepProgress(plan, skipped, "complete-1", 1,
                CookingEngine.StepProgressStatus.COMPLETED).state();
        assertEquals(Set.of("prepare"), completed.completedActionIds());
        assertEquals(Set.of(1), completed.completedSourceStepNumbers());
        assertTrue(completed.skippedSourceStepNumbers().isEmpty());
        assertTrue(completed.observedConditionIds().isEmpty());
        assertFalse(plan.availableActionIds(completed).contains("add"));

        CookingState undone = CookingEngine.setStepProgress(plan, completed, "undo-1", 1,
                CookingEngine.StepProgressStatus.PENDING).state();
        assertTrue(undone.completedActionIds().isEmpty());
        assertTrue(undone.completedSourceStepNumbers().isEmpty());
    }

    @Test
    void multiStepActionRequiresEveryExplicitSourceStepCompletionAndQuestionKeepsPartialProgress() {
        CookingPlan grouped = new CookingPlan(Map.of("group", new CookingPlan.Action("group", Set.of(1, 2),
                Set.of(), Set.of())), Map.of(), Map.of(), Set.of(1, 2));
        CookingState first = CookingEngine.setStepProgress(grouped, CookingState.empty(), "one", 1,
                CookingEngine.StepProgressStatus.COMPLETED).state();
        assertTrue(first.completedActionIds().isEmpty());
        CookingState afterQuestion = CookingEngine.applyTurn(grouped, first, "question", "얼마나?",
                decision(TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of(), List.of())).state();
        assertEquals(Set.of(1), afterQuestion.completedSourceStepNumbers());
        CookingState all = CookingEngine.setStepProgress(grouped, afterQuestion, "two", 2,
                CookingEngine.StepProgressStatus.COMPLETED).state();
        assertEquals(Set.of("group"), all.completedActionIds());
    }

    @Test
    void ingredientQuestionsCannotBecomeAcceptedSubstitutionsAndUserFactsAreCorrectable() {
        IngredientAdjustment missing = new IngredientAdjustment(IngredientAdjustment.Kind.MISSING,
                "양파", "", "", "양파 없어");
        TurnDecision report = new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.MIXED,
                "대체 재료를 확인할게요.", Set.of(1), Set.of(), List.of(), List.of(), List.of(), List.of(missing));
        CookingState first = CookingEngine.applyTurn(plan, CookingState.empty(), "missing",
                "양파 없어. 대신 뭘 넣어?", report).state();
        assertEquals(List.of(missing), first.ingredientAdjustments());
        assertTrue(first.completedActionIds().isEmpty());

        IngredientAdjustment replaced = new IngredientAdjustment(IngredientAdjustment.Kind.REPLACED,
                "양파", "대파", "", "양파 대신 대파 넣었어");
        TurnDecision replacement = new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                "변경을 반영했어요.", Set.of(1), Set.of(), List.of(), List.of(), List.of(), List.of(replaced));
        CookingState corrected = CookingEngine.applyTurn(plan, first, "replacement",
                "양파 대신 대파 넣었어", replacement).state();
        assertEquals(List.of(replaced), corrected.ingredientAdjustments());
        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(plan, first, "question-replacement",
                "양파 대신 대파 넣었어?", replacement));
        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(plan, first, "invented-replacement",
                "당근을 넣었어", replacement));
    }

    @Test
    void photoMustReferToAnActuallyCitedSourceStep() {
        TurnDecision uncited = new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.QUESTION_ONLY,
                "사진이에요", Set.of(1), Set.of(2), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> CookingEngine.applyTurn(plan, CookingState.empty(),
                "photo", "어떻게 썰어?", uncited));
    }

    @Test
    void negativeQuestionHypothesisFutureAndQuotedActionCannotBecomeCompleted() {
        for (String text : List.of("재료 안 준비했어", "재료 못 준비했어", "재료 준비한 건 아니야",
                "재료 준비했어?", "재료 준비했다면 다음은", "재료 준비하려고 했어",
                "재료 준비하지 않았어", "재료 준비했지만 지금 완료한 건 아니야",
                "이전에는 안 했지만 지금 재료 준비했어", "친구가 재료 준비했다고 했어",
                "재료 준비했어. 아니 준비 안 했어")) {
            TurnDecision report = decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                    List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.ACTION_DONE, "prepare", text)),
                    List.of(), List.of());
            assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                    () -> CookingEngine.applyTurn(plan, CookingState.empty(), "negative", text, report), text);
        }
        TurnDecision fragment = decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.ACTION_DONE, "add", "넣었어")),
                List.of(), List.of());
        assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                () -> CookingEngine.applyTurn(plan, CookingState.empty(), "fragment", "양파 안 넣었어", fragment));
        TurnDecision instruction = decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.ACTION_DONE, "add", "양파 넣어줘")),
                List.of(), List.of());
        assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                () -> CookingEngine.applyTurn(plan, CookingState.empty(), "instruction", "양파 넣어줘", instruction));
    }

    @Test
    void absenceAndAvailabilityAreNotTheSamePolarityAsCompletedIngredientUse() {
        for (String text : List.of("버터 없어", "버터 아직 없어", "버터 부족해")) {
            IngredientAdjustment report = new IngredientAdjustment(IngredientAdjustment.Kind.MISSING,
                    "버터", "", "", text);
            CookingState state = CookingEngine.applyTurn(plan, CookingState.empty(), "shortage", text,
                    ingredientDecision(report)).state();
            assertEquals(List.of(report), state.ingredientAdjustments(), text);
            assertTrue(state.completedActionIds().isEmpty());
        }
        for (String text : List.of("버터 없는 건 아니야", "버터 없지 않아", "버터 없는 거 아냐",
                "버터 없다고는 안 했어", "버터 없으면 우유 써도 돼", "버터 없나",
                "버터는 있고 양파는 없어")) {
            IngredientAdjustment report = new IngredientAdjustment(IngredientAdjustment.Kind.MISSING,
                    "버터", "", "", text);
            assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                    () -> CookingEngine.applyTurn(plan, CookingState.empty(), "ambiguous-shortage", text,
                            ingredientDecision(report)), text);
        }
        IngredientAdjustment notAdded = new IngredientAdjustment(IngredientAdjustment.Kind.ADDED,
                "버터", "", "", "버터 안 넣었어");
        assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                () -> CookingEngine.applyTurn(plan, CookingState.empty(), "not-added", notAdded.evidence(),
                        ingredientDecision(notAdded)));
        IngredientAdjustment futureReplacement = new IngredientAdjustment(IngredientAdjustment.Kind.REPLACED,
                "버터", "우유", "", "버터 대신 우유 넣으려고 했어");
        assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                () -> CookingEngine.applyTurn(plan, CookingState.empty(), "future", futureReplacement.evidence(),
                        ingredientDecision(futureReplacement)));
        IngredientAdjustment available = new IngredientAdjustment(IngredientAdjustment.Kind.AVAILABLE,
                "버터", "", "", "버터 찾았어");
        assertEquals(List.of(available), CookingEngine.applyTurn(plan, CookingState.empty(), "found",
                available.evidence(), ingredientDecision(available)).state().ingredientAdjustments());
    }

    @Test
    void currentConditionsRequireAssertiveObservationButExplicitRetractionCanBeNegative() {
        TurnDecision denied = decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.CONDITION_OBSERVED,
                        "boiling", "물이 안 끓고 있어")), List.of(), List.of());
        assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                () -> CookingEngine.applyTurn(plan, CookingState.empty(), "not-boiling", "물이 안 끓고 있어", denied));
        CookingState completed = CookingEngine.setStepProgress(plan, CookingState.empty(), "done", 1,
                CookingEngine.StepProgressStatus.COMPLETED).state();
        TurnDecision retracted = decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.ACTION_RETRACTED,
                        "prepare", "재료 준비 안 했어")), List.of(), List.of());
        assertTrue(CookingEngine.applyTurn(plan, completed, "retract", "재료 준비 안 했어", retracted)
                .state().completedActionIds().isEmpty());
    }

    @Test
    void timerAcceptanceCannotBeQuestionNegativeHypothesisOrLaterCancellation() {
        CookingState offered = CookingEngine.applyTurn(plan, CookingState.empty(), "offer-consent", "몇 분?",
                decision(TurnDecision.SpeechAct.QUESTION_ONLY, List.of(), List.of("soak"), List.of())).state();
        TurnDecision consent = decision(TurnDecision.SpeechAct.REPORT_OR_COMMAND, List.of(), List.of(), List.of("soak"));
        for (String text : List.of("타이머 맞춰줘?", "타이머 안 맞춰줘", "타이머 시작하면 될까",
                "타이머 시작해줘, 아니 안 할래", "응 아닌데", "타이머 필요 없어")) {
            assertThrows(CookingEngine.UncertainReportEvidenceException.class,
                    () -> CookingEngine.applyTurn(plan, offered, "bad-consent", text, consent), text);
        }
        assertEquals(Set.of("soak"), CookingEngine.applyTurn(plan, offered, "good-consent", "타이머 시작해줘", consent)
                .state().requestedTimerCueIds());
    }

    @Test
    void validIngredientNameDoesNotTriggerHypothesisFromTheNounRamen() {
        IngredientAdjustment report = new IngredientAdjustment(IngredientAdjustment.Kind.ADDED,
                "라면", "", "", "라면 넣었어");
        assertEquals(List.of(report), CookingEngine.applyTurn(plan, CookingState.empty(), "ramen",
                report.evidence(), ingredientDecision(report)).state().ingredientAdjustments());
    }

    private static TurnDecision ingredientDecision(IngredientAdjustment report) {
        return new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                "확인했어요", Set.of(), Set.of(), List.of(), List.of(), List.of(), List.of(report));
    }

    private static TurnDecision decision(
            TurnDecision.SpeechAct speechAct,
            List<TurnDecision.UserReport> reports,
            List<String> timerOffers,
            List<String> timerAcceptances) {
        return new TurnDecision(
                TurnDecision.Addressing.DIRECT,
                speechAct,
                "알겠습니다",
                Set.of(1),
                Set.of(),
                reports,
                timerOffers,
                timerAcceptances);
    }

    private static CookingPlan plan() {
        Map<String, CookingPlan.Action> actions = new LinkedHashMap<>();
        actions.put("prepare", new CookingPlan.Action("prepare", Set.of(1), Set.of(), Set.of()));
        actions.put("add", new CookingPlan.Action("add", Set.of(2), Set.of("prepare"), Set.of("boiling")));
        return new CookingPlan(
                actions,
                Map.of("boiling", new CookingPlan.Condition("boiling", Set.of(2))),
                Map.of("soak", new CookingPlan.TimerCue("soak", Duration.ofMinutes(5), 1)),
                Set.of(1, 2));
    }
}
