package com.hansul.app.data.video

import com.hansul.app.data.network.CreateRecipeImportJobRequest
import com.hansul.app.data.network.CreateVideoUploadRequest
import com.hansul.app.data.network.RecipeImportJobResponse
import com.hansul.app.data.network.RecipeImportResponse
import com.hansul.app.data.network.RecipeStepResponse
import com.hansul.app.data.network.RecipeVideoResponse
import com.hansul.app.data.network.UnusedRecipeImportApi
import com.hansul.app.data.network.VideoUploadResponse
import com.hansul.app.model.RecipeOrigin
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.model.VideoProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class VideoRecipeImporterTest {
    @get:Rule val temp = TemporaryFolder()

    private val watchUrl = "https://www.youtube.com/watch?v=AbCdEfGhIjK"
    private val videoId = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val pseudoUrl = "hansul-video:$videoId"

    @Test fun youTubePollsEveryTwoSecondsForHalfAMinuteThenEveryFourAndReturnsTheRecipe() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.onPoll = { index ->
            when {
                index < 3 -> job("RUNNING", "FETCHING_METADATA")
                index < 19 -> job("RUNNING", "WATCHING_VIDEO")
                else -> job("SUCCEEDED", "DONE", recipeId = "r1")
            }
        }
        api.recipes["r1"] = recipe("r1", watchUrl)
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })
        val seen = record(importer)

        val recipe = importer.importYouTube("https://youtu.be/AbCdEfGhIjK?si=share")

        assertEquals(listOf(CreateRecipeImportJobRequest(url = watchUrl)), api.jobRequests)
        assertEquals((1..15).map { it * 2_000L } + (1..5).map { 30_000L + it * 4_000L }, api.pollTimes)
        assertEquals("r1", recipe.id)
        assertEquals(RecipeOrigin.VIDEO_AI, recipe.origin)
        assertEquals(listOf(95), recipe.steps.map { it.keyFrameSeconds })
        assertEquals(
            listOf(VideoImportStage.QUEUED, VideoImportStage.FETCHING_METADATA, VideoImportStage.WATCHING_VIDEO, VideoImportStage.SAVING),
            seen.mapNotNull { it?.stage }.distinct(),
        )
        assertNull(importer.progress.value)
    }

    @Test fun cachedJobReturnsWithoutPolling() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.onCreateJob = { job("SUCCEEDED", "DONE", recipeId = "r1") }
        api.recipes["r1"] = recipe("r1", watchUrl)
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        assertEquals("r1", importer.importYouTube(watchUrl).id)
        assertTrue(api.pollTimes.isEmpty())
    }

    @Test fun failedJobCarriesTheServerCodeAndClearsProgress() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.onPoll = { job("FAILED", "WATCHING_VIDEO", errorCode = "NOT_A_RECIPE_VIDEO", errorMessage = "No cooking") }
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        val error = expect<VideoImportException> { importer.importYouTube(watchUrl) }

        assertEquals("NOT_A_RECIPE_VIDEO", error.code)
        assertNull(importer.progress.value)
    }

    @Test fun unauthorizedVideoEndpointMeansLoginRequiredAndErrorBodiesKeepTheirCode() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        api.onCreateJob = { throw http(401, "") }
        expect<VideoLoginRequiredException> { importer.importYouTube(watchUrl) }

        api.onCreateJob = { throw http(429, """{"code":"VIDEO_IMPORT_DAILY_LIMIT","message":"Limit","timestamp":"2026-11-05T00:00:00Z"}""") }
        assertEquals("VIDEO_IMPORT_DAILY_LIMIT", expect<VideoImportException> { importer.importYouTube(watchUrl) }.code)

        api.onCreateJob = { throw http(422, """{"code":"VIDEO_LINK_NOT_SUPPORTED","message":"No"}""") }
        assertEquals("VIDEO_LINK_NOT_SUPPORTED", expect<VideoImportException> { importer.importYouTube(watchUrl) }.code)

        api.onCreateJob = { throw http(502, "<html>Bad Gateway</html>") }
        assertEquals(502, expect<HttpException> { importer.importYouTube(watchUrl) }.code())
        assertNull(importer.progress.value)
    }

    @Test fun pollingGivesUpAfterTwentyMinutes() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.onPoll = { job("RUNNING", "WATCHING_VIDEO") }
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        val error = expect<VideoImportException> { importer.importYouTube(watchUrl) }

        assertEquals(VideoImportException.TIMEOUT, error.code)
        // The slowest server analysis takes about 15 minutes; polling outlasts it at the 4 s pace.
        assertTrue(testScheduler.currentTime in 1_200_000L..1_204_000L)
        assertEquals(4_000L, api.pollTimes.last() - api.pollTimes[api.pollTimes.lastIndex - 1])
        assertNull(importer.progress.value)
    }

    @Test fun pollingToleratesThreeConsecutiveNetworkErrorsButNotFour() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.recipes["r1"] = recipe("r1", watchUrl)
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        api.onPoll = { index -> if (index < 3) throw IOException("offline") else job("SUCCEEDED", "DONE", recipeId = "r1") }
        assertEquals("r1", importer.importYouTube(watchUrl).id)

        api.pollTimes.clear()
        api.onPoll = { index ->
            when (index) {
                in 0..1, in 3..6 -> throw IOException("offline")
                2 -> job("RUNNING", "WATCHING_VIDEO")
                else -> job("SUCCEEDED", "DONE", recipeId = "r1")
            }
        }
        expect<IOException> { importer.importYouTube(watchUrl) }
        assertEquals(7, api.pollTimes.size)
    }

    @Test fun pollingWaitsOutAProxyErrorButStopsAtOnceWhenTheSessionExpires() = runTest {
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.recipes["r1"] = recipe("r1", watchUrl)
        val importer = VideoRecipeImporter(api, FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        api.onPoll = { index -> if (index == 0) throw http(502, "<html>Bad Gateway</html>") else job("SUCCEEDED", "DONE", recipeId = "r1") }
        assertEquals("r1", importer.importYouTube(watchUrl).id)

        api.pollTimes.clear()
        api.onPoll = { throw http(401, "") }
        expect<VideoLoginRequiredException> { importer.importYouTube(watchUrl) }
        assertEquals("A signed-out user is not polled again", 1, api.pollTimes.size)
        assertNull(importer.progress.value)
    }

    @Test fun localVideoUploadsOnlyMissingChunksThenAddsSceneFramesToTheRecipe() = runTest {
        val bytes = ByteArray(10) { (it + 1).toByte() }
        val file = temp.newFile("prepared.mp4").apply { writeBytes(bytes) }
        val library = FakeLibrary(file)
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.upload = VideoUploadResponse("u1", "RECEIVING", chunkSizeBytes = 4, chunkCount = 3, receivedChunks = listOf(1))
        api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r2") }
        api.recipes["r2"] = recipe("r2", "hansul-upload:u1", video = RecipeVideoResponse("UPLOAD", durationSeconds = 600))
        val importer = VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime })
        val seen = record(importer)

        val recipe = importer.importLocalVideo(pseudoUrl)

        assertEquals(listOf(CreateVideoUploadRequest("video/mp4", 10, "a".repeat(64), 612)), api.uploadRequests)
        assertEquals(listOf(0, 2), api.chunkIndexes)
        assertArrayEquals(bytes.copyOfRange(0, 4), api.chunks.getValue(0))
        assertArrayEquals(bytes.copyOfRange(8, 10), api.chunks.getValue(2))
        assertEquals(listOf(CreateRecipeImportJobRequest(uploadId = "u1")), api.jobRequests)
        assertEquals(listOf("upload=u1", "recipe=r2"), library.log)
        assertEquals(listOf(StepFrame(1, 95), StepFrame(2, 15)), library.frameRequests)
        assertEquals(pseudoUrl, recipe.sourceUrl)
        assertEquals(RecipeVideo(VideoProvider.LOCAL_FILE, localFilePath = file.absolutePath, durationSeconds = 612), recipe.video)
        assertEquals(listOf("file:/frames/step-1.jpg", "file:/frames/step-2.jpg"), recipe.steps.map { it.imageUrl })
        assertEquals(
            listOf(VideoImportStage.PREPARING_VIDEO, VideoImportStage.COMPRESSING_VIDEO, VideoImportStage.UPLOADING_VIDEO,
                VideoImportStage.QUEUED, VideoImportStage.SAVING, VideoImportStage.EXTRACTING_FRAMES),
            seen.mapNotNull { it?.stage }.distinct(),
        )
        assertEquals(listOf(0.4f, 0.8f, 1f), seen.filter { it?.stage == VideoImportStage.UPLOADING_VIDEO }.map { it?.fraction })
        assertNull(importer.progress.value)
    }

    @Test fun chunkUploadRetriesNetworkErrorsThreeTimesButNotRejections() = runTest {
        val file = temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) }
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.upload = VideoUploadResponse("u1", "RECEIVING", chunkSizeBytes = 4, chunkCount = 2)
        api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r2") }
        api.recipes["r2"] = recipe("r2", "hansul-upload:u1")
        fun importer() = VideoRecipeImporter(api, FakeLibrary(file), clockMillis = { testScheduler.currentTime })

        api.chunkFailures[1] = ArrayDeque<Exception>(List(3) { IOException("reset") })
        importer().importLocalVideo(pseudoUrl)
        assertEquals(listOf(0, 1, 1, 1, 1), api.chunkIndexes)

        api.received.clear()
        api.chunkIndexes.clear()
        api.chunkFailures[0] = ArrayDeque<Exception>(List(4) { IOException("reset") })
        expect<IOException> { importer().importLocalVideo(pseudoUrl) }
        assertEquals(listOf(0, 0, 0, 0), api.chunkIndexes)

        api.chunkIndexes.clear()
        api.chunkFailures[0] = ArrayDeque<Exception>(listOf(http(409, """{"code":"VIDEO_UPLOAD_CLOSED","message":"closed"}""")))
        assertEquals("VIDEO_UPLOAD_CLOSED", expect<VideoImportException> { importer().importLocalVideo(pseudoUrl) }.code)
        assertEquals(listOf(0), api.chunkIndexes)
    }

    @Test fun storedRecipeIsFetchedWithoutUploadingOrAnalysingAgain() = runTest {
        val file = temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) }
        val library = FakeLibrary(file, recipeId = "r9", uploadId = "u9")
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.recipes["r9"] = recipe("r9", "hansul-upload:u9")
        val importer = VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime })

        val recipe = importer.importLocalVideo(pseudoUrl)

        assertEquals("r9", recipe.id)
        assertEquals(pseudoUrl, recipe.sourceUrl)
        assertEquals(0, library.prepareCalls)
        assertTrue(api.uploadRequests.isEmpty() && api.jobRequests.isEmpty())
        assertEquals("file:/frames/step-1.jpg", recipe.steps.first().imageUrl)
    }

    @Test fun recipeGoneOnTheServerIsAnalysedAgain() = runTest {
        val file = temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) }
        val library = FakeLibrary(file, recipeId = "r-old")
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.upload = VideoUploadResponse("u1", "COMPLETE", chunkSizeBytes = 4, chunkCount = 2)
        api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r-new") }
        api.recipes["r-new"] = recipe("r-new", "hansul-upload:u1")
        api.missingRecipes += "r-old"
        val importer = VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime })

        assertEquals("r-new", importer.importLocalVideo(pseudoUrl).id)
        assertEquals(listOf("recipe=null", "upload=u1", "recipe=r-new"), library.log)
        assertTrue("A COMPLETE upload needs no chunks", api.chunkIndexes.isEmpty())
    }

    @Test fun storedConsumedUploadAsksTheJobForItsExistingRecipe() = runTest {
        val file = temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) }
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.storedUploads["u-old"] = VideoUploadResponse("u-old", "CONSUMED", chunkSizeBytes = 4, chunkCount = 2)
        api.onCreateJob = { job("SUCCEEDED", "DONE", recipeId = "r3") }
        api.recipes["r3"] = recipe("r3", "hansul-upload:u-old")
        val importer = VideoRecipeImporter(api, FakeLibrary(file, uploadId = "u-old"), clockMillis = { testScheduler.currentTime })

        assertEquals("r3", importer.importLocalVideo(pseudoUrl).id)
        assertEquals(listOf(CreateRecipeImportJobRequest(uploadId = "u-old")), api.jobRequests)
        assertTrue(api.uploadRequests.isEmpty())
    }

    @Test fun rememberedUploadTheServerNoLongerAcceptsIsReplacedOnce() = runTest {
        val file = temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) }
        val library = FakeLibrary(file, uploadId = "u-old")
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.storedUploads["u-old"] = VideoUploadResponse("u-old", "CONSUMED", chunkSizeBytes = 4, chunkCount = 2)
        api.onCreateJob = { request ->
            if (request.uploadId == "u-old") throw http(409, """{"code":"VIDEO_UPLOAD_CLOSED","message":"no recipe"}""")
            job("QUEUED", "QUEUED")
        }
        api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r4") }
        api.recipes["r4"] = recipe("r4", "hansul-upload:u1")
        val importer = VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime })

        assertEquals("r4", importer.importLocalVideo(pseudoUrl).id)
        assertEquals(listOf("u-old", "u1"), api.jobRequests.map { it.uploadId })
        assertEquals(listOf(0, 1), api.chunkIndexes)
        assertEquals(listOf("upload=null", "upload=u1", "recipe=r4"), library.log)

        val failing = FakeLibrary(file)
        api.onCreateJob = { throw http(409, """{"code":"UPLOAD_CHECKSUM_MISMATCH","message":"sha"}""") }
        assertEquals("UPLOAD_CHECKSUM_MISMATCH", expect<VideoImportException> {
            VideoRecipeImporter(api, failing, clockMillis = { testScheduler.currentTime }).importLocalVideo(pseudoUrl)
        }.code)
        assertNull("A failed upload is not resumed next time", failing.stored.uploadId)
    }

    @Test fun aJobTheAiCouldNotFinishKeepsItsUploadSoTryingAgainSendsNoVideo() = runTest {
        for (code in listOf("AI_QUOTA_EXCEEDED", "AI_UNAVAILABLE")) {
            val library = FakeLibrary(temp.newFile().apply { writeBytes(ByteArray(8)) })
            val api = FakeVideoApi(now = { testScheduler.currentTime })
            api.onPoll = { job("FAILED", "WATCHING_VIDEO", errorCode = code, errorMessage = "Gemini") }
            val importer = VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime })

            assertEquals(code, expect<VideoImportException> { importer.importLocalVideo(pseudoUrl) }.code)
            assertEquals(code, "u1", library.stored.uploadId)

            // The server keeps the upload COMPLETE after these failures, so the retry only queues a new job.
            api.storedUploads["u1"] = VideoUploadResponse("u1", "COMPLETE", chunkSizeBytes = 4, chunkCount = 2)
            api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r6") }
            api.recipes["r6"] = recipe("r6", "hansul-upload:u1")
            assertEquals(code, "r6", importer.importLocalVideo(pseudoUrl).id)

            assertEquals(code, 1, api.uploadRequests.size)
            assertEquals(code, listOf(0, 1), api.chunkIndexes)
            assertEquals(code, listOf("u1", "u1"), api.jobRequests.map { it.uploadId })
            assertEquals(code, listOf("upload=u1", "recipe=r6"), library.log)
        }
    }

    @Test fun otherJobFailuresLeaveItToTheServerWhetherTheUploadCanBeUsedAgain() = runTest {
        val library = FakeLibrary(temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) })
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.onPoll = { job("FAILED", "WATCHING_VIDEO", errorCode = "NOT_A_RECIPE_VIDEO", errorMessage = "No cooking") }
        val importer = VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime })
        expect<VideoImportException> { importer.importLocalVideo(pseudoUrl) }
        assertEquals("u1", library.stored.uploadId)

        // The server failed this upload together with its job, so the video goes up again as a new upload.
        api.storedUploads["u1"] = VideoUploadResponse("u1", "FAILED", chunkSizeBytes = 4, chunkCount = 2)
        api.received.clear()
        api.upload = VideoUploadResponse("u2", "RECEIVING", chunkSizeBytes = 4, chunkCount = 2)
        api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r7") }
        api.recipes["r7"] = recipe("r7", "hansul-upload:u2")
        assertEquals("r7", importer.importLocalVideo(pseudoUrl).id)

        assertEquals(listOf("upload=u1", "upload=null", "upload=u2", "recipe=r7"), library.log)
        assertEquals(listOf(0, 1, 0, 1), api.chunkIndexes)
        assertEquals(listOf("u1", "u2"), api.jobRequests.map { it.uploadId })
    }

    @Test fun uploadIdIsReadAfterPreparingBecauseANewFileForgetsTheOldUpload() = runTest {
        val file = temp.newFile("prepared.mp4").apply { writeBytes(ByteArray(8)) }
        val library = FakeLibrary(file, uploadId = "u-of-an-older-file", forgetUploadOnPrepare = true)
        val api = FakeVideoApi(now = { testScheduler.currentTime })
        api.onPoll = { job("SUCCEEDED", "DONE", recipeId = "r5") }
        api.recipes["r5"] = recipe("r5", "hansul-upload:u1")

        VideoRecipeImporter(api, library, clockMillis = { testScheduler.currentTime }).importLocalVideo(pseudoUrl)

        assertTrue(api.uploadStatusRequests.isEmpty())
        assertEquals(listOf("u1"), api.jobRequests.map { it.uploadId })
    }

    @Test fun unknownLocalVideoIsUnreadable() = runTest {
        val importer = VideoRecipeImporter(FakeVideoApi(), FakeLibrary(temp.newFile()), clockMillis = { testScheduler.currentTime })

        assertEquals(VideoImportException.LOCAL_VIDEO_UNREADABLE,
            expect<VideoImportException> { importer.importLocalVideo("hansul-video:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") }.code)
        assertEquals(VideoImportException.INVALID_RECIPE_URL,
            expect<VideoImportException> { importer.importYouTube("https://www.10000recipe.com/recipe/1") }.code)
    }

    private fun TestScope.record(importer: VideoRecipeImporter): List<VideoImportProgress?> {
        val seen = mutableListOf<VideoImportProgress?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { importer.progress.collect { seen += it } }
        return seen
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    private fun job(status: String, stage: String, recipeId: String? = null, errorCode: String? = null, errorMessage: String? = null) =
        RecipeImportJobResponse("j1", status, stage, recipeId, errorCode, errorMessage)

    private fun recipe(id: String, sourceUrl: String, video: RecipeVideoResponse? = null) = RecipeImportResponse(
        id = JsonPrimitive(id), sourceUrl = sourceUrl, title = "김치찌개", baseServings = JsonPrimitive(2),
        steps = listOf(
            RecipeStepResponse(1, "양파를 썰어주세요.", videoStartSeconds = 83, videoEndSeconds = 112, keyFrameSeconds = 95),
            RecipeStepResponse(2, "끓여주세요.", videoStartSeconds = 10, videoEndSeconds = 20),
        ).take(if (sourceUrl.startsWith("https://www.youtube.com")) 1 else 2),
        sourceKind = "VIDEO_AI", video = video,
    )

    private fun http(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private inner class FakeLibrary(
        private val file: File,
        recipeId: String? = null,
        uploadId: String? = null,
        private val forgetUploadOnPrepare: Boolean = false,
    ) : LocalVideoSource {
        var stored = StoredVideo(videoId, 612, uploadId, recipeId, file)
        val log = mutableListOf<String>()
        var frameRequests: List<StepFrame>? = null
        var prepareCalls = 0

        override suspend fun find(videoId: String) = stored.takeIf { it.id == videoId }

        override suspend fun prepare(videoId: String, onProgress: (VideoImportProgress) -> Unit): PreparedVideo {
            prepareCalls++
            if (forgetUploadOnPrepare) stored = stored.copy(uploadId = null)
            onProgress(VideoImportProgress(VideoImportStage.COMPRESSING_VIDEO, 0.5f))
            return PreparedVideo(file, "video/mp4", file.length(), "a".repeat(64), stored.durationSeconds)
        }

        override suspend fun rememberUpload(videoId: String, uploadId: String?) {
            stored = stored.copy(uploadId = uploadId)
            log += "upload=$uploadId"
        }

        override suspend fun rememberRecipe(videoId: String, recipeId: String?) {
            stored = stored.copy(recipeId = recipeId)
            log += "recipe=$recipeId"
        }

        override suspend fun extractFrames(videoId: String, frames: List<StepFrame>, onProgress: (Float) -> Unit): Map<Int, String> {
            frameRequests = frames
            onProgress(1f)
            return frames.associate { it.stepNumber to "file:/frames/step-${it.stepNumber}.jpg" }
        }
    }

    private inner class FakeVideoApi(private val now: () -> Long = { 0 }) : UnusedRecipeImportApi() {
        var onCreateJob: (CreateRecipeImportJobRequest) -> RecipeImportJobResponse = { job("QUEUED", "QUEUED") }
        var onPoll: (Int) -> RecipeImportJobResponse = { job("RUNNING", "WATCHING_VIDEO") }
        val jobRequests = mutableListOf<CreateRecipeImportJobRequest>()
        val pollTimes = mutableListOf<Long>()
        val recipes = mutableMapOf<String, RecipeImportResponse>()
        val missingRecipes = mutableSetOf<String>()
        var upload = VideoUploadResponse("u1", "RECEIVING", chunkSizeBytes = 4, chunkCount = 2)
        val storedUploads = mutableMapOf<String, VideoUploadResponse>()
        val uploadStatusRequests = mutableListOf<String>()
        val uploadRequests = mutableListOf<CreateVideoUploadRequest>()
        val received = mutableSetOf<Int>()
        val chunkIndexes = mutableListOf<Int>()
        val chunks = mutableMapOf<Int, ByteArray>()
        val chunkFailures = mutableMapOf<Int, ArrayDeque<Exception>>()

        override suspend fun createImportJob(request: CreateRecipeImportJobRequest): RecipeImportJobResponse {
            jobRequests += request
            return onCreateJob(request)
        }

        override suspend fun getImportJob(jobId: String): RecipeImportJobResponse {
            pollTimes += now()
            return onPoll(pollTimes.size - 1)
        }

        override suspend fun getRecipe(recipeId: String): RecipeImportResponse {
            if (recipeId in missingRecipes) throw http(404, """{"code":"RECIPE_NOT_FOUND","message":"gone"}""")
            return recipes.getValue(recipeId)
        }

        override suspend fun getVideoUpload(uploadId: String): VideoUploadResponse {
            uploadStatusRequests += uploadId
            return storedUploads[uploadId] ?: throw http(404, """{"code":"VIDEO_UPLOAD_NOT_FOUND","message":"gone"}""")
        }

        override suspend fun createVideoUpload(request: CreateVideoUploadRequest): VideoUploadResponse {
            uploadRequests += request
            received += upload.receivedChunks
            return upload.copy(receivedChunks = received.sorted())
        }

        override suspend fun uploadVideoChunk(uploadId: String, index: Int, chunk: RequestBody): VideoUploadResponse {
            chunkIndexes += index
            chunkFailures[index]?.removeFirstOrNull()?.let { throw it }
            assertEquals("application/octet-stream", chunk.contentType().toString())
            chunks[index] = Buffer().also(chunk::writeTo).readByteArray()
            received += index
            return upload.copy(receivedChunks = received.sorted())
        }
    }
}
