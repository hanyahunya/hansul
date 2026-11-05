package com.hansul.recipe.application;

import java.util.UUID;

public class RecipeNotFoundException extends RuntimeException {

    public RecipeNotFoundException(UUID id) {
        super("Recipe not found: " + id);
    }
}
