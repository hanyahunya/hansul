package com.hansul.recipe.video.job;

import static com.hansul.recipe.video.job.VideoJobFixtures.VIDEO_ID;
import static com.hansul.recipe.video.job.VideoJobFixtures.WATCH_URL;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.VIDEO;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.request;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.sendAllChunks;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.sendChunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.recipe.application.RecipeImportService;
import com.hansul.recipe.application.RecipeImportServiceFixture;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.InvalidRecipeUrlException;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.UnsupportedVideoPlatformException;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import com.hansul.recipe.video.VideoImportProperties;
import com.hansul.recipe.video.job.RecipeImportJobException.Code;
import com.hansul.recipe.video.upload.VideoUploadException;
import com.hansul.recipe.video.upload.VideoUploadRepository;
import com.hansul.recipe.video.upload.VideoUploadService;
import com.hansul.recipe.video.upload.VideoUploadStatus;
import com.hansul.recipe.video.upload.VideoUploadStorage;
import com.hansul.recipe.video.upload.VideoUploadTestSupport;
import com.hansul.recipe.video.upload.VideoUploadTestSupport.MutableClock;
import com.hansul.recipe.video.upload.VideoUploadTestSupport.SwitchableUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Against PostgreSQL without a test transaction, so every repository call commits on its own as in production
 * and the in-flight index really rejects a second insert. Gemini, the user and the clock are fakes.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecipeImportJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    @Autowired
    private RecipeImportJobRepository jobs;

    @Autowired
    private VideoUploadRepository uploadRepository;

    @Autowired
    private RecipeSnapshotRepository snapshots;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path uploadRoot;

    private final SwitchableUser user = new SwitchableUser("cook-a");
    private final MutableClock clock = new MutableClock(NOW);
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private RecipeImportService recipes;
    private VideoUploadService uploads;
    private RecipeImportJobService service;

    @BeforeEach
    void setUp() {
        recipes = RecipeImportServiceFixture.create(snapshots, user, clock);
        uploads = uploadService(true);
        service = service(jobs, properties(true, 10));
    }

    @AfterEach
    void deleteRows() {
        jdbc.update("DELETE FROM recipe_import_job");
        jdbc.update("DELETE FROM video_upload");
        jdbc.update("DELETE FROM recipe_snapshot WHERE source_provider IN ('youtube', 'upload')");
    }

    @Test
    void queuesANewYouTubeAnalysisUnderTheCanonicalWatchUrl() {
        var submission = service.submit(link("https://youtu.be/" + VIDEO_ID + "?si=shared"));

        assertThat(submission.queued()).isTrue();
        var job = submission.job();
        assertThat(job.status()).isEqualTo(ImportJobStatus.QUEUED);
        assertThat(job.stage()).isEqualTo(ImportJobStage.QUEUED);
        assertThat(job.sourceKind()).isEqualTo(ImportJobSourceKind.YOUTUBE);
        assertThat(job.sourceUrl()).isEqualTo(WATCH_URL);
        assertThat(job.recipeId()).isNull();
        assertThat(job.errorCode()).isNull();
        assertThat(job.createdAt()).isEqualTo(NOW);
        assertThat(events).containsExactly(new RecipeImportJobQueuedEvent(job.jobId()));

        var row = jobs.findById(job.jobId()).orElseThrow();
        assertThat(row.getOwnerSubject()).isEqualTo("cook-a");
        assertThat(row.getSourceProvider()).isEqualTo("youtube");
        assertThat(row.getSourceRef()).isEqualTo(VIDEO_ID);
        assertThat(row.isCacheHit()).isFalse();
        assertThat(row.getAttempts()).isZero();
        assertThat(service.get(job.jobId())).isEqualTo(job);
    }

    @Test
    void answersFromTheSharedCacheWithASucceededCacheHitJob() {
        var cached = recipes.saveVideoRecipe(
                YouTubeUrlPolicy.forVideoId(VIDEO_ID),
                VideoJobFixtures.youTubeResult(VIDEO_ID).recipe(),
                "youtube-gemini-v1",
                null
        );

        var submission = service.submit(link("https://www.youtube.com/shorts/" + VIDEO_ID));

        assertThat(submission.queued()).isFalse();
        assertThat(submission.job().status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(submission.job().stage()).isEqualTo(ImportJobStage.DONE);
        assertThat(submission.job().recipeId()).isEqualTo(cached);
        assertThat(jobs.findById(submission.job().jobId()).orElseThrow().isCacheHit()).isTrue();
        assertThat(jobs.findInFlight("youtube", VIDEO_ID)).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void ignoresRecipesOfAnotherParserVersionOrOwner() {
        var recipe = VideoJobFixtures.youTubeResult(VIDEO_ID).recipe();
        recipes.saveVideoRecipe(YouTubeUrlPolicy.forVideoId(VIDEO_ID), recipe, "youtube-gemini-v0", null);
        recipes.saveVideoRecipe(YouTubeUrlPolicy.forVideoId(VIDEO_ID), recipe, "youtube-gemini-v1", "cook-b");

        assertThat(service.submit(link(WATCH_URL)).queued()).isTrue();
    }

    @Test
    void joinsTheJobAlreadyInFlightForTheSameVideoWhoeverStartedIt() {
        var first = service.submit(link(WATCH_URL)).job();

        user.signIn("cook-b");
        var second = service.submit(link("https://m.youtube.com/watch?v=" + VIDEO_ID + "&t=42"));

        assertThat(second.queued()).isFalse();
        assertThat(second.job().jobId()).isEqualTo(first.jobId());
        assertThat(jobs.count()).isOne();
        assertThat(events).hasSize(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLostInsertRaceOnTheInFlightIndexReturnsTheJobThatWon(CapturedOutput output) {
        user.signIn("cook-b");
        var winner = service.submit(link(WATCH_URL)).job();
        user.signIn("cook-a");
        var staleOnce = new AtomicBoolean(true);
        var racing = mock(RecipeImportJobRepository.class, delegatesTo(jobs));
        doAnswer(invocation -> staleOnce.getAndSet(false) ? Optional.empty() : jobs.findInFlight("youtube", VIDEO_ID))
                .when(racing).findInFlight("youtube", VIDEO_ID);

        var loser = service(racing, properties(true, 10)).submit(link(WATCH_URL));

        assertThat(staleOnce).isFalse();
        assertThat(loser.queued()).isFalse();
        assertThat(loser.job().jobId()).isEqualTo(winner.jobId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_import_job", Long.class)).isOne();
        assertThat(events).containsExactly(new RecipeImportJobQueuedEvent(winner.jobId()));
        assertThat(output).doesNotContain("uq_recipe_import_job_in_flight");
    }

    @Test
    void limitsEachUserToTheDailyNumberOfAnalyses() {
        var limited = service(jobs, properties(true, 3));
        for (var number = 1; number <= 3; number++) {
            assertThat(limited.submit(link(watchUrl(VideoJobFixtures.videoId(number)))).queued()).isTrue();
        }
        jdbc.update("UPDATE recipe_import_job SET status = 'FAILED' WHERE source_ref = ?", VideoJobFixtures.videoId(1));

        assertRefused(() -> limited.submit(link(watchUrl(VideoJobFixtures.videoId(4)))), Code.VIDEO_IMPORT_DAILY_LIMIT);

        // Joining an in-flight job and answering from the cache are not analyses.
        assertThat(limited.submit(link(watchUrl(VideoJobFixtures.videoId(2)))).job().status())
                .isEqualTo(ImportJobStatus.QUEUED);
        recipes.saveVideoRecipe(YouTubeUrlPolicy.forVideoId(VIDEO_ID),
                VideoJobFixtures.youTubeResult(VIDEO_ID).recipe(), "youtube-gemini-v1", null);
        assertThat(limited.submit(link(WATCH_URL)).job().status()).isEqualTo(ImportJobStatus.SUCCEEDED);

        user.signIn("cook-b");
        assertThat(limited.submit(link(watchUrl(VideoJobFixtures.videoId(5)))).queued()).isTrue();

        user.signIn("cook-a");
        clock.advance(Duration.ofHours(24));
        assertThat(limited.submit(link(watchUrl(VideoJobFixtures.videoId(6)))).queued()).isTrue();
    }

    @Test
    void refusesWhileVideoAnalysisIsNotConfigured() {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        var unconfigured = service(jobs, properties(false, 10));

        assertRefused(() -> unconfigured.submit(link(WATCH_URL)), Code.VIDEO_ANALYSIS_UNAVAILABLE);
        assertRefused(() -> unconfigured.submit(uploadRequest(upload.uploadId())), Code.VIDEO_ANALYSIS_UNAVAILABLE);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void analysesYouTubeLinksButNotPhoneVideosUnlessTheGeminiKeyIsPaidTier() {
        var unpaid = VideoUploadTestSupport.properties(uploadRoot, true, false, 10, 2);
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);

        assertThat(service(jobs, unpaid).submit(link(WATCH_URL)).queued()).isTrue();
        assertRefused(() -> service(jobs, unpaid).submit(uploadRequest(upload.uploadId())),
                Code.VIDEO_ANALYSIS_UNAVAILABLE);
        assertThat(jobs.findInFlight("upload", upload.uploadId().toString())).isEmpty();
    }

    @Test
    void aRequestRacingTheFinishOfTheSameVideosAnalysisGetsItsRecipe() {
        var running = service.submit(link(WATCH_URL)).job();
        jobs.claim(running.jobId(), NOW);
        var finishing = mock(RecipeImportJobRepository.class, delegatesTo(jobs));
        var recipeId = new AtomicReference<UUID>();
        // The worker's success commit lands between the request's two reads.
        doAnswer(invocation -> {
            if (recipeId.get() == null) {
                recipeId.set(recipes.saveVideoRecipe(YouTubeUrlPolicy.forVideoId(VIDEO_ID),
                        VideoJobFixtures.youTubeResult(VIDEO_ID).recipe(), "youtube-gemini-v1", null));
                jobs.succeed(running.jobId(), recipeId.get(), 1, 1, NOW);
            }
            return jobs.findInFlight(invocation.getArgument(0), invocation.getArgument(1));
        }).when(finishing).findInFlight("youtube", VIDEO_ID);
        user.signIn("cook-b");

        var submission = service(finishing, properties(true, 10)).submit(link(WATCH_URL));

        assertThat(submission.queued()).isFalse();
        assertThat(submission.job().status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(submission.job().recipeId()).isEqualTo(recipeId.get());
        assertThat(jobs.findInFlight("youtube", VIDEO_ID)).isEmpty();
        assertThat(events).containsExactly(new RecipeImportJobQueuedEvent(running.jobId()));
    }

    @Test
    void aRequestRacingTheFinishOfTheSameUploadsAnalysisGetsItsRecipe() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var running = service.submit(uploadRequest(upload.uploadId())).job();
        jobs.claim(running.jobId(), NOW);
        var finishing = mock(RecipeImportJobRepository.class, delegatesTo(jobs));
        var recipeId = new AtomicReference<UUID>();
        doAnswer(invocation -> {
            if (recipeId.get() == null) {
                recipeId.set(recipes.saveVideoRecipe(CanonicalRecipeUrl.forUpload(upload.uploadId()),
                        VideoJobFixtures.uploadResult(upload.uploadId()).recipe(), "upload-gemini-v1", "cook-a"));
                jobs.succeed(running.jobId(), recipeId.get(), 1, 1, NOW);
                uploads.markConsumed(upload.uploadId(), recipeId.get());
            }
            return jobs.findInFlight(invocation.getArgument(0), invocation.getArgument(1));
        }).when(finishing).findInFlight("upload", upload.uploadId().toString());

        var submission = service(finishing, properties(true, 10)).submit(uploadRequest(upload.uploadId()));

        assertThat(submission.queued()).isFalse();
        assertThat(submission.job().status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(submission.job().recipeId()).isEqualTo(recipeId.get());
        assertThat(jobs.findById(submission.job().jobId()).orElseThrow().isCacheHit()).isTrue();
    }

    @Test
    void anUploadWhoseVideoFileIsGoneQueuesNothingAndMustBeUploadedAgain() throws Exception {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var failed = service.submit(uploadRequest(upload.uploadId())).job();
        jdbc.update("UPDATE recipe_import_job SET status = 'FAILED' WHERE id = ?", failed.jobId());
        Files.delete(uploads.videoFile(upload.uploadId()));

        assertThatThrownBy(() -> service.submit(uploadRequest(upload.uploadId())))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> assertThat(exception.code())
                        .isEqualTo(VideoUploadException.Code.VIDEO_UPLOAD_CLOSED));
        assertThat(uploads.get(upload.uploadId()).status()).isEqualTo(VideoUploadStatus.FAILED);
        assertThat(jobs.findInFlight("upload", upload.uploadId().toString())).isEmpty();
    }

    @Test
    void refusesThePlaylistAndChannelLiveWordsAsVideoIds() {
        for (var url : List.of("https://www.youtube.com/watch?v=videoseries", "https://youtu.be/live_stream")) {
            assertThatThrownBy(() -> service.submit(link(url))).isInstanceOf(InvalidRecipeUrlException.class);
        }
        assertThat(jobs.count()).isZero();
    }

    @Test
    void validatesTheRequestBody() {
        assertRefused(() -> service.submit(new CreateRecipeImportJobRequest(WATCH_URL, UUID.randomUUID().toString())),
                Code.INVALID_REQUEST);
        assertRefused(() -> service.submit(new CreateRecipeImportJobRequest(null, null)), Code.INVALID_REQUEST);
        assertRefused(() -> service.submit(new CreateRecipeImportJobRequest(" ", "")), Code.INVALID_REQUEST);
        assertRefused(() -> service.submit(new CreateRecipeImportJobRequest(null, "not-a-uuid")), Code.INVALID_REQUEST);
        assertRefused(() -> service.submit(new CreateRecipeImportJobRequest(null, "1-1-1-1-1")), Code.INVALID_REQUEST);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void sendsOtherLinksBackToTheirOwnEndpointOrGuidance() {
        assertRefused(() -> service.submit(link("https://www.10000recipe.com/recipe/6944989")), Code.USE_RECIPE_IMPORTS);
        assertThatThrownBy(() -> service.submit(link("https://www.instagram.com/reel/C1a2b3c4d5e/")))
                .isInstanceOf(UnsupportedVideoPlatformException.class);
        assertThatThrownBy(() -> service.submit(link("https://www.youtube.com/playlist?list=PL123")))
                .isInstanceOf(InvalidRecipeUrlException.class);
        assertThatThrownBy(() -> service.submit(link("https://example.com/video")))
                .isInstanceOf(InvalidRecipeUrlException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void showsYouTubeJobsToEveryoneButUploadJobsOnlyToTheirOwner() {
        var youTubeJob = service.submit(link(WATCH_URL)).job();
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var uploadJob = service.submit(uploadRequest(upload.uploadId())).job();

        user.signIn("cook-b");
        assertThat(service.get(youTubeJob.jobId())).isEqualTo(youTubeJob);
        assertRefused(() -> service.get(uploadJob.jobId()), Code.IMPORT_JOB_NOT_FOUND);
        assertRefused(() -> service.get(UUID.randomUUID()), Code.IMPORT_JOB_NOT_FOUND);

        user.signIn("cook-a");
        assertThat(service.get(uploadJob.jobId()).sourceKind()).isEqualTo(ImportJobSourceKind.UPLOAD);
    }

    @Test
    void assemblesAFullyUploadedVideoAndQueuesItsAnalysis() throws Exception {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);

        var submission = service.submit(uploadRequest(upload.uploadId()));

        assertThat(submission.queued()).isTrue();
        assertThat(submission.job().sourceKind()).isEqualTo(ImportJobSourceKind.UPLOAD);
        assertThat(submission.job().sourceUrl()).isNull();
        var row = jobs.findById(submission.job().jobId()).orElseThrow();
        assertThat(row.getSourceProvider()).isEqualTo("upload");
        assertThat(row.getSourceRef()).isEqualTo(upload.uploadId().toString());
        assertThat(uploadRepository.findById(upload.uploadId()).orElseThrow().getStatus())
                .isEqualTo(VideoUploadStatus.COMPLETE);
        try (var files = Files.list(uploadRoot.resolve(upload.uploadId().toString()))) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("video");
        }
        assertThat(uploads.videoFile(upload.uploadId())).hasBinaryContent(VIDEO);

        var again = service.submit(uploadRequest(upload.uploadId()));
        assertThat(again.queued()).isFalse();
        assertThat(again.job().jobId()).isEqualTo(submission.job().jobId());
        assertThat(events).containsExactly(new RecipeImportJobQueuedEvent(submission.job().jobId()));
    }

    @Test
    void refusesAnUploadThatIsStillMissingChunks() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendChunk(uploads, upload.uploadId(), 0, VIDEO);
        sendChunk(uploads, upload.uploadId(), 2, VIDEO);

        assertThatThrownBy(() -> service.submit(uploadRequest(upload.uploadId())))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(VideoUploadException.Code.UPLOAD_INCOMPLETE);
                    assertThat(exception.getMessage()).startsWith("1 of 3 chunks");
                });
        assertThat(uploads.get(upload.uploadId()).status()).isEqualTo(VideoUploadStatus.RECEIVING);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void failsAnUploadWhoseChunksDoNotMatchTheDeclaredChecksum() {
        var declared = VideoUploadTestSupport.request("9876543210".getBytes(), 612);
        var upload = uploads.create(declared).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);

        assertThatThrownBy(() -> service.submit(uploadRequest(upload.uploadId())))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> assertThat(exception.code())
                        .isEqualTo(VideoUploadException.Code.UPLOAD_CHECKSUM_MISMATCH));

        assertThat(uploads.get(upload.uploadId()).status()).isEqualTo(VideoUploadStatus.FAILED);
        assertThat(uploadRoot.resolve(upload.uploadId().toString())).doesNotExist();
        assertThatThrownBy(() -> service.submit(uploadRequest(upload.uploadId())))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> assertThat(exception.code())
                        .isEqualTo(VideoUploadException.Code.VIDEO_UPLOAD_CLOSED));
        assertThat(jobs.count()).isZero();
    }

    @Test
    void answersAnAnalysedUploadFromItsRecipe() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        var recipeId = recipes.saveVideoRecipe(
                CanonicalRecipeUrl.forUpload(upload.uploadId()),
                VideoJobFixtures.uploadResult(upload.uploadId()).recipe(),
                "upload-gemini-v1",
                "cook-a"
        );
        jdbc.update("UPDATE video_upload SET status = 'CONSUMED', recipe_id = ? WHERE id = ?", recipeId, upload.uploadId());

        var submission = service.submit(uploadRequest(upload.uploadId()));

        assertThat(submission.queued()).isFalse();
        assertThat(submission.job().status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(submission.job().recipeId()).isEqualTo(recipeId);
        assertThat(jobs.findById(submission.job().jobId()).orElseThrow().isCacheHit()).isTrue();
    }

    @Test
    void treatsSomeoneElsesUploadAsMissing() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);

        user.signIn("cook-b");

        assertThatThrownBy(() -> service.submit(uploadRequest(upload.uploadId())))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> assertThat(exception.code())
                        .isEqualTo(VideoUploadException.Code.VIDEO_UPLOAD_NOT_FOUND));
        assertThat(uploads.findOwned(upload.uploadId(), "cook-a").getStatus()).isEqualTo(VideoUploadStatus.RECEIVING);
    }

    @Test
    void refusesAnExpiredUpload() {
        var upload = uploads.create(request(VIDEO, 612)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        clock.advance(Duration.ofHours(6));

        assertThatThrownBy(() -> service.submit(uploadRequest(upload.uploadId())))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> assertThat(exception.code())
                        .isEqualTo(VideoUploadException.Code.VIDEO_UPLOAD_CLOSED));
        assertThat(uploads.findOwned(upload.uploadId(), "cook-a").getStatus()).isEqualTo(VideoUploadStatus.EXPIRED);
    }

    private RecipeImportJobService service(RecipeImportJobRepository repository, VideoImportProperties properties) {
        return new RecipeImportJobService(
                new RecipeSourcePolicy(new Hundred00RecipeUrlPolicy(), new YouTubeUrlPolicy()),
                recipes,
                repository,
                uploads,
                properties,
                user,
                events::add,
                clock
        );
    }

    private VideoUploadService uploadService(boolean geminiConfigured) {
        var properties = properties(geminiConfigured, 10);
        return new VideoUploadService(uploadRepository, new VideoUploadStorage(properties), properties, user, clock);
    }

    private VideoImportProperties properties(boolean geminiConfigured, int dailyLimit) {
        return VideoUploadTestSupport.properties(uploadRoot, geminiConfigured, dailyLimit, 2);
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

    private static void assertRefused(ThrowingCallable call, Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RecipeImportJobException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
