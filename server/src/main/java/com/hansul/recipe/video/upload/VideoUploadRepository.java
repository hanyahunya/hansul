package com.hansul.recipe.video.upload;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Status changes are conditional updates on the current status; each returns the number of rows changed. */
public interface VideoUploadRepository extends JpaRepository<VideoUploadEntity, UUID> {

    // uq_video_upload_active_hash allows one RECEIVING or COMPLETE upload per owner and sha256.
    Optional<VideoUploadEntity> findByOwnerSubjectAndSha256AndStatusIn(
            String ownerSubject,
            String sha256,
            Collection<VideoUploadStatus> statuses
    );

    List<VideoUploadEntity> findByOwnerSubjectAndStatusIn(String ownerSubject, Collection<VideoUploadStatus> statuses);

    List<VideoUploadEntity> findByStatusIn(Collection<VideoUploadStatus> statuses);

    List<VideoUploadEntity> findByStatusInAndExpiresAtLessThanEqual(
            Collection<VideoUploadStatus> statuses,
            Instant expiresAt
    );

    @Query("SELECT COALESCE(SUM(upload.sizeBytes), 0) FROM VideoUploadEntity upload WHERE upload.status IN :statuses")
    long sumSizeBytesByStatusIn(Collection<VideoUploadStatus> statuses);

    /** A stored chunk; {@code updated_at} of a RECEIVING upload is when it last made progress. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE video_upload SET updated_at = :now
            WHERE id = :id AND status = 'RECEIVING'
            """, nativeQuery = true)
    int touch(UUID id, Instant now);

    /**
     * Inserts the upload unless its owner already has an active upload of the same file, in which case nothing
     * is written. Losing that race is a normal outcome, and as a constraint violation it would put the owner
     * and the file hash into the error message and the log.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            INSERT INTO video_upload (id, owner_subject, content_type, size_bytes, sha256, duration_seconds,
                chunk_size_bytes, chunk_count, status, created_at, updated_at, expires_at)
            VALUES (:#{#upload.id}, :#{#upload.ownerSubject}, :#{#upload.contentType}, :#{#upload.sizeBytes},
                :#{#upload.sha256}, :#{#upload.durationSeconds}, :#{#upload.chunkSizeBytes}, :#{#upload.chunkCount},
                :#{#upload.status.name()}, :#{#upload.createdAt}, :#{#upload.updatedAt}, :#{#upload.expiresAt})
            ON CONFLICT (owner_subject, sha256) WHERE status IN ('RECEIVING', 'COMPLETE') DO NOTHING
            """, nativeQuery = true)
    int insertUnlessActive(VideoUploadEntity upload);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE video_upload SET status = 'COMPLETE', updated_at = :now
            WHERE id = :id AND status = 'RECEIVING'
            """, nativeQuery = true)
    int markComplete(UUID id, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE video_upload SET status = 'CONSUMED', recipe_id = :recipeId, updated_at = :now
            WHERE id = :id AND status = 'COMPLETE'
            """, nativeQuery = true)
    int markConsumed(UUID id, UUID recipeId, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE video_upload SET status = 'FAILED', updated_at = :now
            WHERE id = :id AND status IN ('RECEIVING', 'COMPLETE')
            """, nativeQuery = true)
    int markFailed(UUID id, Instant now);

    /** An upload whose analysis is still queued or running keeps its file however old it is. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE video_upload SET status = 'EXPIRED', updated_at = :now
            WHERE id = :id AND status IN ('RECEIVING', 'COMPLETE') AND expires_at <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM recipe_import_job job
                  WHERE job.source_provider = 'upload'
                    AND job.source_ref = CAST(video_upload.id AS VARCHAR)
                    AND job.status IN ('QUEUED', 'RUNNING'))
            """, nativeQuery = true)
    int expire(UUID id, Instant now);

    /** Expires an upload that has not changed since {@code idleSince}, unless its analysis is queued or running. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE video_upload SET status = 'EXPIRED', updated_at = :now
            WHERE id = :id AND status IN ('RECEIVING', 'COMPLETE') AND updated_at <= :idleSince
              AND NOT EXISTS (
                  SELECT 1 FROM recipe_import_job job
                  WHERE job.source_provider = 'upload'
                    AND job.source_ref = CAST(video_upload.id AS VARCHAR)
                    AND job.status IN ('QUEUED', 'RUNNING'))
            """, nativeQuery = true)
    int expireIdle(UUID id, Instant idleSince, Instant now);
}
