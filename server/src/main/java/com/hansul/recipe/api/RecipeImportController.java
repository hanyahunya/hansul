package com.hansul.recipe.api;

import com.hansul.recipe.application.RecipeImportService;
import jakarta.validation.Valid;
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
@RequestMapping("/v1")
public class RecipeImportController {

    private final RecipeImportService recipeImportService;

    public RecipeImportController(RecipeImportService recipeImportService) {
        this.recipeImportService = recipeImportService;
    }

    @PostMapping("/recipe-imports")
    public ResponseEntity<RecipeImportResponse> importRecipe(
            @Valid @RequestBody RecipeImportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(recipeImportService.importRecipe(request.url()));
    }

    @GetMapping("/recipes/{id}")
    public RecipeImportResponse getRecipe(@PathVariable UUID id) {
        return recipeImportService.getRecipe(id);
    }
}
