package com.hansul.recipe.infrastructure.persistence;

import com.hansul.recipe.domain.ParsedStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "recipe_step")
public class RecipeStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeSnapshotEntity recipe;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(name = "normalized_text", nullable = false, columnDefinition = "text")
    private String normalizedText;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_locator", nullable = false, length = 500)
    private String sourceLocator;

    @Column(name = "video_start_seconds")
    private Integer videoStartSeconds;

    @Column(name = "video_end_seconds")
    private Integer videoEndSeconds;

    @Column(name = "key_frame_seconds")
    private Integer keyFrameSeconds;

    protected RecipeStepEntity() {
    }

    static RecipeStepEntity create(RecipeSnapshotEntity recipe, ParsedStep parsed) {
        var entity = new RecipeStepEntity();
        entity.recipe = recipe;
        entity.stepNumber = parsed.number();
        entity.rawText = parsed.rawText();
        entity.normalizedText = parsed.text();
        entity.imageUrl = parsed.imageUrl();
        entity.sourceType = parsed.evidence().type().name();
        entity.sourceLocator = parsed.evidence().locator();
        entity.videoStartSeconds = parsed.videoStartSeconds();
        entity.videoEndSeconds = parsed.videoEndSeconds();
        entity.keyFrameSeconds = parsed.keyFrameSeconds();
        return entity;
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public String getRawText() {
        return rawText;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceLocator() {
        return sourceLocator;
    }

    public Integer getVideoStartSeconds() {
        return videoStartSeconds;
    }

    public Integer getVideoEndSeconds() {
        return videoEndSeconds;
    }

    public Integer getKeyFrameSeconds() {
        return keyFrameSeconds;
    }
}
