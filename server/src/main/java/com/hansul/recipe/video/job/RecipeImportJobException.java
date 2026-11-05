package com.hansul.recipe.video.job;

import org.springframework.http.HttpStatus;

/** A refused import job request; each {@link Code} is an API error code together with its HTTP status. */
public class RecipeImportJobException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST),
        USE_RECIPE_IMPORTS(HttpStatus.BAD_REQUEST),
        IMPORT_JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
        VIDEO_IMPORT_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS),
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

    public RecipeImportJobException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
