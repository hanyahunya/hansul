package com.hansul.cooking.session;

import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.cooking.CookingEngine;
import com.hansul.cooking.CookingPlan;
import com.hansul.cooking.CookingState;
import com.hansul.cooking.TurnDecision;
import com.hansul.cooking.ServingScaler;
import com.hansul.cooking.ai.AgentDecisionProvider;
import com.hansul.cooking.ai.AgentTurnContext;
import com.hansul.cooking.ai.RecipePlanBuilder;
import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.application.RecipeImportService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Coordinates imported recipe evidence, model proposals, deterministic state transitions, and persistence. */
@Service
public class CookingSessionService {
    private final CurrentUserProvider users;
    private final RecipeImportService recipes;
    private final RecipePlanBuilder planBuilder;
    private final AgentDecisionProvider decisionProvider;
    private final CookingSessionStore sessions;
    private final JsonMapper json;
    private final Clock clock;

    public CookingSessionService(
            CurrentUserProvider users,
            RecipeImportService recipes,
            RecipePlanBuilder planBuilder,
            AgentDecisionProvider decisionProvider,
            CookingSessionStore sessions,
            JsonMapper json,
            Clock clock) {
        this.users = users;
        this.recipes = recipes;
        this.planBuilder = planBuilder;
        this.decisionProvider = decisionProvider;
        this.sessions = sessions;
        this.json = json;
        this.clock = clock;
    }

    public SessionView create(UUID recipeId, BigDecimal targetServings) {
        if (targetServings == null || targetServings.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetServings must be positive");
        }
        String owner = ownerSubject();
        RecipeImportResponse recipe = recipes.getRecipe(recipeId);
        RecipePlanBuilder.PlanBuildResult built = planBuilder.build(recipe);
        Instant now = Instant.now(clock);
        CookingSessionSnapshot snapshot = new CookingSessionSnapshot(
                UUID.randomUUID(), owner, recipeId, targetServings, built.plan(), built.source(),
                built.warnings(), CookingState.empty(), now, now);
        sessions.create(snapshot);
        return view(snapshot);
    }

    public SessionView get(UUID sessionId) {
        return view(requireSession(sessionId));
    }

    public TurnView turn(UUID sessionId, String clientTurnId, String transcript) {
        CookingSessionSnapshot session = requireSession(sessionId);
        String commandKey = commandKey("turn", clientTurnId);
        String requestHash = hash(transcript);
        var priorResult = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
        if (priorResult.isPresent()) {
            ensureSameRequest(priorResult.get(), requestHash);
            return json.readValue(priorResult.get().responseJson(), TurnView.class).asDuplicate();
        }

        RecipeImportResponse recipe = recipes.getRecipe(session.recipeId());
        AgentTurnContext context = new AgentTurnContext(
                recipe.title(),
                recipe.steps().stream().map(step -> new AgentTurnContext.SourceStep(
                        step.number(), step.text(), step.imageUrl() != null && !step.imageUrl().isBlank())).toList(),
                session.plan(), session.state(), transcript,
                ingredientContext(recipe, session.targetServings()), recipe.baseServings(), session.targetServings(),
                recipe.sourceKind());
        TurnDecision decision = decisionProvider.decide(context);
        CookingEngine.Outcome outcome;
        try {
            Set<Integer> availablePhotoSteps = recipe.steps().stream()
                    .filter(step -> step.imageUrl() != null && !step.imageUrl().isBlank())
                    .map(RecipeImportResponse.Step::number).collect(java.util.stream.Collectors.toSet());
            if (!availablePhotoSteps.containsAll(decision.photoSourceSteps())) {
                throw new IllegalArgumentException("AI selected a source step that has no photo");
            }
            outcome = CookingEngine.applyTurn(session.plan(), session.state(), clientTurnId, transcript, decision);
        } catch (CookingEngine.UncertainReportEvidenceException ambiguousEvidence) {
            decision = new TurnDecision(TurnDecision.Addressing.UNCERTAIN, TurnDecision.SpeechAct.OTHER,
                    "완료한 동작이나 재료 상황을 한 문장으로 다시 말씀해 주세요.",
                    Set.of(), Set.of(), List.of(), List.of(), List.of(), List.of());
            outcome = CookingEngine.applyTurn(session.plan(), session.state(), clientTurnId, transcript, decision);
        } catch (IllegalArgumentException invalidModelDecision) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI decision did not match recipe evidence or current state", invalidModelDecision);
        }
        TurnDecision acceptedDecision = decision;
        String replyText = switch (decision.addressing()) {
            case DIRECT -> decision.replyText();
            case UNRELATED -> "";
            case UNCERTAIN -> "완료한 동작이나 재료 상황을 한 문장으로 다시 말씀해 주세요.";
        };
        Set<Integer> citedSteps = decision.addressing() == TurnDecision.Addressing.DIRECT
                ? decision.citedSourceSteps() : Set.of();
        List<PhotoCue> photos = decision.addressing() == TurnDecision.Addressing.DIRECT
                ? recipe.steps().stream()
                        .filter(step -> acceptedDecision.photoSourceSteps().contains(step.number()))
                        .filter(step -> step.imageUrl() != null && !step.imageUrl().isBlank())
                        .map(step -> new PhotoCue(step.number(), step.imageUrl()))
                        .toList()
                : List.of();
        TurnView response = new TurnView(
                session.id(), clientTurnId, decision.addressing(), replyText,
                citedSteps, photos, outcome.events(), outcome.state(),
                outcome.availableActionIds(), false);
        try {
            sessions.saveTransition(session, outcome.state(), commandKey, requestHash,
                    json.writeValueAsString(response), outcome.events(), Instant.now(clock));
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode() == HttpStatus.CONFLICT) {
                var winner = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
                if (winner.isPresent()) {
                    ensureSameRequest(winner.get(), requestHash);
                    return json.readValue(winner.get().responseJson(), TurnView.class).asDuplicate();
                }
            }
            throw conflict;
        }
        return response;
    }

    public NavigationView navigate(UUID sessionId, String clientNavigationId, int sourceStepNumber) {
        CookingSessionSnapshot session = requireSession(sessionId);
        String commandKey = commandKey("navigation", clientNavigationId);
        String requestHash = hash(Integer.toString(sourceStepNumber));
        var priorResult = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
        if (priorResult.isPresent()) {
            ensureSameRequest(priorResult.get(), requestHash);
            return json.readValue(priorResult.get().responseJson(), NavigationView.class).asDuplicate();
        }
        CookingEngine.Outcome outcome;
        try {
            outcome = CookingEngine.navigate(session.plan(), session.state(), clientNavigationId, sourceStepNumber);
        } catch (IllegalArgumentException invalidNavigation) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidNavigation.getMessage(), invalidNavigation);
        }
        RecipeImportResponse recipe = recipes.getRecipe(session.recipeId());
        List<PhotoCue> photos = recipe.steps().stream()
                .filter(step -> step.number() == sourceStepNumber && step.imageUrl() != null && !step.imageUrl().isBlank())
                .map(step -> new PhotoCue(step.number(), step.imageUrl())).toList();
        NavigationView response = new NavigationView(session.id(), clientNavigationId, sourceStepNumber,
                photos, outcome.events(), outcome.state(), outcome.availableActionIds(), false);
        try {
            sessions.saveTransition(session, outcome.state(), commandKey, requestHash,
                    json.writeValueAsString(response), outcome.events(), Instant.now(clock));
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode() == HttpStatus.CONFLICT) {
                var winner = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
                if (winner.isPresent()) {
                    ensureSameRequest(winner.get(), requestHash);
                    return json.readValue(winner.get().responseJson(), NavigationView.class).asDuplicate();
                }
            }
            throw conflict;
        }
        return response;
    }

    private static List<AgentTurnContext.SourceIngredient> ingredientContext(
            RecipeImportResponse recipe, BigDecimal targetServings) {
        return recipe.ingredients().stream().map(ingredient -> {
            String original = ingredient.quantityText() == null ? "" : ingredient.quantityText();
            if (recipe.baseServings() == null || recipe.baseServings().signum() <= 0) {
                return new AgentTurnContext.SourceIngredient(ingredient.name(), original, original, "BASE_SERVINGS_UNKNOWN");
            }
            ServingScaler.ScaledAmount scaled = ServingScaler.scale(original, recipe.baseServings(), targetServings);
            return new AgentTurnContext.SourceIngredient(ingredient.name(), original,
                    scaled.displayText(), scaled.status().name());
        }).toList();
    }

    public StepProgressView setStepProgress(UUID sessionId, String clientCommandId, int sourceStepNumber,
            CookingEngine.StepProgressStatus status) {
        CookingSessionSnapshot session = requireSession(sessionId);
        String commandKey = commandKey("stepProgress", clientCommandId);
        String requestHash = hash(sourceStepNumber + ":" + status);
        var priorResult = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
        if (priorResult.isPresent()) {
            ensureSameRequest(priorResult.get(), requestHash);
            return json.readValue(priorResult.get().responseJson(), StepProgressView.class).asDuplicate();
        }
        CookingEngine.Outcome outcome;
        try {
            outcome = CookingEngine.setStepProgress(session.plan(), session.state(), clientCommandId,
                    sourceStepNumber, status);
        } catch (IllegalArgumentException invalidProgress) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidProgress.getMessage(), invalidProgress);
        }
        StepProgressView response = new StepProgressView(session.id(), clientCommandId, sourceStepNumber,
                status, outcome.events(), outcome.state(), outcome.availableActionIds(), false);
        try {
            sessions.saveTransition(session, outcome.state(), commandKey, requestHash,
                    json.writeValueAsString(response), outcome.events(), Instant.now(clock));
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode() == HttpStatus.CONFLICT) {
                var winner = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
                if (winner.isPresent()) {
                    ensureSameRequest(winner.get(), requestHash);
                    return json.readValue(winner.get().responseJson(), StepProgressView.class).asDuplicate();
                }
            }
            throw conflict;
        }
        return response;
    }

    public TimerRequestView requestTimerStart(UUID sessionId, String clientCommandId, String timerCueId) {
        CookingSessionSnapshot session = requireSession(sessionId);
        String commandKey = commandKey("timerRequest", clientCommandId);
        String requestHash = hash(timerCueId);
        var priorResult = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
        if (priorResult.isPresent()) {
            ensureSameRequest(priorResult.get(), requestHash);
            return json.readValue(priorResult.get().responseJson(), TimerRequestView.class).asDuplicate();
        }
        if (sessions.findTimerDeadlines(sessionId, session.ownerSubject()).stream()
                .anyMatch(deadline -> deadline.timerCueId().equals(timerCueId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This source timer has already been started");
        }
        CookingEngine.Outcome outcome;
        try {
            outcome = CookingEngine.requestTimerStart(session.plan(), session.state(), clientCommandId, timerCueId);
        } catch (IllegalArgumentException invalidRequest) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidRequest.getMessage(), invalidRequest);
        } catch (IllegalStateException alreadyStarted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, alreadyStarted.getMessage(), alreadyStarted);
        }
        TimerRequestView response = new TimerRequestView(session.id(), clientCommandId, timerCueId,
                outcome.events(), outcome.state(), outcome.availableActionIds(), false);
        try {
            sessions.saveTransition(session, outcome.state(), commandKey, requestHash,
                    json.writeValueAsString(response), outcome.events(), Instant.now(clock));
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode() == HttpStatus.CONFLICT) {
                var winner = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
                if (winner.isPresent()) {
                    ensureSameRequest(winner.get(), requestHash);
                    return json.readValue(winner.get().responseJson(), TimerRequestView.class).asDuplicate();
                }
            }
            throw conflict;
        }
        return response;
    }

    public TimerAckView acknowledgeTimer(UUID sessionId, String clientAckId, String timerCueId) {
        CookingSessionSnapshot session = requireSession(sessionId);
        String commandKey = commandKey("timerAck", clientAckId);
        String requestHash = hash(timerCueId);
        var priorResult = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
        if (priorResult.isPresent()) {
            ensureSameRequest(priorResult.get(), requestHash);
            return json.readValue(priorResult.get().responseJson(), TimerAckView.class).asDuplicate();
        }
        CookingEngine.Outcome outcome;
        try {
            outcome = CookingEngine.acknowledgeTimerStart(
                    session.plan(), session.state(), clientAckId, timerCueId);
        } catch (IllegalArgumentException invalidAck) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, invalidAck.getMessage(), invalidAck);
        }
        Instant now = Instant.now(clock);
        TimerAckView response = new TimerAckView(
                session.id(), clientAckId, outcome.events(), outcome.state(),
                outcome.availableActionIds(),
                new CookingSessionStore.TimerDeadline(
                        timerCueId, now,
                        now.plus(session.plan().timerCues().get(timerCueId).duration()), null),
                false);
        try {
            sessions.saveTransition(session, outcome.state(), commandKey, requestHash,
                    json.writeValueAsString(response), outcome.events(), now);
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode() == HttpStatus.CONFLICT) {
                var winner = sessions.findCommandResult(sessionId, session.ownerSubject(), commandKey);
                if (winner.isPresent()) {
                    ensureSameRequest(winner.get(), requestHash);
                    return json.readValue(winner.get().responseJson(), TimerAckView.class).asDuplicate();
                }
            }
            throw conflict;
        }
        return response;
    }

    private SessionView view(CookingSessionSnapshot snapshot) {
        return new SessionView(
                snapshot.id(), snapshot.recipeId(), snapshot.targetServings(), PlanView.from(snapshot.plan()),
                snapshot.planSource(), snapshot.planWarnings(), snapshot.state(),
                snapshot.plan().availableActionIds(snapshot.state()),
                sessions.findTimerDeadlines(snapshot.id(), snapshot.ownerSubject()),
                snapshot.createdAt(), snapshot.updatedAt());
    }

    private CookingSessionSnapshot requireSession(UUID sessionId) {
        String owner = ownerSubject();
        for (int attempt = 0; attempt < 3; attempt++) {
            CookingSessionSnapshot session = sessions.find(sessionId, owner)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cooking session not found"));
            Instant now = Instant.now(clock);
            Set<String> expiredIds = sessions.findTimerDeadlines(sessionId, owner).stream()
                    .filter(deadline -> deadline.elapsedAt() == null && !deadline.endsAt().isAfter(now))
                    .map(CookingSessionStore.TimerDeadline::timerCueId)
                    .filter(session.state().runningTimerCueIds()::contains)
                    .collect(java.util.stream.Collectors.toSet());
            if (expiredIds.isEmpty()) {
                return session;
            }
            CookingEngine.Outcome outcome = CookingEngine.expireTimers(session.plan(), session.state(), expiredIds);
            try {
                sessions.saveTransition(
                        session, outcome.state(), "timerElapsed:" + UUID.randomUUID(),
                        hash(expiredIds.toString()), "{}", outcome.events(), now);
            } catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode() == HttpStatus.CONFLICT) {
                    continue;
                }
                throw conflict;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Cooking session changed; retry");
    }

    private String ownerSubject() {
        return users.requireCurrentUser().id().value();
    }

    private static String commandKey(String kind, String id) {
        if (id == null || id.isBlank() || id.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid client command ID");
        }
        return kind + ":" + id;
    }

    private static void ensureSameRequest(CookingSessionStore.StoredCommandResult prior, String requestHash) {
        if (!prior.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A different request already used this client command ID");
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record SessionView(
            UUID id,
            UUID recipeId,
            BigDecimal targetServings,
            PlanView plan,
            RecipePlanBuilder.PlanSource planSource,
            List<String> planWarnings,
            CookingState state,
            List<String> availableActionIds,
            List<CookingSessionStore.TimerDeadline> timerDeadlines,
            Instant createdAt,
            Instant updatedAt) {}

    /** Public API shape does not depend on Jackson's Duration serialization format. */
    public record PlanView(
            Map<String, CookingPlan.Action> actions,
            Map<String, CookingPlan.Condition> conditions,
            Map<String, TimerCueView> timerCues,
            Set<Integer> sourceStepNumbers) {
        static PlanView from(CookingPlan plan) {
            Map<String, TimerCueView> cues = new LinkedHashMap<>();
            plan.timerCues().forEach((id, cue) -> cues.put(id, new TimerCueView(
                    cue.id(), cue.duration().toSeconds(), cue.sourceStepNumber(), cue.approximate())));
            return new PlanView(plan.actions(), plan.conditions(),
                    Collections.unmodifiableMap(cues), plan.sourceStepNumbers());
        }
    }

    public record TimerCueView(String id, long durationSeconds, int sourceStepNumber, boolean approximate) {}

    public record PhotoCue(int sourceStepNumber, String imageUrl) {}

    public record NavigationView(UUID sessionId, String clientNavigationId, int sourceStepNumber,
            List<PhotoCue> photos, List<CookingEngine.Event> events, CookingState state,
            List<String> availableActionIds, boolean duplicate) {
        public NavigationView asDuplicate() {
            return new NavigationView(sessionId, clientNavigationId, sourceStepNumber, photos,
                    events, state, availableActionIds, true);
        }
    }

    public record StepProgressView(UUID sessionId, String clientCommandId, int sourceStepNumber,
            CookingEngine.StepProgressStatus status, List<CookingEngine.Event> events, CookingState state,
            List<String> availableActionIds, boolean duplicate) {
        public StepProgressView asDuplicate() {
            return new StepProgressView(sessionId, clientCommandId, sourceStepNumber, status,
                    events, state, availableActionIds, true);
        }
    }

    public record TurnView(
            UUID sessionId,
            String clientTurnId,
            TurnDecision.Addressing addressing,
            String replyText,
            Set<Integer> citedSourceSteps,
            List<PhotoCue> photos,
            List<CookingEngine.Event> events,
            CookingState state,
            List<String> availableActionIds,
            boolean duplicate) {
        public TurnView asDuplicate() {
            return new TurnView(sessionId, clientTurnId, addressing, replyText, citedSourceSteps,
                    photos, events, state, availableActionIds, true);
        }
    }

    public record TimerAckView(
            UUID sessionId,
            String clientAckId,
            List<CookingEngine.Event> events,
            CookingState state,
            List<String> availableActionIds,
            CookingSessionStore.TimerDeadline timerDeadline,
            boolean duplicate) {
        public TimerAckView asDuplicate() {
            return new TimerAckView(sessionId, clientAckId, events, state, availableActionIds, timerDeadline, true);
        }
    }

    public record TimerRequestView(UUID sessionId, String clientCommandId, String timerCueId,
            List<CookingEngine.Event> events, CookingState state, List<String> availableActionIds,
            boolean duplicate) {
        public TimerRequestView asDuplicate() {
            return new TimerRequestView(sessionId, clientCommandId, timerCueId,
                    events, state, availableActionIds, true);
        }
    }
}
