package com.hansul.app.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeTest {
    // Same configuration as LocalCookingStore.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun recipesStoredBeforeVideoImportStillDecode() {
        val stored = """{"id":"local-recipe-1","sourceUrl":"https://www.10000recipe.com/recipe/1","title":"된장국",
            "baseServings":"2인분","ingredients":[{"name":"된장","quantityText":"1큰술"}],
            "steps":[{"number":1,"text":"끓여주세요.","imageUrl":null}],"imageUrl":null}"""

        val recipe = json.decodeFromString<Recipe>(stored)

        assertEquals(RecipeOrigin.WEB_PAGE, recipe.origin)
        assertNull(recipe.video)
        assertNull(recipe.steps.single().videoStartSeconds)
        assertNull(recipe.steps.single().originalText)
    }

    @Test fun videoRecipesSurviveTheLocalStoreRoundTrip() {
        val recipe = Recipe(
            "local-recipe-2", "hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e", "내 영상 요리", "", emptyList(),
            listOf(RecipeStep(1, "끓여주세요.", "file:/frames/step-1.jpg", "끓일게요", 10, 20, 15)),
            video = RecipeVideo(VideoProvider.LOCAL_FILE, localFilePath = "/data/prepared.mp4", durationSeconds = 612),
            origin = RecipeOrigin.VIDEO_AI,
        )

        assertEquals(recipe, json.decodeFromString<Recipe>(json.encodeToString(recipe)))
    }
}
