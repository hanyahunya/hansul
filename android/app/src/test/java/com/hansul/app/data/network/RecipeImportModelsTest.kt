package com.hansul.app.data.network

import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeOrigin
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeImportModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    // AppContainer's Retrofit configuration.
    private val appJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    @Test
    fun `decodes the exact recipe bodies the server writes`() {
        assertEquals(
            Recipe(
                id = "0b6f4a51-3f7e-4d38-9a77-5a8f2f0c1d2e", sourceUrl = "https://www.youtube.com/watch?v=AbCdEfGhIjK",
                title = "양파 달걀볶음", baseServings = "2", ingredients = listOf(Ingredient("양파", "1개")),
                steps = listOf(RecipeStep(1, "양파 1개를 채 썰어주세요.", null, "양파 한 개 채 썰어 줄게요", 83, 112, 95)),
                imageUrl = "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg",
                video = RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", "https://www.youtube.com/watch?v=AbCdEfGhIjK",
                    durationSeconds = 754, channel = "채널명", embeddable = true),
                origin = RecipeOrigin.VIDEO_AI,
            ),
            appJson.decodeFromString<RecipeImportResponse>(YOUTUBE_RECIPE_JSON).toDomain(),
        )
        assertEquals(
            Recipe(
                id = "6a1d3c9e-0f4b-4e2a-8c7d-1b2e3f4a5b6c", sourceUrl = "hansul-upload:5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10",
                title = "영상 레시피", baseServings = "", ingredients = emptyList(),
                steps = listOf(RecipeStep(1, "물을 끓여주세요.", null, "물 끓일게요", 0, 9, 4)),
                video = RecipeVideo(VideoProvider.LOCAL_FILE, durationSeconds = 612), origin = RecipeOrigin.VIDEO_AI,
            ),
            appJson.decodeFromString<RecipeImportResponse>(UPLOAD_RECIPE_JSON).toDomain(),
        )
        assertEquals(
            Recipe(
                id = "9c8b7a65-4321-4fed-8cba-0123456789ab", sourceUrl = "https://www.10000recipe.com/recipe/6944989",
                title = "고구마 맛탕", baseServings = "2", ingredients = listOf(Ingredient("고구마", "2개")),
                steps = listOf(RecipeStep(1, "고구마를 깍둑썰기 해주세요.", "https://recipe1.ezmember.co.kr/step1.jpg",
                    "고구마를 깍둑썰기 해주세요.")),
                imageUrl = "https://recipe1.ezmember.co.kr/main.jpg",
                // The same video LocalRecipeParser reads from the page's Naver TV embed in local mode.
                video = RecipeVideo(VideoProvider.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225"),
                origin = RecipeOrigin.WEB_PAGE,
            ),
            appJson.decodeFromString<RecipeImportResponse>(WEB_PAGE_RECIPE_JSON).toDomain(),
        )
    }

    @Test
    fun `decodes the exact job and upload bodies the server writes`() {
        assertEquals(
            RecipeImportJobResponse("7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d", "QUEUED", "QUEUED", null, null, null,
                "YOUTUBE", "https://www.youtube.com/watch?v=AbCdEfGhIjK", "2026-11-05T09:00:00Z", "2026-11-05T09:00:00Z"),
            appJson.decodeFromString<RecipeImportJobResponse>(QUEUED_JOB_JSON),
        )
        assertEquals(
            RecipeImportJobResponse("7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d", "FAILED", "WATCHING_VIDEO", null,
                "NOT_A_RECIPE_VIDEO", "The video does not show cooking", "UPLOAD", null, "2026-11-05T09:00:00Z",
                "2026-11-05T09:03:30.123456Z"),
            appJson.decodeFromString<RecipeImportJobResponse>(FAILED_UPLOAD_JOB_JSON),
        )
        assertEquals(
            VideoUploadResponse("5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10", "RECEIVING", 4_194_304, 30, listOf(0, 1, 2),
                "2026-11-05T15:00:00Z"),
            appJson.decodeFromString<VideoUploadResponse>(UPLOAD_JSON),
        )
        // VideoUploadControllerTest posts exactly this request body.
        assertEquals(
            """{"contentType":"video/mp4","sizeBytes":123456789,"sha256":"84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882","durationSeconds":612}""",
            appJson.encodeToString(CreateVideoUploadRequest("video/mp4", 123_456_789,
                "84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882", 612)),
        )
    }

    @Test
    fun `decodes dynamic recipe and ignores additional server fields`() {
        val payload =
            """
            {
              "id": 42,
              "sourceUrl": "https://m.10000recipe.com/recipe/42",
              "title": "서버에서 받은 제목",
              "baseServings": 3,
              "ingredients": [
                {"name": "첫 번째 재료", "quantityText": "2개", "unit": "piece"},
                {"name": "양 없는 재료", "quantityText": null}
              ],
              "steps": [
                {"number": 2, "text": "두 번째 단계", "imageUrl": null},
                {"number": 1, "text": "첫 번째 단계", "imageUrl": "https://example.com/1.jpg"}
              ],
              "serverOnlyField": {"ignored": true}
            }
            """.trimIndent()

        val recipe = json.decodeFromString<RecipeImportResponse>(payload).toDomain()

        assertEquals("42", recipe.id)
        assertEquals("서버에서 받은 제목", recipe.title)
        assertEquals("3", recipe.baseServings)
        assertEquals("첫 번째 재료", recipe.ingredients[0].name)
        assertEquals("", recipe.ingredients[1].quantityText)
        assertEquals(listOf(1, 2), recipe.steps.map { it.number })
    }

    @Test
    fun `maps an AI video recipe with step segments and the YouTube video`() {
        val payload =
            """
            {
              "id": "0f8fad5b-d9cb-469f-a165-70867728950e",
              "sourceUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK",
              "title": "김치찌개",
              "baseServings": 2,
              "ingredients": [{"name": "김치", "quantityText": "1/4포기"}],
              "steps": [{
                "number": 1, "text": "양파 1개를 채 썰어주세요.", "imageUrl": null,
                "originalText": "양파 한 개 채 썰어 줄게요",
                "evidence": {"sourceType": "VIDEO_SEGMENT", "locator": "segments:s3-s4@83-112"},
                "videoStartSeconds": 83, "videoEndSeconds": 112, "keyFrameSeconds": 95
              }],
              "baseServingsText": "2인분", "description": "설명", "totalTimeText": null,
              "primaryImageUrl": "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg",
              "parserVersion": "youtube-gemini-v1", "importedAt": "2026-11-05T01:02:03Z", "warnings": [],
              "sourceKind": "VIDEO_AI",
              "video": {"provider": "YOUTUBE", "videoId": "AbCdEfGhIjK",
                        "pageUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK",
                        "durationSeconds": 754, "channel": "채널명", "embeddable": true}
            }
            """.trimIndent()

        val recipe = json.decodeFromString<RecipeImportResponse>(payload).toDomain()

        assertEquals(RecipeOrigin.VIDEO_AI, recipe.origin)
        assertEquals("https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg", recipe.imageUrl)
        assertEquals("2", recipe.baseServings)
        assertEquals(
            RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", "https://www.youtube.com/watch?v=AbCdEfGhIjK",
                durationSeconds = 754, channel = "채널명", embeddable = true),
            recipe.video,
        )
        val step = recipe.steps.single()
        assertEquals("양파 한 개 채 썰어 줄게요", step.originalText)
        assertEquals(listOf(83, 112, 95), listOf(step.videoStartSeconds, step.videoEndSeconds, step.keyFrameSeconds))
        assertNull(step.imageUrl)
    }

    @Test
    fun `web page recipes keep defaults and fall back to imageUrl`() {
        val recipe = json.decodeFromString<RecipeImportResponse>(
            """{"id": "r1", "sourceUrl": "https://www.10000recipe.com/recipe/1", "title": "제목",
               "baseServings": null, "baseServingsText": "2~3인분", "imageUrl": "https://recipe1.ezmember.co.kr/a.jpg",
               "primaryImageUrl": "", "sourceKind": "WEB_PAGE", "video": null,
               "steps": [{"number": 1, "text": "끓여주세요.", "videoStartSeconds": null}]}""",
        ).toDomain()

        assertEquals(RecipeOrigin.WEB_PAGE, recipe.origin)
        assertEquals("https://recipe1.ezmember.co.kr/a.jpg", recipe.imageUrl)
        assertEquals("2~3인분", recipe.baseServings)
        assertNull(recipe.video)
        assertNull(recipe.steps.single().videoStartSeconds)
        assertEquals(RecipeOrigin.WEB_PAGE, json.decodeFromString<RecipeImportResponse>(
            """{"sourceUrl": "https://www.10000recipe.com/recipe/1", "title": "옛 서버"}""",
        ).toDomain().origin)
    }

    @Test
    fun `server upload video becomes a local file and only valid YouTube videos embed`() {
        fun video(body: String) = json.decodeFromString<RecipeImportResponse>(
            """{"sourceUrl": "hansul-upload:9b2c", "title": "t", "sourceKind": "VIDEO_AI", "video": $body}""",
        ).toDomain().video

        assertEquals(
            RecipeVideo(VideoProvider.LOCAL_FILE, durationSeconds = 612),
            video("""{"provider": "UPLOAD", "videoId": null, "pageUrl": null, "durationSeconds": 612, "channel": null, "embeddable": true}"""),
        )
        assertEquals(
            RecipeVideo(VideoProvider.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225"),
            video("""{"provider": "NAVER_TV", "videoId": "52277225", "pageUrl": "https://tv.naver.com/v/52277225", "embeddable": true}"""),
        )
        val unsafe = video("""{"provider": "YOUTUBE", "videoId": "bad id", "pageUrl": "intent://evil", "embeddable": true}""")
        assertEquals(false, unsafe?.embeddable)
        assertNull(unsafe?.pageUrl)
        assertNull(video("""{"provider": "VIMEO", "videoId": "1"}"""))
    }

    @Test
    fun `decodes import jobs and video uploads and encodes only the chosen job source`() {
        val appJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
        val job = appJson.decodeFromString<RecipeImportJobResponse>(
            """{"jobId": "j1", "status": "FAILED", "stage": "WATCHING_VIDEO", "recipeId": null,
               "errorCode": "NOT_A_RECIPE_VIDEO", "errorMessage": "No cooking", "sourceKind": "YOUTUBE",
               "sourceUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK", "createdAt": "2026-11-05T01:00:00Z",
               "updatedAt": "2026-11-05T01:01:00Z"}""",
        )
        assertEquals("NOT_A_RECIPE_VIDEO", job.errorCode)
        assertEquals("WATCHING_VIDEO", job.stage)

        val upload = appJson.decodeFromString<VideoUploadResponse>(
            """{"uploadId": "u1", "status": "RECEIVING", "chunkSizeBytes": 4194304, "chunkCount": 30,
               "receivedChunks": [0, 1, 2], "expiresAt": "2026-11-05T07:00:00Z"}""",
        )
        assertEquals(listOf(0, 1, 2), upload.receivedChunks)
        assertEquals(4_194_304L, upload.chunkSizeBytes)

        assertEquals("""{"url":"https://www.youtube.com/watch?v=AbCdEfGhIjK"}""",
            appJson.encodeToString(CreateRecipeImportJobRequest(url = "https://www.youtube.com/watch?v=AbCdEfGhIjK")))
        assertEquals("""{"uploadId":"u1"}""", appJson.encodeToString(CreateRecipeImportJobRequest(uploadId = "u1")))
        assertEquals("""{"contentType":"video/mp4","sizeBytes":123456789,"sha256":"ab","durationSeconds":612}""",
            appJson.encodeToString(CreateVideoUploadRequest("video/mp4", 123_456_789, "ab", 612)))
    }

    @Test
    fun `decodes scaled ingredients without recipe specific assumptions`() {
        val payload =
            """
            {
              "recipeId": "recipe-42",
              "title": "동적 제목",
              "baseServings": 4,
              "targetServings": 1,
              "ingredients": [
                {
                  "name": "동적 재료",
                  "originalQuantityText": "2개",
                  "scaledQuantityText": "0.5개",
                  "originalNote": "선택 사항",
                  "status": "SCALED"
                }
              ],
              "warnings": ["계량하기 쉬운 양으로 조절하세요"],
              "additionalField": true
            }
            """.trimIndent()

        val scaled = json.decodeFromString<ScaledIngredientsResponse>(payload).toDomain()

        assertEquals("recipe-42", scaled.recipeId)
        assertEquals("1", scaled.targetServings)
        assertEquals("0.5개", scaled.ingredients.single().scaledQuantityText)
        assertEquals(1, scaled.warnings.size)
    }

    // The server's RecipeImportControllerTest, RecipeImportJobControllerTest and VideoUploadControllerTest pin these
    // exact bodies with a strict JSON compare; keep both copies identical.
    private companion object {
        val YOUTUBE_RECIPE_JSON = """
            {"id": "0b6f4a51-3f7e-4d38-9a77-5a8f2f0c1d2e", "sourceUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK",
             "title": "양파 달걀볶음", "baseServings": 2,
             "ingredients": [{"name": "양파", "quantityText": "1개", "originalText": "양파 1개", "normalizedAmount": 1,
                              "normalizedUnit": "개", "normalizationWarning": null,
                              "evidence": {"sourceType": "VIDEO_DESCRIPTION", "locator": "description:d3"}}],
             "steps": [{"number": 1, "text": "양파 1개를 채 썰어주세요.", "imageUrl": null,
                        "originalText": "양파 한 개 채 썰어 줄게요",
                        "evidence": {"sourceType": "VIDEO_SEGMENT", "locator": "segments:s3-s4@83-112"},
                        "videoStartSeconds": 83, "videoEndSeconds": 112, "keyFrameSeconds": 95}],
             "baseServingsText": "2인분", "description": "양파 1개, 달걀 2개", "totalTimeText": null,
             "primaryImageUrl": "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg",
             "parserVersion": "youtube-gemini-v1", "importedAt": "2026-11-05T01:02:03Z",
             "warnings": ["Recipe generated by AI from the video's speech and on-screen text; verify amounts and times in the video."],
             "sourceKind": "VIDEO_AI",
             "video": {"provider": "YOUTUBE", "videoId": "AbCdEfGhIjK", "pageUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK",
                       "durationSeconds": 754, "channel": "채널명", "embeddable": true}}
        """
        val UPLOAD_RECIPE_JSON = """
            {"id": "6a1d3c9e-0f4b-4e2a-8c7d-1b2e3f4a5b6c", "sourceUrl": "hansul-upload:5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10",
             "title": "영상 레시피", "baseServings": null, "ingredients": [],
             "steps": [{"number": 1, "text": "물을 끓여주세요.", "imageUrl": null, "originalText": "물 끓일게요",
                        "evidence": {"sourceType": "VIDEO_SEGMENT", "locator": "segments:s1@0-9"},
                        "videoStartSeconds": 0, "videoEndSeconds": 9, "keyFrameSeconds": 4}],
             "baseServingsText": null, "description": null, "totalTimeText": null, "primaryImageUrl": null,
             "parserVersion": "upload-gemini-v1", "importedAt": "2026-11-05T01:02:03Z", "warnings": [],
             "sourceKind": "VIDEO_AI",
             "video": {"provider": "UPLOAD", "videoId": null, "pageUrl": null, "durationSeconds": 612, "channel": null,
                       "embeddable": false}}
        """
        val WEB_PAGE_RECIPE_JSON = """
            {"id": "9c8b7a65-4321-4fed-8cba-0123456789ab", "sourceUrl": "https://www.10000recipe.com/recipe/6944989",
             "title": "고구마 맛탕", "baseServings": 2,
             "ingredients": [{"name": "고구마", "quantityText": "2개", "originalText": "고구마 2개", "normalizedAmount": 2,
                              "normalizedUnit": "개", "normalizationWarning": null,
                              "evidence": {"sourceType": "DOM", "locator": "ingredient:1"}}],
             "steps": [{"number": 1, "text": "고구마를 깍둑썰기 해주세요.", "imageUrl": "https://recipe1.ezmember.co.kr/step1.jpg",
                        "originalText": "고구마를 깍둑썰기 해주세요.", "evidence": {"sourceType": "DOM", "locator": "step:1"},
                        "videoStartSeconds": null, "videoEndSeconds": null, "keyFrameSeconds": null}],
             "baseServingsText": "2인분", "description": null, "totalTimeText": "30분 이내",
             "primaryImageUrl": "https://recipe1.ezmember.co.kr/main.jpg",
             "parserVersion": "10000recipe-v2", "importedAt": "2026-11-05T01:02:03Z", "warnings": [],
             "sourceKind": "WEB_PAGE",
             "video": {"provider": "NAVER_TV", "videoId": "52277225", "pageUrl": "https://tv.naver.com/v/52277225",
                       "durationSeconds": null, "channel": null, "embeddable": false}}
        """
        val QUEUED_JOB_JSON = """
            {"jobId": "7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d", "status": "QUEUED", "stage": "QUEUED", "recipeId": null,
             "errorCode": null, "errorMessage": null, "sourceKind": "YOUTUBE",
             "sourceUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK",
             "createdAt": "2026-11-05T09:00:00Z", "updatedAt": "2026-11-05T09:00:00Z"}
        """
        val FAILED_UPLOAD_JOB_JSON = """
            {"jobId": "7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d", "status": "FAILED", "stage": "WATCHING_VIDEO",
             "recipeId": null, "errorCode": "NOT_A_RECIPE_VIDEO", "errorMessage": "The video does not show cooking",
             "sourceKind": "UPLOAD", "sourceUrl": null,
             "createdAt": "2026-11-05T09:00:00Z", "updatedAt": "2026-11-05T09:03:30.123456Z"}
        """
        val UPLOAD_JSON = """
            {"uploadId": "5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10", "status": "RECEIVING", "chunkSizeBytes": 4194304,
             "chunkCount": 30, "receivedChunks": [0, 1, 2], "expiresAt": "2026-11-05T15:00:00Z"}
        """
    }
}
