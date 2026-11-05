package com.hansul.recipe.video.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Status changes after the insert are conditional updates in {@link RecipeImportJobRepository}. */
@Entity
@Table(name = "recipe_import_job")
public class RecipeImportJobEntity {

    @Id
    private UUID id;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 20)
    private ImportJobSourceKind sourceKind;

    @Column(name = "source_provider", nullable = false, length = 40)
    private String sourceProvider;

    @Column(name = "source_ref", nullable = false, length = 100)
    private String sourceRef;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImportJobStage stage;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected RecipeImportJobEntity() {
    }

    static RecipeImportJobEntity queued(String ownerSubject, ImportJobSource source, Instant now) {
        var job = create(ownerSubject, source, now);
        job.status = ImportJobStatus.QUEUED;
        job.stage = ImportJobStage.QUEUED;
        return job;
    }

    /** A request answered from an earlier analysis; it does not count against the daily limit. */
    static RecipeImportJobEntity cacheHit(String ownerSubject, ImportJobSource source, UUID recipeId, Instant now) {
        var job = create(ownerSubject, source, now);
        job.status = ImportJobStatus.SUCCEEDED;
        job.stage = ImportJobStage.DONE;
        job.cacheHit = true;
        job.recipeId = recipeId;
        job.finishedAt = now;
        return job;
    }

    private static RecipeImportJobEntity create(String ownerSubject, ImportJobSource source, Instant now) {
        var job = new RecipeImportJobEntity();
        job.id = UUID.randomUUID();
        job.ownerSubject = ownerSubject;
        job.sourceKind = source.kind();
        job.sourceProvider = source.provider();
        job.sourceRef = source.ref();
        job.sourceUrl = source.url();
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public ImportJobSourceKind getSourceKind() {
        return sourceKind;
    }

    public String getSourceProvider() {
        return sourceProvider;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public ImportJobStatus getStatus() {
        return status;
    }

    public ImportJobStage getStage() {
        return stage;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public UUID getRecipeId() {
        return recipeId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttempts() {
        return attempts;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
