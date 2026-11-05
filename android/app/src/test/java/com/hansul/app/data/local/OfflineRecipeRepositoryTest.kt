package com.hansul.app.data.local

import com.hansul.app.data.video.VideoImportException
import com.hansul.app.data.video.VideoImporter
import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeOrigin
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoProvider
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineRecipeRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val watchUrl = "https://www.youtube.com/watch?v=AbCdEfGhIjK"
    private val pseudoUrl = "hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e"
    private val requested = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        requested += url
        val (status, type, body) = when {
            url.startsWith("https://i.ytimg.com/") -> Triple(200, "image/jpeg", "jpeg")
            url == "https://www.10000recipe.com/recipe/42" -> Triple(200, "text/html; charset=utf-8", PAGE)
            else -> Triple(404, "text/plain", "")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody(type.toMediaType())).build()
    }.build()

    @Test fun youTubeVideoIsAnalysedOnceThenServedFromTheDevice() = runBlocking {
        val store = FakeStore(temp.newFolder())
        val importer = FakeImporter()
        val repository = OfflineRecipeRepository(store, FakePending(), client, importer)
        val shared = "https://youtu.be/AbCdEfGhIjK?si=share"

        assertTrue(repository.needsServer(shared))
        val recipe = repository.importRecipe(shared)

        assertEquals(listOf("youtube:$watchUrl"), importer.calls)
        assertEquals(LocalCookingStore.recipeId(watchUrl), recipe.id)
        assertEquals(watchUrl, recipe.sourceUrl)
        assertEquals(RecipeOrigin.VIDEO_AI, recipe.origin)
        assertEquals(95, recipe.steps.single().keyFrameSeconds)
        assertTrue("Thumbnail is cached like a 10000recipe photo", recipe.imageUrl.orEmpty().startsWith("file:"))
        assertEquals(listOf("https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg"), requested)
        assertFalse(repository.needsServer(shared))
        assertFalse(repository.needsServer(watchUrl))

        assertEquals(recipe, repository.importRecipe(watchUrl))
        assertEquals("The stored recipe is reused without the server", 1, importer.calls.size)
    }

    @Test fun phoneVideoIsStoredUnderItsPseudoUrlWithLocalScenePhotos() = runBlocking {
        val store = FakeStore(temp.newFolder())
        val importer = FakeImporter()
        val repository = OfflineRecipeRepository(store, FakePending(), client, importer)

        val recipe = repository.importRecipe(pseudoUrl)

        assertEquals(listOf("local:$pseudoUrl"), importer.calls)
        assertEquals(LocalCookingStore.recipeId(pseudoUrl), recipe.id)
        assertEquals(pseudoUrl, recipe.sourceUrl)
        assertEquals("file:/frames/step-1.jpg", recipe.steps.single().imageUrl)
        assertEquals(VideoProvider.LOCAL_FILE, recipe.video?.provider)
        assertTrue("Local frames are never downloaded", requested.isEmpty())
        assertFalse(repository.needsServer(pseudoUrl))
        assertNotNullRecipe(store, LocalCookingStore.recipeId(pseudoUrl))
    }

    @Test fun failedAnalysisSavesNothingAndStillNeedsTheServer() = runBlocking {
        val store = FakeStore(temp.newFolder())
        val importer = FakeImporter().apply { failure = VideoImportException("NOT_A_RECIPE_VIDEO") }
        val repository = OfflineRecipeRepository(store, FakePending(), client, importer)

        val error = runCatching { repository.importRecipe(watchUrl) }.exceptionOrNull()

        assertEquals("NOT_A_RECIPE_VIDEO", (error as VideoImportException).code)
        assertTrue(store.recipes.isEmpty())
        assertTrue(repository.needsServer(watchUrl))
    }

    @Test fun unreadableStoredVideoRecipeStillNeedsTheServerInsteadOfFailingTheCheck() = runBlocking {
        val store = FakeStore(temp.newFolder()).apply { readFailure = IllegalStateException("corrupt recipe file") }
        val repository = OfflineRecipeRepository(store, FakePending(), client, FakeImporter())

        assertTrue(repository.needsServer(watchUrl))
        assertTrue(repository.needsServer(pseudoUrl))
        assertFalse(repository.needsServer("https://www.10000recipe.com/recipe/42"))
    }

    @Test fun tenThousandRecipePagesNeverNeedTheServerOrTheImporter() = runBlocking {
        val importer = FakeImporter()
        val repository = OfflineRecipeRepository(FakeStore(temp.newFolder()), FakePending(), client, importer)

        assertFalse(repository.needsServer("https://m.10000recipe.com/recipe/42"))
        val recipe = repository.importRecipe("https://m.10000recipe.com/recipe/42")

        assertEquals("영상 요리", recipe.title)
        assertEquals(VideoProvider.YOUTUBE, recipe.video?.provider)
        assertTrue(importer.calls.isEmpty())
        assertSame(importer.progress, repository.importProgress)
        assertFalse(repository.needsServer("https://www.instagram.com/reel/C1a2B3c4D5e/"))
    }

    @Test fun aPageSavedBeforeRecipeVideosWereReadGetsItsVideoOnceAndKeepsItsSteps() = runBlocking {
        val store = FakeStore(temp.newFolder())
        val page = "https://www.10000recipe.com/recipe/42"
        val saved = Recipe(LocalCookingStore.recipeId(page), page, "예전에 저장한 요리", "2인분",
            listOf(Ingredient("양파", "1개")), listOf(RecipeStep(4, "예전에 읽은 단계", null)))
        store.recipes[saved.id] = saved
        val repository = OfflineRecipeRepository(store, FakePending(), client, FakeImporter())

        val recipe = repository.importRecipe(page)

        assertEquals(RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", "https://www.youtube.com/watch?v=AbCdEfGhIjK",
            embeddable = true), recipe.video)
        // A cooking session may point at the saved steps, so only the video comes from the page read now.
        assertEquals(listOf(saved.title, saved.baseServings), listOf(recipe.title, recipe.baseServings))
        assertEquals(saved.ingredients, recipe.ingredients)
        assertEquals(saved.steps, recipe.steps)
        assertEquals(recipe.video, store.findRecipe(saved.id)?.video)
        repository.importRecipe(page)
        assertEquals("The page is read again only once", listOf(page), requested)
    }

    @Test fun aSavedPageThatCannotBeReadAgainOpensAsSaved() = runBlocking {
        val store = FakeStore(temp.newFolder())
        val page = "https://www.10000recipe.com/recipe/43"
        val saved = Recipe(LocalCookingStore.recipeId(page), page, "지금은 못 읽는 요리", "1인분", emptyList(),
            listOf(RecipeStep(1, "저장한 단계", null)))
        store.recipes[saved.id] = saved
        val repository = OfflineRecipeRepository(store, FakePending(), client, FakeImporter())

        assertEquals(saved, repository.importRecipe(page))
        assertEquals(saved, repository.importRecipe(page))
        assertEquals("It is tried again next time", listOf(page, page), requested)
    }

    private suspend fun assertNotNullRecipe(store: FakeStore, id: String) {
        assertEquals(id, store.findRecipe(id)?.id)
    }

    private class FakeStore(override val imageDirectory: File) : LocalRecipeStore {
        val recipes = mutableMapOf<String, Recipe>()
        var readFailure: Exception? = null

        override suspend fun saveRecipe(recipe: Recipe) {
            recipes[recipe.id] = recipe
        }

        override suspend fun findRecipe(id: String): Recipe? {
            readFailure?.let { throw it }
            return recipes[id]
        }

        override fun imageFile(sourceUrl: String) = File(imageDirectory, "${LocalCookingStore.digest(sourceUrl)}.image")

        override fun withCachedImages(recipe: Recipe): Recipe = recipe.copy(
            imageUrl = cached(recipe.imageUrl),
            steps = recipe.steps.map { it.copy(imageUrl = cached(it.imageUrl)) },
        )

        private fun cached(url: String?) = url?.let { imageFile(it).takeIf(File::isFile)?.toURI()?.toString() ?: it }
    }

    private class FakePending : PendingRecipeUrls {
        override val pendingUrl = MutableStateFlow<String?>(null)
        override suspend fun save(url: String) { pendingUrl.value = url }
        override suspend fun clear() { pendingUrl.value = null }
        override suspend fun failure(url: String): String? = null
        override suspend fun rememberFailure(url: String, code: String?) = Unit
    }

    private class FakeImporter : VideoImporter {
        override val progress = MutableStateFlow<VideoImportProgress?>(null)
        val calls = mutableListOf<String>()
        var failure: Exception? = null

        override suspend fun importYouTube(canonicalUrl: String): Recipe {
            calls += "youtube:$canonicalUrl"
            failure?.let { throw it }
            return Recipe(
                "server-recipe-id", canonicalUrl, "김치찌개", "2", listOf(Ingredient("김치", "1/4포기")),
                listOf(RecipeStep(1, "김치를 볶아주세요.", null, "김치 볶을게요", 83, 112, 95)),
                imageUrl = "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg",
                video = RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", canonicalUrl, embeddable = true),
                origin = RecipeOrigin.VIDEO_AI,
            )
        }

        override suspend fun importLocalVideo(pseudoUrl: String): Recipe {
            calls += "local:$pseudoUrl"
            failure?.let { throw it }
            return Recipe(
                "server-recipe-id", pseudoUrl, "내 영상 요리", "", emptyList(),
                listOf(RecipeStep(1, "끓여주세요.", "file:/frames/step-1.jpg", null, 10, 20, 15)),
                video = RecipeVideo(VideoProvider.LOCAL_FILE, localFilePath = "/data/prepared.mp4", durationSeconds = 612),
                origin = RecipeOrigin.VIDEO_AI,
            )
        }
    }

    private companion object {
        val PAGE = """
            <div class="view2_summary"><h3>영상 요리</h3></div>
            <iframe id="ifrmRecipeVideo" src="about:blank" org_src="https://www.youtube.com/embed/AbCdEfGhIjK"></iframe>
            <div class="view_step_cont" id="stepDiv1"><div id="stepdescr1">재료를 준비해주세요.</div></div>
        """.trimIndent()
    }
}
