package com.hansul.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeUrlParserTest {
    @Test
    fun `extracts recipe url from browser share text`() {
        val sharedText = "고구마 요리 https://m.10000recipe.com/recipe/6944989"

        assertEquals(
            "https://www.10000recipe.com/recipe/6944989",
            RecipeUrlParser.extract(sharedText),
        )
    }

    @Test
    fun `normalizes http and removes fragments`() {
        assertEquals(
            "https://www.10000recipe.com/recipe/12345",
            RecipeUrlParser.extract(
                "http://www.10000recipe.com/recipe/12345?from=share#comments",
            ),
        )
    }

    @Test
    fun `rejects unsupported hosts and non recipe paths`() {
        assertNull(RecipeUrlParser.extract("https://example.com/recipe/12345"))
        assertNull(RecipeUrlParser.extract("https://m.10000recipe.com/profile/12345"))
    }

    @Test
    fun `canonicalizes every documented YouTube link shape to the same watch url`() {
        val canonical = "https://www.youtube.com/watch?v=AbCdEfGhIjK"
        listOf(
            "https://www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://youtube.com/watch?v=AbCdEfGhIjK",
            "https://m.youtube.com/watch?v=AbCdEfGhIjK&pp=ygUJ6rmA7LmY7LCM",
            "https://www.youtube.com/watch?feature=shared&v=AbCdEfGhIjK&t=83s&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://www.youtube.com/watch/?v=AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK?si=Xy-Z_123abc",
            "https://youtu.be/AbCdEfGhIjK/?t=30",
            "https://www.youtube.com/shorts/AbCdEfGhIjK",
            "https://youtube.com/shorts/AbCdEfGhIjK?si=Xy-Z_123abc",
            "https://m.youtube.com/shorts/AbCdEfGhIjK/",
            "https://www.youtube.com/live/AbCdEfGhIjK?si=Xy-Z_123abc",
            "https://www.youtube.com/embed/AbCdEfGhIjK?start=95&rel=0",
            "https://www.youtube-nocookie.com/embed/AbCdEfGhIjK",
            "http://www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://WWW.YouTube.com/watch?v=AbCdEfGhIjK#t=10",
        ).forEach { link ->
            assertEquals(link, canonical, RecipeUrlParser.extract(link))
            assertEquals(link, "AbCdEfGhIjK", RecipeUrlParser.youTubeVideoId(link))
            assertEquals(link, RecipeSourceKind.YOUTUBE, RecipeUrlParser.kind(link))
        }
        assertEquals(
            "https://www.youtube.com/watch?v=a-b_c-D_e-F",
            RecipeUrlParser.extract("백종원 김치찌개 https://youtu.be/a-b_c-D_e-F?si=abc 꼭 보세요."),
        )
    }

    @Test
    fun `rejects YouTube links that do not name exactly one watchable video`() {
        listOf(
            "https://www.youtube.com/watch?v=AbCdEfGhIj",
            "https://www.youtube.com/watch?v=AbCdEfGhIjKL",
            "https://www.youtube.com/watch?v=AbCdEfGh!jK",
            "https://www.youtube.com/watch?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://www.youtube.com/channel/UCyn-K7rZLXjGl7VXGweIlcA",
            "https://www.youtube.com/@paikscuisine",
            "https://www.youtube.com/shorts/AbCdEfGhIjK/extra",
            "https://www.youtube.com/v/AbCdEfGhIjK",
            "https://youtu.be/",
            "https://youtu.be/AbCdEfGhIjK/extra",
            "https://music.youtube.com/watch?v=AbCdEfGhIjK",
            "https://youtube-nocookie.com/embed/AbCdEfGhIjK",
            "https://www.youtube.com.evil.example/watch?v=AbCdEfGhIjK",
            "https://evil-youtube.com/watch?v=AbCdEfGhIjK",
            "https://user@www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://www.youtube.com:8443/watch?v=AbCdEfGhIjK",
            "ftp://www.youtube.com/watch?v=AbCdEfGhIjK",
            // Playlist and channel-live players use 11-character words where a video id would be, in every link shape.
            "https://www.youtube.com/embed/videoseries?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://www.youtube.com/embed/live_stream?channel=UCyn-K7rZLXjGl7VXGweIlcA",
            "https://www.youtube-nocookie.com/embed/live_stream?channel=UCyn-K7rZLXjGl7VXGweIlcA",
            "https://www.youtube.com/watch?v=videoseries&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://m.youtube.com/watch?v=live_stream",
            "https://youtu.be/videoseries",
            "https://youtu.be/live_stream?si=Xy-Z_123abc",
            "https://www.youtube.com/shorts/videoseries",
            "https://www.youtube.com/live/live_stream",
            "https://www.youtube.com/live/videoseries/",
            // The server reads the first v key, even an empty one, and never decodes the id.
            "https://www.youtube.com/watch?v&v=AbCdEfGhIjK",
            "https://www.youtube.com/watch?v=&v=AbCdEfGhIjK",
            "https://www.youtube.com/watch?v=AbCdEfGh%49jK",
            "https://www.youtube.com/watch?vv=AbCdEfGhIjK",
        ).forEach { link ->
            assertNull(link, RecipeUrlParser.extract(link))
            assertNull(link, RecipeUrlParser.youTubeVideoId(link))
            assertNull(link, RecipeUrlParser.kind(link))
        }
    }

    @Test
    fun `emits the same canonical YouTube urls as the server`() {
        // The server's YouTubeUrlPolicyTest holds the same table, so both sides emit byte-identical canonical URLs
        // ("-" = rejected). The app also accepts each link over http and upgrades it; the server takes https only.
        """
            https://www.youtube.com/watch?v=AbCdEfGhIjK                            | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://youtube.com/watch?v=AbCdEfGhIjK&t=83s                          | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://m.youtube.com/watch?feature=share&v=AbCdEfGhIjK&pp=ygUE        | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://www.youtube.com/watch/?v=AbCdEfGhIjK&list=PL0123456789&index=2 | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://www.youtube-nocookie.com/watch?v=AbCdEfGhIjK                   | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://www.youtube.com/shorts/AbCdEfGhIjK?si=Xy12_ab-CD               | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://m.youtube.com/shorts/AbCdEfGhIjK/                              | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://www.youtube.com/live/AbCdEfGhIjK?feature=share                 | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://www.youtube.com/embed/AbCdEfGhIjK?start=95&rel=0               | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://www.youtube-nocookie.com/embed/AbCdEfGhIjK                     | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://youtu.be/AbCdEfGhIjK?si=Xy12ab&t=15                            | https://www.youtube.com/watch?v=AbCdEfGhIjK
            https://youtu.be/AbCdEfGhIjK/                                          | https://www.youtube.com/watch?v=AbCdEfGhIjK
            HTTPS://WWW.YouTube.COM/watch?v=a_b-C1d2E3f#t=1m                       | https://www.youtube.com/watch?v=a_b-C1d2E3f
            https://www.youtube.com/watch?v=AbCdEfGhIj                             | -
            https://www.youtube.com/watch?v=AbCdEfGh%49jK                          | -
            https://www.youtube.com/watch?v=&v=AbCdEfGhIjK                         | -
            https://www.youtube.com/watch?vi=AbCdEfGhIjK                           | -
            https://www.youtube.com/Watch?v=AbCdEfGhIjK                            | -
            https://www.youtube.com/embed/videoseries?list=PL0123456789            | -
            https://www.youtube.com/playlist?list=PL0123456789                     | -
            https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv               | -
            https://youtu.be/embed/AbCdEfGhIjK                                     | -
            https://music.youtube.com/watch?v=AbCdEfGhIjK                          | -
            https://user@www.youtube.com/watch?v=AbCdEfGhIjK                       | -
            https://www.youtube.com:443/watch?v=AbCdEfGhIjK                        | -
            https://www.youtube.com/watch?v=videoseries                            | -
            https://youtu.be/live_stream                                           | -
        """.trimIndent().lines().map { it.split('|').map(String::trim) }.forEach { (link, canonical) ->
            val expected = canonical.takeUnless { it == "-" }
            val http = link.replaceFirst(Regex("^https:", RegexOption.IGNORE_CASE), "http:")
            assertEquals(link, expected, RecipeUrlParser.extract(link))
            assertEquals(link, expected, RecipeUrlParser.extract(http))
            assertEquals(link, expected?.substringAfter("?v="), RecipeUrlParser.youTubeVideoId(link))
            assertEquals(link, expected?.let { RecipeSourceKind.YOUTUBE }, RecipeUrlParser.kind(link))
            // The app stores and sends the canonical URL itself, which has to map to itself.
            expected?.let { assertEquals(link, it, RecipeUrlParser.extract(it)) }
        }
    }

    @Test
    fun `reads embedded player ids like the server and nothing else`() {
        listOf(
            "https://www.youtube.com/embed/AbCdEfGhIjK?rel=0&autoplay=1",
            "https://www.youtube.com/embed/AbCdEfGhIjK/",
            "http://www.youtube.com/embed/AbCdEfGhIjK",
            "//www.youtube.com/embed/AbCdEfGhIjK",
            "https://www.youtube-nocookie.com/embed/AbCdEfGhIjK",
        ).forEach { embed -> assertEquals(embed, "AbCdEfGhIjK", RecipeUrlParser.youTubeEmbedVideoId(embed)) }
        listOf(
            "", "about:blank",
            "https://www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://www.youtube.com/shorts/AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK",
            "https://www.youtube.com/embed/videoseries?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "https://www.youtube.com/embed/live_stream?channel=UCyn-K7rZLXjGl7VXGweIlcA",
            "https://www.youtube.com/embed/AbCdEfGhIj",
            "https://www.youtube.com:8443/embed/AbCdEfGhIjK",
            "https://tv.naver.com/embed/52277225",
            "https://www.10000recipe.com/embed/AbCdEfGhIjK",
            "ftp://www.youtube.com/embed/AbCdEfGhIjK",
        ).forEach { embed -> assertNull(embed, RecipeUrlParser.youTubeEmbedVideoId(embed)) }
    }

    @Test
    fun `classifies sources and only videos need server analysis`() {
        assertEquals(RecipeSourceKind.TEN_THOUSAND_RECIPE, RecipeUrlParser.kind("https://m.10000recipe.com/recipe/42"))
        assertFalse(RecipeUrlParser.requiresServerAnalysis("https://www.10000recipe.com/recipe/42"))
        assertTrue(RecipeUrlParser.requiresServerAnalysis("https://youtu.be/AbCdEfGhIjK"))
        assertTrue(RecipeUrlParser.requiresServerAnalysis("hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(RecipeUrlParser.requiresServerAnalysis("https://www.instagram.com/reel/C1a2B3c4D5e/"))
        assertNull(RecipeUrlParser.kind("양파 https://youtu.be/AbCdEfGhIjK"))
    }

    @Test
    fun `names unsupported video platforms in Korean`() {
        assertEquals("인스타그램", RecipeUrlParser.unsupportedVideoPlatform("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=MWQ1"))
        assertEquals("틱톡", RecipeUrlParser.unsupportedVideoPlatform("이거 봐 https://vt.tiktok.com/ZSabc123/"))
        assertEquals("틱톡", RecipeUrlParser.unsupportedVideoPlatform("https://www.tiktok.com/@cook/video/7301234567890123456"))
        assertEquals("네이버TV", RecipeUrlParser.unsupportedVideoPlatform("https://tv.naver.com/v/52277225"))
        assertEquals("네이버TV", RecipeUrlParser.unsupportedVideoPlatform("https://naver.me/5aBcDeFg"))
        assertEquals("페이스북", RecipeUrlParser.unsupportedVideoPlatform("https://m.facebook.com/watch/?v=123456789"))
        assertEquals("페이스북", RecipeUrlParser.unsupportedVideoPlatform("https://fb.watch/abc123/"))
        assertNull(RecipeUrlParser.unsupportedVideoPlatform("https://notinstagram.com/reel/abc"))
        assertNull(RecipeUrlParser.unsupportedVideoPlatform("https://youtu.be/AbCdEfGhIjK"))
        assertNull(RecipeUrlParser.unsupportedVideoPlatform(null))
        assertNull(RecipeUrlParser.extract("https://www.instagram.com/reel/C1a2B3c4D5e/"))
    }

    @Test
    fun `local video pseudo urls round trip and are never taken from shared text`() {
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val url = RecipeUrlParser.localVideoUrl(id)

        assertEquals("hansul-video:$id", url)
        assertEquals(id, RecipeUrlParser.localVideoId(url))
        assertEquals(RecipeSourceKind.LOCAL_VIDEO, RecipeUrlParser.kind(url))
        assertNull(RecipeUrlParser.localVideoId("hansul-video:0F8FAD5B-D9CB-469F-A165-70867728950E"))
        assertNull(RecipeUrlParser.localVideoId("hansul-video:../../shared_prefs"))
        assertNull(RecipeUrlParser.localVideoId("hansul-video:$id/extra"))
        assertNull(RecipeUrlParser.extract(url))
        assertNull(RecipeUrlParser.youTubeVideoId(url))
    }
}
