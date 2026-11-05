package com.hansul.recipe.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hansul.recipe.infrastructure.parser.IngredientTextParser;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class VideoImportPropertiesTest {

    private static final VideoImportProperties DEFAULTS = new VideoImportProperties(null, null, 0, 0, 0, null);

    @Test
    void fillsTheContractDefaultsWhenNothingIsConfigured() {
        assertThat(DEFAULTS.gemini()).isEqualTo(new VideoImportProperties.Gemini(
                "https://generativelanguage.googleapis.com", "", "gemini-3.8-flash", "", "", 65536, 16384,
                Duration.ofSeconds(300), false));
        assertThat(DEFAULTS.youtube()).isEqualTo(
                new VideoImportProperties.YouTube("https://www.googleapis.com", "https://www.youtube.com", ""));
        assertThat(DEFAULTS.maxVideoSeconds()).isEqualTo(1800);
        assertThat(DEFAULTS.dailyLimitPerUser()).isEqualTo(10);
        assertThat(DEFAULTS.workerConcurrency()).isEqualTo(2);
        assertThat(DEFAULTS.upload().directory())
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "hansul-video-uploads").toString());
        assertThat(DEFAULTS.upload().directoryPath()).isEqualTo(Path.of(DEFAULTS.upload().directory()));
        assertThat(DEFAULTS.upload().maxBytes()).isEqualTo(314_572_800L);
        assertThat(DEFAULTS.upload().chunkBytes()).isEqualTo(4_194_304);
        assertThat(DEFAULTS.upload().maxActivePerUser()).isEqualTo(2);
        assertThat(DEFAULTS.upload().ttl()).isEqualTo(Duration.ofHours(6));
        assertThat(DEFAULTS.upload().maxTotalBytes()).isEqualTo(3_221_225_472L);
        assertThat(DEFAULTS.upload().minFreeBytes()).isEqualTo(2_147_483_648L);
        assertThat(DEFAULTS.upload().idleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(DEFAULTS.youtube().hasApiKey()).isFalse();
        assertThat(DEFAULTS.isConfigured()).isFalse();
        assertThat(DEFAULTS.isConfiguredForUploads()).isFalse();
    }

    @Test
    void theApplicationYamlBlockBindsToTheSameDefaults() throws IOException {
        var properties = bind(new MockEnvironment());

        assertThat(properties).isEqualTo(DEFAULTS);
    }

    @Test
    void environmentVariablesConfigureGeminiAndYouTube() throws IOException {
        var properties = bind(new MockEnvironment()
                .withProperty("HANSUL_GEMINI_API_KEY", "test-key")
                .withProperty("HANSUL_GEMINI_API_BASE_URL", "http://localhost:8089/")
                .withProperty("HANSUL_GEMINI_MODEL", "gemini-3.8-pro")
                .withProperty("HANSUL_GEMINI_THINKING_LEVEL", "low")
                .withProperty("HANSUL_GEMINI_MEDIA_RESOLUTION", "medium")
                .withProperty("HANSUL_YOUTUBE_API_KEY", "yt-key")
                .withProperty("HANSUL_VIDEO_UPLOAD_DIR", "/var/lib/hansul/videos")
                .withProperty("HANSUL_GEMINI_PAID_TIER", "true"));

        assertThat(properties.gemini().apiKey()).isEqualTo("test-key");
        assertThat(properties.gemini().apiBaseUrl()).isEqualTo("http://localhost:8089");
        assertThat(properties.gemini().model()).isEqualTo("gemini-3.8-pro");
        assertThat(properties.gemini().thinkingLevel()).isEqualTo("LOW");
        assertThat(properties.gemini().mediaResolution()).isEqualTo("MEDIA_RESOLUTION_MEDIUM");
        assertThat(properties.youtube().hasApiKey()).isTrue();
        assertThat(properties.upload().directory()).isEqualTo("/var/lib/hansul/videos");
        assertThat(properties.gemini().paidTier()).isTrue();
        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.isConfiguredForUploads()).isTrue();
    }

    @Test
    void aKeyAloneAnalysesYouTubeLinksButNotPhoneVideos() throws IOException {
        var properties = bind(new MockEnvironment().withProperty("HANSUL_GEMINI_API_KEY", "test-key"));

        assertThat(properties.gemini().paidTier()).isFalse();
        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.isConfiguredForUploads()).isFalse();
        var paidWithoutKey = new VideoImportProperties.Gemini(null, "", null, null, null, 0, 0, null, true);
        assertThat(new VideoImportProperties(paidWithoutKey, null, 0, 0, 0, null).isConfiguredForUploads()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "key, gemini-3.8-flash, true",
            "key, gemini-3.8-flash-preview_01, true",
            "' ', gemini-3.8-flash, false",
            "key, '', false",
            "key, models/gemini-3.8-flash, false",
            "key, 'gemini 3', false",
            "key, gemini:generateContent, false"
    })
    void isConfiguredOnlyWithAKeyAndAModelThatFitsTheRequestPath(String key, String model, boolean configured) {
        var gemini = new VideoImportProperties.Gemini(null, key, model, null, null, 0, 0, null, true);

        assertThat(new VideoImportProperties(gemini, null, 0, 0, 0, null).isConfigured()).isEqualTo(configured);
    }

    @Test
    void normalizesTuningValuesAndRejectsUnknownOnes() {
        assertThat(gemini("low", "high").thinkingLevel()).isEqualTo("LOW");
        assertThat(gemini("low", "high").mediaResolution()).isEqualTo("MEDIA_RESOLUTION_HIGH");
        assertThat(gemini(null, "media_resolution_medium").mediaResolution()).isEqualTo("MEDIA_RESOLUTION_MEDIUM");
        assertThatThrownBy(() -> gemini(null, "ultra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media-resolution");
        assertThatThrownBy(() -> gemini("low\"}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thinking-level");
    }

    @Test
    void wiresThePipelineFromTheBoundProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class)
                .withPropertyValues(
                        "hansul.video-import.gemini.api-key=test-key",
                        "hansul.video-import.gemini.thinking-level=low",
                        "hansul.video-import.youtube.api-key=yt-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VideoRecipeImportPipeline.class);
                    assertThat(context).getBean(GeminiClient.class).isInstanceOf(GeminiRestClient.class);
                    assertThat(context).getBean(VideoMetadataSource.class).isInstanceOf(YouTubeMetadataClient.class);
                    var properties = context.getBean(VideoImportProperties.class);
                    assertThat(properties.isConfigured()).isTrue();
                    assertThat(properties.gemini().thinkingLevel()).isEqualTo("LOW");
                    assertThat(properties.youtube().hasApiKey()).isTrue();
                });
    }

    @Test
    void anUnknownMediaResolutionStopsTheApplicationFromStarting() {
        new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class)
                .withPropertyValues("hansul.video-import.gemini.media-resolution=ultra")
                .run(context -> assertThat(context).hasFailed());
    }

    private static VideoImportProperties bind(MockEnvironment environment) throws IOException {
        for (var source : new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("hansul.video-import", VideoImportProperties.class).get();
    }

    private static VideoImportProperties.Gemini gemini(String thinkingLevel, String mediaResolution) {
        return new VideoImportProperties.Gemini(null, "key", null, thinkingLevel, mediaResolution, 0, 0, null, false);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VideoImportProperties.class)
    @Import({
            GeminiRestClient.class,
            YouTubeMetadataClient.class,
            VideoTranscriptPass.class,
            VideoRecipeStructuringPass.class,
            VideoRecipeValidator.class,
            GeminiVideoRecipeImportPipeline.class,
            IngredientTextParser.class
    })
    static class Wiring {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
