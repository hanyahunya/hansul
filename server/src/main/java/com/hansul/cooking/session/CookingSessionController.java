package com.hansul.cooking.session;

import com.hansul.cooking.CookingEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/cooking-sessions")
public class CookingSessionController {
    private final CookingSessionService service;

    public CookingSessionController(CookingSessionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CookingSessionService.SessionView> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request.recipeId(), request.targetServings()));
    }

    @GetMapping("/{id}")
    public CookingSessionService.SessionView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/turns")
    public CookingSessionService.TurnView turn(
            @PathVariable UUID id, @Valid @RequestBody TurnRequest request) {
        return service.turn(id, request.clientTurnId(), request.transcript());
    }

    @PostMapping("/{id}/timer-start-acks")
    public CookingSessionService.TimerAckView acknowledgeTimer(
            @PathVariable UUID id, @Valid @RequestBody TimerAckRequest request) {
        return service.acknowledgeTimer(id, request.clientAckId(), request.timerCueId());
    }

    @PostMapping("/{id}/timer-start-requests")
    public CookingSessionService.TimerRequestView requestTimerStart(
            @PathVariable UUID id, @Valid @RequestBody TimerRequest request) {
        return service.requestTimerStart(id, request.clientCommandId(), request.timerCueId());
    }

    @PostMapping("/{id}/navigation")
    public CookingSessionService.NavigationView navigate(
            @PathVariable UUID id, @Valid @RequestBody NavigationRequest request) {
        return service.navigate(id, request.clientNavigationId(), request.sourceStepNumber());
    }

    @PostMapping("/{id}/step-progress")
    public CookingSessionService.StepProgressView setStepProgress(
            @PathVariable UUID id, @Valid @RequestBody StepProgressRequest request) {
        return service.setStepProgress(id, request.clientCommandId(), request.sourceStepNumber(), request.status());
    }

    public record CreateRequest(@NotNull UUID recipeId, @NotNull BigDecimal targetServings) {}

    public record TurnRequest(
            @NotBlank @Size(max = 100) String clientTurnId,
            @NotBlank @Size(max = 3000) String transcript) {}

    public record TimerAckRequest(
            @NotBlank @Size(max = 100) String clientAckId,
            @NotBlank String timerCueId) {}

    public record TimerRequest(
            @NotBlank @Size(max = 100) String clientCommandId,
            @NotBlank @Size(max = 120) String timerCueId) {}

    public record NavigationRequest(
            @NotBlank @Size(max = 100) String clientNavigationId,
            @NotNull @Positive Integer sourceStepNumber) {}

    public record StepProgressRequest(
            @NotBlank @Size(max = 100) String clientCommandId,
            @NotNull @Positive Integer sourceStepNumber,
            @NotNull CookingEngine.StepProgressStatus status) {}
}
