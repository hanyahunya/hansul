package com.hansul.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.application.RecipeImportService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScaledIngredientsControllerTest {
    @Test
    void changedServingsExposeBaselineAndOriginalWithoutClaimingAnUnverifiedCookingAmount() {
        RecipeImportService recipes = mock(RecipeImportService.class);
        UUID recipeId = UUID.randomUUID();
        RecipeImportResponse recipe = new RecipeImportResponse(recipeId, "https://www.10000recipe.com/recipe/123",
                "제품 설명이 필요한 요리", BigDecimal.ONE,
                List.of(new RecipeImportResponse.Ingredient("물", "550ml", "물 550ml", new BigDecimal("550"), "ml", null, null),
                        new RecipeImportResponse.Ingredient("소금", "취향껏", "소금 취향껏", null, null, null, null)),
                List.of(new RecipeImportResponse.Step(1, "제품 설명에 따라 익힙니다.", null,
                        "제품 설명에 따라 익힙니다.", null)),
                "1인분", null, null, null, "test", Instant.EPOCH, List.of("기존 경고"));
        when(recipes.getRecipe(recipeId)).thenReturn(recipe);

        var response = new ScaledIngredientsController(recipes).getScaledIngredients(recipeId, BigDecimal.valueOf(2));
        assertEquals("550ml", response.ingredients().getFirst().originalQuantityText());
        assertEquals("1100ml", response.ingredients().getFirst().scaledQuantityText());
        assertEquals("PROPORTIONAL_ESTIMATE", response.ingredients().getFirst().status());
        assertEquals("취향껏", response.ingredients().get(1).scaledQuantityText());
        assertEquals("UNPARSEABLE", response.ingredients().get(1).status());
        assertTrue(response.warnings().stream().anyMatch(warning -> warning.contains("일괄 배수로 확정하지 않습니다")));
        assertEquals("550ml", recipe.ingredients().getFirst().quantityText());
        assertEquals(List.of("기존 경고"), recipe.warnings());
    }
}
