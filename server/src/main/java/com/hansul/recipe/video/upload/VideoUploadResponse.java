package com.hansul.recipe.video.upload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VideoUploadResponse(
        UUID uploadId,
        VideoUploadStatus status,
        int chunkSizeBytes,
        int chunkCount,
        List<Integer> receivedChunks,
        Instant expiresAt
) {
}
