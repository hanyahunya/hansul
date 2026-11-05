package com.hansul.cooking.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hansul.cooking.CookingPlan;
import com.hansul.cooking.CookingState;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CookingSessionJsonTest {
    @Test
    void oldPersistedStateLoadsWithEmptyNavigationAndIngredientFacts() {
        JsonMapper json = JsonMapper.builder().build();
        CookingState restored = json.readValue("""
                {"version":1,"completedActionIds":[],"observedConditionIds":[],
                 "offeredTimerCueIds":[],"requestedTimerCueIds":[],"runningTimerCueIds":[],
                 "processedCommandIds":["turn:old"]}
                """, CookingState.class);
        assertNull(restored.focusedSourceStepNumber());
        assertTrue(restored.ingredientAdjustments().isEmpty());
        assertTrue(restored.completedSourceStepNumbers().isEmpty());
        assertTrue(restored.skippedSourceStepNumbers().isEmpty());
    }

    @Test
    void persistedPlanAndStateRoundTripWithoutLosingTimerDurationsOrActionOrder() {
        JsonMapper json = JsonMapper.builder().build();
        Map<String, CookingPlan.Action> actions = new LinkedHashMap<>();
        actions.put("first", new CookingPlan.Action("first", Set.of(1), Set.of(), Set.of()));
        actions.put("second", new CookingPlan.Action("second", Set.of(2), Set.of("first"), Set.of()));
        CookingPlan plan = new CookingPlan(
                actions, Map.of(), Map.of("timer", new CookingPlan.TimerCue("timer", Duration.ofMinutes(5), 2, true)),
                Set.of(1, 2));
        CookingState state = CookingState.empty();

        CookingPlan restoredPlan = json.readValue(json.writeValueAsString(plan), CookingPlan.class);
        CookingState restoredState = json.readValue(json.writeValueAsString(state), CookingState.class);

        assertEquals(plan, restoredPlan);
        assertEquals(300, restoredPlan.timerCues().get("timer").duration().toSeconds());
        assertEquals(true, restoredPlan.timerCues().get("timer").approximate());
        assertEquals(actions.keySet().stream().toList(), restoredPlan.actions().keySet().stream().toList());
        assertEquals(state, restoredState);

        String currentJson = json.writeValueAsString(plan);
        String legacyJson = currentJson.replace(",\"approximate\":true", "")
                .replace("\"approximate\":true,", "");
        assertNotEquals(currentJson, legacyJson);
        CookingPlan legacyPlan = json.readValue(legacyJson, CookingPlan.class);
        assertFalse(legacyPlan.timerCues().get("timer").approximate());
    }
}
