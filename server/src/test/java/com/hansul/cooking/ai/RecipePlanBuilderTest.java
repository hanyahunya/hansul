package com.hansul.cooking.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hansul.cooking.CookingPlan;
import com.hansul.recipe.api.RecipeImportResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecipePlanBuilderTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void acceptsSourceBoundActionsConditionsAndExactTimer() {
        RecipePlanBuilder builder = new RecipePlanBuilder(model("""
                {
                  "actions": [
                    {"id":"boil-water","sourceStepNumbers":[1],"prerequisiteActionIds":[],"requiredConditionIds":[]},
                    {"id":"add-onion","sourceStepNumbers":[2],"prerequisiteActionIds":["boil-water"],"requiredConditionIds":["water-boiling"]}
                  ],
                  "conditions": [{"id":"water-boiling","sourceStepNumbers":[2]}],
                  "timerCues": [{"id":"onion-simmer","sourceStepNumber":2,"durationSeconds":300,"approximate":false,"evidence":"5분"}],
                  "unlistedIngredients": []
                }
                """));

        RecipePlanBuilder.PlanBuildResult result = builder.build(recipe("물이 끓으면 양파를 넣고 5분 끓입니다."));

        assertEquals(RecipePlanBuilder.PlanSource.MODEL, result.source());
        assertEquals(2, result.plan().actions().size());
        assertEquals(300, result.plan().timerCues().get("onion-simmer").duration().toSeconds());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void rejectsTimerWhenEvidenceOrDurationIsNotExact() {
        String ambiguous = """
                {
                  "actions": [{"id":"step","sourceStepNumbers":[2],"prerequisiteActionIds":[],"requiredConditionIds":[]}],
                  "conditions": [],
                  "timerCues": [{"id":"simmer","sourceStepNumber":2,"durationSeconds":900,"approximate":false,"evidence":"5분~10분"}],
                  "unlistedIngredients": []
                }
                """;
        RecipePlanBuilder.PlanBuildResult result = new RecipePlanBuilder(model(ambiguous))
                .build(recipe("양파를 5분~10분 끓입니다."));

        assertEquals(RecipePlanBuilder.PlanSource.SOURCE_ORDER_FALLBACK, result.source());
        assertTrue(result.plan().timerCues().isEmpty());
        assertEquals(List.of("source-step-1", "source-step-2"),
                result.plan().actions().keySet().stream().toList());
    }

    @Test
    void missingProviderFallsBackWithoutInventingConditionsOrTimers() {
        StructuredOutputClient unavailable = new StructuredOutputClient() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public JsonNode generate(String instructions, Object context, String schemaName,
                    Map<String, Object> schema) {
                throw new AssertionError("Unconfigured provider must not be called");
            }
        };

        RecipePlanBuilder.PlanBuildResult result = new RecipePlanBuilder(unavailable)
                .build(recipe("양파를 넣습니다."));

        assertEquals(RecipePlanBuilder.PlanSource.SOURCE_ORDER_FALLBACK, result.source());
        assertTrue(result.plan().conditions().isEmpty());
        assertTrue(result.plan().timerCues().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("재료표 밖의 재료")));
    }

    @Test
    void fallbackOffersOnlyUnambiguousSourceDurationForExplicitManualStart() {
        RecipePlanBuilder builder = new RecipePlanBuilder(model("{}"));
        var approximate = builder.build(recipe("소금물에 약 5분 담급니다."));
        assertEquals(RecipePlanBuilder.PlanSource.SOURCE_ORDER_FALLBACK, approximate.source());
        assertEquals(300, approximate.plan().timerCues().get("source-timer-step-2").duration().toSeconds());
        assertTrue(approximate.plan().timerCues().get("source-timer-step-2").approximate());

        var lowHeat = builder.build(recipe("약불에서 5분 끓입니다."));
        // Approximation markers must surround the duration; 약불 is a heat setting, not 'approximately'.
        assertEquals(false, lowHeat.plan().timerCues().get("source-timer-step-2").approximate());
        assertTrue(builder.build(recipe("5~10분 끓입니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("10분 후 5분 더 끓입니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("전체 예상 조리 시간은 30분입니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("최소 5분 끓입니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("물이 끓을 때까지 기다립니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("1/2시간 기다립니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("-5분 기다립니다.")).plan().timerCues().isEmpty());
        assertTrue(builder.build(recipe("1시간 반 기다립니다.")).plan().timerCues().isEmpty());
        assertEquals(30, builder.build(recipe(".5분 기다립니다.")).plan().timerCues()
                .get("source-timer-step-2").duration().toSeconds());
        assertEquals(5400, builder.build(recipe("1시간 30분 기다립니다.")).plan().timerCues()
                .get("source-timer-step-2").duration().toSeconds());
    }

    @Test
    void flagsIngredientOnlyWhenTheSourceStepContainsItsExactEvidence() {
        RecipePlanBuilder builder = new RecipePlanBuilder(model("""
                {
                  "actions": [{"id":"add-egg","sourceStepNumbers":[2],"prerequisiteActionIds":[],"requiredConditionIds":[]}],
                  "conditions": [],
                  "timerCues": [],
                  "unlistedIngredients": [{"name":"계란","sourceStepNumber":2,"evidence":"계란을 넣습니다"}]
                }
                """));

        RecipePlanBuilder.PlanBuildResult result = builder.build(recipe("계란을 넣습니다."));

        assertEquals(RecipePlanBuilder.PlanSource.MODEL, result.source());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("계란")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("2단계")));
    }

    @Test
    void neverAddsSeparateTimesIntoOneTimerButKeepsApproximateTimeExplicit() {
        RecipePlanBuilder separated = new RecipePlanBuilder(model("""
                {
                  "actions": [{"id":"step","sourceStepNumbers":[2],"prerequisiteActionIds":[],"requiredConditionIds":[]}],
                  "conditions": [],
                  "timerCues": [{"id":"bad","sourceStepNumber":2,"durationSeconds":900,"approximate":false,"evidence":"10분 후 5분"}],
                  "unlistedIngredients": []
                }
                """));
        RecipePlanBuilder.PlanBuildResult result = separated.build(recipe("10분 후 5분 더 끓입니다."));
        assertEquals(RecipePlanBuilder.PlanSource.SOURCE_ORDER_FALLBACK, result.source());

        RecipePlanBuilder approximate = new RecipePlanBuilder(model("""
                {
                  "actions": [{"id":"step","sourceStepNumbers":[2],"prerequisiteActionIds":[],"requiredConditionIds":[]}],
                  "conditions": [],
                  "timerCues": [{"id":"soak","sourceStepNumber":2,"durationSeconds":300,"approximate":true,"evidence":"약 5분"}],
                  "unlistedIngredients": []
                }
                """));
        RecipePlanBuilder.PlanBuildResult approximateResult = approximate.build(recipe("약 5분 끓입니다."));
        assertEquals(RecipePlanBuilder.PlanSource.MODEL, approximateResult.source());
        assertTrue(approximateResult.plan().timerCues().get("soak").approximate());
    }

    private StructuredOutputClient model(String response) {
        return new StructuredOutputClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public JsonNode generate(String instructions, Object context, String schemaName,
                    Map<String, Object> schema) {
                return mapper.readTree(response);
            }
        };
    }

    private static RecipeImportResponse recipe(String secondStep) {
        return new RecipeImportResponse(
                UUID.randomUUID(),
                "https://www.10000recipe.com/recipe/12345",
                "시험 요리",
                BigDecimal.valueOf(2),
                List.of(),
                List.of(
                        new RecipeImportResponse.Step(1, "물을 올립니다.", null, "물을 올립니다.", null),
                        new RecipeImportResponse.Step(2, secondStep, null, secondStep, null)),
                "2인분",
                null,
                null,
                null,
                "test",
                Instant.EPOCH,
                List.of());
    }
}
