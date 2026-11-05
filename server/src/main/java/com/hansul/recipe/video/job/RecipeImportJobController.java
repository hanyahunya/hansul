package com.hansul.recipe.video.job;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/recipe-import-jobs")
public class RecipeImportJobController {

    private final RecipeImportJobService service;

    public RecipeImportJobController(RecipeImportJobService service) {
        this.service = service;
    }

    /** 202 when a new analysis was queued, 200 for a job already in flight or a recipe analysed before. */
    @PostMapping
    public ResponseEntity<RecipeImportJobResponse> create(@RequestBody CreateRecipeImportJobRequest request) {
        var submission = service.submit(request);
        return ResponseEntity.status(submission.queued() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(submission.job());
    }

    @GetMapping("/{jobId}")
    public RecipeImportJobResponse get(@PathVariable UUID jobId) {
        return service.get(jobId);
    }
}
