package com.hansul.recipe.infrastructure.persistence;

import com.hansul.recipe.domain.ParsedIngredient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "recipe_ingredient")
public class RecipeIngredientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private RecipeSnapshotEntity recipe;

    @Column(nullable = false)
    private int position;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(name = "quantity_text", length = 300)
    private String quantityText;

    @Column(name = "normalized_amount", precision = 18, scale = 6)
    private BigDecimal normalizedAmount;

    @Column(name = "normalized_unit", length = 100)
    private String normalizedUnit;

    @Column(name = "normalization_warning", length = 300)
    private String normalizationWarning;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_locator", nullable = false, length = 500)
    private String sourceLocator;

    protected RecipeIngredientEntity() {
    }

    static RecipeIngredientEntity create(RecipeSnapshotEntity recipe, ParsedIngredient parsed) {
        var entity = new RecipeIngredientEntity();
        entity.recipe = recipe;
        entity.position = parsed.position();
        entity.rawText = parsed.rawText();
        entity.name = parsed.name();
        entity.quantityText = parsed.quantityText();
        entity.normalizedAmount = parsed.normalizedAmount();
        entity.normalizedUnit = parsed.normalizedUnit();
        entity.normalizationWarning = parsed.normalizationWarning();
        entity.sourceType = parsed.evidence().type().name();
        entity.sourceLocator = parsed.evidence().locator();
        return entity;
    }

    public int getPosition() {
        return position;
    }

    public String getRawText() {
        return rawText;
    }

    public String getName() {
        return name;
    }

    public String getQuantityText() {
        return quantityText;
    }

    public BigDecimal getNormalizedAmount() {
        return normalizedAmount;
    }

    public String getNormalizedUnit() {
        return normalizedUnit;
    }

    public String getNormalizationWarning() {
        return normalizationWarning;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceLocator() {
        return sourceLocator;
    }
}
