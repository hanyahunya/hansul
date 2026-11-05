package com.hansul.recipe.infrastructure.source;

import java.net.URI;
import java.util.UUID;

public record CanonicalRecipeUrl(
        String provider,
        String recipeId,
        URI uri
) {
    public static final String UPLOAD_PROVIDER = "upload";

    /** An uploaded phone video has no public URL; the pseudo URL keeps {@code source_url} meaningful. */
    public static CanonicalRecipeUrl forUpload(UUID uploadId) {
        var id = uploadId.toString();
        return new CanonicalRecipeUrl(UPLOAD_PROVIDER, id, URI.create("hansul-upload:" + id));
    }
}
