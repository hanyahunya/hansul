package com.hansul.recipe.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeUrlPolicyTest {

    private static final String CANONICAL = "https://www.youtube.com/watch?v=AbCdEfGhIjK";

    private final YouTubeUrlPolicy policy = new YouTubeUrlPolicy();
    private final RecipeSourcePolicy sourcePolicy = new RecipeSourcePolicy(new Hundred00RecipeUrlPolicy(), policy);

    // The Android app's RecipeUrlParserTest holds the same table, so both sides emit byte-identical canonical URLs
    // ("-" = rejected). The app also accepts each link over http and upgrades it; the server takes https only.
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
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
            """)
    void emitsTheSameCanonicalUrlsAsTheAndroidApp(String link, String canonical) {
        if (canonical.equals("-")) {
            assertThatThrownBy(() -> policy.canonicalize(link)).isInstanceOf(InvalidRecipeUrlException.class);
            assertThatThrownBy(() -> sourcePolicy.canonicalize(link)).isInstanceOf(InvalidRecipeUrlException.class);
            return;
        }
        assertThat(policy.canonicalize(link).uri().toString()).isEqualTo(canonical);
        assertThat(sourcePolicy.canonicalize(link).uri().toString()).isEqualTo(canonical);
        // The app sends the canonical URL itself, which has to map to itself.
        assertThat(sourcePolicy.canonicalize(canonical).uri().toString()).isEqualTo(canonical);
        assertThatThrownBy(() -> sourcePolicy.canonicalize(link.replaceFirst("(?i)^https:", "http:")))
                .isInstanceOf(InvalidRecipeUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://youtube.com/watch?v=AbCdEfGhIjK",
            "https://m.youtube.com/watch?v=AbCdEfGhIjK&feature=share",
            "https://www.youtube.com/watch?feature=share&v=AbCdEfGhIjK&t=30s&list=PL0123456789&pp=ygUE",
            "https://www.youtube.com/watch?v=AbCdEfGhIjK&si=Xy12_ab-CD",
            "https://www.youtube.com/watch/?v=AbCdEfGhIjK",
            "https://www.youtube.com/shorts/AbCdEfGhIjK",
            "https://youtube.com/shorts/AbCdEfGhIjK/?feature=share",
            "https://m.youtube.com/shorts/AbCdEfGhIjK",
            "https://www.youtube.com/live/AbCdEfGhIjK?si=Xy12ab",
            "https://www.youtube.com/live/AbCdEfGhIjK/",
            "https://www.youtube.com/embed/AbCdEfGhIjK?start=30&rel=0",
            "https://www.youtube-nocookie.com/embed/AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK?si=Xy12ab&t=15",
            "https://youtu.be/AbCdEfGhIjK/",
            "https://WWW.YouTube.com/watch?v=AbCdEfGhIjK",
            "  https://www.youtube.com/watch?v=AbCdEfGhIjK#t=1m  "
    })
    void acceptsEveryKnownVideoShapeAndEmitsOneCanonicalWatchUrl(String url) {
        var canonical = policy.canonicalize(url);

        assertThat(canonical.provider()).isEqualTo("youtube");
        assertThat(canonical.recipeId()).isEqualTo("AbCdEfGhIjK");
        assertThat(canonical.uri().toString()).isEqualTo(CANONICAL);
    }

    @Test
    void keepsTheCaseSensitiveIdWithUnderscoreAndDashUntouched() {
        var canonical = policy.canonicalize("https://youtu.be/a_b-C1d2E3f");

        assertThat(canonical.recipeId()).isEqualTo("a_b-C1d2E3f");
        assertThat(canonical.uri().toString()).isEqualTo("https://www.youtube.com/watch?v=a_b-C1d2E3f");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=AbCdEfGhIj",
            "https://www.youtube.com/watch?v=AbCdEfGhIjKL",
            "https://youtu.be/AbCdEfGhIj",
            "https://www.youtube.com/shorts/AbCdEfGhIjKLM",
            "https://www.youtube.com/watch?v=AbCdEfGh%49jK",
            "https://www.youtube.com/watch?v=AbCdEfGh.jK",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch?list=PL0123456789",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            "https://www.youtube.com/@cookingchannel",
            "https://www.youtube.com/playlist?list=PL0123456789",
            "https://www.youtube.com/embed/videoseries?list=PL0123456789",
            "https://www.youtube.com/embed/live_stream?channel=UCabc",
            "https://www.youtube.com/shorts/AbCdEfGhIjK/extra",
            "https://www.youtube.com/",
            "https://youtu.be/",
            "http://www.youtube.com/watch?v=AbCdEfGhIjK",
            "http://youtu.be/AbCdEfGhIjK",
            "https://user@www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://user:secret@youtu.be/AbCdEfGhIjK",
            "https://www.youtube.com:443/watch?v=AbCdEfGhIjK",
            "https://youtu.be:8443/AbCdEfGhIjK",
            "https://music.youtube.com/watch?v=AbCdEfGhIjK",
            "https://www.youtube.com.evil.test/watch?v=AbCdEfGhIjK",
            "https://www.youtube.com/watch?v=AbCdEfGhIjK withspace"
    })
    void rejectsEverythingThatIsNotAnExactVideoUrl(String url) {
        assertThatThrownBy(() -> policy.canonicalize(url)).isInstanceOf(InvalidRecipeUrlException.class);
    }

    // Both words are 11 characters of the id alphabet, so only this rule keeps them from passing as video ids.
    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=videoseries",
            "https://m.youtube.com/watch?v=live_stream&list=PL0123456789",
            "https://youtu.be/videoseries",
            "https://youtu.be/live_stream?si=Xy12ab",
            "https://www.youtube.com/shorts/videoseries",
            "https://www.youtube.com/shorts/live_stream",
            "https://www.youtube.com/live/videoseries",
            "https://www.youtube.com/live/live_stream",
            "https://www.youtube.com/embed/videoseries",
            "https://www.youtube-nocookie.com/embed/live_stream"
    })
    void rejectsThePlaylistAndChannelLiveWordsInEveryShape(String url) {
        assertThatThrownBy(() -> policy.canonicalize(url)).isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> sourcePolicy.canonicalize(url)).isInstanceOf(InvalidRecipeUrlException.class);
        assertThat(YouTubeUrlPolicy.isValidVideoId("videoseries")).isFalse();
        assertThat(YouTubeUrlPolicy.isValidVideoId("live_stream")).isFalse();
        assertThatThrownBy(() -> YouTubeUrlPolicy.watchUrl("videoseries")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void requiresAUrl(String url) {
        assertThatThrownBy(() -> policy.canonicalize(url)).isInstanceOf(InvalidRecipeUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/embed/AbCdEfGhIjK?rel=0&autoplay=1",
            "https://www.youtube.com/embed/AbCdEfGhIjK/",
            "http://www.youtube.com/embed/AbCdEfGhIjK",
            "//www.youtube.com/embed/AbCdEfGhIjK",
            "https://www.youtube-nocookie.com/embed/AbCdEfGhIjK"
    })
    void readsTheVideoIdOfAnEmbeddedPlayer(String embedUrl) {
        assertThat(YouTubeUrlPolicy.videoIdFromEmbed(embedUrl)).contains("AbCdEfGhIjK");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "about:blank",
            "https://www.youtube.com/watch?v=AbCdEfGhIjK",
            "https://youtu.be/AbCdEfGhIjK",
            "https://www.youtube.com/embed/videoseries?list=PL0123456789",
            "https://www.youtube.com/embed/AbCdEfGhIj",
            "https://www.youtube.com:8443/embed/AbCdEfGhIjK",
            "https://tv.naver.com/embed/52277225",
            "https://www.10000recipe.com/embed/AbCdEfGhIjK",
            "ftp://www.youtube.com/embed/AbCdEfGhIjK"
    })
    void ignoresEmbedsThatAreNotASingleYouTubeVideo(String embedUrl) {
        assertThat(YouTubeUrlPolicy.videoIdFromEmbed(embedUrl)).isEmpty();
    }

    @Test
    void buildsCanonicalSourcesForKnownVideoIds() {
        var source = YouTubeUrlPolicy.forVideoId("AbCdEfGhIjK");

        assertThat(source).isEqualTo(policy.canonicalize("https://youtu.be/AbCdEfGhIjK"));
        assertThat(YouTubeUrlPolicy.watchUrl("AbCdEfGhIjK")).isEqualTo(CANONICAL);
        assertThatThrownBy(() -> YouTubeUrlPolicy.watchUrl("not-an-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YouTubeUrlPolicy.watchUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
