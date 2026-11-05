package com.hansul.app.api;

import com.hansul.recipe.application.RecipeImportException;
import com.hansul.recipe.application.RecipeNotFoundException;
import com.hansul.recipe.infrastructure.source.InvalidRecipeUrlException;
import com.hansul.recipe.infrastructure.source.UnsupportedVideoPlatformException;
import com.hansul.recipe.video.job.RecipeImportJobException;
import com.hansul.recipe.video.upload.VideoUploadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidRecipeUrlException.class)
    public ResponseEntity<ApiErrorResponse> invalidRecipeUrl(InvalidRecipeUrlException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_RECIPE_URL", exception.getMessage());
    }

    @ExceptionHandler(UnsupportedVideoPlatformException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedVideoPlatform(UnsupportedVideoPlatformException exception) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, "VIDEO_LINK_NOT_SUPPORTED", exception.getMessage());
    }

    @ExceptionHandler(RecipeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> recipeNotFound(RecipeNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "RECIPE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(RecipeImportException.class)
    public ResponseEntity<ApiErrorResponse> recipeImportFailed(RecipeImportException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    // Each code enum pairs the error code with its HTTP status.
    @ExceptionHandler(RecipeImportJobException.class)
    public ResponseEntity<ApiErrorResponse> recipeImportJobRefused(RecipeImportJobException exception) {
        return response(exception.code().status(), exception.code().name(), exception.getMessage());
    }

    @ExceptionHandler(VideoUploadException.class)
    public ResponseEntity<ApiErrorResponse> videoUploadRefused(VideoUploadException exception) {
        return response(exception.code().status(), exception.code().name(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validationFailed(MethodArgumentNotValidException exception) {
        var fieldError = exception.getBindingResult().getFieldErrors().stream().findFirst();
        var message = fieldError
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message));
    }
}
