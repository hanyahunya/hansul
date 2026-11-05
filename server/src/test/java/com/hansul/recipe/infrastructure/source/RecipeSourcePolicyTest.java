package com.hansul.recipe.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecipeSourcePolicyTest {

    private final RecipeSourcePolicy policy = new RecipeSourcePolicy(
            new Hundred00RecipeUrlPolicy(),
            new YouTubeUrlPolicy()
    );

    @Test
    void sends10000recipePagesThroughTheirOwnPolicy() {
        var source = policy.canonicalize("https://m.10000recipe.com/recipe/6944989?tracking=1");

        assertThat(source.provider()).isEqualTo(Hundred00RecipeUrlPolicy.PROVIDER);
        assertThat(source.recipeId()).isEqualTo("6944989");
        assertThat(source.uri().toString()).isEqualTo("https://www.10000recipe.com/recipe/6944989");
    }

    @Test
    void keepsThe10000recipeRulesUnchanged() {
        assertThatThrownBy(() -> policy.canonicalize("http://www.10000recipe.com/recipe/6850292"))
                .isInstanceOf(InvalidRecipeUrlException.class)
                .hasMessage("Only HTTPS recipe URLs are supported");
        assertThatThrownBy(() -> policy.canonicalize("https://www.10000recipe.com/recipe/6850292/extra"))
                .isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> policy.canonicalize("https://blog.10000recipe.com/recipe/6850292"))
                .isInstanceOf(InvalidRecipeUrlException.class)
                .hasMessage("Only www.10000recipe.com and m.10000recipe.com are supported");
    }

    @Test
    void sendsYouTubeLinksThroughTheYouTubePolicy() {
        var source = policy.canonicalize("https://youtu.be/AbCdEfGhIjK?si=share");

        assertThat(source.provider()).isEqualTo(YouTubeUrlPolicy.PROVIDER);
        assertThat(source.recipeId()).isEqualTo("AbCdEfGhIjK");
        assertThat(source.uri().toString()).isEqualTo("https://www.youtube.com/watch?v=AbCdEfGhIjK");
        assertThatThrownBy(() -> policy.canonicalize("https://www.youtube.com/channel/UCabc"))
                .isInstanceOf(InvalidRecipeUrlException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.instagram.com/reel/C1a2b3c4d5e/, Instagram",
            "https://instagram.com/p/C1a2b3c4d5e, Instagram",
            "https://www.tiktok.com/@cook/video/7312345678901234567, TikTok",
            "https://vt.tiktok.com/ZSabc123/, TikTok",
            "https://tv.naver.com/v/52277225, Naver TV",
            "https://m.tv.naver.com/v/52277225, Naver TV",
            "https://naver.me/xAbCdEf, Naver TV",
            "https://www.facebook.com/watch/?v=1234567890, Facebook",
            "https://fb.watch/abcDEF123/, Facebook",
            "http://m.facebook.com/reel/1234567890, Facebook"
    })
    void rejectsVideoPlatformsThatCannotBeAnalysedFromALink(String url, String platform) {
        assertThatThrownBy(() -> policy.canonicalize(url))
                .isInstanceOfSatisfying(UnsupportedVideoPlatformException.class, exception ->
                        assertThat(exception.platform()).isEqualTo(platform));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/recipe/123",
            "https://notinstagram.com/reel/abc",
            "https://music.youtube.com/watch?v=AbCdEfGhIjK",
            "https://blog.naver.com/cook/123"
    })
    void rejectsOtherSitesAsInvalidRecipeUrls(String url) {
        assertThatThrownBy(() -> policy.canonicalize(url))
                .isInstanceOf(InvalidRecipeUrlException.class)
                .hasMessage("Only 10000recipe recipe pages and YouTube videos are supported");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "recipe/123", "https://", "https://www.youtube.com/watch?v=a b"})
    void rejectsBlankAndMalformedInput(String url) {
        assertThatThrownBy(() -> policy.canonicalize(url)).isInstanceOf(InvalidRecipeUrlException.class);
    }
}
