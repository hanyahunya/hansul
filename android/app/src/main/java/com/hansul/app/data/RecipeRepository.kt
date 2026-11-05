package com.hansul.app.data

import com.hansul.app.data.local.PendingRecipeUrls
import com.hansul.app.data.network.RecipeImportApi
import com.hansul.app.data.network.RecipeImportRequest
import com.hansul.app.data.network.toDomain
import com.hansul.app.data.video.VideoImporter
import com.hansul.app.model.Recipe
import com.hansul.app.model.ScaledIngredients
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.share.RecipeSourceKind
import com.hansul.app.share.RecipeUrlParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val NoImportProgress: StateFlow<VideoImportProgress?> = MutableStateFlow<VideoImportProgress?>(null).asStateFlow()

interface RecipeRepository {
    val pendingRecipeUrl: Flow<String?>

    /** Stage of the video import in flight, null otherwise. 10000recipe imports report nothing. */
    val importProgress: StateFlow<VideoImportProgress?> get() = NoImportProgress

    suspend fun rememberRecipeUrl(url: String)

    suspend fun forgetRecipeUrl()

    suspend fun importRecipe(url: String): Recipe

    suspend fun getScaledIngredients(recipeId: String, servings: Int): ScaledIngredients

    /** True when importing [url] has to go through the Hansul server, which needs a signed-in user. */
    suspend fun needsServer(url: String): Boolean = RecipeUrlParser.requiresServerAnalysis(url)

    /** Code of the failure the pending link's last import ended with; such a link is not imported again by itself. */
    suspend fun importFailureCode(url: String): String? = null

    /** Remembers (or with null forgets) how the import of the pending link ended, across process death. */
    suspend fun rememberImportFailure(url: String, code: String?) = Unit
}

class DefaultRecipeRepository(
    private val api: RecipeImportApi,
    private val pendingRecipeStore: PendingRecipeUrls,
    private val videoImporter: VideoImporter,
) : RecipeRepository {
    override val pendingRecipeUrl: Flow<String?> = pendingRecipeStore.pendingUrl
    override val importProgress: StateFlow<VideoImportProgress?> = videoImporter.progress

    override suspend fun rememberRecipeUrl(url: String) {
        pendingRecipeStore.save(url)
    }

    override suspend fun forgetRecipeUrl() {
        pendingRecipeStore.clear()
    }

    override suspend fun importFailureCode(url: String): String? = pendingRecipeStore.failure(url)

    override suspend fun rememberImportFailure(url: String, code: String?) = pendingRecipeStore.rememberFailure(url, code)

    override suspend fun importRecipe(url: String): Recipe = when (RecipeUrlParser.kind(url)) {
        RecipeSourceKind.YOUTUBE -> videoImporter.importYouTube(url)
        RecipeSourceKind.LOCAL_VIDEO -> videoImporter.importLocalVideo(url)
        else -> api.importRecipe(RecipeImportRequest(url)).toDomain()
    }

    override suspend fun getScaledIngredients(
        recipeId: String,
        servings: Int,
    ): ScaledIngredients = api.getScaledIngredients(recipeId, servings).toDomain()

    override suspend fun needsServer(url: String): Boolean = true
}
