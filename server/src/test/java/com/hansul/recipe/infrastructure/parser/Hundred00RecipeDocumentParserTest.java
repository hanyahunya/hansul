package com.hansul.recipe.infrastructure.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.domain.ParsedVideo;
import com.hansul.recipe.domain.SourceEvidence.SourceType;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class Hundred00RecipeDocumentParserTest {

    private final Hundred00RecipeDocumentParser parser = new Hundred00RecipeDocumentParser(
            JsonMapper.builder().build(),
            new IngredientTextParser()
    );
    private final Hundred00RecipeUrlPolicy urlPolicy = new Hundred00RecipeUrlPolicy();

    @Test
    void parsesRecipeFromJsonLdGraphAndUsesDomOnlyForAMissingStepImage() {
        var html = """
                <html><head>
                  <meta property="og:image" content="https://recipe1.ezmember.co.kr/fallback.jpg">
                  <script type="application/ld+json">
                  {
                    "@context":"https://schema.org",
                    "@graph":[
                      {"@type":"WebSite","name":"Example"},
                      {
                        "@type":["Thing","Recipe"],
                        "name":"테스트 찌개",
                        "description":"  따뜻한   찌개  ",
                        "recipeYield":"2 servings",
                        "totalTime":"PT30M",
                        "image":["https://recipe1.ezmember.co.kr/main.jpg"],
                        "recipeIngredient":[
                          "순두부찌개용 350g (한봉지)",
                          "대파 1/2개",
                          "소금​ 약간"
                        ],
                        "recipeInstructions":[
                          {
                            "@type":"HowToSection",
                            "name":"준비",
                            "itemListElement":[
                              {"@type":"HowToStep","text":"물을 올립니다."},
                              {"@type":"HowToStep","text":"재료를 넣습니다.","image":{"url":"https://recipe1.ezmember.co.kr/step2.jpg"}}
                            ]
                          }
                        ]
                      }
                    ]
                  }
                  </script>
                </head><body>
                  <div id="stepDiv1" class="view_step_cont">
                    <div id="stepdescr1">DOM 문장은 JSON-LD를 덮어쓰면 안 됩니다.</div>
                    <div id="stepimg1"><img src="https://recipe1.ezmember.co.kr/step1.jpg"></div>
                  </div>
                </body></html>
                """;

        var result = parser.parse(
                html,
                urlPolicy.canonicalize("https://www.10000recipe.com/recipe/12345")
        );

        assertThat(result.title()).isEqualTo("테스트 찌개");
        assertThat(result.baseServings()).isEqualByComparingTo("2");
        assertThat(result.baseServingsText()).isEqualTo("2 servings");
        assertThat(result.ingredients()).hasSize(3);
        assertThat(result.ingredients().get(0).name()).isEqualTo("순두부찌개용");
        assertThat(result.ingredients().get(0).quantityText()).isEqualTo("350g (한봉지)");
        assertThat(result.ingredients().get(2).name()).isEqualTo("소금");
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).text()).isEqualTo("물을 올립니다.");
        assertThat(result.steps().get(0).imageUrl())
                .isEqualTo("https://recipe1.ezmember.co.kr/step1.jpg");
        assertThat(result.steps().get(0).evidence().type()).isEqualTo(SourceType.JSON_LD);
        assertThat(result.steps().get(1).imageUrl())
                .isEqualTo("https://recipe1.ezmember.co.kr/step2.jpg");
        assertThat(result.sourceFormat()).isEqualTo("JSON_LD_WITH_DOM");
    }

    @Test
    void fallsBackToKnownRecipeDomWithoutDependingOnARecipeId() {
        var html = """
                <html><head><meta property="og:image" content="https://recipe1.ezmember.co.kr/main.jpg"></head>
                <body>
                  <div class="view2_summary"><h3>DOM 레시피</h3></div>
                  <span class="view2_summary_info1">4인분</span>
                  <span class="view2_summary_info2">20분 이내</span>
                  <div id="divConfirmedMaterialArea">
                    <li><div class="ingre_list_name">양파</div><span class="ingre_list_ea">1/2개</span></li>
                  </div>
                  <div id="stepDiv1" class="view_step_cont">
                    <div id="stepdescr1">양파를 썹니다.</div>
                    <div id="stepimg1"><img src="https://recipe1.ezmember.co.kr/one.jpg"></div>
                  </div>
                </body></html>
                """;

        var result = parser.parse(
                html,
                urlPolicy.canonicalize("https://m.10000recipe.com/recipe/9999999?tracking=1")
        );

        assertThat(result.title()).isEqualTo("DOM 레시피");
        assertThat(result.baseServings()).isEqualByComparingTo("4");
        assertThat(result.ingredients()).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.name()).isEqualTo("양파");
            assertThat(ingredient.quantityText()).isEqualTo("1/2개");
            assertThat(ingredient.evidence().type()).isEqualTo(SourceType.DOM);
        });
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.number()).isEqualTo(1);
            assertThat(step.text()).isEqualTo("양파를 썹니다.");
            assertThat(step.imageUrl()).isEqualTo("https://recipe1.ezmember.co.kr/one.jpg");
        });
        assertThat(result.sourceFormat()).isEqualTo("DOM");
        assertThat(result.video()).isNull();
    }

    @Test
    void readsTheLazyYouTubeEmbedFromOrgSrcWithoutGivingStepsVideoTimes() {
        var result = parse(pageWithVideoFrame("""
                src="about:blank" org_src="https://www.youtube.com/embed/AbCdEfGhIjK?rel=0&amp;autoplay=1"
                """));

        assertThat(Hundred00RecipeDocumentParser.PARSER_VERSION).isEqualTo("10000recipe-v2");
        assertThat(result.video()).isNotNull();
        assertThat(result.video().provider()).isEqualTo("YOUTUBE");
        assertThat(result.video().videoId()).isEqualTo("AbCdEfGhIjK");
        assertThat(result.video().pageUrl()).isEqualTo("https://www.youtube.com/watch?v=AbCdEfGhIjK");
        assertThat(result.video().durationSeconds()).isNull();
        assertThat(result.video().channel()).isNull();
        assertThat(result.video().embeddable()).isTrue();
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.videoStartSeconds()).isNull();
            assertThat(step.videoEndSeconds()).isNull();
            assertThat(step.keyFrameSeconds()).isNull();
        });
    }

    @Test
    void readsANaverTvEmbedAsAnExternalOnlyVideo() {
        var result = parse(pageWithVideoFrame("""
                src="about:blank" org_src="https://tv.naver.com/embed/52277225?autoPlay=true"
                """));

        assertThat(result.video()).isNotNull();
        assertThat(result.video().provider()).isEqualTo("NAVER_TV");
        assertThat(result.video().videoId()).isEqualTo("52277225");
        assertThat(result.video().pageUrl()).isEqualTo("https://tv.naver.com/v/52277225");
        assertThat(result.video().embeddable()).isFalse();
    }

    @Test
    void fallsBackToARealSrcWhenOrgSrcIsMissing() {
        var result = parse(pageWithVideoFrame("""
                src="//www.youtube-nocookie.com/embed/a_b-C1d2E3f"
                """));

        assertThat(result.video()).isNotNull();
        assertThat(result.video().provider()).isEqualTo("YOUTUBE");
        assertThat(result.video().videoId()).isEqualTo("a_b-C1d2E3f");
    }

    @Test
    void ignoresBlankAndUnknownEmbeds() {
        assertThat(parse(pageWithVideoFrame("src=\"about:blank\" org_src=\"\"")).video()).isNull();
        assertThat(parse(pageWithVideoFrame("src=\"about:blank\"")).video()).isNull();
        assertThat(parse(pageWithVideoFrame("""
                org_src="https://player.vimeo.com/video/123456"
                """)).video()).isNull();
        assertThat(parse(pageWithVideoFrame("""
                org_src="https://www.youtube.com/embed/videoseries?list=PL0123456789"
                """)).video()).isNull();
    }

    // Trimmed copies of real pages: the JSON-LD, the lazy player block and two step divs as the site serves them.
    @Test
    void readsTheYouTubeVideoOfARealRecipePage() throws IOException {
        var result = parser.parse(fixture("recipe-7083038-youtube.html"),
                urlPolicy.canonicalize("https://www.10000recipe.com/recipe/7083038"));

        assertThat(result.title()).isEqualTo("은은한 얼그레이 향에 파삭파삭 고급스러운 제니쿠키, 선물해보세요");
        assertThat(result.video()).isEqualTo(new ParsedVideo(
                ParsedVideo.YOUTUBE, "Q6WQQrVwZDA", "https://www.youtube.com/watch?v=Q6WQQrVwZDA", null, null, true));
        assertThat(result.steps()).hasSize(2).allSatisfy(step -> {
            assertThat(step.imageUrl()).startsWith("https://recipe1.ezmember.co.kr/");
            assertThat(step.videoStartSeconds()).isNull();
        });
    }

    @Test
    void readsTheNaverTvVideoOfARealRecipePage() throws IOException {
        var result = parser.parse(fixture("recipe-7029097-naver-tv.html"),
                urlPolicy.canonicalize("https://www.10000recipe.com/recipe/7029097"));

        assertThat(result.title()).isEqualTo("여름반찬 아삭오이고추 만능된장무침 레시피");
        assertThat(result.video()).isEqualTo(new ParsedVideo(
                ParsedVideo.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225", null, null, false));
        assertThat(result.steps()).hasSize(2);
    }

    @Test
    void aRealRecipePageWithoutAPlayerHasNoVideoAlthoughItsScriptNamesOne() throws IOException {
        var html = fixture("recipe-6850292-no-video.html");
        var result = parser.parse(html, urlPolicy.canonicalize("https://www.10000recipe.com/recipe/6850292"));

        assertThat(html).contains("$(\"#ifrmRecipeVideo\")");
        assertThat(result.title()).isEqualTo("바지락순두부찌개,순두부찌개 양념장");
        assertThat(result.video()).isNull();
        assertThat(result.baseServings()).isEqualByComparingTo("2");
        assertThat(result.steps()).hasSize(2);
    }

    private ParsedRecipe parse(String html) {
        return parser.parse(html, urlPolicy.canonicalize("https://www.10000recipe.com/recipe/7000001"));
    }

    private static String fixture(String name) throws IOException {
        try (var input = Hundred00RecipeDocumentParserTest.class.getResourceAsStream("/10000recipe/" + name)) {
            return new String(Objects.requireNonNull(input, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String pageWithVideoFrame(String iframeAttributes) {
        return """
                <html><body>
                  <div class="view2_summary"><h3>영상 레시피</h3></div>
                  <div class="view_step">
                    <iframe id="ifrmRecipeVideo" name="ifrmRecipeVideo" width="100%%" %s allowfullscreen></iframe>
                  </div>
                  <div id="stepDiv1" class="view_step_cont">
                    <div id="stepdescr1">양파를 썹니다.</div>
                  </div>
                </body></html>
                """.formatted(iframeAttributes.strip());
    }
}
