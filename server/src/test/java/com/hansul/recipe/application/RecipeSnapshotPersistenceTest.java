package com.hansul.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.auth.api.AuthenticatedUser;
import com.hansul.auth.api.AuthenticatedUserId;
import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.infrastructure.parser.Hundred00RecipeDocumentParser;
import com.hansul.recipe.infrastructure.parser.IngredientTextParser;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.persistence.RecipeStepEntity;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Flyway V1..V5 on the production PostgreSQL image, Hibernate validating every mapping against it. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PostgresTestcontainersConfiguration.class,
        RecipeSnapshotPersistenceTest.ServiceConfiguration.class
})
class RecipeSnapshotPersistenceTest {

    @Autowired
    private RecipeImportService service;

    @Autowired
    private RecipeSnapshotRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SwitchableCurrentUser currentUser;

    @AfterEach
    void signOut() {
        currentUser.signIn(null);
    }

    @Test
    void migratesThroughV5IncludingTheJobAndUploadTables() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class
        )).containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name IN ('video_upload', 'recipe_import_job')
                ORDER BY table_name
                """, String.class)).containsExactly("recipe_import_job", "video_upload");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = 'ix_recipe_snapshot_video_cache'
                """, String.class)).containsExactly("ix_recipe_snapshot_video_cache");
    }

    @Test
    void roundTripsAPublicYouTubeRecipeWithTheVideoSegmentOfEachStep() {
        var id = service.saveVideoRecipe(
                YouTubeUrlPolicy.forVideoId(VideoRecipeFixtures.VIDEO_ID),
                VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."),
                "youtube-gemini-v1",
                null
        );
        entityManager.flush();
        entityManager.clear();

        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getOwnerSubject()).isNull();
        assertThat(stored.getSourceProvider()).isEqualTo("youtube");
        assertThat(stored.getSourceRecipeId()).isEqualTo(VideoRecipeFixtures.VIDEO_ID);
        assertThat(stored.getSourceUrl()).isEqualTo(VideoRecipeFixtures.WATCH_URL);
        assertThat(stored.getSourceFormat()).isEqualTo("VIDEO_AI_TRANSCRIPT");
        assertThat(stored.getVideoProvider()).isEqualTo("YOUTUBE");
        assertThat(stored.getVideoId()).isEqualTo(VideoRecipeFixtures.VIDEO_ID);
        assertThat(stored.getVideoPageUrl()).isEqualTo(VideoRecipeFixtures.WATCH_URL);
        assertThat(stored.getVideoDurationSeconds()).isEqualTo(754);
        assertThat(stored.getVideoChannel()).isEqualTo("채널명");
        assertThat(stored.getVideoEmbeddable()).isTrue();
        assertThat(stored.getSteps())
                .extracting(
                        RecipeStepEntity::getStepNumber,
                        RecipeStepEntity::getVideoStartSeconds,
                        RecipeStepEntity::getVideoEndSeconds,
                        RecipeStepEntity::getKeyFrameSeconds,
                        RecipeStepEntity::getSourceType
                )
                .containsExactly(
                        tuple(1, 83, 112, 95, "VIDEO_SEGMENT"),
                        tuple(2, 140, 161, 150, "VIDEO_SEGMENT")
                );
        entityManager.clear();

        var response = service.getRecipe(id);
        assertThat(response.sourceKind()).isEqualTo("VIDEO_AI");
        assertThat(response.video()).isEqualTo(new RecipeImportResponse.Video(
                "YOUTUBE", VideoRecipeFixtures.VIDEO_ID, VideoRecipeFixtures.WATCH_URL, 754, "채널명", true));
        assertThat(response.steps().getFirst().text()).isEqualTo("양파 1개를 채 썰어주세요.");
        assertThat(response.steps().getFirst().originalText()).isEqualTo("양파 한 개 채 썰어 줄게요");
        assertThat(response.steps().getFirst().evidence().locator()).isEqualTo("segments:s3-s4@83-112");
        assertThat(response.ingredients()).extracting(RecipeImportResponse.Ingredient::name)
                .containsExactly("양파", "달걀");
        assertThat(response.warnings()).containsExactly(VideoRecipeFixtures.AI_WARNING);
    }

    @Test
    void reusesIdenticalContentAndServesOnlyTheNewestPublicSnapshotFromTheCache() {
        var source = YouTubeUrlPolicy.forVideoId(VideoRecipeFixtures.VIDEO_ID);
        var first = service.saveVideoRecipe(
                source, VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."), "youtube-gemini-v1", null);
        var identical = service.saveVideoRecipe(
                source, VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."), "youtube-gemini-v1", null);
        var newer = service.saveVideoRecipe(
                source, VideoRecipeFixtures.youTubeRecipe("양파 1개를 얇게 채 썰어주세요."), "youtube-gemini-v1", null);
        var newestButOwned = service.saveVideoRecipe(
                source, VideoRecipeFixtures.youTubeRecipe("양파를 썰어주세요."), "youtube-gemini-v1", "owner-subject");
        entityManager.flush();

        assertThat(identical).isEqualTo(first);
        assertThat(newer).isNotEqualTo(first);
        assertThat(newestButOwned).isNotIn(first, newer);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recipe_snapshot WHERE source_provider = 'youtube' AND source_recipe_id = ?",
                Long.class,
                VideoRecipeFixtures.VIDEO_ID
        )).isEqualTo(3L);
        assertThat(service.findCachedVideoRecipe("youtube", VideoRecipeFixtures.VIDEO_ID, "youtube-gemini-v1"))
                .contains(newer);
        assertThat(service.findCachedVideoRecipe("youtube", VideoRecipeFixtures.VIDEO_ID, "youtube-gemini-v2"))
                .isEmpty();
        assertThat(service.findCachedVideoRecipe("youtube", "ZyXwVuTsRqP", "youtube-gemini-v1")).isEmpty();
    }

    @Test
    void keepsUploadedVideoRecipesPrivateToTheirOwner() {
        var uploadId = UUID.randomUUID();
        var id = service.saveVideoRecipe(
                CanonicalRecipeUrl.forUpload(uploadId),
                VideoRecipeFixtures.uploadRecipe(uploadId.toString(), 612),
                "upload-gemini-v1",
                "owner-subject"
        );
        entityManager.flush();
        entityManager.clear();

        currentUser.signIn("owner-subject");
        var response = service.getRecipe(id);
        assertThat(response.sourceUrl()).isEqualTo("hansul-upload:" + uploadId);
        assertThat(response.sourceKind()).isEqualTo("VIDEO_AI");
        assertThat(response.video()).isEqualTo(new RecipeImportResponse.Video(
                "UPLOAD", null, null, 612, null, false));

        currentUser.signIn("other-subject");
        assertThatThrownBy(() -> service.getRecipe(id)).isInstanceOf(RecipeNotFoundException.class);
        currentUser.signIn(null);
        assertThatThrownBy(() -> service.getRecipe(id)).isInstanceOf(RecipeNotFoundException.class);
        assertThat(service.findCachedVideoRecipe("upload", uploadId.toString(), "upload-gemini-v1")).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ServiceConfiguration {

        @Bean
        SwitchableCurrentUser switchableCurrentUser() {
            return new SwitchableCurrentUser();
        }

        @Bean
        RecipeImportService recipeImportService(
                RecipeSnapshotRepository repository,
                SwitchableCurrentUser currentUser
        ) {
            return new RecipeImportService(
                    new RecipeSourcePolicy(new Hundred00RecipeUrlPolicy(), new YouTubeUrlPolicy()),
                    source -> {
                        throw new AssertionError("Saving a video recipe must not fetch a page");
                    },
                    new Hundred00RecipeDocumentParser(JsonMapper.builder().build(), new IngredientTextParser()),
                    repository,
                    new RecipeResponseMapper(),
                    new RecipeFingerprint(),
                    currentUser,
                    new TickingClock()
            );
        }
    }

    static final class SwitchableCurrentUser implements CurrentUserProvider {

        private final AtomicReference<String> subject = new AtomicReference<>();

        void signIn(String subject) {
            this.subject.set(subject);
        }

        @Override
        public Optional<AuthenticatedUser> findCurrentUser() {
            return Optional.ofNullable(subject.get())
                    .map(value -> new AuthenticatedUser(new AuthenticatedUserId(value), null, null));
        }
    }

    /** Every reading is a minute later, so snapshots saved one after another are strictly newer. */
    static final class TickingClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.getAndUpdate(instant -> instant.plusSeconds(60));
        }
    }
}
