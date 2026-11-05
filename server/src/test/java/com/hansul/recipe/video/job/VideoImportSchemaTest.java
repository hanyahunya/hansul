package com.hansul.recipe.video.job;

import static com.hansul.recipe.video.job.VideoJobFixtures.VIDEO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotEntity;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** V5's job and upload tables on the production database: partial unique indexes and recipe references. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VideoImportSchemaTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");
    private static final String SHA256 = "84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882";

    @Autowired
    private RecipeImportJobRepository jobs;

    @Autowired
    private RecipeSnapshotRepository snapshots;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void deleteRows() {
        jdbc.update("DELETE FROM recipe_import_job");
        jdbc.update("DELETE FROM video_upload");
        jdbc.update("DELETE FROM recipe_snapshot WHERE source_provider IN ('youtube', 'upload')");
    }

    @Test
    void createsTheIndexesTheJobsAndUploadsRelyOn() {
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename IN ('recipe_import_job', 'video_upload')
                  AND indexname NOT LIKE '%pkey'
                ORDER BY indexname
                """, String.class)).containsExactly(
                "ix_recipe_import_job_owner_created",
                "ix_recipe_import_job_status",
                "ix_video_upload_expiry",
                "ix_video_upload_owner_status",
                "uq_recipe_import_job_in_flight",
                "uq_video_upload_active_hash"
        );
    }

    @Test
    void allowsOneQueuedOrRunningJobPerSourceButAnyNumberOfFinishedOnes() {
        jobs.saveAndFlush(queued(VIDEO_ID));

        assertThatThrownBy(() -> jobs.saveAndFlush(queued(VIDEO_ID))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE recipe_import_job SET status = 'RUNNING'");
        assertThatThrownBy(() -> jobs.saveAndFlush(queued(VIDEO_ID))).isInstanceOf(DataIntegrityViolationException.class);

        jobs.saveAndFlush(queued("ZyXwVuTsRqP"));
        jobs.saveAndFlush(RecipeImportJobEntity.queued("cook-a", ImportJobSource.upload(UUID.randomUUID()), NOW));
        jdbc.update("UPDATE recipe_import_job SET status = 'FAILED' WHERE source_ref = ?", VIDEO_ID);
        jobs.saveAndFlush(queued(VIDEO_ID));
        jdbc.update("UPDATE recipe_import_job SET status = 'SUCCEEDED' WHERE source_ref = ? AND status = 'QUEUED'", VIDEO_ID);
        jobs.saveAndFlush(queued(VIDEO_ID));

        assertThat(jdbc.queryForList(
                "SELECT status FROM recipe_import_job WHERE source_ref = ? ORDER BY status", String.class, VIDEO_ID))
                .containsExactly("FAILED", "QUEUED", "SUCCEEDED");
    }

    @Test
    void theQueueingInsertSkipsOnlyASourceThatIsAlreadyInFlight() {
        var uploadJob = RecipeImportJobEntity.queued("cook-a", ImportJobSource.upload(UUID.randomUUID()), NOW);

        assertThat(jobs.insertUnlessInFlight(uploadJob)).isOne();
        assertThat(jobs.insertUnlessInFlight(queued(VIDEO_ID))).isOne();
        assertThat(jobs.insertUnlessInFlight(queued(VIDEO_ID))).isZero();
        jdbc.update("UPDATE recipe_import_job SET status = 'RUNNING' WHERE source_ref = ?", VIDEO_ID);
        assertThat(jobs.insertUnlessInFlight(queued(VIDEO_ID))).isZero();
        jdbc.update("UPDATE recipe_import_job SET status = 'FAILED' WHERE source_ref = ?", VIDEO_ID);
        assertThat(jobs.insertUnlessInFlight(queued(VIDEO_ID))).isOne();

        var stored = jobs.findById(uploadJob.getId()).orElseThrow();
        assertThat(stored)
                .usingRecursiveComparison()
                .isEqualTo(uploadJob);
        assertThat(stored.getSourceUrl()).isNull();
        assertThat(stored.getStatus()).isEqualTo(ImportJobStatus.QUEUED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_import_job", Long.class)).isEqualTo(3);
    }

    @Test
    void allowsOneReceivingOrCompleteUploadPerOwnerAndFile() {
        insertUpload("cook-a", "RECEIVING");

        assertThatThrownBy(() -> insertUpload("cook-a", "COMPLETE")).isInstanceOf(DataIntegrityViolationException.class);
        insertUpload("cook-a", "CONSUMED");
        insertUpload("cook-a", "EXPIRED");
        insertUpload("cook-a", "FAILED");
        insertUpload("cook-b", "RECEIVING");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_upload WHERE sha256 = ?", Long.class, SHA256))
                .isEqualTo(5);
    }

    @Test
    void storesTheJobColumnsUnderTheirContractNames() {
        var recipeId = savedRecipe();
        var job = jobs.saveAndFlush(RecipeImportJobEntity.cacheHit(
                "cook-a", ImportJobSource.youTube(YouTubeUrlPolicy.forVideoId(VIDEO_ID)), recipeId, NOW));

        var row = jdbc.queryForMap("SELECT * FROM recipe_import_job WHERE id = ?", job.getId());

        assertThat(row)
                .containsEntry("owner_subject", "cook-a")
                .containsEntry("source_kind", "YOUTUBE")
                .containsEntry("source_provider", "youtube")
                .containsEntry("source_ref", VIDEO_ID)
                .containsEntry("source_url", "https://www.youtube.com/watch?v=" + VIDEO_ID)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("stage", "DONE")
                .containsEntry("cache_hit", true)
                .containsEntry("recipe_id", recipeId)
                .containsEntry("attempts", 0)
                .containsEntry("created_at", Timestamp.from(NOW))
                .containsEntry("finished_at", Timestamp.from(NOW));
        assertThat(row.get("started_at")).isNull();
        assertThat(row.get("error_code")).isNull();
    }

    @Test
    void deletingARecipeKeepsTheJobsAndUploadsThatProducedIt() {
        var recipeId = savedRecipe();
        var job = jobs.saveAndFlush(RecipeImportJobEntity.cacheHit(
                "cook-a", ImportJobSource.youTube(YouTubeUrlPolicy.forVideoId(VIDEO_ID)), recipeId, NOW));
        var uploadId = insertUpload("cook-a", "CONSUMED");
        jdbc.update("UPDATE video_upload SET recipe_id = ? WHERE id = ?", recipeId, uploadId);

        jdbc.update("DELETE FROM recipe_snapshot WHERE id = ?", recipeId);

        assertThat(jobs.findById(job.getId()).orElseThrow().getRecipeId()).isNull();
        assertThat(jdbc.queryForObject("SELECT recipe_id FROM video_upload WHERE id = ?", UUID.class, uploadId)).isNull();
    }

    private static RecipeImportJobEntity queued(String videoId) {
        return RecipeImportJobEntity.queued("cook-a", ImportJobSource.youTube(YouTubeUrlPolicy.forVideoId(videoId)), NOW);
    }

    private UUID savedRecipe() {
        var recipe = VideoJobFixtures.youTubeResult(VIDEO_ID);
        return snapshots.saveAndFlush(RecipeSnapshotEntity.create(
                YouTubeUrlPolicy.forVideoId(VIDEO_ID), recipe.recipe(), recipe.parserVersion(), "0".repeat(64), NOW
        )).getId();
    }

    private UUID insertUpload(String owner, String status) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO video_upload (id, owner_subject, content_type, size_bytes, sha256, duration_seconds,
                    chunk_size_bytes, chunk_count, status, created_at, updated_at, expires_at)
                VALUES (?, ?, 'video/mp4', 10, ?, 60, 4, 3, ?, ?, ?, ?)
                """, id, owner, SHA256, status, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(21600)));
        return id;
    }
}
