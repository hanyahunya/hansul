package com.hansul.recipe.video.upload;

import org.springframework.http.HttpStatus;

/** A refused upload request; each {@link Code} is an API error code together with its HTTP status. */
public class VideoUploadException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST),
        VIDEO_CHUNK_INVALID(HttpStatus.BAD_REQUEST),
        VIDEO_UPLOAD_NOT_FOUND(HttpStatus.NOT_FOUND),
        VIDEO_UPLOAD_CLOSED(HttpStatus.CONFLICT),
        UPLOAD_INCOMPLETE(HttpStatus.CONFLICT),
        UPLOAD_CHECKSUM_MISMATCH(HttpStatus.CONFLICT),
        VIDEO_UPLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
        VIDEO_TOO_LONG(HttpStatus.UNPROCESSABLE_CONTENT),
        VIDEO_UPLOAD_LIMIT(HttpStatus.TOO_MANY_REQUESTS),
        VIDEO_ANALYSIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final Code code;

    public VideoUploadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
