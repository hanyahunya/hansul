package com.hansul.recipe.video.job;

/** Exactly one of the two; {@code uploadId} stays a string so a malformed id is an INVALID_REQUEST. */
public record CreateRecipeImportJobRequest(
        String url,
        String uploadId
) {
}
