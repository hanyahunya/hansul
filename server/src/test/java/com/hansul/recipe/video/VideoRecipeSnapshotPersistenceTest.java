package com.hansul.recipe.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.infrastructure.parser.IngredientTextParser;
import com.hansul.recipe.infrastructure.persistence.RecipeIngredientEntity;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotEntity;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.persistence.RecipeStepEntity;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** What the pipeline writes must fit the recipe tables (lengths, NOT NULLs) on the production database. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
class VideoRecipeSnapshotPersistenceTest {

    private static final UUID UPLOAD_ID = UUID.fromString("0b9c5ae4-1f0f-4c39-9d9c-0f7d2b2a1e10");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private RecipeSnapshotRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void storesAYouTubeRecipeWithItsStepSegments() {
        var gemini = new FakeGeminiClient().answer(VideoFixtures.TRANSCRIPT_JSON).answer(VideoFixtures.RECIPE_JSON);
        var result = pipeline(gemini).importYouTube(VideoFixtures.VIDEO_ID, null);

        var stored = saveAndReload(YouTubeUrlPolicy.forVideoId(VideoFixtures.VIDEO_ID), result, null);

        assertThat(stored.getSourceFormat()).isEqualTo("VIDEO_AI_TRANSCRIPT");
        assertThat(stored.getOwnerSubject()).isNull();
        assertThat(stored.getVideoProvider()).isEqualTo("YOUTUBE");
        assertThat(stored.getVideoPageUrl()).isEqualTo(VideoFixtures.WATCH_URL);
        assertThat(stored.getVideoDurationSeconds()).isEqualTo(95);
        assertThat(stored.getVideoChannel()).isEqualTo("한술키친");
        assertThat(stored.getVideoEmbeddable()).isTrue();
        assertThat(stored.getBaseServings()).isEqualByComparingTo("2");
        assertThat(stored.getSteps()).extracting(RecipeStepEntity::getStepNumber, RecipeStepEntity::getSourceLocator,
                        RecipeStepEntity::getVideoStartSeconds, RecipeStepEntity::getVideoEndSeconds,
                        RecipeStepEntity::getKeyFrameSeconds)
                .containsExactly(
                        tuple(1, "segments:s2@6-15", 6, 15, 11),
                        tuple(2, "segments:s3@15-28", 15, 28, 22),
                        tuple(3, "segments:s4@28-45", 28, 45, 40),
                        tuple(4, "segments:s5@45-58", 45, 58, 50),
                        tuple(5, "segments:s6@58-76", 58, 76, 66),
                        tuple(6, "segments:s7@75-90", 75, 90, 85));
        assertThat(stored.getIngredients()).extracting(RecipeIngredientEntity::getSourceType,
                        RecipeIngredientEntity::getSourceLocator)
                .contains(tuple("VIDEO_DESCRIPTION", "description:d3"), tuple("VIDEO_SEGMENT", "segment:s3"));
        assertThat(stored.getWarnings()).hasSize(1);
    }

    @Test
    void storesAnUploadRecipeForItsOwner() {
        var gemini = new FakeGeminiClient()
                .answer(VideoFixtures.TRANSCRIPT_JSON)
                .answer(VideoFixtures.UPLOAD_RECIPE_JSON);
        var result = pipeline(gemini).importUpload(UPLOAD_ID, Path.of("video"), "video/mp4", 612, null);

        var stored = saveAndReload(CanonicalRecipeUrl.forUpload(UPLOAD_ID), result, "owner-subject");

        assertThat(stored.getSourceUrl()).isEqualTo("hansul-upload:" + UPLOAD_ID);
        assertThat(stored.getOwnerSubject()).isEqualTo("owner-subject");
        assertThat(stored.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(stored.getVideoId()).isNull();
        assertThat(stored.getVideoDurationSeconds()).isEqualTo(612);
        assertThat(stored.getVideoEmbeddable()).isFalse();
        assertThat(stored.getPrimaryImageUrl()).isNull();
        assertThat(stored.getSteps()).hasSize(3);
    }

    @Test
    void storesARecipeAtEveryLengthLimit() {
        var segments = new ArrayList<Map<String, Object>>();
        for (var index = 0; index < 800; index++) {
            var speech = "양파를 볶아요 " + "가".repeat(590);
            var screenText = index == 0 ? "1" : "";
            segments.add(Map.of("startSeconds", index * 2, "endSeconds", index * 2 + 2,
                    "speech", index == 0 ? "나".repeat(100) + " " + speech : speech, "screenText", screenText));
        }
        var everyOtherSegment = IntStream.range(0, 400).mapToObj(index -> "s" + (index * 2 + 1)).toList();
        var steps = IntStream.range(0, 60).mapToObj(index -> Map.<String, Object>of(
                "text", "양파를 볶아주세요 " + "다".repeat(280),
                "segmentIds", everyOtherSegment,
                "startSeconds", 0,
                "endSeconds", 1600,
                "keyFrameSeconds", 800,
                "quote", "양파를 볶아요")).toList();
        var ingredients = IntStream.range(0, 80).mapToObj(index -> Map.<String, Object>of(
                "name", "나".repeat(99) + (char) ('가' + index),
                "quantityText", "1" + "개".repeat(99),
                "ref", "s1",
                "quote", "나".repeat(100))).toList();
        var gemini = new FakeGeminiClient()
                .answer(mapper.writeValueAsString(Map.of("isCookingVideo", true, "language", "ko", "segments", segments)))
                .answer(mapper.writeValueAsString(Map.of(
                        "title", "아주 긴 제목 ".repeat(40),
                        "servings", Map.of("text", "", "source", "NONE", "ref", "", "quote", ""),
                        "ingredients", ingredients,
                        "steps", steps)))
                .answer("{\"title\": \"\", \"steps\": []}");
        var result = pipeline(gemini).importYouTube(VideoFixtures.VIDEO_ID, null);

        var stored = saveAndReload(YouTubeUrlPolicy.forVideoId(VideoFixtures.VIDEO_ID), result, null);

        assertThat(stored.getTitle()).hasSizeLessThanOrEqualTo(200);
        assertThat(stored.getSteps()).hasSize(60).allSatisfy(step -> {
            assertThat(step.getSourceLocator()).isEqualTo("segments:s1-s799@0-1600");
            assertThat(step.getRawText()).hasSizeLessThanOrEqualTo(1000);
            assertThat(step.getNormalizedText()).hasSizeLessThanOrEqualTo(300);
        });
        assertThat(stored.getIngredients()).hasSize(80).allSatisfy(ingredient -> {
            assertThat(ingredient.getName()).hasSize(100);
            assertThat(ingredient.getQuantityText()).hasSize(100);
            assertThat(ingredient.getNormalizedUnit()).hasSize(99);
        });
    }

    private RecipeSnapshotEntity saveAndReload(CanonicalRecipeUrl source, VideoImportResult result, String owner) {
        ParsedRecipe recipe = result.recipe();
        var hash = Integer.toHexString(recipe.hashCode()) + "0".repeat(56);
        var entity = RecipeSnapshotEntity.create(source, recipe, result.parserVersion(), hash, Instant.now(), owner);
        repository.saveAndFlush(entity);
        entityManager.clear();
        return repository.findById(entity.getId()).orElseThrow();
    }

    private VideoRecipeImportPipeline pipeline(FakeGeminiClient gemini) {
        var properties = VideoFixtures.properties();
        return new GeminiVideoRecipeImportPipeline(
                gemini,
                videoId -> VideoFixtures.metadata(),
                new VideoTranscriptPass(gemini, properties),
                new VideoRecipeStructuringPass(gemini, mapper, properties),
                new VideoRecipeValidator(new IngredientTextParser()),
                mapper,
                properties
        );
    }
}
