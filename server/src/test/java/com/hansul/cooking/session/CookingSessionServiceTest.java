package com.hansul.cooking.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hansul.auth.api.AuthenticatedUser;
import com.hansul.auth.api.AuthenticatedUserId;
import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.cooking.CookingEngine;
import com.hansul.cooking.CookingState;
import com.hansul.cooking.TurnDecision;
import com.hansul.cooking.ai.AgentDecisionProvider;
import com.hansul.cooking.ai.AgentTurnContext;
import com.hansul.cooking.ai.RecipePlanBuilder;
import com.hansul.cooking.ai.StructuredOutputClient;
import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.application.RecipeImportService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CookingSessionServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RecipeImportResponse recipe = recipe();
    private final RecipeImportService recipes = mock(RecipeImportService.class);
    private final FakeStore store = new FakeStore();
    private final AtomicReference<String> principal = new AtomicReference<>("user-a");
    private final CurrentUserProvider users = () -> Optional.of(new AuthenticatedUser(
            new AuthenticatedUserId(principal.get()), null, null));
    private final RecipePlanBuilder builder = new RecipePlanBuilder(unavailableModel());
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void reportChangesOnlyExistingRecipeActionAndRetryDoesNotRepeatInference() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        AtomicInteger calls = new AtomicInteger();
        AgentDecisionProvider provider = context -> {
            calls.incrementAndGet();
            return new TurnDecision(
                    TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.MIXED,
                    "이제 양파를 넣으세요.", Set.of(2), Set.of(2),
                    List.of(new TurnDecision.UserReport(
                            TurnDecision.ReportKind.ACTION_DONE, "source-step-1", "물 올렸어")),
                    List.of(), List.of());
        };
        CookingSessionService service = service(provider);
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();

        CookingSessionService.TurnView first = service.turn(sessionId, "turn-1", "물 올렸어. 이제? ");
        CookingSessionService.TurnView retry = service.turn(sessionId, "turn-1", "물 올렸어. 이제? ");

        assertEquals(1, calls.get());
        assertTrue(first.state().completedActionIds().contains("source-step-1"));
        assertEquals(List.of("source-step-2"), first.availableActionIds());
        assertEquals(List.of(2), first.photos().stream().map(CookingSessionService.PhotoCue::sourceStepNumber).toList());
        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertEquals(first.state(), retry.state());
        assertEquals(1, store.events.size());
    }

    @Test
    void unrelatedSpeechIsSilentAndCannotInventProgress() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        CookingSessionService service = service(context -> new TurnDecision(
                TurnDecision.Addressing.UNRELATED, TurnDecision.SpeechAct.OTHER,
                "This must never be spoken", Set.of(), Set.of(), List.of(), List.of(), List.of()));
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();

        CookingSessionService.TurnView result = service.turn(sessionId, "turn-2", "그릇 좀 줘");

        assertEquals("", result.replyText());
        assertTrue(result.state().completedActionIds().isEmpty());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void badModelProposalAndReusedCommandIdAreRejectedWithoutMutation() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        CookingSessionService service = service(context -> new TurnDecision(
                TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.QUESTION_ONLY,
                "네", Set.of(1), Set.of(),
                List.of(new TurnDecision.UserReport(
                        TurnDecision.ReportKind.ACTION_DONE, "source-step-1", "물 올렸어")),
                List.of(), List.of()));
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.turn(sessionId, "bad", "물 올렸어?"));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertEquals(CookingState.empty(), service.get(sessionId).state());

        CookingSessionService goodService = service(context -> new TurnDecision(
                TurnDecision.Addressing.UNRELATED, TurnDecision.SpeechAct.OTHER,
                "", Set.of(), Set.of(), List.of(), List.of(), List.of()));
        goodService.turn(sessionId, "same", "안녕");
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> goodService.turn(sessionId, "same", "다른 발화"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    @Test
    void groundedButNegativeOrAmbiguousProposalClarifiesWithoutChangingCookingFacts() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        AtomicInteger calls = new AtomicInteger();
        CookingSessionService service = service(context -> {
            calls.incrementAndGet();
            return new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                    "완료했네요. 다음 단계입니다.", Set.of(1), Set.of(),
                    List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.ACTION_DONE,
                            "source-step-1", "물 올렸어")), List.of(), List.of());
        });
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        service.navigate(sessionId, "focus", 2);
        service.setStepProgress(sessionId, "skip", 2, CookingEngine.StepProgressStatus.SKIPPED);
        CookingState before = service.get(sessionId).state();

        CookingSessionService.TurnView result = service.turn(sessionId, "uncertain", "물 올렸어. 아니 아직 안 올렸어");
        CookingSessionService.TurnView retry = service.turn(sessionId, "uncertain", "물 올렸어. 아니 아직 안 올렸어");

        assertEquals(TurnDecision.Addressing.UNCERTAIN, result.addressing());
        assertTrue(result.replyText().contains("다시"));
        assertTrue(result.citedSourceSteps().isEmpty());
        assertTrue(result.photos().isEmpty());
        assertTrue(result.events().isEmpty());
        assertEquals(before.completedActionIds(), result.state().completedActionIds());
        assertEquals(before.observedConditionIds(), result.state().observedConditionIds());
        assertEquals(before.focusedSourceStepNumber(), result.state().focusedSourceStepNumber());
        assertEquals(before.skippedSourceStepNumbers(), result.state().skippedSourceStepNumbers());
        assertEquals(before.ingredientAdjustments(), result.state().ingredientAdjustments());
        assertEquals(before.requestedTimerCueIds(), result.state().requestedTimerCueIds());
        assertTrue(retry.duplicate());
        assertEquals(1, calls.get());
        assertEquals(result.state(), retry.state());
    }

    @Test
    void manualNavigationAndTimerDuringModelInferenceWinAndStaleProposalCannotOverwriteThem() {
        RecipeImportResponse timedRecipe = new RecipeImportResponse(recipe.id(), recipe.sourceUrl(), recipe.title(),
                recipe.baseServings(), recipe.ingredients(),
                List.of(new RecipeImportResponse.Step(1, "물을 올립니다. 5분 끓입니다.", null,
                                "물을 올립니다. 5분 끓입니다.", null), recipe.steps().get(1)),
                recipe.baseServingsText(), recipe.description(), recipe.totalTimeText(), recipe.primaryImageUrl(),
                recipe.parserVersion(), recipe.importedAt(), recipe.warnings());
        when(recipes.getRecipe(recipe.id())).thenReturn(timedRecipe);
        AtomicReference<CookingSessionService> activeService = new AtomicReference<>();
        AtomicReference<UUID> activeSession = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CookingSessionService service = service(context -> {
            if (calls.incrementAndGet() == 1) {
                assertEquals(0, context.state().version());
                // The provider has not returned: touch commands must not wait for inference.
                activeService.get().navigate(activeSession.get(), "during-inference-focus", 2);
                activeService.get().requestTimerStart(activeSession.get(), "during-inference-timer", "source-timer-step-1");
                activeService.get().acknowledgeTimer(activeSession.get(), "during-inference-ack", "source-timer-step-1");
            } else {
                assertEquals(3, context.state().version());
                assertEquals(2, context.state().focusedSourceStepNumber());
            }
            return new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                    "완료 표시했어요.", Set.of(1), Set.of(),
                    List.of(new TurnDecision.UserReport(TurnDecision.ReportKind.ACTION_DONE,
                            "source-step-1", "1단계 전체 완료했어")), List.of(), List.of());
        });
        activeService.set(service);
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        activeSession.set(sessionId);

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> service.turn(sessionId, "stale-turn", "1단계 전체 완료했어"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        CookingState winner = service.get(sessionId).state();
        assertEquals(2, winner.focusedSourceStepNumber());
        assertEquals(Set.of("source-timer-step-1"), winner.runningTimerCueIds());
        assertTrue(winner.completedActionIds().isEmpty());
        assertFalse(winner.processedCommandIds().contains("turn:stale-turn"));
        assertEquals(1, service.get(sessionId).timerDeadlines().size());

        // Only an explicit retry recomputes with the latest focus and timer state.
        CookingSessionService.TurnView retried = service.turn(sessionId, "stale-turn", "1단계 전체 완료했어");
        assertFalse(retried.duplicate());
        assertEquals(Set.of("source-step-1"), retried.state().completedActionIds());
        assertEquals(2, retried.state().focusedSourceStepNumber());
        assertEquals(Set.of("source-timer-step-1"), retried.state().runningTimerCueIds());
        assertEquals(2, calls.get());
    }

    @Test
    void sessionIsScopedToStableAuthenticatedSubject() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        CookingSessionService service = service(context -> new TurnDecision(
                TurnDecision.Addressing.UNRELATED, TurnDecision.SpeechAct.OTHER,
                "", Set.of(), Set.of(), List.of(), List.of(), List.of()));
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        principal.set("user-b");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.get(sessionId));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void acceptedTimerRequiresDeviceAckAndAckRetryReusesTheSameDeadline() {
        RecipeImportResponse timedRecipe = new RecipeImportResponse(
                recipe.id(), recipe.sourceUrl(), recipe.title(), recipe.baseServings(),
                recipe.ingredients(),
                List.of(new RecipeImportResponse.Step(1, "물에 5분 담급니다.", null,
                        "물에 5분 담급니다.", null)),
                recipe.baseServingsText(), recipe.description(), recipe.totalTimeText(),
                recipe.primaryImageUrl(), recipe.parserVersion(), recipe.importedAt(), recipe.warnings());
        when(recipes.getRecipe(recipe.id())).thenReturn(timedRecipe);
        RecipePlanBuilder timedBuilder = new RecipePlanBuilder(new StructuredOutputClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public JsonNode generate(String instructions, Object context, String schemaName,
                    Map<String, Object> schema) {
                return json.readTree("""
                        {
                          "actions": [{"id":"soak-action","sourceStepNumbers":[1],"prerequisiteActionIds":[],"requiredConditionIds":[]}],
                          "conditions": [],
                          "timerCues": [{"id":"soak-timer","sourceStepNumber":1,"durationSeconds":300,"approximate":false,"evidence":"5분"}],
                          "unlistedIngredients": []
                        }
                        """);
            }
        });
        AtomicInteger turns = new AtomicInteger();
        AgentDecisionProvider provider = context -> turns.incrementAndGet() == 1
                ? new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.QUESTION_ONLY,
                        "5분 타이머를 맞춰드릴까요?", Set.of(1), Set.of(), List.of(),
                        List.of("soak-timer"), List.of())
                : new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.REPORT_OR_COMMAND,
                        "타이머를 시작할게요.", Set.of(1), Set.of(), List.of(),
                        List.of(), List.of("soak-timer"));
        CookingSessionService service = new CookingSessionService(
                users, recipes, timedBuilder, provider, store, json, clock);
        CookingSessionService.SessionView created = service.create(recipe.id(), BigDecimal.ONE);
        UUID sessionId = created.id();
        assertEquals(300, created.plan().timerCues().get("soak-timer").durationSeconds());
        assertEquals(300, json.readTree(json.writeValueAsString(created))
                .path("plan").path("timerCues").path("soak-timer").path("durationSeconds").asLong());

        service.turn(sessionId, "offer", "얼마나 담가?");
        CookingSessionService.TurnView accepted = service.turn(sessionId, "accept", "응 맞춰줘");
        assertTrue(accepted.state().requestedTimerCueIds().contains("soak-timer"));
        assertTrue(accepted.state().runningTimerCueIds().isEmpty());

        CookingSessionService.TimerAckView ack = service.acknowledgeTimer(
                sessionId, "device-ack", "soak-timer");
        CookingSessionService.TimerAckView retry = service.acknowledgeTimer(
                sessionId, "device-ack", "soak-timer");
        assertTrue(ack.state().runningTimerCueIds().contains("soak-timer"));
        assertEquals(Instant.parse("2026-09-28T00:05:00Z"), ack.timerDeadline().endsAt());
        assertTrue(json.readTree(json.writeValueAsString(ack))
                .path("timerDeadline").path("endsAt").isTextual());
        assertEquals(ack.timerDeadline(), retry.timerDeadline());
        assertTrue(retry.duplicate());

        CookingSessionService laterService = new CookingSessionService(
                users, recipes, timedBuilder, provider, store, json,
                Clock.fixed(Instant.parse("2026-09-28T00:06:00Z"), ZoneOffset.UTC));
        CookingSessionService.SessionView afterExpiry = laterService.get(sessionId);
        assertTrue(afterExpiry.state().runningTimerCueIds().isEmpty());
        assertEquals(Instant.parse("2026-09-28T00:06:00Z"),
                afterExpiry.timerDeadlines().getFirst().elapsedAt());
    }

    @Test
    void concurrentIdenticalTurnUsesCommittedWinnersResponse() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        store.simulateCommittedWinnerThenConflict = true;
        CookingSessionService service = service(context -> new TurnDecision(
                TurnDecision.Addressing.UNRELATED, TurnDecision.SpeechAct.OTHER,
                "", Set.of(), Set.of(), List.of(), List.of(), List.of()));
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();

        CookingSessionService.TurnView duplicate = service.turn(sessionId, "same", "안녕");

        assertTrue(duplicate.duplicate());
        assertEquals(1, duplicate.state().version());
        assertEquals(1, service.get(sessionId).state().version());
    }

    @Test
    void navigationIsIdempotentAndNextQuestionReceivesViewedStepAndServingContext() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        AtomicReference<AgentTurnContext> seen = new AtomicReference<>();
        CookingSessionService service = service(context -> {
            seen.set(context);
            return new TurnDecision(TurnDecision.Addressing.DIRECT, TurnDecision.SpeechAct.QUESTION_ONLY,
                    "이 단계에는 양파 투입만 있고 손질 사진은 없어요.", Set.of(2), Set.of(),
                    List.of(), List.of(), List.of());
        });
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        var first = service.navigate(sessionId, "view-2", 2);
        var retry = service.navigate(sessionId, "view-2", 2);
        assertEquals(2, first.state().focusedSourceStepNumber());
        assertEquals(List.of(2), first.photos().stream().map(CookingSessionService.PhotoCue::sourceStepNumber).toList());
        assertTrue(first.state().completedActionIds().isEmpty());
        assertTrue(retry.duplicate());
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> service.navigate(sessionId, "view-2", 1));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        var question = service.turn(sessionId, "cut-question", "양파 어떻게 썰어?");
        assertEquals(2, seen.get().state().focusedSourceStepNumber());
        assertEquals(BigDecimal.valueOf(2), seen.get().baseServings());
        assertEquals(BigDecimal.ONE, seen.get().targetServings());
        assertEquals("WEB_PAGE", seen.get().recipeSourceKind());
        assertTrue(question.photos().isEmpty());
        assertTrue(question.state().completedActionIds().isEmpty());
        assertEquals(2, question.state().focusedSourceStepNumber());
    }

    @Test
    void tellsTheAgentWhenTheRecipeWasWrittenByAiFromAVideo() {
        RecipeImportResponse videoRecipe = new RecipeImportResponse(recipe.id(),
                "https://www.youtube.com/watch?v=AbCdEfGhIjK", recipe.title(), recipe.baseServings(),
                recipe.ingredients(), recipe.steps(), recipe.baseServingsText(), null, null, null,
                "youtube-gemini-v1", Instant.EPOCH, List.of(), RecipeImportResponse.SOURCE_KIND_VIDEO_AI, null);
        when(recipes.getRecipe(recipe.id())).thenReturn(videoRecipe);
        AtomicReference<AgentTurnContext> seen = new AtomicReference<>();
        CookingSessionService service = service(context -> {
            seen.set(context);
            return new TurnDecision(TurnDecision.Addressing.UNRELATED, TurnDecision.SpeechAct.OTHER,
                    "", Set.of(), Set.of(), List.of(), List.of(), List.of());
        });
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();

        service.turn(sessionId, "video-question", "양파 몇 개 넣어?");

        assertEquals("VIDEO_AI", seen.get().recipeSourceKind());
    }

    @Test
    void manualStepProgressIsExplicitAndIdempotent() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        CookingSessionService service = service(context -> {
            throw new AssertionError("Manual progress must not call inference");
        });
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        var skipped = service.setStepProgress(sessionId, "skip", 1, CookingEngine.StepProgressStatus.SKIPPED);
        assertTrue(skipped.state().completedActionIds().isEmpty());
        assertEquals(Set.of(1), skipped.state().skippedSourceStepNumbers());
        assertTrue(service.setStepProgress(sessionId, "skip", 1, CookingEngine.StepProgressStatus.SKIPPED).duplicate());
        var completed = service.setStepProgress(sessionId, "done", 2, CookingEngine.StepProgressStatus.COMPLETED);
        assertEquals(Set.of("source-step-2"), completed.state().completedActionIds());
        assertEquals(Set.of(2), completed.state().completedSourceStepNumbers());
        assertEquals(Set.of(1), completed.state().skippedSourceStepNumbers());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.setStepProgress(sessionId, "bad", 99,
                        CookingEngine.StepProgressStatus.COMPLETED)).getStatusCode());
    }

    @Test
    void nonexistentSourcePhotoIsRejectedInsteadOfReturningInventedVisualEvidence() {
        when(recipes.getRecipe(recipe.id())).thenReturn(recipe);
        CookingSessionService service = service(context -> new TurnDecision(TurnDecision.Addressing.DIRECT,
                TurnDecision.SpeechAct.QUESTION_ONLY, "사진이에요", Set.of(1), Set.of(1),
                List.of(), List.of(), List.of()));
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ResponseStatusException.class,
                () -> service.turn(sessionId, "bad-photo", "사진 보여줘")).getStatusCode());
        assertEquals(CookingState.empty(), service.get(sessionId).state());
    }

    @Test
    void sourceTimerCanBeRequestedAndAcknowledgedWithoutAnyModelInference() {
        RecipeImportResponse timedRecipe = new RecipeImportResponse(
                recipe.id(), recipe.sourceUrl(), recipe.title(), recipe.baseServings(), recipe.ingredients(),
                List.of(new RecipeImportResponse.Step(1, "소금물에 약 5분 담급니다.", null,
                        "소금물에 약 5분 담급니다.", null)),
                recipe.baseServingsText(), recipe.description(), recipe.totalTimeText(), recipe.primaryImageUrl(),
                recipe.parserVersion(), recipe.importedAt(), recipe.warnings());
        when(recipes.getRecipe(recipe.id())).thenReturn(timedRecipe);
        CookingSessionService service = service(context -> {
            throw new AssertionError("Manual timer request must not call a model");
        });
        UUID sessionId = service.create(recipe.id(), BigDecimal.ONE).id();
        String cueId = "source-timer-step-1";
        assertEquals(300, service.get(sessionId).plan().timerCues().get(cueId).durationSeconds());
        assertTrue(service.get(sessionId).plan().timerCues().get(cueId).approximate());

        var request = service.requestTimerStart(sessionId, "tap", cueId);
        assertEquals(Set.of(cueId), request.state().requestedTimerCueIds());
        assertTrue(request.state().runningTimerCueIds().isEmpty());
        assertTrue(service.get(sessionId).timerDeadlines().isEmpty());
        assertTrue(service.requestTimerStart(sessionId, "tap", cueId).duplicate());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestTimerStart(sessionId, "tap", "different-cue")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.requestTimerStart(sessionId, "another-tap", cueId)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.requestTimerStart(sessionId, "unknown-tap", "unknown-cue")).getStatusCode());

        var ack = service.acknowledgeTimer(sessionId, "device-tap-ack", cueId);
        assertEquals(Set.of(cueId), ack.state().runningTimerCueIds());
        assertEquals(Instant.parse("2026-09-28T00:05:00Z"), ack.timerDeadline().endsAt());
        CookingSessionService later = new CookingSessionService(users, recipes, builder, context -> {
            throw new AssertionError("Manual timer request must not call a model");
        }, store, json, Clock.fixed(Instant.parse("2026-09-28T00:06:00Z"), ZoneOffset.UTC));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> later.requestTimerStart(sessionId, "restart-same-cue", cueId)).getStatusCode());
        assertEquals(1, later.get(sessionId).timerDeadlines().size());
    }

    private CookingSessionService service(AgentDecisionProvider provider) {
        return new CookingSessionService(users, recipes, builder, provider, store, json, clock);
    }

    private static StructuredOutputClient unavailableModel() {
        return new StructuredOutputClient() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public JsonNode generate(String instructions, Object context, String schemaName,
                    Map<String, Object> schema) {
                throw new AssertionError("Unconfigured model was called");
            }
        };
    }

    private static RecipeImportResponse recipe() {
        return new RecipeImportResponse(
                UUID.randomUUID(), "https://www.10000recipe.com/recipe/123", "시험 요리",
                BigDecimal.valueOf(2), List.of(),
                List.of(
                        new RecipeImportResponse.Step(1, "물을 올립니다.", null, "물을 올립니다.", null),
                        new RecipeImportResponse.Step(2, "양파를 넣습니다.", "https://example.test/onion.jpg",
                                "양파를 넣습니다.", null)),
                "2인분", null, null, null, "test", Instant.EPOCH, List.of());
    }

    private final class FakeStore implements CookingSessionStore {
        private final Map<UUID, CookingSessionSnapshot> snapshots = new HashMap<>();
        private final Map<String, StoredCommandResult> results = new HashMap<>();
        private final List<CookingEngine.Event> events = new java.util.ArrayList<>();
        private final Map<UUID, List<TimerDeadline>> deadlines = new HashMap<>();
        private boolean simulateCommittedWinnerThenConflict;

        @Override
        public void create(CookingSessionSnapshot snapshot) {
            snapshots.put(snapshot.id(), snapshot);
        }

        @Override
        public Optional<CookingSessionSnapshot> find(UUID sessionId, String ownerSubject) {
            return Optional.ofNullable(snapshots.get(sessionId))
                    .filter(snapshot -> snapshot.ownerSubject().equals(ownerSubject));
        }

        @Override
        public Optional<StoredCommandResult> findCommandResult(
                UUID sessionId, String ownerSubject, String commandKey) {
            return find(sessionId, ownerSubject).flatMap(snapshot ->
                    Optional.ofNullable(results.get(sessionId + ":" + commandKey)));
        }

        @Override
        public List<TimerDeadline> findTimerDeadlines(UUID sessionId, String ownerSubject) {
            return find(sessionId, ownerSubject).isPresent()
                    ? List.copyOf(deadlines.getOrDefault(sessionId, List.of()))
                    : List.of();
        }

        @Override
        public void saveTransition(CookingSessionSnapshot prior, CookingState updatedState,
                String commandKey, String requestHash, String responseJson,
                List<CookingEngine.Event> newEvents, Instant now) {
            if (snapshots.get(prior.id()).state().version() != prior.state().version()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale state");
            }
            snapshots.put(prior.id(), new CookingSessionSnapshot(
                    prior.id(), prior.ownerSubject(), prior.recipeId(), prior.targetServings(),
                    prior.plan(), prior.planSource(), prior.planWarnings(), updatedState,
                    prior.createdAt(), now));
            results.put(prior.id() + ":" + commandKey, new StoredCommandResult(requestHash, responseJson));
            events.addAll(newEvents);
            for (CookingEngine.Event event : newEvents) {
                if (event.type() == CookingEngine.EventType.TIMER_STARTED) {
                    var deadline = new TimerDeadline(event.targetId(), now,
                            now.plus(prior.plan().timerCues().get(event.targetId()).duration()), null);
                    deadlines.computeIfAbsent(prior.id(), ignored -> new java.util.ArrayList<>()).add(deadline);
                } else if (event.type() == CookingEngine.EventType.TIMER_ELAPSED) {
                    deadlines.computeIfPresent(prior.id(), (ignored, existing) -> existing.stream()
                            .map(deadline -> deadline.timerCueId().equals(event.targetId())
                                    ? new TimerDeadline(deadline.timerCueId(), deadline.startedAt(), deadline.endsAt(), now)
                                    : deadline)
                            .toList());
                }
            }
            if (simulateCommittedWinnerThenConflict) {
                simulateCommittedWinnerThenConflict = false;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent loser");
            }
        }
    }
}
