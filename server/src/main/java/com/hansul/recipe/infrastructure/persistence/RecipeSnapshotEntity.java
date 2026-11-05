package com.hansul.recipe.infrastructure.persistence;

import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "recipe_snapshot")
public class RecipeSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "source_provider", nullable = false, length = 40)
    private String sourceProvider;

    @Column(name = "source_recipe_id", nullable = false, length = 40)
    private String sourceRecipeId;

    @Column(name = "source_url", nullable = false, length = 500)
    private String sourceUrl;

    @Column(name = "source_format", nullable = false, length = 30)
    private String sourceFormat;

    @Column(name = "source_evidence_locator", nullable = false, length = 500)
    private String sourceEvidenceLocator;

    @Column(name = "parser_version", nullable = false, length = 30)
    private String parserVersion;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "base_servings", precision = 10, scale = 3)
    private BigDecimal baseServings;

    @Column(name = "base_servings_text", length = 200)
    private String baseServingsText;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "total_time_text", length = 100)
    private String totalTimeText;

    @Column(name = "primary_image_url", columnDefinition = "text")
    private String primaryImageUrl;

    @Column(name = "raw_structured_data", columnDefinition = "text")
    private String rawStructuredData;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @Column(name = "video_provider", length = 20)
    private String videoProvider;

    @Column(name = "video_id", length = 100)
    private String videoId;

    @Column(name = "video_page_url", length = 500)
    private String videoPageUrl;

    @Column(name = "video_duration_seconds")
    private Integer videoDurationSeconds;

    @Column(name = "video_channel", length = 300)
    private String videoChannel;

    @Column(name = "video_embeddable")
    private Boolean videoEmbeddable;

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecipeIngredientEntity> ingredients = new ArrayList<>();

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecipeStepEntity> steps = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "recipe_warning", joinColumns = @JoinColumn(name = "recipe_id"))
    @OrderColumn(name = "position")
    @Column(name = "warning", nullable = false, columnDefinition = "text")
    private List<String> warnings = new ArrayList<>();

    protected RecipeSnapshotEntity() {
    }

    public static RecipeSnapshotEntity create(
            CanonicalRecipeUrl source,
            ParsedRecipe parsed,
            String parserVersion,
            String contentHash,
            Instant importedAt
    ) {
        return create(source, parsed, parserVersion, contentHash, importedAt, null);
    }

    /** A null {@code ownerSubject} makes the snapshot public; otherwise only that subject may read it. */
    public static RecipeSnapshotEntity create(
            CanonicalRecipeUrl source,
            ParsedRecipe parsed,
            String parserVersion,
            String contentHash,
            Instant importedAt,
            String ownerSubject
    ) {
        var entity = new RecipeSnapshotEntity();
        entity.id = UUID.randomUUID();
        entity.sourceProvider = source.provider();
        entity.sourceRecipeId = source.recipeId();
        entity.sourceUrl = source.uri().toString();
        entity.sourceFormat = parsed.sourceFormat();
        entity.sourceEvidenceLocator = parsed.sourceEvidenceLocator();
        entity.parserVersion = parserVersion;
        entity.contentHash = contentHash;
        entity.title = parsed.title();
        entity.baseServings = parsed.baseServings();
        entity.baseServingsText = parsed.baseServingsText();
        entity.description = parsed.description();
        entity.totalTimeText = parsed.totalTimeText();
        entity.primaryImageUrl = parsed.primaryImageUrl();
        entity.rawStructuredData = parsed.rawStructuredData();
        entity.importedAt = importedAt;
        entity.ownerSubject = ownerSubject;
        var video = parsed.video();
        if (video != null) {
            entity.videoProvider = video.provider();
            entity.videoId = video.videoId();
            entity.videoPageUrl = video.pageUrl();
            entity.videoDurationSeconds = video.durationSeconds();
            entity.videoChannel = video.channel();
            entity.videoEmbeddable = video.embeddable();
        }
        entity.warnings.addAll(parsed.warnings());

        parsed.ingredients().forEach(ingredient ->
                entity.ingredients.add(RecipeIngredientEntity.create(entity, ingredient)));
        parsed.steps().forEach(step ->
                entity.steps.add(RecipeStepEntity.create(entity, step)));
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceProvider() {
        return sourceProvider;
    }

    public String getSourceRecipeId() {
        return sourceRecipeId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceFormat() {
        return sourceFormat;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getBaseServings() {
        return baseServings;
    }

    public String getBaseServingsText() {
        return baseServingsText;
    }

    public String getDescription() {
        return description;
    }

    public String getTotalTimeText() {
        return totalTimeText;
    }

    public String getPrimaryImageUrl() {
        return primaryImageUrl;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public String getVideoProvider() {
        return videoProvider;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getVideoPageUrl() {
        return videoPageUrl;
    }

    public Integer getVideoDurationSeconds() {
        return videoDurationSeconds;
    }

    public String getVideoChannel() {
        return videoChannel;
    }

    public Boolean getVideoEmbeddable() {
        return videoEmbeddable;
    }

    public List<RecipeIngredientEntity> getIngredients() {
        return ingredients.stream()
                .sorted((left, right) -> Integer.compare(left.getPosition(), right.getPosition()))
                .toList();
    }

    public List<RecipeStepEntity> getSteps() {
        return steps.stream()
                .sorted((left, right) -> Integer.compare(left.getStepNumber(), right.getStepNumber()))
                .toList();
    }

    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }
}
