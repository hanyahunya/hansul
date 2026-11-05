package com.hansul.app.data.network

import okhttp3.RequestBody

/** Test base: every endpoint fails unless a fake overrides the ones its test is about. */
open class UnusedRecipeImportApi : RecipeImportApi {
    private fun unused(): Nothing = throw AssertionError("Unexpected API call")

    override suspend fun getCookingSession(sessionId: String): CookingSessionResponse = unused()
    override suspend fun importRecipe(request: RecipeImportRequest): RecipeImportResponse = unused()
    override suspend fun getRecipe(recipeId: String): RecipeImportResponse = unused()
    override suspend fun createImportJob(request: CreateRecipeImportJobRequest): RecipeImportJobResponse = unused()
    override suspend fun getImportJob(jobId: String): RecipeImportJobResponse = unused()
    override suspend fun createVideoUpload(request: CreateVideoUploadRequest): VideoUploadResponse = unused()
    override suspend fun uploadVideoChunk(uploadId: String, index: Int, chunk: RequestBody): VideoUploadResponse = unused()
    override suspend fun getVideoUpload(uploadId: String): VideoUploadResponse = unused()
    override suspend fun getScaledIngredients(recipeId: String, servings: Int): ScaledIngredientsResponse = unused()
    override suspend fun startCookingSession(request: CreateCookingSessionRequest): CookingSessionResponse = unused()
    override suspend fun submitCookingTurn(sessionId: String, request: CookingTurnRequest): CookingTurnResponse = unused()
    override suspend fun navigateCookingStep(sessionId: String, request: CookingNavigationRequest): CookingNavigationResponse =
        unused()
    override suspend fun updateStepProgress(sessionId: String, request: StepProgressRequest): CookingNavigationResponse =
        unused()
    override suspend fun acknowledgeTimerStart(sessionId: String, request: TimerStartAckRequest): TimerStartAckResponse =
        unused()
    override suspend fun requestTimerStart(sessionId: String, request: TimerStartRequest): TimerStartRequestResponse = unused()
}
