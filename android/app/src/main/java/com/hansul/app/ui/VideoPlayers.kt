package com.hansul.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoProvider
import java.io.File

/** A tap on a time chip or button. Each new [token] (re)starts playback at [startSeconds]; nothing autoplays before. */
@Immutable
internal data class VideoPlayRequest(val startSeconds: Int, val token: Int)

internal fun VideoPlayRequest?.next(startSeconds: Int) =
    VideoPlayRequest(startSeconds.coerceAtLeast(0), (this?.token ?: 0) + 1)

/** A step segment and the microphone never run together: the recognizer would take the video's speech for the cook. */
internal fun VideoPlayRequest?.withVoice(voiceEnabled: Boolean): VideoPlayRequest? = takeUnless { voiceEnabled }

/** Stops a step video's sound once the app is off the screen: Home, another app, or the screen timing out. */
internal class StopWhenHidden(private val stop: () -> Unit) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP) stop()
    }
}

/** WebView construction throws where no WebView provider works (none installed, disabled, or mid-update). */
internal inline fun <T : Any> createWebViewOrNull(create: () -> T): T? = try {
    create()
} catch (_: Exception) {
    null
}

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
// YouTube identifies WebView embeds by an HTTPS Referer that names the app.
private const val HANSUL_REFERER = "https://com.hansul.app"
private val MIN_PLAYER_HEIGHT = 200.dp

/** Embeddable YouTube and stored phone videos play inside the app; everything else opens outside it. */
internal fun RecipeVideo.playsInApp(): Boolean = when (provider) {
    VideoProvider.YOUTUBE -> embeddable && videoId?.matches(YOUTUBE_ID) == true
    VideoProvider.LOCAL_FILE -> !localFilePath.isNullOrBlank()
    VideoProvider.NAVER_TV -> false
}

/** The page to open outside the app; YouTube also starts at [atSeconds]. */
internal fun RecipeVideo.externalUrl(atSeconds: Int? = null): String? = when (provider) {
    VideoProvider.YOUTUBE -> videoId?.takeIf(YOUTUBE_ID::matches)
        ?.let { id -> "https://www.youtube.com/watch?v=$id" + (atSeconds?.takeIf { it > 0 }?.let { "&t=${it}s" } ?: "") }
        ?: pageUrl?.takeIf { it.startsWith("https://") }
    VideoProvider.NAVER_TV -> pageUrl?.takeIf { it.startsWith("https://") }
    VideoProvider.LOCAL_FILE -> null
}

/** Step times are only offered where a tap can land on that moment: in the app, or on YouTube. */
internal fun RecipeVideo.opensAtTime(): Boolean =
    playsInApp() || provider == VideoProvider.YOUTUBE && externalUrl() != null

internal fun youTubeEmbedUrl(videoId: String, startSeconds: Int): String =
    "https://www.youtube.com/embed/$videoId?start=$startSeconds&autoplay=1&playsinline=1&rel=0"

/**
 * The recipe's video. YouTube shows only a poster until the first tap. [onRelease] runs when a player leaves the
 * screen (scrolled away, another step), so coming back shows it stopped instead of autoplaying again.
 */
@Composable
internal fun RecipeVideoPanel(video: RecipeVideo, posterUrl: String?, request: VideoPlayRequest?,
    onPlay: (Int) -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val release by rememberUpdatedState(onRelease)
    val poster = posterUrl ?: video.videoId
        ?.takeIf { video.provider == VideoProvider.YOUTUBE && YOUTUBE_ID.matches(it) }
        ?.let(::youTubePoster)
    when {
        !video.playsInApp() -> {
            val page = video.externalUrl()
            val unavailable = if (video.provider == VideoProvider.LOCAL_FILE) "기기에서 영상을 찾지 못했어요." else "영상을 열 수 없어요."
            VideoPoster(poster, if (page != null) "영상 보기" else unavailable,
                page?.let { url -> { openExternally(context, url) } }, modifier)
        }
        video.provider == VideoProvider.LOCAL_FILE -> {
            DisposableEffect(Unit) { onDispose { release() } }
            LocalVideoPlayer(video.localFilePath.orEmpty(), request?.startSeconds ?: 0, request?.token ?: 0, modifier)
        }
        request == null -> VideoPoster(poster, "▶ 영상 재생", { onPlay(0) }, modifier)
        else -> {
            DisposableEffect(Unit) { onDispose { release() } }
            // Coming back after leaving the app shows the poster again instead of a blank player.
            YouTubeEmbedPlayer(video.videoId.orEmpty(), request.startSeconds, request.token, modifier, onStop = { release() })
        }
    }
}

/**
 * The YouTube embed player in a WebView. Only the embed page loads here; any other link opens outside the app.
 * Leaving the app blanks the page, which silences the video; [onStop] then runs.
 */
@Composable
internal fun YouTubeEmbedPlayer(videoId: String, startSeconds: Int, playToken: Int, modifier: Modifier = Modifier,
    onStop: () -> Unit = {}) {
    if (!YOUTUBE_ID.matches(videoId)) return
    val start = startSeconds.coerceAtLeast(0)
    val context = LocalContext.current
    var webViewMissing by remember(videoId) { mutableStateOf(false) }
    if (webViewMissing) {
        // Without a working WebView the video still opens in the YouTube app or a browser.
        val page = RecipeVideo(VideoProvider.YOUTUBE, videoId).externalUrl(start)
        VideoPoster(youTubePoster(videoId), "영상 보기", page?.let { url -> { openExternally(context, url) } }, modifier)
        return
    }
    // Compose does not recompose while the app is stopped, so the page is silenced on the view itself, not via state.
    val shown = remember(videoId) { object { var webView: WebView? = null } }
    StopWhenHiddenEffect {
        shown.webView?.loadUrl("about:blank")
        onStop()
    }
    VideoFrame(modifier) {
        key(videoId) {
            AndroidView(
                factory = { viewContext ->
                    createWebViewOrNull { embedWebView(viewContext, videoId) }?.also { shown.webView = it }
                        ?: View(viewContext).also { webViewMissing = true }
                },
                update = { view ->
                    val webView = view as? WebView ?: return@AndroidView
                    // Reload only for a new tap; other recompositions must not restart the video.
                    val request = "$start#$playToken"
                    if (webView.tag != request) {
                        webView.tag = request
                        webView.loadUrl(youTubeEmbedUrl(videoId, start), mapOf("Referer" to HANSUL_REFERER))
                    }
                },
                onRelease = { view ->
                    if (shown.webView === view) shown.webView = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    (view as? WebView)?.destroy()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun youTubePoster(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

@Composable
private fun StopWhenHiddenEffect(stop: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentStop by rememberUpdatedState(stop)
    DisposableEffect(lifecycleOwner) {
        val observer = StopWhenHidden { currentStop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun embedWebView(context: Context, videoId: String) = WebView(context).apply {
    // The embed exists only after a tap to play; the screen must not time out under the video.
    keepScreenOn = true
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            if (url.scheme == "https" && url.host == "www.youtube.com" && url.path == "/embed/$videoId") return false
            if (url.scheme == "https" || url.scheme == "http") openExternally(view.context, url.toString())
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // A crashed renderer must not take the cooking screen down with it.
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return true
        }
    }
}

/** A stored phone video in Media3's player; each new [playToken] seeks to [startSeconds] and plays. */
@OptIn(UnstableApi::class)
@Composable
internal fun LocalVideoPlayer(filePath: String, startSeconds: Int, playToken: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(filePath) { File(filePath).takeIf(File::isFile) }
    if (file == null) {
        VideoPoster(null, "기기에서 영상을 찾지 못했어요.", null, modifier)
        return
    }
    var aspectRatio by remember(filePath) { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember(filePath) { mutableStateOf(false) }
    val player = remember(filePath) {
        ExoPlayer.Builder(context.applicationContext)
            // Gives way to calls, other apps and Hansul's own voice instead of talking over them.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    StopWhenHiddenEffect { player.pause() }
    LaunchedEffect(player, playToken) {
        if (playToken > 0) {
            player.seekTo(startSeconds.coerceAtLeast(0) * 1_000L)
            player.play()
        }
    }
    VideoFrame(modifier, aspectRatio) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    // Keeps the video surface in step with Compose layout while the list scrolls (API 34+).
                    setEnableComposeSurfaceSyncWorkaround(true)
                    this.player = player
                }
            },
            // PlayerView never holds the screen on itself, so a video watched on the recipe screen would go dark.
            update = { it.player = player; it.keepScreenOn = isPlaying },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A still frame with one centred label; a poster is never drawn over a playing player. */
@Composable
private fun VideoPoster(imageUrl: String?, label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    VideoFrame(modifier) {
        Box(Modifier.fillMaxSize().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center) {
            imageUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = .65f), contentColor = Color.White) {
                Text(label, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

/** At least 200 dp tall, [aspectRatio] wide-to-high otherwise, and never taller than [maxHeight]. */
@Composable
private fun VideoFrame(modifier: Modifier = Modifier, aspectRatio: Float = 16f / 9f, maxHeight: Dp = 420.dp,
    content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val height = (maxWidth / aspectRatio).coerceIn(MIN_PLAYER_HEIGHT, maxOf(MIN_PLAYER_HEIGHT, maxHeight))
        Box(Modifier.fillMaxWidth().height(height).background(Color.Black), contentAlignment = Alignment.Center, content = content)
    }
}

internal fun openExternally(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "영상을 열 수 있는 앱이 없어요.", Toast.LENGTH_SHORT).show()
    }
}
