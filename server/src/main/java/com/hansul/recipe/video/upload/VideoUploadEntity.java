package com.hansul.recipe.video.upload;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The chunk files on disk say which chunks arrived; this row holds the declared file and its status. */
@Entity
@Table(name = "video_upload")
public class VideoUploadEntity {

    @Id
    private UUID id;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "chunk_size_bytes", nullable = false)
    private int chunkSizeBytes;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoUploadStatus status;

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected VideoUploadEntity() {
    }

    static VideoUploadEntity receiving(
            String ownerSubject,
            String contentType,
            long sizeBytes,
            String sha256,
            Integer durationSeconds,
            int chunkSizeBytes,
            Instant now,
            Duration ttl
    ) {
        var upload = new VideoUploadEntity();
        upload.id = UUID.randomUUID();
        upload.ownerSubject = ownerSubject;
        upload.contentType = contentType;
        upload.sizeBytes = sizeBytes;
        upload.sha256 = sha256;
        upload.durationSeconds = durationSeconds;
        // The chunk size is fixed per upload, so a configuration change cannot break an upload in progress.
        upload.chunkSizeBytes = chunkSizeBytes;
        upload.chunkCount = Math.toIntExact(Math.ceilDiv(sizeBytes, (long) chunkSizeBytes));
        upload.status = VideoUploadStatus.RECEIVING;
        upload.createdAt = now;
        upload.updatedAt = now;
        upload.expiresAt = now.plus(ttl);
        return upload;
    }

    /** Every chunk is {@code chunkSizeBytes} long except the last one, which holds the rest. */
    long chunkLength(int index) {
        return index < chunkCount - 1 ? chunkSizeBytes : sizeBytes - (long) index * chunkSizeBytes;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public VideoUploadStatus getStatus() {
        return status;
    }

    public UUID getRecipeId() {
        return recipeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
