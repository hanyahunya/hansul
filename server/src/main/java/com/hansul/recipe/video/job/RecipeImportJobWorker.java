package com.hansul.recipe.video.job;

import com.hansul.recipe.application.RecipeImportService;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import com.hansul.recipe.video.StageListener;
import com.hansul.recipe.video.VideoImportFailure;
import com.hansul.recipe.video.VideoImportProperties;
import com.hansul.recipe.video.VideoImportResult;
import com.hansul.recipe.video.VideoRecipeImportPipeline;
import com.hansul.recipe.video.upload.VideoUploadService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs queued analyses on virtual threads, at most {@code worker-concurrency} at a time. The pipeline takes
 * minutes and holds no transaction; every job update is its own short transaction, and saving the recipe
 * commits together with the job's (and upload's) final status.
 */
@Component
public class RecipeImportJobWorker {

    static final String SERVER_RESTARTED = "SERVER_RESTARTED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String SERVER_RESTARTED_MESSAGE = "The server restarted while the video was being analyzed";
    private static final String INTERNAL_ERROR_MESSAGE = "Unexpected error while importing the video";
    private static final int MAX_ERROR_CODE = 60;
    private static final int MAX_ERROR_MESSAGE = 500;
    private static final Set<String> RETRYABLE_CODES =
            Set.of(VideoImportFailure.AI_QUOTA_EXCEEDED, VideoImportFailure.AI_UNAVAILABLE);
    private static final Logger log = LoggerFactory.getLogger(RecipeImportJobWorker.class);

    private final RecipeImportJobRepository jobs;
    private final VideoUploadService uploads;
    private final VideoRecipeImportPipeline pipeline;
    private final RecipeImportService recipes;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Semaphore permits;
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("recipe-import-", 0).factory());
    // Jobs dispatched in this process and not yet done: waiting for a permit or running.
    private final Set<UUID> handled = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting;
    private volatile boolean stopping;

    public RecipeImportJobWorker(
            RecipeImportJobRepository jobs,
            VideoUploadService uploads,
            VideoRecipeImportPipeline pipeline,
            RecipeImportService recipes,
            PlatformTransactionManager transactionManager,
            VideoImportProperties properties,
            Clock clock
    ) {
        this.jobs = jobs;
        this.uploads = uploads;
        this.pipeline = pipeline;
        this.recipes = recipes;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        // Fair, so waiting jobs start in the order they were dispatched.
        this.permits = new Semaphore(properties.workerConcurrency(), true);
    }

    /**
     * A job left RUNNING was claimed by the previous process and will never finish; QUEUED jobs are still
     * owed an analysis. Dispatch starts only now, so nothing this process runs is mistaken for a leftover.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterStartup() {
        var abandoned = jobs.failAllRunning(SERVER_RESTARTED, SERVER_RESTARTED_MESSAGE, now());
        if (abandoned > 0) {
            log.warn("Marked {} import jobs left running by the previous server process as failed", abandoned);
        }
        accepting = true;
        jobs.findByStatusOrderByCreatedAt(ImportJobStatus.QUEUED).forEach(job -> dispatch(job.getId()));
    }

    /**
     * A job whose own bookkeeping failed (a lost database connection, say) is neither waiting nor running in this
     * process: it would stay QUEUED or RUNNING, and hold its video against every other request, until the next
     * restart. A QUEUED one is dispatched again (the claim makes that harmless) and a RUNNING one fails.
     */
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    void recoverStrandedJobs() {
        if (!accepting || stopping) {
            return;
        }
        for (var job : jobs.findByStatusInOrderByCreatedAt(ImportJobStatus.IN_FLIGHT)) {
            if (handled.contains(job.getId())) {
                continue;
            }
            if (job.getStatus() == ImportJobStatus.QUEUED) {
                dispatch(job.getId());
            } else if (recordFailure(job.getId(), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE) > 0) {
                // The upload stays COMPLETE, as after any failure that was not the video's.
                log.warn("Import job {} was left running without a worker and is marked failed", job.getId());
            }
        }
    }

    /** Runs after the commit of the transaction that queued the job, or at once when there was none. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onJobQueued(RecipeImportJobQueuedEvent event) {
        dispatch(event.jobId());
    }

    /** Before startup recovery and during shutdown the job stays QUEUED; the next startup dispatches it. */
    void dispatch(UUID jobId) {
        if (!accepting || stopping || !handled.add(jobId)) {
            return;
        }
        try {
            executor.execute(() -> runWhenPermitted(jobId));
        } catch (RejectedExecutionException shuttingDown) {
            handled.remove(jobId);
            log.debug("Import job {} stays queued for the next start", jobId);
        }
    }

    @PreDestroy
    void shutDown() throws InterruptedException {
        stopping = true;
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Import jobs were still running at shutdown; the next start marks them SERVER_RESTARTED");
        }
    }

    private void runWhenPermitted(UUID jobId) {
        try {
            permits.acquire();
        } catch (InterruptedException shuttingDown) {
            handled.remove(jobId);
            Thread.currentThread().interrupt();
            return;
        }
        try {
            process(jobId);
        } catch (RuntimeException exception) {
            // Only the job bookkeeping itself can get here; recoverStrandedJobs picks the job up again.
            log.error("Import job {} could not be recorded", jobId, exception);
        } finally {
            handled.remove(jobId);
            permits.release();
        }
    }

    /** Runs the job on the calling thread, unless it is no longer QUEUED (another dispatch claimed it). */
    void process(UUID jobId) {
        if (jobs.claim(jobId, now()) == 0) {
            return;
        }
        var job = jobs.findById(jobId).orElseThrow();
        try {
            switch (job.getSourceKind()) {
                case YOUTUBE -> importYouTube(job);
                case UPLOAD -> importUpload(job);
            }
        } catch (RuntimeException exception) {
            // A shutdown interrupts the Gemini call, which then looks like an AI outage. Leaving the job
            // RUNNING (and its upload COMPLETE) makes the next start report SERVER_RESTARTED instead.
            if (!stopping) {
                recordFailure(job, exception);
            }
        }
    }

    private void recordFailure(RecipeImportJobEntity job, RuntimeException exception) {
        if (exception instanceof VideoImportFailure failure) {
            var code = Objects.requireNonNullElse(failure.code(), INTERNAL_ERROR);
            recordFailure(job.getId(), code, failure.getMessage());
            // A quota or an outage says nothing about the video, so a retry reuses the uploaded file.
            if (job.getSourceKind() == ImportJobSourceKind.UPLOAD && !RETRYABLE_CODES.contains(code)) {
                uploads.markFailed(UUID.fromString(job.getSourceRef()));
            }
            return;
        }
        // The upload stays COMPLETE: the failure was not the video's, so a retry can use the same file.
        log.error("Import job {} failed unexpectedly", job.getId(), exception);
        recordFailure(job.getId(), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    private void importYouTube(RecipeImportJobEntity job) {
        var videoId = job.getSourceRef();
        var result = pipeline.importYouTube(videoId, stageUpdates(job.getId()));
        recordStage(job.getId(), ImportJobStage.SAVING);
        // No owner: a YouTube recipe is the shared cache for every later request of the same video.
        transactions.executeWithoutResult(status -> succeed(job, recipes.saveVideoRecipe(
                YouTubeUrlPolicy.forVideoId(videoId),
                result.recipe(),
                result.parserVersion(),
                null
        ), result));
    }

    private void importUpload(RecipeImportJobEntity job) {
        var uploadId = UUID.fromString(job.getSourceRef());
        var upload = uploads.requireAnalysable(uploadId);
        var result = pipeline.importUpload(
                uploadId,
                uploads.videoFile(uploadId),
                upload.getContentType(),
                upload.getDurationSeconds(),
                stageUpdates(job.getId())
        );
        recordStage(job.getId(), ImportJobStage.SAVING);
        uploads.whileConsuming(uploadId, () -> transactions.executeWithoutResult(status -> {
            var recipeId = recipes.saveVideoRecipe(
                    CanonicalRecipeUrl.forUpload(uploadId),
                    result.recipe(),
                    result.parserVersion(),
                    upload.getOwnerSubject()
            );
            succeed(job, recipeId, result);
            uploads.markConsumed(uploadId, recipeId);
        }));
        uploads.deleteFiles(uploadId);
    }

    private void succeed(RecipeImportJobEntity job, UUID recipeId, VideoImportResult result) {
        var updated = jobs.succeed(job.getId(), recipeId, result.inputTokens(), result.outputTokens(), now());
        if (updated == 0) {
            // The recipe is still worth keeping: it serves the next request for the same video.
            log.warn("Import job {} was no longer running when its recipe was saved", job.getId());
        }
    }

    private StageListener stageUpdates(UUID jobId) {
        return stage -> recordStage(jobId, ImportJobStage.of(stage));
    }

    private void recordStage(UUID jobId, ImportJobStage stage) {
        jobs.updateStage(jobId, stage.name(), now());
    }

    private int recordFailure(UUID jobId, String code, String message) {
        return jobs.fail(jobId, clamp(code, MAX_ERROR_CODE), clamp(message, MAX_ERROR_MESSAGE), now());
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String clamp(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
