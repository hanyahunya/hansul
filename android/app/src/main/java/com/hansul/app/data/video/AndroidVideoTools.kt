package com.hansul.app.data.video

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Asked once; the answer is kept with the app's other settings. */
class VideoUploadConsentStore(context: Context) : VideoUploadConsent {
    private val preferences = context.getSharedPreferences("video-upload-consent", Context.MODE_PRIVATE)

    override suspend fun isGiven(): Boolean = withContext(Dispatchers.IO) { preferences.getBoolean(GIVEN, false) }

    override suspend fun give() {
        withContext(Dispatchers.IO) { preferences.edit().putBoolean(GIVEN, true).commit() }
    }

    private companion object {
        const val GIVEN = "given"
    }
}

/** Only content URIs are accepted, so another app cannot point the copy at this app's private files. */
internal class AndroidVideoContentReader(private val resolver: ContentResolver) : VideoContentReader {
    override fun describe(uriString: String): SharedVideoInfo {
        val uri = contentUri(uriString)
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it > 0 }
                }
            }
        }
        return SharedVideoInfo(runCatching { resolver.getType(uri) }.getOrNull(), name, size)
    }

    override fun open(uriString: String): InputStream =
        requireNotNull(resolver.openInputStream(contentUri(uriString))) { "The shared video cannot be opened" }

    private fun contentUri(uriString: String): Uri = Uri.parse(uriString).also {
        require(it.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URIs can be imported" }
    }
}

internal class MediaRetrieverVideoProbe : VideoProbe {
    override fun probe(file: File): VideoFacts {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun value(key: Int): String? = retriever.extractMetadata(key)
            return VideoFacts(
                durationMillis = value(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                width = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                mimeType = value(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasVideo = value(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * H.264 + AAC with Media3 Transformer. Transformer must live on a Looper thread, so it runs on the main thread,
 * is polled for progress, and is cancelled when the calling coroutine is.
 */
@OptIn(UnstableApi::class)
internal class Media3VideoTranscoder(private val context: Context) : VideoTranscoder {
    override suspend fun transcode(input: File, output: File, spec: TranscodeSpec, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {
            val finished = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                        .setEnableFallback(true)
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished.completeExceptionally(exportException)
                    }
                })
                .build()
            val effects = listOfNotNull<Effect>(spec.shortSide?.let(Presentation::createForShortSide))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(emptyList(), effects))
                .build()
            // H.264 output is SDR; tone-map HDR phone recordings instead of failing on them. A video without
            // sound gets a silent AAC track, which the server and players handle like any other file.
            val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item)))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            transformer.start(composition, output.absolutePath)
            val holder = ProgressHolder()
            try {
                while (withTimeoutOrNull(PROGRESS_POLL_MS) { finished.await() } == null) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                }
            } finally {
                if (!finished.isCompleted) transformer.cancel()
            }
        }

    private companion object {
        const val PROGRESS_POLL_MS = 500L
    }
}
