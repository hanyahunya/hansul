package com.hansul.app.data

import com.hansul.app.model.StepProgressStatus
import com.hansul.app.model.VideoImportProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * Existing entities route by origin, even if the setting changes while a command is in flight.
 * Both modes analyse videos with one shared importer, so its progress is the progress of either mode.
 */
internal class ModeRecipeRepository(
    private val local: RecipeRepository,
    private val cloud: RecipeRepository,
    private val mode: StateFlow<ExecutionMode>,
    override val importProgress: StateFlow<VideoImportProgress?> = local.importProgress,
) : RecipeRepository {
    private val forNewImports get() = if (mode.value == ExecutionMode.LOCAL) local else cloud
    override val pendingRecipeUrl = local.pendingRecipeUrl
    override suspend fun rememberRecipeUrl(url: String) = local.rememberRecipeUrl(url)
    override suspend fun forgetRecipeUrl() = local.forgetRecipeUrl()
    override suspend fun importFailureCode(url: String) = local.importFailureCode(url)
    override suspend fun rememberImportFailure(url: String, code: String?) = local.rememberImportFailure(url, code)
    override suspend fun importRecipe(url: String) = forNewImports.importRecipe(url)
    override suspend fun needsServer(url: String) = forNewImports.needsServer(url)
    override suspend fun getScaledIngredients(recipeId: String, servings: Int) =
        (if (recipeId.startsWith("local-recipe-")) local else cloud).getScaledIngredients(recipeId, servings)
}

internal class ModeCookingSessionRepository(
    private val local: CookingSessionRepository,
    private val cloud: CookingSessionRepository,
) : CookingSessionRepository {
    private fun forSession(id: String) = if (id.startsWith("local-session-")) local else cloud
    override suspend fun start(recipeId: String, targetServings: Int) =
        (if (recipeId.startsWith("local-recipe-")) local else cloud).start(recipeId, targetServings)
    override suspend fun getSession(sessionId: String) = forSession(sessionId).getSession(sessionId)
    override suspend fun navigate(sessionId: String, clientNavigationId: String, sourceStepNumber: Int) =
        forSession(sessionId).navigate(sessionId, clientNavigationId, sourceStepNumber)
    override suspend fun updateStepProgress(sessionId: String, clientCommandId: String, sourceStepNumber: Int,
        status: StepProgressStatus) = forSession(sessionId).updateStepProgress(sessionId, clientCommandId, sourceStepNumber, status)
    override suspend fun submitTurn(sessionId: String, clientTurnId: String, transcript: String) =
        forSession(sessionId).submitTurn(sessionId, clientTurnId, transcript)
    override suspend fun acknowledgeTimerStart(sessionId: String, clientAckId: String, timerCueId: String) =
        forSession(sessionId).acknowledgeTimerStart(sessionId, clientAckId, timerCueId)
    override suspend fun requestTimerStart(sessionId: String, clientCommandId: String, timerCueId: String) =
        forSession(sessionId).requestTimerStart(sessionId, clientCommandId, timerCueId)
}
