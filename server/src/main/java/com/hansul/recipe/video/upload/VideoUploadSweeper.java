package com.hansul.recipe.video.upload;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uploads nobody finished or analysed within their time to live, and files a failed delete left behind. */
@Component
class VideoUploadSweeper {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadSweeper.class);

    private final VideoUploadService uploads;

    VideoUploadSweeper(VideoUploadService uploads) {
        this.uploads = uploads;
    }

    @Scheduled(initialDelay = 1, fixedDelay = 15, timeUnit = TimeUnit.MINUTES)
    void sweep() {
        var expired = uploads.expireDueUploads();
        var removed = uploads.deleteLeftoverFiles();
        if (expired > 0 || removed > 0) {
            log.info("Expired {} video uploads and removed the files of {} closed uploads", expired, removed);
        }
    }
}
