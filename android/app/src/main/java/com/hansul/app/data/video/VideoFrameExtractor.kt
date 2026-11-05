package com.hansul.app.data.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One JPEG per step at its key frame, written as `step-{n}.jpg` and returned as file URIs in the same form
 * [com.hansul.app.data.local.LocalCookingStore.withCachedImages] uses. Existing files are reused; the library
 * deletes them when the recipe for the video changes.
 */
class VideoFrameExtractor : StepFrameExtractor {
    override suspend fun extract(
        video: File,
        frames: List<StepFrame>,
        directory: File,
        onProgress: (Float) -> Unit,
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) return@withContext emptyMap()
        directory.mkdirs()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            buildMap {
                frames.forEachIndexed { index, frame ->
                    ensureActive()
                    val target = File(directory, "step-${frame.stepNumber}.jpg")
                    if (target.length() > 0 || runCatching { write(retriever, frame.atSeconds, target) }.getOrDefault(false)) {
                        put(frame.stepNumber, target.toURI().toString())
                    }
                    onProgress((index + 1f) / frames.size)
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun write(retriever: MediaMetadataRetriever, seconds: Int, target: File): Boolean {
        val frame = retriever.getFrameAtTime(seconds * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
        val scaled = if (frame.width <= MAX_WIDTH) frame else Bitmap.createScaledBitmap(
            frame, MAX_WIDTH, (frame.height * MAX_WIDTH.toFloat() / frame.width).roundToInt().coerceAtLeast(1), true,
        )
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            val written = partial.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (written && partial.renameTo(target)) return true
            partial.delete()
            return false
        } finally {
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
        }
    }

    private companion object {
        const val MAX_WIDTH = 1280
        const val JPEG_QUALITY = 85
    }
}
