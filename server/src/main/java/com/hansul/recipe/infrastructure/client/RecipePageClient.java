package com.hansul.recipe.infrastructure.client;

import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;

public interface RecipePageClient {

    String fetch(CanonicalRecipeUrl source);
}
