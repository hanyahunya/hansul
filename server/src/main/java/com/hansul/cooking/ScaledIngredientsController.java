package com.hansul.cooking;

import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.application.RecipeImportService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A deterministic view; the stored recipe snapshot remains unchanged. */
@RestController
@RequestMapping("/v1/recipes")
public class ScaledIngredientsController {
    private final RecipeImportService recipes;

    public ScaledIngredientsController(RecipeImportService recipes) {
        this.recipes = recipes;
    }

    @GetMapping("/{id}/scaled-ingredients")
    public ScaledIngredientsResponse getScaledIngredients(
            @PathVariable UUID id,
            @RequestParam BigDecimal servings) {
        if (servings == null || servings.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "servings must be positive");
        }
        RecipeImportResponse recipe = recipes.getRecipe(id);
        BigDecimal baseServings = recipe.baseServings();
        boolean canScale = baseServings != null && baseServings.signum() > 0;
        List<ScaledIngredientsResponse.Ingredient> ingredients = new ArrayList<>();
        List<String> warnings = new ArrayList<>(recipe.warnings());
        if (canScale && baseServings.compareTo(servings) != 0) {
            warnings.add("환산량은 인분 비율로 계산한 기준값입니다. 물·육수·양념과 조리 시간은 냄비 크기, 증발량, 제품 설명과 맛에 따라 별도 조정해야 하며 일괄 배수로 확정하지 않습니다.");
        }
        for (RecipeImportResponse.Ingredient ingredient : recipe.ingredients()) {
            String originalQuantity = ingredient.quantityText() == null ? "" : ingredient.quantityText();
            if (!canScale) {
                ingredients.add(new ScaledIngredientsResponse.Ingredient(
                        ingredient.name(), originalQuantity, originalQuantity, "", "BASE_SERVINGS_UNKNOWN"));
                continue;
            }
            ServingScaler.ScaledAmount scaled = ServingScaler.scale(
                    originalQuantity, baseServings, servings);
            ingredients.add(new ScaledIngredientsResponse.Ingredient(
                    ingredient.name(),
                    originalQuantity,
                    scaled.displayText(),
                    scaled.originalNote(),
                    scaled.status().name()));
        }
        return new ScaledIngredientsResponse(
                id,
                recipe.title(),
                baseServings,
                servings,
                List.copyOf(ingredients),
                List.copyOf(warnings));
    }

    public record ScaledIngredientsResponse(
            UUID recipeId,
            String title,
            BigDecimal baseServings,
            BigDecimal targetServings,
            List<Ingredient> ingredients,
            List<String> warnings) {
        public record Ingredient(
                String name,
                String originalQuantityText,
                String scaledQuantityText,
                String originalNote,
                String status) {}
    }
}
