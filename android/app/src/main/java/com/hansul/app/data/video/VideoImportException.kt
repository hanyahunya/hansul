package com.hansul.app.data.video

import com.hansul.app.data.network.ApiErrorBody
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * A video import stopped for a reason with a stable [code]: a server code from contract section 2.4 (HTTP error
 * body or failed job), or one of the device codes below. The message is English diagnostics; the UI shows its own
 * Korean text per code.
 */
class VideoImportException(
    val code: String,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: code, cause) {
    companion object {
        const val TIMEOUT = "TIMEOUT"
        const val VIDEO_TOO_LONG = "VIDEO_TOO_LONG"
        const val VIDEO_UPLOAD_TOO_LARGE = "VIDEO_UPLOAD_TOO_LARGE"
        const val UPLOAD_INCOMPLETE = "UPLOAD_INCOMPLETE"
        const val VIDEO_CHUNK_INVALID = "VIDEO_CHUNK_INVALID"
        const val INVALID_RECIPE_URL = "INVALID_RECIPE_URL"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"

        /** The shared or stored video cannot be read on this device (missing, not a video, no duration). */
        const val LOCAL_VIDEO_UNREADABLE = "LOCAL_VIDEO_UNREADABLE"

        /** Media3 could not re-encode the video into an uploadable size. */
        const val VIDEO_COMPRESSION_FAILED = "VIDEO_COMPRESSION_FAILED"

        /** Not enough free device storage to keep a copy of the video. */
        const val LOCAL_STORAGE_FULL = "LOCAL_STORAGE_FULL"
    }
}

/** A video endpoint answered 401: video analysis runs on the Hansul server and needs a signed-in user. */
class VideoLoginRequiredException(cause: Throwable? = null) :
    RuntimeException("Sign-in is required for video analysis", cause)

private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val errorCode = Regex("^[A-Z][A-Z0-9_]{1,59}$")

/** 401 → login required; a JSON error code → [VideoImportException]; anything else stays an [HttpException]. */
internal fun HttpException.toVideoFailure(): Exception {
    if (code() == 401) return VideoLoginRequiredException(this)
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val error = body?.let { runCatching { errorJson.decodeFromString<ApiErrorBody>(it) }.getOrNull() }
    val code = error?.code?.takeIf(errorCode::matches) ?: return this
    return VideoImportException(code, error.message, this)
}

internal suspend fun <T> videoCall(block: suspend () -> T): T = try {
    block()
} catch (error: HttpException) {
    throw error.toVideoFailure()
}
