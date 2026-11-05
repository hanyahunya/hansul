package com.hansul.recipe.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeSnapshotRepository extends JpaRepository<RecipeSnapshotEntity, UUID> {

    Optional<RecipeSnapshotEntity> findBySourceProviderAndSourceRecipeIdAndContentHash(
            String sourceProvider,
            String sourceRecipeId,
            String contentHash
    );

    // Backed by the partial index ix_recipe_snapshot_video_cache (owner_subject IS NULL).
    Optional<RecipeSnapshotEntity> findFirstBySourceProviderAndSourceRecipeIdAndParserVersionAndOwnerSubjectIsNullOrderByImportedAtDesc(
            String sourceProvider,
            String sourceRecipeId,
            String parserVersion
    );
}
