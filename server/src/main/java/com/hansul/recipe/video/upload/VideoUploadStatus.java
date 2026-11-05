package com.hansul.recipe.video.upload;

import java.util.Set;

public enum VideoUploadStatus {
    RECEIVING,
    COMPLETE,
    CONSUMED,
    EXPIRED,
    FAILED;

    /** The statuses covered by the partial unique index uq_video_upload_active_hash. */
    static final Set<VideoUploadStatus> ACTIVE = Set.of(RECEIVING, COMPLETE);

    boolean isActive() {
        return ACTIVE.contains(this);
    }
}
