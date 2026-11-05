package com.hansul.recipe.video.job;

import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import java.util.UUID;

/** What a job analyses; {@code provider} + {@code ref} is the key of the in-flight dedupe. */
record ImportJobSource(ImportJobSourceKind kind, String provider, String ref, String url) {

    static ImportJobSource youTube(CanonicalRecipeUrl video) {
        return new ImportJobSource(ImportJobSourceKind.YOUTUBE, video.provider(), video.recipeId(), video.uri().toString());
    }

    /** An uploaded video has no public URL to report. */
    static ImportJobSource upload(UUID uploadId) {
        return new ImportJobSource(
                ImportJobSourceKind.UPLOAD,
                CanonicalRecipeUrl.UPLOAD_PROVIDER,
                uploadId.toString(),
                null
        );
    }
}
