package com.hansul.app.data.network

import kotlinx.serialization.Serializable

/** Exactly one of [url] (canonical YouTube watch URL) or [uploadId] is set; nulls are not encoded. */
@Serializable
data class CreateRecipeImportJobRequest(
    val url: String? = null,
    val uploadId: String? = null,
)

@Serializable
data class RecipeImportJobResponse(
    val jobId: String,
    val status: String,
    val stage: String? = null,
    val recipeId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val sourceKind: String? = null,
    val sourceUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CreateVideoUploadRequest(
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val durationSeconds: Int? = null,
)

@Serializable
data class VideoUploadResponse(
    val uploadId: String,
    val status: String,
    val chunkSizeBytes: Long,
    val chunkCount: Int,
    val receivedChunks: List<Int> = emptyList(),
    val expiresAt: String? = null,
)

/** The server's ApiErrorResponse body; the timestamp is not needed on the device. */
@Serializable
internal data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
)
