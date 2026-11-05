package com.hansul.app.data.video

import com.hansul.app.model.VideoImportProgress
import java.io.File
import java.io.InputStream

/** Copies a shared or picked video into app storage and returns its `hansul-video:{uuid}` pseudo URL. */
fun interface VideoIntake {
    suspend fun intake(uriString: String): String
}

/**
 * The one-time agreement that phone videos are sent through the Hansul server to Google's Gemini for analysis.
 * Nothing of a phone video leaves the device before it is given.
 */
interface VideoUploadConsent {
    suspend fun isGiven(): Boolean

    suspend fun give()
}

/** Forgotten with the process; the app keeps the answer in [VideoUploadConsentStore]. */
class InMemoryVideoUploadConsent(private var given: Boolean = false) : VideoUploadConsent {
    override suspend fun isGiven() = given

    override suspend fun give() {
        given = true
    }
}

/** Device-side video storage as the importer sees it; [LocalVideoLibrary] implements it. */
interface LocalVideoSource {
    suspend fun find(videoId: String): StoredVideo?

    /** Returns the file to upload, compressing it first when needed. Idempotent once prepared. */
    suspend fun prepare(videoId: String, onProgress: (VideoImportProgress) -> Unit): PreparedVideo

    suspend fun rememberUpload(videoId: String, uploadId: String?)

    /** A different recipe than the stored one invalidates frames extracted for the old steps. */
    suspend fun rememberRecipe(videoId: String, recipeId: String?)

    /** Step number → file URI of a JPEG scene. Steps without a usable frame are left out. */
    suspend fun extractFrames(videoId: String, frames: List<StepFrame>, onProgress: (Float) -> Unit): Map<Int, String>
}

data class StoredVideo(
    val id: String,
    val durationSeconds: Int,
    val uploadId: String? = null,
    val recipeId: String? = null,
    val playableFile: File? = null,
)

data class PreparedVideo(
    val file: File,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val durationSeconds: Int,
)

data class StepFrame(val stepNumber: Int, val atSeconds: Int)

// The Android media classes stay behind these interfaces so the library logic runs in JVM unit tests.

data class SharedVideoInfo(val mimeType: String?, val displayName: String?, val sizeBytes: Long?)

interface VideoContentReader {
    fun describe(uriString: String): SharedVideoInfo

    fun open(uriString: String): InputStream
}

data class VideoFacts(
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val hasVideo: Boolean = true,
)

fun interface VideoProbe {
    fun probe(file: File): VideoFacts
}

/** [shortSide] null keeps the frame size; otherwise the short side is scaled to it (never upscaled by callers). */
data class TranscodeSpec(val shortSide: Int?, val videoBitrate: Int)

fun interface VideoTranscoder {
    suspend fun transcode(input: File, output: File, spec: TranscodeSpec, onProgress: (Float) -> Unit)
}

fun interface StepFrameExtractor {
    suspend fun extract(video: File, frames: List<StepFrame>, directory: File, onProgress: (Float) -> Unit): Map<Int, String>
}
