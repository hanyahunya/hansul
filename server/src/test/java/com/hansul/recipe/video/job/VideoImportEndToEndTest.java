package com.hansul.recipe.video.job;

import static com.hansul.recipe.video.job.VideoJobFixtures.VIDEO_ID;
import static com.hansul.recipe.video.job.VideoJobFixtures.WATCH_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.recipe.video.StageListener;
import com.hansul.recipe.video.VideoImportResult;
import com.hansul.recipe.video.VideoImportStage;
import com.hansul.recipe.video.VideoRecipeImportPipeline;
import com.hansul.recipe.video.upload.VideoUploadTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

/**
 * The whole application on PostgreSQL behind MockMvc with only the Gemini pipeline faked: security, both
 * controllers and their JSON, the worker started after the commit, the chunk files on disk, the recipe the app
 * reads at the end and the schedule of the upload sweeper.
 */
@SpringBootTest(properties = {
        "hansul.video-import.gemini.api-key=test-key",
        "hansul.video-import.gemini.paid-tier=true",
        "hansul.video-import.upload.chunk-bytes=" + VideoUploadTestSupport.CHUNK_BYTES,
        "hansul.video-import.upload.min-free-bytes=1"
})
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, VideoImportEndToEndTest.TestBeans.class})
class VideoImportEndToEndTest {

    private static final Path UPLOAD_ROOT = createUploadRoot();
    private static final byte[] VIDEO = VideoUploadTestSupport.VIDEO;

    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("hansul.video-import.upload.directory", UPLOAD_ROOT::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatedPipeline pipeline;

    @Autowired
    private List<ScheduledTaskHolder> scheduledTaskHolders;

    @AfterEach
    void deleteRows() {
        pipeline.reset();
        jdbc.update("DELETE FROM recipe_import_job");
        jdbc.update("DELETE FROM video_upload");
        jdbc.update("DELETE FROM recipe_snapshot WHERE source_provider IN ('youtube', 'upload')");
    }

    @AfterAll
    static void deleteUploadRoot() throws IOException {
        FileSystemUtils.deleteRecursively(UPLOAD_ROOT);
    }

    @Test
    void analysesAYouTubeVideoOnceAndAnswersLaterRequestsFromTheSharedRecipe() throws Exception {
        pipeline.hold();
        var queued = createJob(cook("a"), "{\"url\": \"https://youtu.be/" + VIDEO_ID + "?si=shared\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$.sourceKind").value("YOUTUBE"))
                .andExpect(jsonPath("$.sourceUrl").value(WATCH_URL))
                .andExpect(jsonPath("$.recipeId").value(nullValue()));
        var jobId = read(queued, "$.jobId");
        awaitJob(jobId, cook("b"), "$.stage", "FETCHING_METADATA");

        createJob(cook("b"), "{\"url\": \"https://m.youtube.com/watch?v=" + VIDEO_ID + "&t=42\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        pipeline.release();

        var done = awaitJob(jobId, cook("b"), "$.status", "SUCCEEDED");
        var recipeId = JsonPath.<String>read(done, "$.recipeId");
        assertThat(JsonPath.<String>read(done, "$.stage")).isEqualTo("DONE");
        mockMvc.perform(get("/v1/recipes/{id}", recipeId).with(cook("c")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceUrl").value(WATCH_URL))
                .andExpect(jsonPath("$.sourceKind").value("VIDEO_AI"))
                .andExpect(jsonPath("$.parserVersion").value("youtube-gemini-v1"))
                .andExpect(jsonPath("$.video.provider").value("YOUTUBE"))
                .andExpect(jsonPath("$.video.videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$.video.embeddable").value(true))
                .andExpect(jsonPath("$.steps[0].videoStartSeconds").value(6))
                .andExpect(jsonPath("$.steps[0].videoEndSeconds").value(15))
                .andExpect(jsonPath("$.steps[0].keyFrameSeconds").value(11));

        createJob(cook("c"), "{\"url\": \"" + WATCH_URL + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(not(jobId)))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.recipeId").value(recipeId));
        assertThat(pipeline.analysed).containsExactly(VIDEO_ID);
    }

    @Test
    void importsAPhoneVideoSentInChunksForItsOwnerOnly() throws Exception {
        var created = mockMvc.perform(post("/v1/video-uploads").with(cook("a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.chunkSizeBytes").value(VideoUploadTestSupport.CHUNK_BYTES))
                .andExpect(jsonPath("$.chunkCount").value(3))
                .andExpect(jsonPath("$.receivedChunks", empty()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        var uploadId = read(created, "$.uploadId");

        putChunk(uploadId, 0, chunk(0)).andExpect(status().isOk()).andExpect(jsonPath("$.receivedChunks", contains(0)));
        putChunk(uploadId, 2, chunk(2)).andExpect(status().isOk()).andExpect(jsonPath("$.receivedChunks", contains(0, 2)));
        putChunk(uploadId, 1, "too long".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VIDEO_CHUNK_INVALID"));
        mockMvc.perform(post("/v1/video-uploads").with(cook("a")).contentType(MediaType.APPLICATION_JSON).content(uploadBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId))
                .andExpect(jsonPath("$.receivedChunks", contains(0, 2)));
        createJob(cook("a"), "{\"uploadId\": \"" + uploadId + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_INCOMPLETE"));

        putChunk(uploadId, 1, chunk(1)).andExpect(status().isOk()).andExpect(jsonPath("$.receivedChunks", contains(0, 1, 2)));
        var queued = createJob(cook("a"), "{\"uploadId\": \"" + uploadId + "\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceKind").value("UPLOAD"))
                .andExpect(jsonPath("$.sourceUrl").value(nullValue()));
        var jobId = read(queued, "$.jobId");
        mockMvc.perform(get("/v1/recipe-import-jobs/{jobId}", jobId).with(cook("b")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_JOB_NOT_FOUND"));

        var recipeId = JsonPath.<String>read(awaitJob(jobId, cook("a"), "$.status", "SUCCEEDED"), "$.recipeId");
        assertThat(pipeline.analysed).containsExactly(new String(VIDEO, StandardCharsets.US_ASCII));
        mockMvc.perform(get("/v1/recipes/{id}", recipeId).with(cook("a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceUrl").value("hansul-upload:" + uploadId))
                .andExpect(jsonPath("$.sourceKind").value("VIDEO_AI"))
                .andExpect(jsonPath("$.video.provider").value("UPLOAD"))
                .andExpect(jsonPath("$.video.videoId").value(nullValue()))
                .andExpect(jsonPath("$.video.embeddable").value(false));
        mockMvc.perform(get("/v1/recipes/{id}", recipeId).with(cook("b")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPE_NOT_FOUND"));
        mockMvc.perform(get("/v1/video-uploads/{uploadId}", uploadId).with(cook("a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"));
        mockMvc.perform(get("/v1/video-uploads/{uploadId}", uploadId).with(cook("b")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VIDEO_UPLOAD_NOT_FOUND"));
        awaitDeleted(UPLOAD_ROOT.resolve(uploadId));

        createJob(cook("a"), "{\"uploadId\": \"" + uploadId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.recipeId").value(recipeId));
    }

    @Test
    void recoversStrandedImportJobsEveryMinute() {
        var recoveries = scheduledTaskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().endsWith("RecipeImportJobWorker.recoverStrandedJobs"))
                .toList();

        assertThat(recoveries).singleElement().isInstanceOfSatisfying(FixedDelayTask.class, task -> {
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void sweepsUploadsEveryFifteenMinutes() {
        var sweeps = scheduledTaskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().endsWith("VideoUploadSweeper.sweep"))
                .toList();

        assertThat(sweeps).singleElement().isInstanceOfSatisfying(FixedDelayTask.class, task -> {
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(15));
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    private ResultActions createJob(RequestPostProcessor user, String body) throws Exception {
        return mockMvc.perform(post("/v1/recipe-import-jobs").with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putChunk(String uploadId, int index, byte[] bytes) throws Exception {
        return mockMvc.perform(put("/v1/video-uploads/{uploadId}/chunks/{index}", uploadId, index)
                .with(cook("a"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(bytes));
    }

    private String awaitJob(String jobId, RequestPostProcessor user, String path, String expected) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            var body = mockMvc.perform(get("/v1/recipe-import-jobs/{jobId}", jobId).with(user))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (expected.equals(JsonPath.read(body, path))) {
                return body;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Import job " + jobId + " never had " + path + " = " + expected + ": " + body);
            }
            Thread.sleep(20);
        }
    }

    // The worker deletes the video right after the commit that makes the job SUCCEEDED.
    private static void awaitDeleted(Path directory) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(directory)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(directory + " was not deleted");
            }
            Thread.sleep(20);
        }
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static RequestPostProcessor cook(String name) {
        return jwt().jwt(token -> token.subject("cook-" + name));
    }

    private static String uploadBody() {
        return """
                {"contentType": "video/mp4", "sizeBytes": %d, "sha256": "%s", "durationSeconds": 612}
                """.formatted(VIDEO.length, VideoUploadTestSupport.sha256(VIDEO));
    }

    private static byte[] chunk(int index) {
        var from = index * VideoUploadTestSupport.CHUNK_BYTES;
        return Arrays.copyOfRange(VIDEO, from, Math.min(VIDEO.length, from + VideoUploadTestSupport.CHUNK_BYTES));
    }

    private static Path createUploadRoot() {
        try {
            return Files.createTempDirectory("hansul-video-uploads-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        GatedPipeline gatedPipeline() {
            return new GatedPipeline();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("JWT decoding is not used by MockMvc jwt() tests");
            };
        }
    }

    /** Reports the stages the real pipeline reports; a test can hold a YouTube analysis to see it in flight. */
    static final class GatedPipeline implements VideoRecipeImportPipeline {

        private final List<String> analysed = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        void reset() {
            release();
            analysed.clear();
        }

        @Override
        public VideoImportResult importYouTube(String videoId, StageListener listener) {
            analysed.add(videoId);
            listener.onStage(VideoImportStage.FETCHING_METADATA);
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The test never released the analysis");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            listener.onStage(VideoImportStage.WATCHING_VIDEO);
            listener.onStage(VideoImportStage.WRITING_RECIPE);
            return VideoJobFixtures.youTubeResult(videoId);
        }

        @Override
        public VideoImportResult importUpload(
                UUID uploadId,
                Path file,
                String contentType,
                Integer durationSeconds,
                StageListener listener
        ) {
            listener.onStage(VideoImportStage.UPLOADING_TO_AI);
            try {
                analysed.add(Files.readString(file, StandardCharsets.US_ASCII));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            listener.onStage(VideoImportStage.WATCHING_VIDEO);
            listener.onStage(VideoImportStage.WRITING_RECIPE);
            return VideoJobFixtures.uploadResult(uploadId);
        }
    }
}
