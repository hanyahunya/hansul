package com.hansul.cooking.session;

import com.hansul.cooking.CookingEngine;
import com.hansul.cooking.CookingPlan;
import com.hansul.cooking.CookingState;
import com.hansul.cooking.ai.RecipePlanBuilder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcCookingSessionStore implements CookingSessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public JdbcCookingSessionStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    @Override
    public void create(CookingSessionSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO cooking_session
                    (id, owner_subject, recipe_id, target_servings, plan_json, plan_source,
                     plan_warnings_json, state_json, state_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.id(), snapshot.ownerSubject(), snapshot.recipeId(), snapshot.targetServings(),
                json.writeValueAsString(snapshot.plan()), snapshot.planSource().name(),
                json.writeValueAsString(snapshot.planWarnings()), json.writeValueAsString(snapshot.state()),
                snapshot.state().version(), Timestamp.from(snapshot.createdAt()), Timestamp.from(snapshot.updatedAt()));
    }

    @Override
    public Optional<CookingSessionSnapshot> find(UUID sessionId, String ownerSubject) {
        return jdbc.query("""
                SELECT * FROM cooking_session WHERE id = ? AND owner_subject = ?
                """, (row, number) -> new CookingSessionSnapshot(
                (UUID) row.getObject("id"),
                row.getString("owner_subject"),
                (UUID) row.getObject("recipe_id"),
                row.getBigDecimal("target_servings"),
                json.readValue(row.getString("plan_json"), CookingPlan.class),
                RecipePlanBuilder.PlanSource.valueOf(row.getString("plan_source")),
                readWarnings(row.getString("plan_warnings_json")),
                json.readValue(row.getString("state_json"), CookingState.class),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), sessionId, ownerSubject).stream().findFirst();
    }

    @Override
    public Optional<StoredCommandResult> findCommandResult(
            UUID sessionId, String ownerSubject, String commandKey) {
        return jdbc.query("""
                SELECT result.request_hash, result.response_json
                FROM cooking_command_result result
                JOIN cooking_session session ON session.id = result.session_id
                WHERE session.id = ? AND session.owner_subject = ? AND result.command_key = ?
                """, (row, number) -> new StoredCommandResult(
                row.getString("request_hash"), row.getString("response_json")),
                sessionId, ownerSubject, commandKey).stream().findFirst();
    }

    @Override
    public List<TimerDeadline> findTimerDeadlines(UUID sessionId, String ownerSubject) {
        return jdbc.query("""
                SELECT deadline.timer_cue_id, deadline.started_at, deadline.ends_at, deadline.elapsed_at
                FROM cooking_timer_deadline deadline
                JOIN cooking_session session ON session.id = deadline.session_id
                WHERE session.id = ? AND session.owner_subject = ?
                ORDER BY deadline.started_at, deadline.timer_cue_id
                """, (row, number) -> new TimerDeadline(
                row.getString("timer_cue_id"),
                row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("ends_at").toInstant(),
                row.getTimestamp("elapsed_at") == null ? null : row.getTimestamp("elapsed_at").toInstant()),
                sessionId, ownerSubject);
    }

    @Override
    public void saveTransition(
            CookingSessionSnapshot prior,
            CookingState updatedState,
            String commandKey,
            String requestHash,
            String responseJson,
            List<CookingEngine.Event> events,
            Instant now) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE cooking_session
                    SET state_json = ?, state_version = ?, updated_at = ?
                    WHERE id = ? AND owner_subject = ? AND state_version = ?
                    """,
                    json.writeValueAsString(updatedState), updatedState.version(), Timestamp.from(now),
                    prior.id(), prior.ownerSubject(), prior.state().version());
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cooking session changed; retry this turn");
            }
            jdbc.update("""
                    INSERT INTO cooking_command_result
                        (session_id, command_key, request_hash, response_json, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, prior.id(), commandKey, requestHash, responseJson, Timestamp.from(now));
            for (CookingEngine.Event event : events) {
                jdbc.update("""
                        INSERT INTO cooking_session_event
                            (session_id, state_version, command_key, event_type, target_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, prior.id(), updatedState.version(), commandKey,
                        event.type().name(), event.targetId(), Timestamp.from(now));
                if (event.type() == CookingEngine.EventType.TIMER_STARTED) {
                    var duration = prior.plan().timerCues().get(event.targetId()).duration();
                    jdbc.update("""
                            INSERT INTO cooking_timer_deadline
                                (session_id, timer_cue_id, started_at, ends_at)
                            VALUES (?, ?, ?, ?)
                            """, prior.id(), event.targetId(), Timestamp.from(now), Timestamp.from(now.plus(duration)));
                } else if (event.type() == CookingEngine.EventType.TIMER_ELAPSED) {
                    jdbc.update("""
                            UPDATE cooking_timer_deadline SET elapsed_at = ?
                            WHERE session_id = ? AND timer_cue_id = ? AND elapsed_at IS NULL
                            """, Timestamp.from(now), prior.id(), event.targetId());
                }
            }
        });
    }

    private List<String> readWarnings(String raw) {
        JsonNode node = json.readTree(raw);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Stored plan warnings are not an array");
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode item : node) {
            warnings.add(item.asText());
        }
        return List.copyOf(warnings);
    }
}
