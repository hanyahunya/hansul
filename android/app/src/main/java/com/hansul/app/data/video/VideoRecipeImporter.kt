package com.hansul.app.data.video

import com.hansul.app.data.network.CreateRecipeImportJobRequest
import com.hansul.app.data.network.CreateVideoUploadRequest
import com.hansul.app.data.network.RecipeImportApi
import com.hansul.app.data.network.RecipeImportJobResponse
import com.hansul.app.data.network.VideoUploadResponse
import com.hansul.app.data.network.toDomain
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.model.VideoProvider
import com.hansul.app.share.RecipeSourceKind
import com.hansul.app.share.RecipeUrlParser
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** Server-side video analysis, shared by the cloud and the local recipe repositories. */
interface VideoImporter {
    val progress: StateFlow<VideoImportProgress?>

    suspend fun importYouTube(canonicalUrl: String): Recipe

    suspend fun importLocalVideo(pseudoUrl: String): Recipe
}

class VideoRecipeImporter(
    private val api: RecipeImportApi,
    private val library: LocalVideoSource,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : VideoImporter {
    private val _progress = MutableStateFlow<VideoImportProgress?>(null)
    override val progress: StateFlow<VideoImportProgress?> = _progress.asStateFlow()
    private val progressLock = Any()
    private var runCounter = 0L
    private var latestRun = 0L

    override suspend fun importYouTube(canonicalUrl: String): Recipe = tracked { run ->
        val url = RecipeUrlParser.extract(canonicalUrl)?.takeIf { RecipeUrlParser.kind(it) == RecipeSourceKind.YOUTUBE }
            ?: throw VideoImportException(VideoImportException.INVALID_RECIPE_URL, "Not a YouTube video URL")
        run.report(VideoImportStage.QUEUED)
        val recipeId = awaitRecipeId(videoCall { api.createImportJob(CreateRecipeImportJobRequest(url = url)) }, run)
        run.report(VideoImportStage.SAVING)
        videoCall { api.getRecipe(recipeId) }.toDomain()
    }

    override suspend fun importLocalVideo(pseudoUrl: String): Recipe = tracked { run ->
        val videoId = RecipeUrlParser.localVideoId(pseudoUrl)
            ?: throw VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "Not a local video URL")
        run.report(VideoImportStage.PREPARING_VIDEO)
        val stored = library.find(videoId)
            ?: throw VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "The video is no longer stored on this device")
        stored.recipeId?.let { recipeId ->
            fetchRecipe(recipeId)?.let { return@tracked withLocalVideo(pseudoUrl, videoId, it, run) }
            // Gone on the server (deleted, or another account's upload): analyse the video again.
            library.rememberRecipe(videoId, null)
        }
        val prepared = library.prepare(videoId) { run.report(it.stage, it.fraction) }
        val recipeId = awaitRecipeId(submitUpload(videoId, prepared, run), run)
        library.rememberRecipe(videoId, recipeId)
        run.report(VideoImportStage.SAVING)
        withLocalVideo(pseudoUrl, videoId, videoCall { api.getRecipe(recipeId) }.toDomain(), run)
    }

    private suspend fun fetchRecipe(recipeId: String): Recipe? = try {
        videoCall { api.getRecipe(recipeId) }.toDomain()
    } catch (error: VideoImportException) {
        if (error.code == "RECIPE_NOT_FOUND") null else throw error
    } catch (error: HttpException) {
        if (error.code() == 404) null else throw error
    }

    private suspend fun withLocalVideo(pseudoUrl: String, videoId: String, recipe: Recipe, run: Run): Recipe {
        val stored = library.find(videoId)
        val file = stored?.playableFile
        val frames = if (file == null) emptyMap() else {
            run.report(VideoImportStage.EXTRACTING_FRAMES, 0f)
            val requests = recipe.steps.mapNotNull { step -> frameSecond(step)?.let { StepFrame(step.number, it) } }
            try {
                library.extractFrames(videoId, requests) { run.report(VideoImportStage.EXTRACTING_FRAMES, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emptyMap() // Scene photos are a convenience; the analysed recipe must not be lost over them.
            }
        }
        return recipe.copy(
            sourceUrl = pseudoUrl,
            video = RecipeVideo(
                VideoProvider.LOCAL_FILE,
                localFilePath = file?.absolutePath,
                durationSeconds = stored?.durationSeconds ?: recipe.video?.durationSeconds,
            ),
            steps = recipe.steps.map { step -> frames[step.number]?.let { step.copy(imageUrl = it) } ?: step },
        )
    }

    private fun frameSecond(step: RecipeStep): Int? = step.keyFrameSeconds
        ?: step.videoStartSeconds?.let { start -> step.videoEndSeconds?.let { end -> (start + end) / 2 } ?: start }

    /**
     * Queues analysis of the prepared file. An upload remembered from an earlier attempt is reused first: CONSUMED
     * still matters because the server answers a job for it with the recipe it already made. A remembered upload the
     * server no longer accepts is forgotten and replaced once, so a stale id can never block every retry.
     * A failed job leaves the id stored: after AI_QUOTA_EXCEEDED or AI_UNAVAILABLE the server keeps the upload
     * COMPLETE, so a retry only queues a new job; after other failures it reports the upload FAILED and it is replaced.
     */
    private suspend fun submitUpload(videoId: String, video: PreparedVideo, run: Run): RecipeImportJobResponse {
        // Read after prepare(): preparing a new file forgets the upload of the old one.
        library.find(videoId)?.uploadId?.let { storedId ->
            try {
                val existing = videoCall { api.getVideoUpload(storedId) }
                val uploadId = when (existing.status) {
                    "COMPLETE", "CONSUMED" -> storedId
                    "RECEIVING" -> sendMissingChunks(existing, video, run)
                    else -> null
                }
                if (uploadId != null) return createUploadJob(uploadId, run)
            } catch (error: VideoImportException) {
                if (error.code !in STALE_UPLOAD_CODES) throw error
            }
            library.rememberUpload(videoId, null)
        }
        val created = videoCall {
            api.createVideoUpload(CreateVideoUploadRequest(video.contentType, video.sizeBytes, video.sha256, video.durationSeconds))
        }
        library.rememberUpload(videoId, created.uploadId)
        try {
            val uploadId = when (created.status) {
                "COMPLETE" -> created.uploadId
                "RECEIVING" -> sendMissingChunks(created, video, run)
                else -> throw VideoImportException("VIDEO_UPLOAD_CLOSED", "Upload ${created.uploadId} is ${created.status}")
            }
            return createUploadJob(uploadId, run)
        } catch (error: VideoImportException) {
            if (error.code in STALE_UPLOAD_CODES) library.rememberUpload(videoId, null)
            throw error
        }
    }

    private suspend fun createUploadJob(uploadId: String, run: Run): RecipeImportJobResponse {
        run.report(VideoImportStage.QUEUED)
        return videoCall { api.createImportJob(CreateRecipeImportJobRequest(uploadId = uploadId)) }
    }

    /** Resumes an upload: only chunks the server does not report as received are sent. */
    private suspend fun sendMissingChunks(start: VideoUploadResponse, video: PreparedVideo, run: Run): String {
        var status = start
        repeat(UPLOAD_PASSES) {
            requireLayout(status, video)
            val missing = (0 until status.chunkCount).filterNot(status.receivedChunks.toSet()::contains)
            if (missing.isEmpty()) return status.uploadId
            var sentBytes = video.sizeBytes - missing.sumOf { chunkLength(status, video, it).toLong() }
            run.report(VideoImportStage.UPLOADING_VIDEO, sentBytes.toFloat() / video.sizeBytes)
            for (index in missing) {
                status = sendChunk(status, video, index)
                sentBytes += chunkLength(status, video, index)
                run.report(VideoImportStage.UPLOADING_VIDEO, sentBytes.toFloat() / video.sizeBytes)
            }
        }
        requireLayout(status, video)
        if ((0 until status.chunkCount).any { it !in status.receivedChunks }) {
            throw VideoImportException(VideoImportException.UPLOAD_INCOMPLETE, "The server still misses chunks of ${status.uploadId}")
        }
        return status.uploadId
    }

    private suspend fun sendChunk(status: VideoUploadResponse, video: PreparedVideo, index: Int): VideoUploadResponse {
        val bytes = readChunk(video.file, index * status.chunkSizeBytes, chunkLength(status, video, index))
        var retries = 0
        while (true) {
            try {
                return videoCall { api.uploadVideoChunk(status.uploadId, index, bytes.toRequestBody(OCTET_STREAM)) }
            } catch (error: Exception) {
                if (error is CancellationException || !error.isTransient() || retries >= CHUNK_RETRIES) throw error
                retries++
                delay(CHUNK_RETRY_DELAY_MS * retries)
            }
        }
    }

    private suspend fun readChunk(file: File, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        RandomAccessFile(file, "r").use { input ->
            ByteArray(length).also { input.seek(offset); input.readFully(it) }
        }
    }

    private fun requireLayout(status: VideoUploadResponse, video: PreparedVideo) {
        val size = status.chunkSizeBytes
        if (size !in 1..MAX_CHUNK_BYTES || status.chunkCount.toLong() != (video.sizeBytes + size - 1) / size) {
            throw VideoImportException(VideoImportException.VIDEO_CHUNK_INVALID, "Unexpected chunk layout for ${status.uploadId}")
        }
    }

    private fun chunkLength(status: VideoUploadResponse, video: PreparedVideo, index: Int): Int =
        minOf(status.chunkSizeBytes, video.sizeBytes - index * status.chunkSizeBytes).toInt()

    /** Polls every 2 s for the first 30 s, then every 4 s, and gives up after 20 minutes (the server needs up to ~15). */
    private suspend fun awaitRecipeId(initial: RecipeImportJobResponse, run: Run): String {
        val startedAt = clockMillis()
        var job = initial
        var failures = 0
        while (true) {
            when (job.status) {
                "SUCCEEDED" -> return job.recipeId?.takeIf(String::isNotBlank)
                    ?: throw VideoImportException(VideoImportException.INTERNAL_ERROR, "Job ${job.jobId} succeeded without a recipe")
                "FAILED" -> throw VideoImportException(
                    job.errorCode?.takeIf(String::isNotBlank) ?: VideoImportException.INTERNAL_ERROR, job.errorMessage,
                )
            }
            serverStage(job.stage)?.let { run.report(it) }
            val elapsed = clockMillis() - startedAt
            if (elapsed >= POLL_TIMEOUT_MS) {
                throw VideoImportException(VideoImportException.TIMEOUT, "Job ${job.jobId} did not finish within 20 minutes")
            }
            delay(if (elapsed < FAST_POLL_WINDOW_MS) FAST_POLL_MS else SLOW_POLL_MS)
            job = try {
                videoCall { api.getImportJob(initial.jobId) }.also { failures = 0 }
            } catch (error: Exception) {
                if (error is CancellationException || !error.isTransient() || ++failures > MAX_POLL_FAILURES) throw error
                job
            }
        }
    }

    private fun serverStage(stage: String?): VideoImportStage? = when (stage) {
        null -> null
        "DONE" -> VideoImportStage.SAVING
        else -> SERVER_STAGES.firstOrNull { it.name == stage }
    }

    // A reverse proxy answers 5xx without a JSON body while the API restarts; that is worth waiting out.
    private fun Exception.isTransient(): Boolean = this is IOException || (this is HttpException && code() >= 500)

    private suspend fun <T> tracked(block: suspend (Run) -> T): T {
        val run = synchronized(progressLock) { Run(++runCounter).also { latestRun = it.id } }
        try {
            return block(run)
        } finally {
            run.finish()
        }
    }

    /** Only the most recently started import reports, so an overlapping older one cannot flicker the stage. */
    private inner class Run(val id: Long) {
        fun report(stage: VideoImportStage, fraction: Float? = null) = synchronized(progressLock) {
            if (latestRun == id) _progress.value = VideoImportProgress(stage, fraction?.coerceIn(0f, 1f))
        }

        fun finish() = synchronized(progressLock) {
            if (latestRun == id) {
                latestRun = 0
                _progress.value = null
            }
        }
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val STALE_UPLOAD_CODES = setOf(
            "VIDEO_UPLOAD_NOT_FOUND", "VIDEO_UPLOAD_CLOSED", "UPLOAD_INCOMPLETE", "UPLOAD_CHECKSUM_MISMATCH",
            VideoImportException.VIDEO_CHUNK_INVALID,
        )
        val SERVER_STAGES = listOf(
            VideoImportStage.QUEUED, VideoImportStage.FETCHING_METADATA, VideoImportStage.UPLOADING_TO_AI,
            VideoImportStage.WATCHING_VIDEO, VideoImportStage.WRITING_RECIPE, VideoImportStage.SAVING,
        )
        const val FAST_POLL_MS = 2_000L
        const val SLOW_POLL_MS = 4_000L
        const val FAST_POLL_WINDOW_MS = 30_000L
        const val POLL_TIMEOUT_MS = 20 * 60_000L
        const val MAX_POLL_FAILURES = 3
        const val CHUNK_RETRIES = 3
        const val CHUNK_RETRY_DELAY_MS = 1_000L
        const val UPLOAD_PASSES = 2
        const val MAX_CHUNK_BYTES = 8L * 1024 * 1024
    }
}
