package com.hansul.app.data.video

import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.share.RecipeUrlParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalVideoLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val content = FakeContent("abc".toByteArray(), SharedVideoInfo("video/mp4", "김치찌개.mp4", 3))
    private var facts = VideoFacts(durationMillis = 612_300, width = 1920, height = 1080, mimeType = "video/mp4")
    private val transcoder = FakeTranscoder()
    private val frames = FakeFrames()

    private fun library(root: File = File(temp.root, "videos")) =
        LocalVideoLibrary(root, content, { facts }, transcoder, frames)

    @Test fun intakeCopiesTheSharedVideoAndPersistsItsMetadata() = runBlocking {
        val root = File(temp.root, "videos")
        val url = library(root).intake("content://media/video/1")
        val id = requireNotNull(RecipeUrlParser.localVideoId(url))

        assertEquals("abc", File(root, "$id/source.mp4").readText())
        assertTrue(File(root, "$id/meta.json").isFile)
        // A new library instance (process restart) reads the same copy back.
        val stored = requireNotNull(library(root).find(id))
        assertEquals(613, stored.durationSeconds)
        assertEquals(File(root, "$id/source.mp4"), stored.playableFile)
        assertNull(stored.uploadId)
        assertNull(stored.recipeId)
    }

    @Test fun videosLongerThanThirtyMinutesAreRejectedAndNotKept() = runBlocking {
        facts = facts.copy(durationMillis = 30 * 60_000L + 1)
        val root = File(temp.root, "videos")

        val error = runCatching { library(root).intake("content://media/video/1") }.exceptionOrNull()

        assertEquals(VideoImportException.VIDEO_TOO_LONG, (error as VideoImportException).code)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        facts = facts.copy(durationMillis = 30 * 60_000L)
        assertNotNull(RecipeUrlParser.localVideoId(library(root).intake("content://media/video/1")))
    }

    @Test fun unreadableOrSilentlyEmptyContentIsReportedAndCleanedUp() = runBlocking {
        val root = File(temp.root, "videos")
        content.failOpen = SecurityException("permission revoked")
        assertEquals(VideoImportException.LOCAL_VIDEO_UNREADABLE, intakeError(root))

        content.failOpen = null
        facts = facts.copy(hasVideo = false)
        assertEquals(VideoImportException.LOCAL_VIDEO_UNREADABLE, intakeError(root))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun smallSupportedVideoIsUploadedAsIsWithItsChecksum() = runBlocking {
        val library = library()
        val id = requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/1")))
        val progress = mutableListOf<VideoImportProgress>()

        val prepared = library.prepare(id) { progress += it }

        assertEquals("source.mp4", prepared.file.name)
        assertEquals("video/mp4", prepared.contentType)
        assertEquals(3L, prepared.sizeBytes)
        assertEquals(abcSha256, prepared.sha256)
        assertEquals(613, prepared.durationSeconds)
        assertEquals(0, transcoder.calls)
        assertEquals(listOf(VideoImportStage.PREPARING_VIDEO), progress.map { it.stage })
        assertEquals(prepared, library.prepare(id) {})
    }

    @Test fun tallVideoIsCompressedTo720pAndTheOriginalIsDropped() = runBlocking {
        facts = facts.copy(width = 3840, height = 2160)
        val library = library()
        val id = requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/1")))
        val source = requireNotNull(library.find(id)?.playableFile)
        val progress = mutableListOf<VideoImportProgress>()

        val prepared = library.prepare(id) { progress += it }

        assertEquals(TranscodeSpec(shortSide = 720, videoBitrate = 2_500_000), transcoder.lastSpec)
        assertEquals("prepared.mp4", prepared.file.name)
        assertEquals("video/mp4", prepared.contentType)
        assertEquals(abcSha256, prepared.sha256)
        assertFalse(source.exists())
        assertEquals(prepared.file, library.find(id)?.playableFile)
        assertEquals(
            listOf(VideoImportStage.COMPRESSING_VIDEO to 0f, VideoImportStage.COMPRESSING_VIDEO to 0.5f, VideoImportStage.PREPARING_VIDEO to null),
            progress.map { it.stage to it.fraction },
        )
        assertEquals(1, transcoder.calls)
        library.prepare(id) {}
        assertEquals("Prepared output is reused", 1, transcoder.calls)
    }

    @Test fun compressionRulesFollowSizeShapeAndContainer() {
        fun meta(mime: String = "video/mp4", width: Int = 1920, height: Int = 1080, seconds: Int = 600) =
            LocalVideoMeta("id", "source.mp4", mime, 1, seconds, width, height)

        assertFalse(LocalVideoLibrary.needsCompression(meta(), 100L * 1024 * 1024))
        assertTrue(LocalVideoLibrary.needsCompression(meta(), 100L * 1024 * 1024 + 1))
        assertTrue(LocalVideoLibrary.needsCompression(meta(width = 1440, height = 2560), 1))
        assertFalse(LocalVideoLibrary.needsCompression(meta(width = 1080, height = 1920), 1))
        assertTrue(LocalVideoLibrary.needsCompression(meta(mime = "video/x-matroska"), 1))
        assertFalse(LocalVideoLibrary.needsCompression(meta(mime = "video/quicktime"), 1))
        // Thirty minutes at 2.5 Mbps would exceed 300 MiB, so long videos get a lower bitrate.
        assertEquals(TranscodeSpec(720, 1_060_386), LocalVideoLibrary.transcodeSpec(meta(seconds = 1800)))
        assertEquals(TranscodeSpec(null, 2_500_000), LocalVideoLibrary.transcodeSpec(meta(width = 1280, height = 720, seconds = 60)))
        assertEquals("mov", LocalVideoLibrary.extensionFor("video/quicktime", "a.mp4"))
        assertEquals("mkv", LocalVideoLibrary.extensionFor(null, "clip.MKV"))
        assertEquals("video", LocalVideoLibrary.extensionFor(null, "../../evil"))
        assertEquals("video/webm", LocalVideoLibrary.mimeFor(null, "video/*", "webm"))
    }

    @Test fun failedOrOversizedCompressionKeepsTheOriginal() = runBlocking {
        facts = facts.copy(mimeType = "video/x-matroska")
        val library = library()
        val id = requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/1")))

        transcoder.failure = IllegalStateException("codec")
        assertEquals(VideoImportException.VIDEO_COMPRESSION_FAILED, prepareError(library, id))
        transcoder.failure = null
        transcoder.outputBytes = LocalVideoLibrary.MAX_UPLOAD_BYTES + 1
        assertEquals(VideoImportException.VIDEO_UPLOAD_TOO_LARGE, prepareError(library, id))

        val directory = File(temp.root, "videos/$id")
        assertEquals(setOf("meta.json", "source.mp4"), directory.list().orEmpty().toSet())
    }

    @Test fun newPreparedFileForgetsTheOldUploadAndANewRecipeDropsOldFrames() = runBlocking {
        val library = library()
        val id = requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/1")))
        library.rememberUpload(id, "u-before-prepare")
        library.prepare(id) {}
        assertNull(library.find(id)?.uploadId)
        library.rememberUpload(id, "u1")
        library.prepare(id) {}
        assertEquals("u1", library.find(id)?.uploadId)

        library.rememberRecipe(id, "r1")
        library.extractFrames(id, listOf(StepFrame(1, 30))) {}
        val frame = File(temp.root, "videos/$id/frames/step-1.jpg")
        assertTrue(frame.isFile)
        library.rememberRecipe(id, "r1")
        assertTrue(frame.isFile)
        library.rememberRecipe(id, "r2")
        assertFalse(frame.exists())
        assertEquals("r2", library.find(id)?.recipeId)
    }

    @Test fun theSameVideoSharedAgainKeepsItsCompressedCopyAndUpload() = runBlocking {
        facts = facts.copy(width = 3840, height = 2160)
        val root = File(temp.root, "videos")
        val url = library(root).intake("content://media/video/1")
        val id = requireNotNull(RecipeUrlParser.localVideoId(url))
        library(root).prepare(id) {}
        library(root).rememberUpload(id, "u1")

        // Compressing again would give other bytes, a new checksum and so a new upload of the whole video.
        assertEquals(url, library(root).intake("content://media/video/9"))
        assertEquals(listOf(id), root.list().orEmpty().toList())
        assertEquals("u1", library(root).find(id)?.uploadId)
        library(root).prepare(id) {}
        assertEquals(1, transcoder.calls)

        content.bytes = "another video".toByteArray()
        assertNotEquals(url, library(root).intake("content://media/video/2"))
    }

    @Test fun theCompressedCopyAndItsMetadataReachTheDiskBeforeTheOriginalIsDeleted() = runBlocking {
        facts = facts.copy(width = 3840, height = 2160)
        val synced = mutableListOf<String>()
        var original: File? = null
        val library = LocalVideoLibrary(File(temp.root, "videos"), content, { facts }, transcoder, frames, sync = { file ->
            synced += file.name + if (original?.exists() == false) " after the original was deleted" else ""
        })
        val id = requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/1")))
        assertEquals(listOf("source.mp4.part", "meta.json.part"), synced)
        original = library.find(id)?.playableFile
        synced.clear()

        library.prepare(id) {}

        assertEquals(listOf("prepared.mp4.part", "meta.json.part"), synced)
        assertFalse(requireNotNull(original).exists())
    }

    @Test fun aFullDiskIsReportedAsFullStorageInsteadOfAnUnreadableVideo() = runBlocking {
        val root = File(temp.root, "videos")
        content.failRead = IOException("write failed: ENOSPC (No space left on device)")

        assertEquals(VideoImportException.LOCAL_STORAGE_FULL, intakeError(root))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun aVideoOfUnknownSizeStopsCopyingBeforeItFillsTheDevice() = runBlocking {
        val root = File(temp.root, "videos")
        val unknownSize = FakeContent(ByteArray(9 * 1024 * 1024), SharedVideoInfo("video/mp4", "clip.mp4", null))
        // Read once before the copy and again after its first few MiB, while another app fills the device.
        val readings = ArrayDeque(listOf(1024L * 1024 * 1024, 100L * 1024 * 1024))
        val library = LocalVideoLibrary(root, unknownSize, { facts }, transcoder, frames,
            freeSpace = { readings.removeFirstOrNull() ?: 0L })

        val error = runCatching { library.intake("content://media/video/1") }.exceptionOrNull()

        assertEquals(VideoImportException.LOCAL_STORAGE_FULL, (error as? VideoImportException)?.code)
        assertTrue(readings.isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun copiesNobodyCanReachAnyMoreAreDeletedAfterADay() = runBlocking {
        val root = File(temp.root, "videos")
        val library = library(root)
        suspend fun stored(bytes: String): String {
            content.bytes = bytes.toByteArray()
            return requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/$bytes")))
        }
        val abandoned = stored("abandoned")
        val analysed = stored("analysed").also { library.rememberRecipe(it, "r1") }
        val pending = stored("pending")
        val recent = stored("recent")
        val broken = stored("broken").also { File(root, "$it/meta.json").writeText("{") }
        val bare = stored("bare").also { File(root, "$it/meta.json").delete() }
        File(root, "thumbnails").mkdirs()
        val now = System.currentTimeMillis()
        fun age(name: String, hours: Long) = File(root, name).let { dir ->
            (dir.listFiles().orEmpty().toList() + dir).forEach { it.setLastModified(now - hours * 3_600_000) }
        }
        listOf(abandoned, analysed, pending, broken, bare, "thumbnails").forEach { age(it, 25) }
        age(recent, 23)

        val deleted = library.deleteAbandoned(inUse = { it == pending }, nowMillis = now)

        assertEquals(3, deleted)
        assertEquals(setOf(analysed, pending, recent, "thumbnails"), root.list().orEmpty().toSet())
        assertEquals("r1", library.find(analysed)?.recipeId)
    }

    @Test fun frameTimesAreClampedIntoTheVideo() = runBlocking {
        val library = library()
        val id = requireNotNull(RecipeUrlParser.localVideoId(library.intake("content://media/video/1")))

        val uris = library.extractFrames(id, listOf(StepFrame(1, -3), StepFrame(2, 95), StepFrame(3, 5_000))) {}

        assertEquals(listOf(StepFrame(1, 0), StepFrame(2, 95), StepFrame(3, 612)), frames.lastRequests)
        assertEquals(setOf(1, 2, 3), uris.keys)
        assertTrue(uris.getValue(2).startsWith("file:"))
        assertTrue(library.extractFrames("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", listOf(StepFrame(1, 1))) {}.isEmpty())
    }

    private suspend fun intakeError(root: File): String? =
        (runCatching { library(root).intake("content://media/video/1") }.exceptionOrNull() as? VideoImportException)?.code

    private suspend fun prepareError(library: LocalVideoLibrary, id: String): String? =
        (runCatching { library.prepare(id) {} }.exceptionOrNull() as? VideoImportException)?.code

    private class FakeContent(var bytes: ByteArray, private val info: SharedVideoInfo) : VideoContentReader {
        var failOpen: Exception? = null
        var failRead: IOException? = null

        override fun describe(uriString: String) = info

        override fun open(uriString: String): InputStream {
            failOpen?.let { throw it }
            val failure = failRead ?: return ByteArrayInputStream(bytes)
            return object : InputStream() {
                override fun read(): Int = throw failure
            }
        }
    }

    private class FakeTranscoder : VideoTranscoder {
        var calls = 0
        var lastSpec: TranscodeSpec? = null
        var failure: Exception? = null
        var outputBytes: Long? = null

        override suspend fun transcode(input: File, output: File, spec: TranscodeSpec, onProgress: (Float) -> Unit) {
            calls++
            lastSpec = spec
            failure?.let { throw it }
            val size = outputBytes
            if (size == null) output.writeText("abc") else RandomAccessFile(output, "rw").use { it.setLength(size) }
            onProgress(0.5f)
        }
    }

    private class FakeFrames : StepFrameExtractor {
        var lastRequests: List<StepFrame>? = null

        override suspend fun extract(video: File, frames: List<StepFrame>, directory: File, onProgress: (Float) -> Unit): Map<Int, String> {
            lastRequests = frames
            directory.mkdirs()
            return frames.associate { frame ->
                val file = File(directory, "step-${frame.stepNumber}.jpg").apply { writeText("jpeg") }
                frame.stepNumber to file.toURI().toString()
            }
        }
    }
}
