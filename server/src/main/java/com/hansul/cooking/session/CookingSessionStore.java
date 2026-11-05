package com.hansul.cooking.session;

import com.hansul.cooking.CookingEngine;
import com.hansul.cooking.CookingState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CookingSessionStore {
    void create(CookingSessionSnapshot snapshot);

    Optional<CookingSessionSnapshot> find(UUID sessionId, String ownerSubject);

    Optional<StoredCommandResult> findCommandResult(UUID sessionId, String ownerSubject, String commandKey);

    List<TimerDeadline> findTimerDeadlines(UUID sessionId, String ownerSubject);

    void saveTransition(
            CookingSessionSnapshot prior,
            CookingState updatedState,
            String commandKey,
            String requestHash,
            String responseJson,
            List<CookingEngine.Event> events,
            Instant now);

    record StoredCommandResult(String requestHash, String responseJson) {}

    record TimerDeadline(String timerCueId, Instant startedAt, Instant endsAt, Instant elapsedAt) {}
}
