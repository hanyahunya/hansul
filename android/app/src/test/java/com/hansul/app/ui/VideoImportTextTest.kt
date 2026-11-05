package com.hansul.app.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeOrigin
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.model.VideoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoImportTextTest {
    @Test
    fun `every stage shows its contract label`() {
        val labels = mapOf(
            VideoImportStage.PREPARING_VIDEO to "영상 준비 중",
            VideoImportStage.COMPRESSING_VIDEO to "영상 크기 줄이는 중",
            VideoImportStage.UPLOADING_VIDEO to "영상 올리는 중 0%",
            VideoImportStage.QUEUED to "분석 차례를 기다리는 중",
            VideoImportStage.FETCHING_METADATA to "영상 정보 확인 중",
            VideoImportStage.UPLOADING_TO_AI to "AI에게 영상 전달 중",
            VideoImportStage.WATCHING_VIDEO to "AI가 영상을 보는 중",
            VideoImportStage.WRITING_RECIPE to "레시피 정리 중",
            VideoImportStage.SAVING to "저장 중",
            VideoImportStage.EXTRACTING_FRAMES to "단계 장면 만드는 중",
        )

        assertEquals(VideoImportStage.entries.toSet(), labels.keys)
        labels.forEach { (stage, label) -> assertEquals(label, VideoImportProgress(stage).stageLabel()) }
        assertEquals("영상 올리는 중 45%", VideoImportProgress(VideoImportStage.UPLOADING_VIDEO, .459f).stageLabel())
        assertEquals("영상 올리는 중 100%", VideoImportProgress(VideoImportStage.UPLOADING_VIDEO, 1f).stageLabel())
    }

    @Test
    fun `video times read as minutes and seconds`() {
        assertEquals("01:23", videoTime(83))
        assertEquals("30:00", videoTime(1800))
        assertEquals("00:00", videoTime(-3))
        assertEquals("1:02:03", videoTime(3723))
        assertEquals("01:23–02:05", videoRange(83, 125))
        assertEquals("01:23", videoRange(83, null))
        assertEquals("01:23", videoRange(83, 83))
    }

    @Test
    fun `source label follows where the recipe came from`() {
        val page = Recipe("r", "https://www.10000recipe.com/recipe/1", "제목", "2", emptyList(), emptyList())
        val youTube = page.copy(sourceUrl = "https://www.youtube.com/watch?v=AbCdEfGhIjK", origin = RecipeOrigin.VIDEO_AI,
            video = RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", embeddable = true))
        val phone = page.copy(sourceUrl = "hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e", origin = RecipeOrigin.VIDEO_AI,
            video = RecipeVideo(VideoProvider.LOCAL_FILE, localFilePath = "/data/video.mp4"))

        assertEquals("만개의레시피", page.sourceLabel())
        assertEquals("만개의레시피", page.copy(video = RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", embeddable = true)).sourceLabel())
        assertEquals("YouTube · AI 정리", youTube.sourceLabel())
        assertEquals("내 영상 · AI 정리", phone.sourceLabel())
        assertEquals("내 영상 · AI 정리", phone.copy(video = null).sourceLabel())
    }

    @Test
    fun `only embeddable youtube and stored phone videos play inside the app`() {
        val embeddable = RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", "https://www.youtube.com/watch?v=AbCdEfGhIjK", embeddable = true)
        val blocked = embeddable.copy(embeddable = false)
        val naver = RecipeVideo(VideoProvider.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225")
        val phone = RecipeVideo(VideoProvider.LOCAL_FILE, localFilePath = "/data/prepared.mp4")

        assertTrue(embeddable.playsInApp())
        assertFalse(embeddable.copy(videoId = "short").playsInApp())
        assertFalse(blocked.playsInApp())
        assertFalse(naver.playsInApp())
        assertTrue(phone.playsInApp())
        assertFalse(phone.copy(localFilePath = null).playsInApp())

        assertEquals("https://www.youtube.com/watch?v=AbCdEfGhIjK&t=83s", blocked.externalUrl(83))
        assertEquals("https://www.youtube.com/watch?v=AbCdEfGhIjK", blocked.externalUrl())
        assertEquals("https://tv.naver.com/v/52277225", naver.externalUrl(83))
        assertNull(naver.copy(pageUrl = "http://tv.naver.com/v/52277225").externalUrl())
        assertNull(phone.externalUrl(10))

        assertTrue(blocked.opensAtTime())
        assertFalse("Naver TV pages cannot be opened at a time", naver.opensAtTime())
        assertFalse(phone.copy(localFilePath = null).opensAtTime())
    }

    @Test
    fun `embed address and play requests follow the contract`() {
        assertEquals("https://www.youtube.com/embed/AbCdEfGhIjK?start=83&autoplay=1&playsinline=1&rel=0",
            youTubeEmbedUrl("AbCdEfGhIjK", 83))
        val nothingPlayed: VideoPlayRequest? = null
        val first = nothingPlayed.next(83)
        assertEquals(VideoPlayRequest(83, 1), first)
        assertEquals(VideoPlayRequest(0, 2), first.next(-5))
    }

    @Test
    fun `turning the microphone on stops a playing step segment`() {
        val playing = VideoPlayRequest(83, 1)

        assertNull("The recognizer must not hear the video", playing.withVoice(true))
        assertEquals(playing, playing.withVoice(false))
    }

    @Test
    fun `a step video stops once the app leaves the screen`() {
        var stops = 0
        val observer = StopWhenHidden { stops++ }
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = throw AssertionError("Not needed")
        }

        Lifecycle.Event.values().filter { it != Lifecycle.Event.ON_STOP }.forEach { observer.onStateChanged(owner, it) }
        assertEquals(0, stops)
        observer.onStateChanged(owner, Lifecycle.Event.ON_STOP)
        assertEquals(1, stops)
    }

    @Test
    fun `a phone without a working WebView gets the outside player instead of a crash`() {
        val missing = RuntimeException(
            "android.webkit.WebViewFactory\$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed",
        )

        assertNull(createWebViewOrNull<Any> { throw missing })
        assertEquals("web view", createWebViewOrNull { "web view" })
    }
}
