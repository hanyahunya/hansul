package com.hansul.recipe.video.job;

import static com.hansul.recipe.video.job.VideoJobFixtures.VIDEO_ID;
import static com.hansul.recipe.video.job.VideoJobFixtures.WATCH_URL;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.VIDEO;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.request;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.sendAllChunks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.application.RecipeImportService;
import com.hansul.recipe.application.RecipeImportServiceFixture;
import com.hansul.recipe.application.RecipeNotFoundException;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import com.hansul.recipe.video.StageListener;
import com.hansul.recipe.video.VideoImportFailure;
import com.hansul.recipe.video.VideoImportResult;
import com.hansul.recipe.video.VideoImportStage;
import com.hansul.recipe.video.VideoRecipeImportPipeline;
import com.hansul.recipe.video.upload.VideoUploadRepository;
import com.hansul.recipe.video.upload.VideoUploadService;
import com.hansul.recipe.video.upload.VideoUploadStatus;
import com.hansul.recipe.video.upload.VideoUploadStorage;
import com.hansul.recipe.video.upload.VideoUploadTestSupport;
import com.hansul.recipe.video.upload.VideoUploadTestSupport.MutableClock;
import com.hansul.recipe.video.upload.VideoUploadTestSupport.SwitchableUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The worker on PostgreSQL with a fake pipeline; jobs run on the test thread unless a test dispatches them. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecipeImportJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    @Autowired
    private RecipeImportJobRepository jobs;

    @Autowired
    private VideoUploadRepository uploadRepository;

    @Autowired
    private RecipeSnapshotRepository snapshots;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path uploadRoot;

    private final SwitchableUser user = new SwitchableUser("cook-a");
    private final MutableClock clock = new MutableClock(NOW);
    private final List<RecipeImportJobWorker> workers = new ArrayList<>();
    private RecipeImportService recipes;
    private VideoUploadService uploads;
    private RecipeImportJobService jobService;

    @BeforeEach
    void setUp() {
        var properties = VideoUploadTestSupport.properties(uploadRoot, true);
        recipes = RecipeImportServiceFixture.create(snapshots, user, clock);
        uploads = new VideoUploadService(uploadRepository, new VideoUploadStorage(properties), properties, user, clock);
        // Jobs are handed to a worker explicitly by each test.
        jobService = new RecipeImportJobService(
                new RecipeSourcePolicy(new Hundred00RecipeUrlPolicy(), new YouTubeUrlPolicy()),
                recipes,
                jobs,
                uploads,
                properties,
                user,
                event -> {
                },
                clock
        );
    }

    @AfterEach
    void stopWorkersAndDeleteRows() throws InterruptedException {
        for (var worker : workers) {
            worker.shutDown();
        }
        jdbc.update("DELETE FROM recipe_import_job");
        jdbc.update("DELETE FROM video_upload");
        jdbc.update("DELETE FROM recipe_snapshot WHERE source_provider IN ('youtube', 'upload')");
    }

    @Test
    void savesAYouTubeRecipeAsThePublicCacheAndRecordsEveryStage() {
        var job = jobService.submit(link(WATCH_URL)).job();
        var pipeline = new FakePipeline(call -> VideoJobFixtures.youTubeResult(call.videoId()));
        clock.advance(Duration.ofSeconds(5));

        worker(pipeline, 2).process(job.jobId());

        assertThat(pipeline.storedStages).containsExactly("FETCHING_METADATA", "WATCHING_VIDEO", "WRITING_RECIPE");
        var done = jobs.findById(job.jobId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(done.getStage()).isEqualTo(ImportJobStage.DONE);
        assertThat(done.getAttempts()).isOne();
        assertThat(done.getInputTokens()).isEqualTo(1200);
        assertThat(done.getOutputTokens()).isEqualTo(340);
        assertThat(done.getStartedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(done.getFinishedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(done.getErrorCode()).isNull();

        var snapshot = snapshots.findById(done.getRecipeId()).orElseThrow();
        assertThat(snapshot.getOwnerSubject()).isNull();
        assertThat(snapshot.getSourceUrl()).isEqualTo(WATCH_URL);
        assertThat(snapshot.getParserVersion()).isEqualTo("youtube-gemini-v1");

        user.signIn("cook-b");
        var later = jobService.submit(link("https://youtu.be/" + VIDEO_ID));
        assertThat(later.queued()).isFalse();
        assertThat(later.job().recipeId()).isEqualTo(done.getRecipeId());
    }

    @Test
    void savesAnUploadRecipeForItsOwnerOnlyAndDeletesTheVideo() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var job = jobService.submit(uploadRequest(upload.uploadId())).job();
        var pipeline = new FakePipeline(call -> VideoJobFixtures.uploadResult(call.uploadId()));

        worker(pipeline, 2).process(job.jobId());

        assertThat(pipeline.calls).singleElement().satisfies(call -> {
            assertThat(call.uploadId()).isEqualTo(upload.uploadId());
            assertThat(call.video()).isEqualTo(VIDEO);
            assertThat(call.contentType()).isEqualTo("video/mp4");
            assertThat(call.durationSeconds()).isEqualTo(612);
        });
        assertThat(pipeline.storedStages).containsExactly("UPLOADING_TO_AI", "WATCHING_VIDEO", "WRITING_RECIPE");
        var done = jobs.findById(job.jobId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ImportJobStatus.SUCCEEDED);
        var recipeId = done.getRecipeId();
        var consumed = uploadRepository.findById(upload.uploadId()).orElseThrow();
        assertThat(consumed.getStatus()).isEqualTo(VideoUploadStatus.CONSUMED);
        assertThat(consumed.getRecipeId()).isEqualTo(recipeId);
        assertThat(uploadRoot.resolve(upload.uploadId().toString())).doesNotExist();

        var recipe = readRecipe(recipeId);
        assertThat(recipe.sourceUrl()).isEqualTo("hansul-upload:" + upload.uploadId());
        assertThat(recipe.sourceKind()).isEqualTo("VIDEO_AI");
        assertThat(snapshots.findById(recipeId).orElseThrow().getOwnerSubject()).isEqualTo("cook-a");
        user.signIn("cook-b");
        assertThatThrownBy(() -> readRecipe(recipeId)).isInstanceOf(RecipeNotFoundException.class);

        user.signIn("cook-a");
        var again = jobService.submit(uploadRequest(upload.uploadId()));
        assertThat(again.queued()).isFalse();
        assertThat(again.job().recipeId()).isEqualTo(recipeId);
    }

    @Test
    void recordsTheFailureCodeAndMessageOfTheAnalysis() {
        var job = jobService.submit(link(WATCH_URL)).job();
        var pipeline = new FakePipeline(call -> {
            throw new VideoImportFailure("NOT_A_RECIPE_VIDEO", "The video does not show cooking");
        });

        worker(pipeline, 2).process(job.jobId());

        var failed = jobService.get(job.jobId());
        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.stage()).isEqualTo(ImportJobStage.WRITING_RECIPE);
        assertThat(failed.errorCode()).isEqualTo("NOT_A_RECIPE_VIDEO");
        assertThat(failed.errorMessage()).isEqualTo("The video does not show cooking");
        assertThat(failed.recipeId()).isNull();
        assertThat(jobs.findById(job.jobId()).orElseThrow().getFinishedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recipe_snapshot WHERE source_recipe_id = ?", Long.class, VIDEO_ID)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "VIDEO_UNAVAILABLE", "VIDEO_TOO_LONG", "NOT_A_RECIPE_VIDEO", "RECIPE_EXTRACTION_FAILED", "AI_BLOCKED"})
    void anAnalysisTheVideoItselfFailedClosesTheUploadAndDeletesItsVideo(String code) {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var job = jobService.submit(uploadRequest(upload.uploadId())).job();
        var pipeline = new FakePipeline(call -> {
            throw new VideoImportFailure(code, "The video cannot become a recipe");
        });

        worker(pipeline, 2).process(job.jobId());

        assertThat(jobService.get(job.jobId()).errorCode()).isEqualTo(code);
        assertThat(uploadRepository.findById(upload.uploadId()).orElseThrow().getStatus())
                .isEqualTo(VideoUploadStatus.FAILED);
        assertThat(uploadRoot.resolve(upload.uploadId().toString())).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_QUOTA_EXCEEDED", "AI_UNAVAILABLE"})
    void aRetryableAiFailureKeepsTheUploadSoARetryNeedsNoSecondUpload(String code) {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var job = jobService.submit(uploadRequest(upload.uploadId())).job();
        var pipeline = new FakePipeline(call -> {
            throw new VideoImportFailure(code, "Gemini is not answering now");
        });

        worker(pipeline, 2).process(job.jobId());

        var failed = jobService.get(job.jobId());
        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(code);
        assertThat(uploads.get(upload.uploadId()).status()).isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(uploads.videoFile(upload.uploadId())).hasBinaryContent(VIDEO);

        var retry = jobService.submit(uploadRequest(upload.uploadId()));
        assertThat(retry.queued()).isTrue();
        var answering = new FakePipeline(call -> VideoJobFixtures.uploadResult(call.uploadId()));
        worker(answering, 2).process(retry.job().jobId());
        assertThat(answering.calls).singleElement().satisfies(call -> assertThat(call.video()).isEqualTo(VIDEO));
        assertThat(jobService.get(retry.job().jobId()).status()).isEqualTo(ImportJobStatus.SUCCEEDED);
    }

    @Test
    void aJobWhoseFailureCouldNotBeRecordedIsFailedByTheNextRecovery() {
        var flaky = mock(RecipeImportJobRepository.class, delegatesTo(jobs));
        doThrow(new IllegalStateException("The database connection was lost"))
                .doAnswer(invocation -> jobs.fail(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)))
                .when(flaky).fail(any(), any(), any(), any());
        var worker = worker(flaky, new FakePipeline(call -> {
            throw new VideoImportFailure("NOT_A_RECIPE_VIDEO", "The video does not show cooking");
        }));
        worker.resumeAfterStartup();
        var job = jobService.submit(link(WATCH_URL)).job();

        assertThatThrownBy(() -> worker.process(job.jobId())).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.findById(job.jobId()).orElseThrow().getStatus()).isEqualTo(ImportJobStatus.RUNNING);

        worker.recoverStrandedJobs();

        var failed = jobService.get(job.jobId());
        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("INTERNAL_ERROR");
        // The video is no longer blocked for everyone else.
        user.signIn("cook-b");
        assertThat(jobService.submit(link(WATCH_URL)).queued()).isTrue();
    }

    @Test
    void aJobWhoseClaimFailedIsDispatchedAgainByTheNextRecovery() throws InterruptedException {
        var flaky = mock(RecipeImportJobRepository.class, delegatesTo(jobs));
        doThrow(new IllegalStateException("The database connection was lost"))
                .doAnswer(invocation -> jobs.claim(invocation.getArgument(0), invocation.getArgument(1)))
                .when(flaky).claim(any(), any());
        var pipeline = new FakePipeline(call -> VideoJobFixtures.youTubeResult(call.videoId()));
        var worker = worker(flaky, pipeline);
        worker.resumeAfterStartup();
        var job = jobService.submit(link(WATCH_URL)).job();

        worker.onJobQueued(new RecipeImportJobQueuedEvent(job.jobId()));
        verify(flaky, timeout(10_000)).claim(eq(job.jobId()), any());
        assertThat(jobs.findById(job.jobId()).orElseThrow().getStatus()).isEqualTo(ImportJobStatus.QUEUED);

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jobs.findById(job.jobId()).orElseThrow().getStatus() != ImportJobStatus.SUCCEEDED) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The stranded job was never run");
            }
            worker.recoverStrandedJobs();
            Thread.sleep(50);
        }
        assertThat(pipeline.calls).hasSize(1);
    }

    @Test
    void recoveryLeavesJobsThisProcessIsStillRunningAlone() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pipeline = new FakePipeline(call -> {
            started.countDown();
            await(release);
            return VideoJobFixtures.youTubeResult(call.videoId());
        });
        var worker = worker(pipeline, 2);
        worker.resumeAfterStartup();
        var job = jobService.submit(link(WATCH_URL)).job();
        worker.onJobQueued(new RecipeImportJobQueuedEvent(job.jobId()));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        worker.recoverStrandedJobs();

        assertThat(jobs.findById(job.jobId()).orElseThrow().getStatus()).isEqualTo(ImportJobStatus.RUNNING);
        release.countDown();
        awaitStatus(job.jobId(), ImportJobStatus.SUCCEEDED);
        assertThat(pipeline.calls).hasSize(1);
    }

    @Test
    void reportsAnUnexpectedErrorGenericallyAndKeepsTheUploadForARetry() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var job = jobService.submit(uploadRequest(upload.uploadId())).job();
        var pipeline = new FakePipeline(call -> {
            throw new IllegalStateException("parser broke on 비밀 레시피");
        });

        worker(pipeline, 2).process(job.jobId());

        var failed = jobService.get(job.jobId());
        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.errorMessage()).isEqualTo("Unexpected error while importing the video");
        assertThat(uploadRepository.findById(upload.uploadId()).orElseThrow().getStatus())
                .isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(uploads.videoFile(upload.uploadId())).hasBinaryContent(VIDEO);

        var retry = jobService.submit(uploadRequest(upload.uploadId()));
        assertThat(retry.queued()).isTrue();
        assertThat(retry.job().jobId()).isNotEqualTo(job.jobId());
    }

    @Test
    void anUploadWhoseVideoFileVanishedFailsAndMustBeUploadedAgain() throws IOException {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var job = jobService.submit(uploadRequest(upload.uploadId())).job();
        Files.delete(uploads.videoFile(upload.uploadId()));
        var pipeline = new FakePipeline(call -> VideoJobFixtures.uploadResult(call.uploadId()));

        worker(pipeline, 2).process(job.jobId());

        assertThat(pipeline.calls).isEmpty();
        assertThat(jobService.get(job.jobId()).errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(uploadRepository.findById(upload.uploadId()).orElseThrow().getStatus())
                .isEqualTo(VideoUploadStatus.FAILED);
    }

    @Test
    void failsJobsLeftRunningByThePreviousProcessAndRunsTheQueuedOnes() throws InterruptedException {
        var abandoned = jobService.submit(link(watchUrl(VideoJobFixtures.videoId(1)))).job();
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var abandonedUpload = jobService.submit(uploadRequest(upload.uploadId())).job();
        jobs.claim(abandoned.jobId(), NOW);
        jobs.claim(abandonedUpload.jobId(), NOW);
        var waiting = jobService.submit(link(watchUrl(VideoJobFixtures.videoId(2)))).job();
        var pipeline = new FakePipeline(call -> VideoJobFixtures.youTubeResult(call.videoId()));

        worker(pipeline, 2).resumeAfterStartup();

        for (var id : List.of(abandoned.jobId(), abandonedUpload.jobId())) {
            var failed = jobService.get(id);
            assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo("SERVER_RESTARTED");
            assertThat(failed.errorMessage()).isEqualTo("The server restarted while the video was being analyzed");
        }
        awaitStatus(waiting.jobId(), ImportJobStatus.SUCCEEDED);
        assertThat(pipeline.calls).extracting(Call::videoId).containsExactly(VideoJobFixtures.videoId(2));
        // The video was not at fault, so the same upload can be analysed again.
        assertThat(uploadRepository.findById(upload.uploadId()).orElseThrow().getStatus())
                .isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(jobService.submit(uploadRequest(upload.uploadId())).queued()).isTrue();
    }

    @Test
    void runsAtMostTheConfiguredNumberOfJobsAtOnce() throws InterruptedException {
        var release = new CountDownLatch(1);
        var started = new Semaphore(0);
        var running = new AtomicInteger();
        var mostAtOnce = new AtomicInteger();
        var pipeline = new FakePipeline(call -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            started.release();
            await(release);
            running.decrementAndGet();
            return VideoJobFixtures.youTubeResult(call.videoId());
        });
        var worker = worker(pipeline, 2);
        worker.resumeAfterStartup();
        var ids = new ArrayList<UUID>();
        for (var number = 1; number <= 3; number++) {
            var id = jobService.submit(link(watchUrl(VideoJobFixtures.videoId(number)))).job().jobId();
            ids.add(id);
            worker.onJobQueued(new RecipeImportJobQueuedEvent(id));
        }

        assertThat(started.tryAcquire(2, 10, TimeUnit.SECONDS)).isTrue();
        assertThat(started.tryAcquire(1, 300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(ids).filteredOn(id -> jobs.findById(id).orElseThrow().getStatus() == ImportJobStatus.QUEUED)
                .hasSize(1);

        release.countDown();
        for (var id : ids) {
            awaitStatus(id, ImportJobStatus.SUCCEEDED);
        }
        assertThat(mostAtOnce).hasValue(2);
    }

    @Test
    void runsAJobOnlyOnceHoweverOftenItIsDispatched() {
        var job = jobService.submit(link(WATCH_URL)).job();
        var pipeline = new FakePipeline(call -> VideoJobFixtures.youTubeResult(call.videoId()));
        var worker = worker(pipeline, 2);

        worker.process(job.jobId());
        worker.process(job.jobId());

        assertThat(pipeline.calls).hasSize(1);
        assertThat(jobs.findById(job.jobId()).orElseThrow().getAttempts()).isOne();
    }

    @Test
    void keepsJobsQueuedUntilStartupRecoveryHasRun() throws InterruptedException {
        var job = jobService.submit(link(WATCH_URL)).job();
        var pipeline = new FakePipeline(call -> VideoJobFixtures.youTubeResult(call.videoId()));
        var worker = worker(pipeline, 2);

        worker.onJobQueued(new RecipeImportJobQueuedEvent(job.jobId()));

        assertThat(jobs.findById(job.jobId()).orElseThrow().getStatus()).isEqualTo(ImportJobStatus.QUEUED);
        worker.resumeAfterStartup();
        awaitStatus(job.jobId(), ImportJobStatus.SUCCEEDED);
        assertThat(pipeline.calls).hasSize(1);
    }

    @Test
    void aJobInterruptedByShutdownIsReportedAsServerRestartedByTheNextStart() throws InterruptedException {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var job = jobService.submit(uploadRequest(upload.uploadId())).job();
        var started = new CountDownLatch(1);
        // Interrupting the real Gemini call surfaces as an AI outage; that must not become the job's result.
        var pipeline = new FakePipeline(call -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new VideoImportFailure("AI_UNAVAILABLE", "The Gemini request was interrupted");
            }
            return VideoJobFixtures.uploadResult(call.uploadId());
        });
        var worker = worker(pipeline, 2);
        worker.resumeAfterStartup();
        worker.onJobQueued(new RecipeImportJobQueuedEvent(job.jobId()));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        worker.shutDown();

        assertThat(jobs.findById(job.jobId()).orElseThrow().getStatus()).isEqualTo(ImportJobStatus.RUNNING);
        assertThat(uploads.videoFile(upload.uploadId())).hasBinaryContent(VIDEO);

        worker(new FakePipeline(call -> VideoJobFixtures.uploadResult(call.uploadId())), 2).resumeAfterStartup();

        assertThat(jobService.get(job.jobId()).errorCode()).isEqualTo("SERVER_RESTARTED");
        assertThat(uploadRepository.findById(upload.uploadId()).orElseThrow().getStatus())
                .isEqualTo(VideoUploadStatus.COMPLETE);
    }

    private RecipeImportJobWorker worker(VideoRecipeImportPipeline pipeline, int concurrency) {
        return worker(jobs, pipeline, concurrency);
    }

    private RecipeImportJobWorker worker(RecipeImportJobRepository repository, VideoRecipeImportPipeline pipeline) {
        return worker(repository, pipeline, 2);
    }

    private RecipeImportJobWorker worker(
            RecipeImportJobRepository repository,
            VideoRecipeImportPipeline pipeline,
            int concurrency
    ) {
        var properties = VideoUploadTestSupport.properties(uploadRoot, true, 10, concurrency);
        var worker = new RecipeImportJobWorker(
                repository, uploads, pipeline, recipes, transactionManager, properties, clock);
        workers.add(worker);
        return worker;
    }

    private RecipeImportResponse readRecipe(UUID recipeId) {
        return new TransactionTemplate(transactionManager).execute(status -> recipes.getRecipe(recipeId));
    }

    private void awaitStatus(UUID jobId, ImportJobStatus status) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jobs.findById(jobId).orElseThrow().getStatus() != status) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Import job " + jobId + " did not reach " + status);
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test never released the pipeline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static CreateRecipeImportJobRequest link(String url) {
        return new CreateRecipeImportJobRequest(url, null);
    }

    private static CreateRecipeImportJobRequest uploadRequest(UUID uploadId) {
        return new CreateRecipeImportJobRequest(null, uploadId.toString());
    }

    private static String watchUrl(String videoId) {
        return YouTubeUrlPolicy.watchUrl(videoId);
    }

    record Call(String videoId, UUID uploadId, byte[] video, String contentType, Integer durationSeconds) {
    }

    /** Reports the stages the real pipeline reports and notes the stage the database shows after each one. */
    private final class FakePipeline implements VideoRecipeImportPipeline {

        private final Function<Call, VideoImportResult> outcome;
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final List<String> storedStages = new CopyOnWriteArrayList<>();

        FakePipeline(Function<Call, VideoImportResult> outcome) {
            this.outcome = outcome;
        }

        @Override
        public VideoImportResult importYouTube(String videoId, StageListener listener) {
            var call = new Call(videoId, null, null, null, null);
            calls.add(call);
            report(listener, videoId, VideoImportStage.FETCHING_METADATA, VideoImportStage.WATCHING_VIDEO,
                    VideoImportStage.WRITING_RECIPE);
            return outcome.apply(call);
        }

        @Override
        public VideoImportResult importUpload(
                UUID uploadId,
                Path file,
                String contentType,
                Integer durationSeconds,
                StageListener listener
        ) {
            final byte[] video;
            try {
                video = Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var call = new Call(null, uploadId, video, contentType, durationSeconds);
            calls.add(call);
            report(listener, uploadId.toString(), VideoImportStage.UPLOADING_TO_AI, VideoImportStage.WATCHING_VIDEO,
                    VideoImportStage.WRITING_RECIPE);
            return outcome.apply(call);
        }

        private void report(StageListener listener, String sourceRef, VideoImportStage... stages) {
            for (var stage : stages) {
                listener.onStage(stage);
                storedStages.add(jdbc.queryForObject(
                        "SELECT stage FROM recipe_import_job WHERE source_ref = ? AND status = 'RUNNING'",
                        String.class,
                        sourceRef
                ));
            }
        }
    }
}
