package com.hansul.recipe.application;

import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.infrastructure.parser.Hundred00RecipeDocumentParser;
import com.hansul.recipe.infrastructure.parser.IngredientTextParser;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.time.Clock;
import tools.jackson.databind.json.JsonMapper;

/** The real recipe service for tests outside this package, whose mapper and fingerprint are package-private. */
public final class RecipeImportServiceFixture {

    private RecipeImportServiceFixture() {
    }

    /** Video imports never fetch a recipe page, so the page client fails the test if anything asks it. */
    public static RecipeImportService create(
            RecipeSnapshotRepository repository,
            CurrentUserProvider currentUser,
            Clock clock
    ) {
        return new RecipeImportService(
                new RecipeSourcePolicy(new Hundred00RecipeUrlPolicy(), new YouTubeUrlPolicy()),
                source -> {
                    throw new AssertionError("A video import must not fetch a recipe page");
                },
                new Hundred00RecipeDocumentParser(JsonMapper.builder().build(), new IngredientTextParser()),
                repository,
                new RecipeResponseMapper(),
                new RecipeFingerprint(),
                currentUser,
                clock
        );
    }
}
