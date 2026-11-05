package com.hansul.app.data.local

import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoProvider
import org.junit.Assert.*
import org.junit.Test

class LocalRecipeParserTest {
    private val parser = LocalRecipeParser()

    @Test fun nestedJsonLdPreservesExactStepPhotoMatchingAndCanonicalSource() {
        val recipe = parser.parse("""
            <script type="application/ld+json">{"@graph":[{"@type":["Thing","Recipe"],"name":"양파 요리",
            "recipeYield":"2인분","recipeIngredient":["양파 1개","물 550ml"],"recipeInstructions":[
            {"@type":"HowToStep","text":"양파를 얇게 썰어주세요."},
            {"@type":"HowToStep","text":"약 5분 끓여주세요."}]}]}</script>
            <div class="view_step_cont" id="stepDiv7"><div id="stepdescr7">양파를 얇게 썰어주세요.</div>
            <div id="stepimg7"><img src="https://recipe1.ezmember.co.kr/onion.jpg"></div></div>
            <div class="view_step_cont" id="stepDiv11"><div id="stepdescr11">약 5분 끓여주세요.</div>
            <div id="stepimg11"><img src="https://recipe1.ezmember.co.kr/pot.jpg"></div></div>
        """.trimIndent(), "https://m.10000recipe.com/recipe/123456?srsltid=tracking")
        assertEquals("https://www.10000recipe.com/recipe/123456", recipe.sourceUrl)
        assertTrue(recipe.id.startsWith("local-recipe-"))
        assertEquals(listOf(7, 11), recipe.steps.map { it.number })
        assertEquals("https://recipe1.ezmember.co.kr/onion.jpg", recipe.steps[0].imageUrl)
        assertEquals("550ml", recipe.ingredients[1].quantityText)
    }

    @Test fun doesNotAttachUnrelatedDomPhotoOrUnsafeImage() {
        val recipe = parser.parse("""
            <script type="application/ld+json">{"@type":"Recipe","name":"요리","recipeInstructions":[
            {"text":"양파를 잘라주세요.","image":"http://127.0.0.1/private.jpg"} ]}</script>
            <div class="view_step_cont" id="stepDiv1"><div id="stepdescr1">완성된 접시 사진입니다.</div>
            <div id="stepimg1"><img src="https://recipe1.ezmember.co.kr/finished.jpg"></div></div>
        """.trimIndent(), "https://www.10000recipe.com/recipe/999")
        assertNull(recipe.steps.single().imageUrl)
        assertNull(LocalRecipeParser.verifiedImage("https://recipe1.ezmember.co.kr.attacker.example/a.jpg", recipe.sourceUrl))
    }

    @Test fun embeddedRecipeVideoIsReadFromOrgSrcLikeTheServer() {
        fun page(iframe: String) = parser.parse("""
            <div class="view2_summary"><h3>영상 요리</h3></div>$iframe
            <div class="view_step_cont" id="stepDiv1"><div id="stepdescr1">재료를 준비해주세요.</div></div>
        """.trimIndent(), "https://www.10000recipe.com/recipe/42")

        assertEquals(
            RecipeVideo(VideoProvider.YOUTUBE, "AbCdEfGhIjK", "https://www.youtube.com/watch?v=AbCdEfGhIjK", embeddable = true),
            page("""<iframe id="ifrmRecipeVideo" src="about:blank" org_src="//www.youtube.com/embed/AbCdEfGhIjK?autoplay=1"></iframe>""").video,
        )
        assertEquals(
            RecipeVideo(VideoProvider.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225"),
            page("""<iframe id="ifrmRecipeVideo" org_src="" src="https://tv.naver.com/embed/52277225?autoPlay=true"></iframe>""").video,
        )
        assertNull(page("""<iframe id="ifrmRecipeVideo" src="about:blank"></iframe>""").video)
        assertNull(page("""<iframe id="ifrmRecipeVideo" org_src="https://example.com/embed/AbCdEfGhIjK"></iframe>""").video)
        assertNull(page("""<iframe id="other" org_src="https://www.youtube.com/embed/AbCdEfGhIjK"></iframe>""").video)
    }

    @Test fun embeddedRecipeVideoFollowsTheServerFallbacksAndRejections() {
        fun video(iframe: String) = parser.parse("""
            <div class="view2_summary"><h3>영상 요리</h3></div>$iframe
            <div class="view_step_cont" id="stepDiv1"><div id="stepdescr1">재료를 준비해주세요.</div></div>
        """.trimIndent(), "https://www.10000recipe.com/recipe/42").video

        // A blank player address in org_src falls back to src, exactly like the server parser.
        assertEquals("AbCdEfGhIjK", video("""<iframe id="ifrmRecipeVideo" org_src="about:blank"
            src="https://www.youtube-nocookie.com/embed/AbCdEfGhIjK"></iframe>""")?.videoId)
        assertEquals(
            RecipeVideo(VideoProvider.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225"),
            video("""<iframe id="ifrmRecipeVideo" org_src="http://tv.naver.com/embed/52277225/"></iframe>"""),
        )
        // Only an embedded player names one video; watch pages, short links and playlists do not.
        listOf(
            "https://www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK",
            "https://www.youtube.com/embed/videoseries?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
            "/embed/AbCdEfGhIjK",
            "https://tv.naver.com/v/52277225",
            "https://tv.naver.com:8443/embed/52277225",
        ).forEach { address ->
            assertNull(address, video("""<iframe id="ifrmRecipeVideo" org_src="$address"></iframe>"""))
        }
    }

    @Test fun savedRecipePagesYieldTheVideoTheirPlayerEmbeds() {
        fun page(id: String) = parser.parse(fixture("r$id.html"), "https://www.10000recipe.com/recipe/$id")

        val youTube = page("7083038")
        assertEquals(
            RecipeVideo(VideoProvider.YOUTUBE, "Q6WQQrVwZDA", "https://www.youtube.com/watch?v=Q6WQQrVwZDA", embeddable = true),
            youTube.video,
        )
        assertEquals("은은한 얼그레이 향에 파삭파삭 고급스러운 제니쿠키, 선물해보세요", youTube.title)
        assertEquals(listOf(1, 2), youTube.steps.map { it.number })

        // Naver TV refuses to play inside other apps, so its page opens outside Hansul.
        assertEquals(RecipeVideo(VideoProvider.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225"), page("7029097").video)

        val withoutVideo = page("6850292")
        assertNull(withoutVideo.video)
        assertEquals(listOf(2, 3), withoutVideo.steps.map { it.number })
    }

    @Test fun malformedJsonLdFallsBackToSourceDom() {
        val recipe = parser.parse("""
            <script type="application/ld+json">broken</script><div class="view2_summary"><h3>수동 원문 요리</h3></div>
            <div class="view_step_cont" id="stepDiv3"><div id="stepdescr3">재료를 준비해주세요.</div></div>
        """.trimIndent(), "https://www.10000recipe.com/recipe/42")
        assertEquals("수동 원문 요리", recipe.title)
        assertEquals(3, recipe.steps.single().number)
    }

    /** Trimmed copies of real 10000recipe pages under src/test/resources/10000recipe. */
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/10000recipe/$name")) { "Missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
