package com.hansul.recipe.video.upload;

public record CreateVideoUploadRequest(
        String contentType,
        Long sizeBytes,
        String sha256,
        Integer durationSeconds
) {
}
