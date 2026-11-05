package com.hansul.app.data

import com.hansul.app.data.local.PendingRecipeUrls
import com.hansul.app.data.network.RecipeImportRequest
import com.hansul.app.data.network.RecipeImportResponse
import com.hansul.app.data.network.UnusedRecipeImportApi
import com.hansul.app.data.video.VideoImporter
import com.hansul.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModeRepositoriesTest {
    @Test fun sessionAndRecipeCommandsKeepOriginAfterModeChanges() = runBlocking {
        val mode = MutableStateFlow(ExecutionMode.LOCAL)
        val localRecipes = FakeRecipes("local-recipe-example")
        val cloudRecipes = FakeRecipes("cloud-recipe-example")
        val recipes = ModeRecipeRepository(localRecipes, cloudRecipes, mode)
        val localSessions = FakeSessions("local-session-example")
        val cloudSessions = FakeSessions("cloud-session-example")
        val sessions = ModeCookingSessionRepository(localSessions, cloudSessions)
        val imported = recipes.importRecipe("https://www.10000recipe.com/recipe/123")
        mode.value = ExecutionMode.CLOUD
        assertEquals("local-session-example", sessions.start(imported.id, 1).id)
        assertEquals("local-session-example", sessions.getSession("local-session-example").id)
        assertEquals("local-recipe-example", recipes.getScaledIngredients(imported.id, 2).recipeId)
        assertEquals("cloud-recipe-example", recipes.importRecipe("https://www.10000recipe.com/recipe/123").id)
        assertEquals(0, cloudSessions.startCalls)
    }

    @Test fun needsServerFollowsTheModeAndProgressComesFromTheSharedImporter() = runBlocking {
        val mode = MutableStateFlow(ExecutionMode.LOCAL)
        val shared = MutableStateFlow<VideoImportProgress?>(VideoImportProgress(VideoImportStage.WATCHING_VIDEO))
        val recipes = ModeRecipeRepository(
            FakeRecipes("local-recipe-example", needsServer = false), FakeRecipes("cloud", needsServer = true), mode, shared,
        )

        assertFalse(recipes.needsServer("https://youtu.be/AbCdEfGhIjK"))
        mode.value = ExecutionMode.CLOUD
        assertTrue(recipes.needsServer("https://youtu.be/AbCdEfGhIjK"))
        assertSame(shared, recipes.importProgress)
        assertTrue("Repositories without an importer default to the URL rule",
            FakeRecipes("x").needsServer("hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertNull(FakeRecipes("x").importProgress.value)
    }

    @Test fun cloudRepositorySendsVideosToTheImporterAndPagesToTheImportEndpoint() = runBlocking {
        val importer = FakeImporter()
        val api = object : UnusedRecipeImportApi() {
            val requests = mutableListOf<String>()
            override suspend fun importRecipe(request: RecipeImportRequest): RecipeImportResponse {
                requests += request.url
                return RecipeImportResponse(sourceUrl = request.url, title = "만개 레시피")
            }
        }
        val repository = DefaultRecipeRepository(api, FakePending(), importer)

        repository.importRecipe("https://youtu.be/AbCdEfGhIjK?si=share")
        repository.importRecipe("hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e")
        assertEquals("만개 레시피", repository.importRecipe("https://www.10000recipe.com/recipe/123").title)

        assertEquals(listOf("youtube:https://youtu.be/AbCdEfGhIjK?si=share",
            "local:hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e"), importer.calls)
        assertEquals(listOf("https://www.10000recipe.com/recipe/123"), api.requests)
        assertTrue(repository.needsServer("https://www.10000recipe.com/recipe/123"))
        assertSame(importer.progress, repository.importProgress)
    }

    @Test fun theCloudRepositoryKeepsAnImportFailureWithThePendingLink() = runBlocking {
        val repository = DefaultRecipeRepository(UnusedRecipeImportApi(), FakePending(), FakeImporter())
        val video = "https://www.youtube.com/watch?v=AbCdEfGhIjK"

        repository.rememberImportFailure(video, "NOT_A_RECIPE_VIDEO")
        assertEquals("NOT_A_RECIPE_VIDEO", repository.importFailureCode(video))
        repository.rememberImportFailure(video, null)
        assertNull(repository.importFailureCode(video))

        repository.rememberImportFailure(video, "AI_QUOTA_EXCEEDED")
        repository.rememberRecipeUrl(video)
        assertNull("Sharing the link again starts over", repository.importFailureCode(video))
    }

    private class FakeRecipes(private val id: String, private val needsServer: Boolean? = null) : RecipeRepository {
        override val pendingRecipeUrl = MutableStateFlow<String?>(null)
        override suspend fun rememberRecipeUrl(url: String) { pendingRecipeUrl.value = url }
        override suspend fun forgetRecipeUrl() { pendingRecipeUrl.value = null }
        override suspend fun importRecipe(url: String) = Recipe(id, url, "원문", "1인분", emptyList(), listOf(RecipeStep(1, "원문 단계", null)))
        override suspend fun getScaledIngredients(recipeId: String, servings: Int) = ScaledIngredients(recipeId, "원문", "1", "$servings", emptyList(), emptyList())
        override suspend fun needsServer(url: String) = needsServer ?: super.needsServer(url)
    }

    private class FakePending : PendingRecipeUrls {
        override val pendingUrl = MutableStateFlow<String?>(null)
        val failures = mutableMapOf<String, String>()
        override suspend fun save(url: String) { pendingUrl.value = url; failures.clear() }
        override suspend fun clear() { pendingUrl.value = null; failures.clear() }
        override suspend fun failure(url: String): String? = failures[url]
        override suspend fun rememberFailure(url: String, code: String?) {
            if (code == null) failures.remove(url) else failures[url] = code
        }
    }

    private class FakeImporter : VideoImporter {
        override val progress = MutableStateFlow<VideoImportProgress?>(null)
        val calls = mutableListOf<String>()
        override suspend fun importYouTube(canonicalUrl: String) = recipe("youtube:$canonicalUrl")
        override suspend fun importLocalVideo(pseudoUrl: String) = recipe("local:$pseudoUrl")
        private fun recipe(call: String): Recipe {
            calls += call
            return Recipe("server-id", call, "영상", "", emptyList(), listOf(RecipeStep(1, "단계", null)), origin = RecipeOrigin.VIDEO_AI)
        }
    }

    private class FakeSessions(private val id: String) : CookingSessionRepository {
        var startCalls = 0
        override suspend fun start(recipeId: String, targetServings: Int): CookingSession {
            startCalls++
            return CookingSession(id, emptyMap(), emptyList())
        }
        override suspend fun getSession(sessionId: String) = CookingSession(id, emptyMap(), emptyList())
        override suspend fun submitTurn(sessionId: String, clientTurnId: String, transcript: String) =
            CookingTurn(id, clientTurnId, Addressing.DIRECT, "답변", emptyList(), emptyList())
        override suspend fun acknowledgeTimerStart(sessionId: String, clientAckId: String, timerCueId: String) =
            TimerAcknowledgement(id, clientAckId, null)
    }
}
