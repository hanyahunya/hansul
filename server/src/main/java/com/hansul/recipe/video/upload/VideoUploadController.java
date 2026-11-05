package com.hansul.recipe.video.upload;

import java.io.InputStream;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/video-uploads")
public class VideoUploadController {

    private final VideoUploadService uploads;

    public VideoUploadController(VideoUploadService uploads) {
        this.uploads = uploads;
    }

    /** 201 for a new upload, 200 when an unfinished upload of the same file is resumed. */
    @PostMapping
    public ResponseEntity<VideoUploadResponse> create(@RequestBody CreateVideoUploadRequest request) {
        var result = uploads.create(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.upload());
    }

    /** The raw request stream, so a chunk is never buffered in memory by a message converter. */
    @PutMapping("/{uploadId}/chunks/{index}")
    public VideoUploadResponse putChunk(
            @PathVariable UUID uploadId,
            @PathVariable int index,
            InputStream body
    ) {
        return uploads.receiveChunk(uploadId, index, body);
    }

    @GetMapping("/{uploadId}")
    public VideoUploadResponse get(@PathVariable UUID uploadId) {
        return uploads.get(uploadId);
    }
}
