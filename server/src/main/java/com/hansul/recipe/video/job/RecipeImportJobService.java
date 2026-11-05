package com.hansul.recipe.video.job;

import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.application.RecipeImportService;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import com.hansul.recipe.video.VideoImportProperties;
import com.hansul.recipe.video.VideoRecipeImportPipeline;
import com.hansul.recipe.video.upload.VideoUploadService;
import com.hansul.recipe.video.upload.VideoUploadStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Starts video analyses. Not transactional on purpose: each repository call commits on its own, so assembling
 * an upload holds no transaction open, a queued job is visible at once to a request racing for the same source,
 * and the worker is only ever handed committed jobs.
 */
@Service
public class RecipeImportJobService {

    private static final Duration DAILY_LIMIT_WINDOW = Duration.ofHours(24);

    private final RecipeSourcePolicy sourcePolicy;
    private final RecipeImportService recipes;
    private final RecipeImportJobRepository jobs;
    private final VideoUploadService uploads;
    private final VideoImportProperties properties;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RecipeImportJobService(
            RecipeSourcePolicy sourcePolicy,
            RecipeImportService recipes,
            RecipeImportJobRepository jobs,
            VideoUploadService uploads,
            VideoImportProperties properties,
            CurrentUserProvider users,
            ApplicationEventPublisher events,
            Clock clock
    ) {
        this.sourcePolicy = sourcePolicy;
        this.recipes = recipes;
        this.jobs = jobs;
        this.uploads = uploads;
        this.properties = properties;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /** {@code queued} is false when an in-flight job or an earlier analysis answers the request. */
    public record Submission(RecipeImportJobResponse job, boolean queued) {
    }

    public Submission submit(CreateRecipeImportJobRequest request) {
        var owner = users.requireCurrentUser().id().value();
        var url = blankToNull(request.url());
        var uploadId = blankToNull(request.uploadId());
        if ((url == null) == (uploadId == null)) {
            throw new RecipeImportJobException(
                    RecipeImportJobException.Code.INVALID_REQUEST,
                    "Send exactly one of url or uploadId"
            );
        }
        return url != null ? submitVideoLink(owner, url) : submitUpload(owner, parseUploadId(uploadId));
    }

    /** YouTube jobs are visible to everyone (public videos, shared cache); upload jobs only to their owner. */
    public RecipeImportJobResponse get(UUID jobId) {
        var owner = users.requireCurrentUser().id().value();
        return jobs.findById(jobId)
                .filter(job -> job.getSourceKind() == ImportJobSourceKind.YOUTUBE || job.getOwnerSubject().equals(owner))
                .map(RecipeImportJobResponse::from)
                .orElseThrow(() -> new RecipeImportJobException(
                        RecipeImportJobException.Code.IMPORT_JOB_NOT_FOUND,
                        "Import job not found: " + jobId
                ));
    }

    private Submission submitVideoLink(String owner, String rawUrl) {
        var video = sourcePolicy.canonicalize(rawUrl);
        // The policy knows exactly two sources, and 10000recipe pages are imported synchronously.
        if (!YouTubeUrlPolicy.PROVIDER.equals(video.provider())) {
            throw new RecipeImportJobException(
                    RecipeImportJobException.Code.USE_RECIPE_IMPORTS,
                    "10000recipe pages are imported through POST /v1/recipe-imports"
            );
        }
        requireAnalysisAvailable(properties.isConfigured());
        var source = ImportJobSource.youTube(video);
        // The in-flight job first: a job's success stores the shared recipe in the same commit that ends it, so a
        // job finishing between the two reads is still found by the second.
        var inFlight = jobs.findInFlight(source.provider(), source.ref());
        if (inFlight.isPresent()) {
            return answered(inFlight.get());
        }
        var cached = recipes.findCachedVideoRecipe(
                source.provider(),
                source.ref(),
                VideoRecipeImportPipeline.YOUTUBE_PARSER_VERSION
        );
        if (cached.isPresent()) {
            return cacheHit(owner, source, cached.get());
        }
        requireWithinDailyLimit(owner);
        return queue(RecipeImportJobEntity.queued(owner, source, now()));
    }

    private Submission submitUpload(String owner, UUID uploadId) {
        requireAnalysisAvailable(properties.isConfiguredForUploads());
        var upload = uploads.findOwned(uploadId, owner);
        var source = ImportJobSource.upload(uploadId);
        if (upload.getStatus() == VideoUploadStatus.CONSUMED && upload.getRecipeId() != null) {
            return cacheHit(owner, source, upload.getRecipeId());
        }
        var inFlight = jobs.findInFlight(source.provider(), source.ref());
        if (inFlight.isPresent()) {
            return answered(inFlight.get());
        }
        // Checked before assembling, so a user over the limit does not make the server hash the whole file.
        requireWithinDailyLimit(owner);
        // A job of this upload that finished since the reads above has consumed it: its recipe is the answer.
        return uploads.whileComplete(
                uploadId,
                () -> queue(RecipeImportJobEntity.queued(owner, source, now())),
                recipeId -> cacheHit(owner, source, recipeId)
        );
    }

    private Submission cacheHit(String owner, ImportJobSource source, UUID recipeId) {
        return answered(jobs.saveAndFlush(RecipeImportJobEntity.cacheHit(owner, source, recipeId, now())));
    }

    private Submission queue(RecipeImportJobEntity job) {
        if (jobs.insertUnlessInFlight(job) == 0) {
            // uq_recipe_import_job_in_flight: a concurrent request queued the same source first.
            return jobs.findInFlight(job.getSourceProvider(), job.getSourceRef())
                    .map(this::answered)
                    .orElseThrow(() -> new IllegalStateException("A concurrent import job of the same source ended at once"));
        }
        events.publishEvent(new RecipeImportJobQueuedEvent(job.getId()));
        return new Submission(RecipeImportJobResponse.from(job), true);
    }

    private Submission answered(RecipeImportJobEntity job) {
        return new Submission(RecipeImportJobResponse.from(job), false);
    }

    // Phone videos also need the operator's word that the Gemini key is paid tier; see VideoImportProperties.
    private void requireAnalysisAvailable(boolean available) {
        if (!available) {
            throw new RecipeImportJobException(
                    RecipeImportJobException.Code.VIDEO_ANALYSIS_UNAVAILABLE,
                    "Video analysis is not configured on this server"
            );
        }
    }

    /** Only analyses count: cache hits are free, and joining an in-flight job never reaches this check. */
    private void requireWithinDailyLimit(String owner) {
        var limit = properties.dailyLimitPerUser();
        var analyses = jobs.countByOwnerSubjectAndCacheHitFalseAndCreatedAtAfter(owner, now().minus(DAILY_LIMIT_WINDOW));
        if (analyses >= limit) {
            throw new RecipeImportJobException(
                    RecipeImportJobException.Code.VIDEO_IMPORT_DAILY_LIMIT,
                    "At most " + limit + " videos can be analyzed in 24 hours"
            );
        }
    }

    private Instant now() {
        // PostgreSQL keeps microseconds; the API then shows the same instant before and after a reload.
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static UUID parseUploadId(String value) {
        try {
            var uploadId = UUID.fromString(value);
            if (uploadId.toString().equalsIgnoreCase(value)) {
                return uploadId;
            }
        } catch (IllegalArgumentException malformed) {
            // reported below
        }
        throw new RecipeImportJobException(RecipeImportJobException.Code.INVALID_REQUEST, "uploadId must be a UUID");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
