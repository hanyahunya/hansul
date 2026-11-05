package com.hansul.app.data.video

import android.content.Context
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.share.RecipeUrlParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LocalVideoMeta(
    val id: String,
    val sourceFile: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationSeconds: Int,
    val width: Int = 0,
    val height: Int = 0,
    val preparedFile: String? = null,
    val preparedMimeType: String? = null,
    val preparedSizeBytes: Long? = null,
    val sha256: String? = null,
    val uploadId: String? = null,
    val recipeId: String? = null,
    /** Checksum of the shared bytes, so sharing the same video again finds this copy. */
    val sourceSha256: String? = null,
)

/**
 * Phone videos under `local-cooking/videos/{uuid}/`: the intake copy (`source.{ext}`), an optional compressed
 * `prepared.mp4`, `meta.json` and `frames/step-{n}.jpg`. Shared content URIs are readable only briefly, so intake
 * copies right away and everything later works from the copy.
 */
class LocalVideoLibrary internal constructor(
    private val root: File,
    private val content: VideoContentReader,
    private val probe: VideoProbe,
    private val transcoder: VideoTranscoder,
    private val frameExtractor: StepFrameExtractor,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val sync: (File) -> Unit = ::syncToDisk,
) : VideoIntake, LocalVideoSource {
    constructor(context: Context) : this(
        File(context.filesDir, "local-cooking/videos"),
        AndroidVideoContentReader(context.contentResolver),
        MediaRetrieverVideoProbe(),
        Media3VideoTranscoder(context.applicationContext),
        VideoFrameExtractor(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val metaMutex = Mutex()
    private val prepareMutex = Mutex()

    override suspend fun intake(uriString: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        try {
            val info = readable { content.describe(uriString) }
            val declared = info.sizeBytes
            if (declared != null && declared > MAX_SOURCE_BYTES) throw tooLarge()
            requireFreeSpace(declared ?: 0)
            if (!directory.mkdirs()) throw IOException("Cannot create ${directory.name}")
            val extension = extensionFor(info.mimeType, info.displayName)
            val source = File(directory, "source.$extension")
            val sourceSha256 = copy(uriString, source)
            // The same video shared again keeps its copy, compressed file, upload and recipe instead of starting over.
            metaMutex.withLock { reusableCopy(sourceSha256) }?.let { existing ->
                directory.deleteRecursively()
                return@withContext RecipeUrlParser.localVideoUrl(existing)
            }
            val facts = readable { probe.probe(source) }
            if (!facts.hasVideo || facts.durationMillis <= 0) {
                throw VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "No video track or duration")
            }
            if (facts.durationMillis > MAX_VIDEO_SECONDS * 1_000L) {
                throw VideoImportException(VideoImportException.VIDEO_TOO_LONG, "Video is longer than $MAX_VIDEO_SECONDS seconds")
            }
            val meta = LocalVideoMeta(
                id = id, sourceFile = source.name,
                mimeType = mimeFor(facts.mimeType, info.mimeType, extension), sizeBytes = source.length(),
                durationSeconds = ((facts.durationMillis + 999) / 1_000).toInt(), width = facts.width, height = facts.height,
                sourceSha256 = sourceSha256,
            )
            metaMutex.withLock { writeMeta(meta) }
            RecipeUrlParser.localVideoUrl(id)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    override suspend fun find(videoId: String): StoredVideo? = withContext(Dispatchers.IO) {
        metaMutex.withLock { readMeta(videoId) }?.let { meta ->
            StoredVideo(meta.id, meta.durationSeconds, meta.uploadId, meta.recipeId, playableFile(meta))
        }
    }

    override suspend fun prepare(videoId: String, onProgress: (VideoImportProgress) -> Unit): PreparedVideo =
        prepareMutex.withLock {
            withContext(Dispatchers.IO) {
                val meta = metaMutex.withLock { readMeta(videoId) } ?: throw missing()
                preparedOf(meta)?.let { return@withContext it }
                val directory = directory(videoId)
                val source = File(directory, meta.sourceFile).takeIf(File::isFile) ?: throw missing()
                val compress = needsCompression(meta, source.length())
                val prepared = if (compress) compress(meta, source, File(directory, PREPARED_FILE), onProgress) else source
                onProgress(VideoImportProgress(VideoImportStage.PREPARING_VIDEO))
                val sha256 = sha256(prepared)
                val updated = metaMutex.withLock {
                    val current = readMeta(videoId) ?: throw missing()
                    // A new prepared file has a new checksum, so an earlier upload of a different file cannot be resumed.
                    current.copy(
                        preparedFile = prepared.name, preparedMimeType = if (compress) MP4 else current.mimeType,
                        preparedSizeBytes = prepared.length(), sha256 = sha256, uploadId = null,
                    ).also(::writeMeta)
                }
                // The compressed copy is what the server analyses and the app replays; the original can be large.
                // Both it and the metadata naming it are on disk by now, so a crash cannot leave nothing behind.
                if (prepared != source) source.delete()
                requireNotNull(preparedOf(updated))
            }
        }

    override suspend fun rememberUpload(videoId: String, uploadId: String?) {
        update(videoId) { it.copy(uploadId = uploadId) }
    }

    override suspend fun rememberRecipe(videoId: String, recipeId: String?) {
        update(videoId) { meta ->
            if (meta.recipeId != recipeId) File(directory(videoId), FRAMES_DIRECTORY).deleteRecursively()
            meta.copy(recipeId = recipeId)
        }
    }

    override suspend fun extractFrames(videoId: String, frames: List<StepFrame>, onProgress: (Float) -> Unit): Map<Int, String> {
        val meta = withContext(Dispatchers.IO) { metaMutex.withLock { readMeta(videoId) } } ?: return emptyMap()
        val video = withContext(Dispatchers.IO) { playableFile(meta) } ?: return emptyMap()
        val lastSecond = (meta.durationSeconds - 1).coerceAtLeast(0)
        val requests = frames.map { it.copy(atSeconds = it.atSeconds.coerceIn(0, lastSecond)) }
        return frameExtractor.extract(video, requests, File(directory(videoId), FRAMES_DIRECTORY), onProgress)
    }

    /**
     * Deletes copies nobody can come back to: older than a day and never analysed (no recipe id) or without
     * readable metadata. [inUse] protects copies the app still points at, such as the pending link's.
     */
    suspend fun deleteAbandoned(inUse: suspend (String) -> Boolean, nowMillis: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            var deleted = 0
            for (directory in videoDirectories()) {
                if (!isAbandoned(directory, nowMillis) || inUse(directory.name)) continue
                // Checked again under the lock: sharing the same video again may have just taken this copy back.
                if (metaMutex.withLock { isAbandoned(directory, nowMillis) && directory.deleteRecursively() }) deleted++
            }
            deleted
        }

    private fun isAbandoned(directory: File, nowMillis: Long): Boolean {
        val touched = maxOf(directory.lastModified(), File(directory, META_FILE).lastModified())
        return nowMillis - touched >= ABANDONED_AFTER_MILLIS && readMeta(directory.name)?.recipeId == null
    }

    private suspend fun compress(meta: LocalVideoMeta, source: File, target: File, onProgress: (VideoImportProgress) -> Unit): File {
        val partial = File(target.parentFile, "$PREPARED_FILE.part")
        partial.delete()
        onProgress(VideoImportProgress(VideoImportStage.COMPRESSING_VIDEO, 0f))
        try {
            transcoder.transcode(source, partial, transcodeSpec(meta)) {
                onProgress(VideoImportProgress(VideoImportStage.COMPRESSING_VIDEO, it))
            }
            // On disk before the metadata that names it is written and the original is deleted.
            sync(partial)
        } catch (error: Throwable) {
            partial.delete()
            if (error is CancellationException || error is VideoImportException) throw error
            if (error.isNoSpaceLeft()) throw storageFull()
            throw VideoImportException(VideoImportException.VIDEO_COMPRESSION_FAILED, "Transcoding failed", error)
        }
        if (!partial.isFile || partial.length() == 0L) {
            partial.delete()
            throw VideoImportException(VideoImportException.VIDEO_COMPRESSION_FAILED, "Transcoder produced no file")
        }
        if (partial.length() > MAX_UPLOAD_BYTES) {
            partial.delete()
            throw tooLarge()
        }
        move(partial, target)
        return target
    }

    private suspend fun update(videoId: String, change: (LocalVideoMeta) -> LocalVideoMeta) = withContext(Dispatchers.IO) {
        metaMutex.withLock { writeMeta(change(readMeta(videoId) ?: throw missing())) }
    }

    private fun preparedOf(meta: LocalVideoMeta): PreparedVideo? {
        val name = meta.preparedFile ?: return null
        val file = File(directory(meta.id), name)
        if (meta.sha256 == null || !file.isFile || file.length() != meta.preparedSizeBytes) return null
        return PreparedVideo(file, meta.preparedMimeType ?: meta.mimeType, file.length(), meta.sha256, meta.durationSeconds)
    }

    private fun playableFile(meta: LocalVideoMeta): File? = listOfNotNull(meta.preparedFile, meta.sourceFile)
        .map { File(directory(meta.id), it) }.firstOrNull { it.isFile && it.length() > 0 }

    /** An intact copy of the same shared bytes, touched so the cleanup keeps it. Call with [metaMutex] held. */
    private fun reusableCopy(sourceSha256: String): String? = videoDirectories()
        .mapNotNull { readMeta(it.name) }
        .filter { meta ->
            meta.sourceSha256 == sourceSha256 && (preparedOf(meta) != null ||
                File(directory(meta.id), meta.sourceFile).let { it.isFile && it.length() == meta.sizeBytes })
        }
        .maxByOrNull { if (it.recipeId != null) 1 else 0 }
        ?.also(::writeMeta)?.id

    private fun videoDirectories(): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && RecipeUrlParser.localVideoId(RecipeUrlParser.localVideoUrl(it.name)) == it.name }

    /** Copies the shared bytes and returns their SHA-256. Providers may not report a size, so space is checked as it goes. */
    private suspend fun copy(uriString: String, target: File): String {
        val partial = File(target.parentFile, "${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            readable { content.open(uriString) }.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    var checkedAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SOURCE_BYTES) throw tooLarge()
                        if (total - checkedAt >= SPACE_CHECK_BYTES) {
                            requireFreeSpace(0)
                            checkedAt = total
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            sync(partial)
        } catch (error: Throwable) {
            partial.delete()
            if (error is CancellationException || error is VideoImportException) throw error
            if (error.isNoSpaceLeft()) throw storageFull()
            throw VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "Could not copy the shared video", error)
        }
        move(partial, target)
        return digest.digest().toHex()
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    /** Room for [bytes] more plus the compressed copy, so the video never fills the phone for other apps. */
    private fun requireFreeSpace(bytes: Long) {
        root.mkdirs()
        val free = freeSpace(root)
        // usableSpace is 0 when the platform cannot tell; only refuse when it clearly cannot hold the copy.
        if (free in 1 until bytes + FREE_SPACE_MARGIN_BYTES) throw storageFull()
    }

    private fun readMeta(videoId: String): LocalVideoMeta? {
        val file = File(directory(videoId), META_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<LocalVideoMeta>(file.readText(Charsets.UTF_8)) }.getOrNull()
            ?.takeIf { it.id == videoId }
    }

    private fun writeMeta(meta: LocalVideoMeta) {
        val directory = directory(meta.id)
        val partial = File(directory, "$META_FILE.part")
        partial.writeText(json.encodeToString(meta), Charsets.UTF_8)
        sync(partial)
        move(partial, File(directory, META_FILE))
    }

    private fun directory(videoId: String): File {
        require(RecipeUrlParser.localVideoId(RecipeUrlParser.localVideoUrl(videoId)) == videoId) { "Invalid local video id" }
        return File(root, videoId)
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (error: VideoImportException) {
        throw error
    } catch (error: Exception) {
        throw VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "Could not read the video", error)
    }

    private fun missing() = VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "The stored video is missing")

    private fun tooLarge() = VideoImportException(VideoImportException.VIDEO_UPLOAD_TOO_LARGE, "The video file is too large")

    private fun storageFull() =
        VideoImportException(VideoImportException.LOCAL_STORAGE_FULL, "Not enough free storage for the video copy")

    internal companion object {
        const val MAX_VIDEO_SECONDS = 30 * 60
        const val MAX_UPLOAD_BYTES = 300L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 8L * 1024 * 1024 * 1024
        const val COMPRESS_ABOVE_BYTES = 100L * 1024 * 1024
        const val MAX_SHORT_SIDE_AS_IS = 1080
        const val TARGET_SHORT_SIDE = 720
        const val TARGET_VIDEO_BITRATE = 2_500_000L
        const val MIN_VIDEO_BITRATE = 400_000L
        const val ASSUMED_AUDIO_BITRATE = 128_000L
        private const val FREE_SPACE_MARGIN_BYTES = MAX_UPLOAD_BYTES + 64L * 1024 * 1024
        private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024
        private const val ABANDONED_AFTER_MILLIS = 24 * 60 * 60 * 1_000L
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val META_FILE = "meta.json"
        private const val PREPARED_FILE = "prepared.mp4"
        private const val FRAMES_DIRECTORY = "frames"
        private const val MP4 = "video/mp4"
        val UPLOADABLE_TYPES = setOf("video/mp4", "video/quicktime", "video/webm", "video/3gpp")
        private val EXTENSIONS = mapOf(
            "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/3gpp" to "3gp",
            "video/x-matroska" to "mkv", "video/mpeg" to "mpg", "video/x-msvideo" to "avi",
        )

        fun needsCompression(meta: LocalVideoMeta, sizeBytes: Long): Boolean =
            sizeBytes > COMPRESS_ABOVE_BYTES || minOf(meta.width, meta.height) > MAX_SHORT_SIDE_AS_IS ||
                meta.mimeType !in UPLOADABLE_TYPES

        /** ~2.5 Mbps, lowered for long videos so the H.264 + AAC result stays under the 300 MiB upload limit. */
        fun transcodeSpec(meta: LocalVideoMeta): TranscodeSpec {
            val budget = MAX_UPLOAD_BYTES * 8 * 85 / 100 / meta.durationSeconds.coerceAtLeast(1) - ASSUMED_AUDIO_BITRATE
            val shortSide = minOf(meta.width, meta.height)
            return TranscodeSpec(
                shortSide = TARGET_SHORT_SIDE.takeIf { shortSide > it },
                videoBitrate = budget.coerceIn(MIN_VIDEO_BITRATE, TARGET_VIDEO_BITRATE).toInt(),
            )
        }

        fun extensionFor(mimeType: String?, displayName: String?): String =
            mimeType?.lowercase()?.let(EXTENSIONS::get)
                ?: displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                ?: "video"

        fun mimeFor(probed: String?, declared: String?, extension: String): String =
            listOfNotNull(probed, declared).map(String::lowercase).firstOrNull { it in EXTENSIONS }
                ?: EXTENSIONS.entries.firstOrNull { it.value == extension }?.key
                ?: probed?.lowercase()?.takeIf { it.startsWith("video/") }
                ?: declared?.lowercase()?.takeIf { it.startsWith("video/") && it != "video/*" }
                ?: "application/octet-stream"
    }
}

/** What AtomicFile does before its rename: the bytes are on the disk before the final name points at them. */
private fun syncToDisk(file: File) = FileOutputStream(file, true).use { it.fd.sync() }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

// ENOSPC reaches Java as an IOException that names the errno only in its message, or in its cause's.
private fun Throwable.isNoSpaceLeft(): Boolean = generateSequence(this) { it.cause }.take(4).any { error ->
    error.message.orEmpty().let { "ENOSPC" in it || it.contains("No space left", ignoreCase = true) }
}
