package com.hansul.app.data.network

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query

interface RecipeImportApi {
    @GET("v1/cooking-sessions/{id}")
    suspend fun getCookingSession(@Path("id") sessionId: String): CookingSessionResponse

    @POST("v1/recipe-imports")
    suspend fun importRecipe(@Body request: RecipeImportRequest): RecipeImportResponse

    @GET("v1/recipes/{id}")
    suspend fun getRecipe(@Path("id") recipeId: String): RecipeImportResponse

    @POST("v1/recipe-import-jobs")
    suspend fun createImportJob(@Body request: CreateRecipeImportJobRequest): RecipeImportJobResponse

    @GET("v1/recipe-import-jobs/{id}")
    suspend fun getImportJob(@Path("id") jobId: String): RecipeImportJobResponse

    @POST("v1/video-uploads")
    suspend fun createVideoUpload(@Body request: CreateVideoUploadRequest): VideoUploadResponse

    /** [chunk] carries application/octet-stream; every chunk but the last is exactly chunkSizeBytes long. */
    @PUT("v1/video-uploads/{id}/chunks/{index}")
    suspend fun uploadVideoChunk(
        @Path("id") uploadId: String,
        @Path("index") index: Int,
        @Body chunk: RequestBody,
    ): VideoUploadResponse

    @GET("v1/video-uploads/{id}")
    suspend fun getVideoUpload(@Path("id") uploadId: String): VideoUploadResponse

    @GET("v1/recipes/{id}/scaled-ingredients")
    suspend fun getScaledIngredients(
        @Path("id") recipeId: String,
        @Query("servings") servings: Int,
    ): ScaledIngredientsResponse

    @POST("v1/cooking-sessions")
    suspend fun startCookingSession(
        @Body request: CreateCookingSessionRequest,
    ): CookingSessionResponse

    @POST("v1/cooking-sessions/{id}/turns")
    suspend fun submitCookingTurn(
        @Path("id") sessionId: String,
        @Body request: CookingTurnRequest,
    ): CookingTurnResponse

    @POST("v1/cooking-sessions/{id}/navigation")
    suspend fun navigateCookingStep(
        @Path("id") sessionId: String,
        @Body request: CookingNavigationRequest,
    ): CookingNavigationResponse

    @POST("v1/cooking-sessions/{id}/step-progress")
    suspend fun updateStepProgress(
        @Path("id") sessionId: String,
        @Body request: StepProgressRequest,
    ): CookingNavigationResponse

    @POST("v1/cooking-sessions/{id}/timer-start-acks")
    suspend fun acknowledgeTimerStart(
        @Path("id") sessionId: String,
        @Body request: TimerStartAckRequest,
    ): TimerStartAckResponse

    @POST("v1/cooking-sessions/{id}/timer-start-requests")
    suspend fun requestTimerStart(@Path("id") sessionId: String,
        @Body request: TimerStartRequest): TimerStartRequestResponse
}
