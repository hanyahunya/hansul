package com.hansul.app.share

import java.net.URI

enum class RecipeSourceKind { TEN_THOUSAND_RECIPE, YOUTUBE, LOCAL_VIDEO }

object RecipeUrlParser {
    private val urlPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val acceptedHosts = setOf("10000recipe.com", "www.10000recipe.com", "m.10000recipe.com")
    private val recipePath = Regex("^/recipe/(\\d+)/?$")
    // Hosts and paths are shared with the server's YouTubeUrlPolicy so both emit byte-identical canonical URLs.
    private val youTubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtube-nocookie.com")
    private val youTubeVideoPath = Regex("^/(shorts|live|embed)/([^/]+)/?$")
    private val youTubeShortLinkPath = Regex("^/([^/]+)/?$")
    private val youTubeId = Regex("^[A-Za-z0-9_-]{11}$")
    // Playlist and channel-live players put these 11-character words where the video id goes, in any link shape.
    private val reservedIds = setOf("videoseries", "live_stream")
    private val localVideoPattern = Regex("^hansul-video:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")
    private val unsupportedPlatforms = listOf(
        "instagram.com" to "인스타그램", "tiktok.com" to "틱톡", "tv.naver.com" to "네이버TV", "naver.me" to "네이버TV",
        "facebook.com" to "페이스북", "fb.watch" to "페이스북",
    )

    /** First supported link in shared text, as its canonical https URL (10000recipe page or YouTube watch URL). */
    fun extract(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null

        return candidates(sharedText).firstNotNullOfOrNull(::normalizeIfSupported)
    }

    fun kind(url: String): RecipeSourceKind? = when {
        localVideoId(url) != null -> RecipeSourceKind.LOCAL_VIDEO
        else -> when (normalizeIfSupported(url.trim())?.startsWith(YOUTUBE_WATCH_PREFIX)) {
            true -> RecipeSourceKind.YOUTUBE
            false -> RecipeSourceKind.TEN_THOUSAND_RECIPE
            null -> null
        }
    }

    fun youTubeVideoId(url: String): String? = parse(url.trim())?.let(::youTubeVideoId)

    /** Korean name of a video platform whose links cannot be analysed directly, when the text carries one. */
    fun unsupportedVideoPlatform(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        return candidates(sharedText).firstNotNullOfOrNull { candidate ->
            val host = parse(candidate)?.host?.lowercase() ?: return@firstNotNullOfOrNull null
            unsupportedPlatforms.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.second
        }
    }

    /** Phone videos never leave the device as URLs; this pseudo URL names the stored copy. */
    fun localVideoUrl(id: String): String = "$LOCAL_VIDEO_SCHEME$id"

    fun localVideoId(url: String): String? = localVideoPattern.matchEntire(url)?.groupValues?.get(1)

    fun requiresServerAnalysis(url: String): Boolean =
        kind(url).let { it == RecipeSourceKind.YOUTUBE || it == RecipeSourceKind.LOCAL_VIDEO }

    private fun candidates(sharedText: String): Sequence<String> = urlPattern.findAll(sharedText)
        .map { match -> match.value.trimEnd('.', ',', ';', ')', ']', '}', '\'', '"') }

    private fun parse(candidate: String): URI? = runCatching { URI(candidate) }.getOrNull()

    private fun normalizeIfSupported(candidate: String): String? {
        val uri = parse(candidate) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        youTubeVideoId(uri)?.let { return "$YOUTUBE_WATCH_PREFIX$it" }
        if (host !in acceptedHosts) return null
        val recipeId = recipePath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
            ?: return null

        return URI(
            "https",
            null,
            "www.10000recipe.com",
            -1,
            "/recipe/$recipeId",
            null,
            null,
        ).toASCIIString()
    }

    /** The id of an embedded player address (`/embed/{id}` only), like the server's YouTubeUrlPolicy.videoIdFromEmbed. */
    internal fun youTubeEmbedVideoId(embedUrl: String): String? {
        val uri = parse(embedUrl.trim().let { if (it.startsWith("//")) "https:$it" else it }) ?: return null
        if (uri.host?.lowercase() == "youtu.be") return null
        if (youTubeVideoPath.matchEntire(uri.rawPath ?: return null)?.groupValues?.get(1) != "embed") return null
        return youTubeVideoId(uri)
    }

    private fun youTubeVideoId(uri: URI): String? {
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in youTubeHosts || uri.rawUserInfo != null || uri.port != -1) return null
        val path = uri.rawPath.orEmpty()
        val id = when {
            host == "youtu.be" -> youTubeShortLinkPath.matchEntire(path)?.groupValues?.get(1)
            path == "/watch" || path == "/watch/" -> queryParameter(uri.rawQuery, "v")
            else -> youTubeVideoPath.matchEntire(path)?.groupValues?.get(2)
        }
        return id?.takeIf { youTubeId.matches(it) && it !in reservedIds }
    }

    // Raw (still percent-encoded) values and the first `v` key wins even when empty, exactly as on the server.
    private fun queryParameter(rawQuery: String?, name: String): String? = rawQuery?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    private const val YOUTUBE_WATCH_PREFIX = "https://www.youtube.com/watch?v="
    private const val LOCAL_VIDEO_SCHEME = "hansul-video:"
}
