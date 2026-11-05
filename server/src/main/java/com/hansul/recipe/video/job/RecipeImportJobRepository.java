package com.hansul.recipe.video.job;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every status change is a conditional update on the current status, so two workers (or a worker and the
 * startup recovery) can never both move the same job. Each returns the number of rows it changed.
 */
public interface RecipeImportJobRepository extends JpaRepository<RecipeImportJobEntity, UUID> {

    // uq_recipe_import_job_in_flight allows at most one QUEUED or RUNNING job per source.
    Optional<RecipeImportJobEntity> findBySourceProviderAndSourceRefAndStatusIn(
            String sourceProvider,
            String sourceRef,
            Collection<ImportJobStatus> statuses
    );

    default Optional<RecipeImportJobEntity> findInFlight(String sourceProvider, String sourceRef) {
        return findBySourceProviderAndSourceRefAndStatusIn(sourceProvider, sourceRef, ImportJobStatus.IN_FLIGHT);
    }

    long countByOwnerSubjectAndCacheHitFalseAndCreatedAtAfter(String ownerSubject, Instant createdAfter);

    List<RecipeImportJobEntity> findByStatusOrderByCreatedAt(ImportJobStatus status);

    List<RecipeImportJobEntity> findByStatusInOrderByCreatedAt(Collection<ImportJobStatus> statuses);

    /**
     * Inserts the job unless a job for the same source is already queued or running, in which case nothing is
     * written. Two requests for one video racing here is normal, so it must not surface (and be logged) as a
     * constraint violation.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            INSERT INTO recipe_import_job (id, owner_subject, source_kind, source_provider, source_ref, source_url,
                status, stage, cache_hit, attempts, created_at, updated_at)
            VALUES (:#{#job.id}, :#{#job.ownerSubject}, :#{#job.sourceKind.name()}, :#{#job.sourceProvider},
                :#{#job.sourceRef}, :#{#job.sourceUrl}, :#{#job.status.name()}, :#{#job.stage.name()},
                :#{#job.cacheHit}, :#{#job.attempts}, :#{#job.createdAt}, :#{#job.updatedAt})
            ON CONFLICT (source_provider, source_ref) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING
            """, nativeQuery = true)
    int insertUnlessInFlight(RecipeImportJobEntity job);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE recipe_import_job
            SET status = 'RUNNING', attempts = attempts + 1, started_at = :now, updated_at = :now
            WHERE id = :id AND status = 'QUEUED'
            """, nativeQuery = true)
    int claim(UUID id, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE recipe_import_job
            SET stage = :stage, updated_at = :now
            WHERE id = :id AND status = 'RUNNING'
            """, nativeQuery = true)
    int updateStage(UUID id, String stage, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE recipe_import_job
            SET status = 'SUCCEEDED', stage = 'DONE', recipe_id = :recipeId,
                input_tokens = :inputTokens, output_tokens = :outputTokens,
                updated_at = :now, finished_at = :now
            WHERE id = :id AND status = 'RUNNING'
            """, nativeQuery = true)
    int succeed(UUID id, UUID recipeId, long inputTokens, long outputTokens, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE recipe_import_job
            SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage,
                updated_at = :now, finished_at = :now
            WHERE id = :id AND status = 'RUNNING'
            """, nativeQuery = true)
    int fail(UUID id, String errorCode, String errorMessage, Instant now);

    /** Startup recovery: nothing can still be running a job that a previous process had claimed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE recipe_import_job
            SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage,
                updated_at = :now, finished_at = :now
            WHERE status = 'RUNNING'
            """, nativeQuery = true)
    int failAllRunning(String errorCode, String errorMessage, Instant now);
}
