package com.hansul.recipe.api;

import jakarta.validation.constraints.NotBlank;

public record RecipeImportRequest(
        @NotBlank(message = "url is required") String url
) {
}
