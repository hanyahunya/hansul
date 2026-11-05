package com.hansul.recipe.video.job;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hansul.auth.config.SecurityConfig;
import com.hansul.recipe.infrastructure.source.InvalidRecipeUrlException;
import com.hansul.recipe.infrastructure.source.UnsupportedVideoPlatformException;
import com.hansul.recipe.video.upload.VideoUploadException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = RecipeImportJobController.class)
@Import({SecurityConfig.class, RecipeImportJobControllerTest.JwtTestConfiguration.class})
class RecipeImportJobControllerTest {

    private static final UUID JOB_ID = UUID.fromString("7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d");
    private static final UUID RECIPE_ID = UUID.fromString("0b6f4a51-3f7e-4d38-9a77-5a8f2f0c1d2e");
    private static final String WATCH_URL = "https://www.youtube.com/watch?v=AbCdEfGhIjK";
    private static final String BODY = "{\"url\":\"" + WATCH_URL + "\"}";

    // The Android app's RecipeImportModelsTest decodes these exact bodies; keep both copies identical.
    private static final String QUEUED_JOB_JSON = """
            {"jobId": "7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d", "status": "QUEUED", "stage": "QUEUED", "recipeId": null,
             "errorCode": null, "errorMessage": null, "sourceKind": "YOUTUBE",
             "sourceUrl": "https://www.youtube.com/watch?v=AbCdEfGhIjK",
             "createdAt": "2026-11-05T09:00:00Z", "updatedAt": "2026-11-05T09:00:00Z"}
            """;
    private static final String FAILED_UPLOAD_JOB_JSON = """
            {"jobId": "7d4c1f62-2b8e-4f0a-9c3d-5e6f7a8b9c0d", "status": "FAILED", "stage": "WATCHING_VIDEO",
             "recipeId": null, "errorCode": "NOT_A_RECIPE_VIDEO", "errorMessage": "The video does not show cooking",
             "sourceKind": "UPLOAD", "sourceUrl": null,
             "createdAt": "2026-11-05T09:00:00Z", "updatedAt": "2026-11-05T09:03:30.123456Z"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecipeImportJobService service;

    @Test
    void answers202WithTheWholeJobWhenANewAnalysisIsQueued() throws Exception {
        when(service.submit(new CreateRecipeImportJobRequest(WATCH_URL, null)))
                .thenReturn(new RecipeImportJobService.Submission(queuedJob(), true));

        mockMvc.perform(create(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$", hasKey("recipeId")))
                .andExpect(jsonPath("$.recipeId").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("errorCode")))
                .andExpect(jsonPath("$.errorCode").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("errorMessage")))
                .andExpect(jsonPath("$.errorMessage").value(nullValue()))
                .andExpect(jsonPath("$.sourceKind").value("YOUTUBE"))
                .andExpect(jsonPath("$.sourceUrl").value(WATCH_URL))
                .andExpect(jsonPath("$.createdAt").value("2026-11-05T09:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-05T09:00:00Z"));
    }

    @Test
    void answers200ForAJobInFlightOrACachedRecipe() throws Exception {
        var cached = new RecipeImportJobResponse(JOB_ID, ImportJobStatus.SUCCEEDED, ImportJobStage.DONE, RECIPE_ID,
                null, null, ImportJobSourceKind.YOUTUBE, WATCH_URL, Instant.EPOCH, Instant.EPOCH);
        when(service.submit(new CreateRecipeImportJobRequest(WATCH_URL, null)))
                .thenReturn(new RecipeImportJobService.Submission(cached, false));

        mockMvc.perform(create(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stage").value("DONE"))
                .andExpect(jsonPath("$.recipeId").value(RECIPE_ID.toString()));
    }

    @Test
    void passesAnUploadIdThroughAndReportsAFailedUploadJob() throws Exception {
        var uploadId = UUID.randomUUID().toString();
        var failed = new RecipeImportJobResponse(JOB_ID, ImportJobStatus.FAILED, ImportJobStage.WATCHING_VIDEO, null,
                "NOT_A_RECIPE_VIDEO", "The video does not show cooking", ImportJobSourceKind.UPLOAD, null,
                Instant.EPOCH, Instant.EPOCH);
        when(service.submit(new CreateRecipeImportJobRequest(null, uploadId)))
                .thenReturn(new RecipeImportJobService.Submission(failed, false));
        when(service.get(JOB_ID)).thenReturn(failed);

        mockMvc.perform(create("{\"uploadId\":\"" + uploadId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKind").value("UPLOAD"));
        mockMvc.perform(get("/v1/recipe-import-jobs/{jobId}", JOB_ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage").value("WATCHING_VIDEO"))
                .andExpect(jsonPath("$.errorCode").value("NOT_A_RECIPE_VIDEO"))
                .andExpect(jsonPath("$.errorMessage").value("The video does not show cooking"))
                .andExpect(jsonPath("$", hasKey("sourceUrl")))
                .andExpect(jsonPath("$.sourceUrl").value(nullValue()));
    }

    @Test
    void writesExactlyTheJobBodiesTheAndroidAppDecodes() throws Exception {
        var failed = new RecipeImportJobResponse(JOB_ID, ImportJobStatus.FAILED, ImportJobStage.WATCHING_VIDEO, null,
                "NOT_A_RECIPE_VIDEO", "The video does not show cooking", ImportJobSourceKind.UPLOAD, null,
                Instant.parse("2026-11-05T09:00:00Z"), Instant.parse("2026-11-05T09:03:30.123456Z"));
        when(service.submit(new CreateRecipeImportJobRequest(WATCH_URL, null)))
                .thenReturn(new RecipeImportJobService.Submission(queuedJob(), true));
        when(service.get(JOB_ID)).thenReturn(failed);

        mockMvc.perform(create(BODY))
                .andExpect(status().isAccepted())
                .andExpect(content().json(QUEUED_JOB_JSON, JsonCompareMode.STRICT));
        mockMvc.perform(get("/v1/recipe-import-jobs/{jobId}", JOB_ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().json(FAILED_UPLOAD_JOB_JSON, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_REQUEST, 400",
            "USE_RECIPE_IMPORTS, 400",
            "IMPORT_JOB_NOT_FOUND, 404",
            "VIDEO_IMPORT_DAILY_LIMIT, 429",
            "VIDEO_ANALYSIS_UNAVAILABLE, 503"
    })
    void mapsEachRefusalToItsStatusAndCode(RecipeImportJobException.Code code, int status) throws Exception {
        when(service.submit(new CreateRecipeImportJobRequest(WATCH_URL, null)))
                .thenThrow(new RecipeImportJobException(code, "refused"));

        mockMvc.perform(create(BODY))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value("refused"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "VIDEO_UPLOAD_NOT_FOUND, 404",
            "VIDEO_UPLOAD_CLOSED, 409",
            "UPLOAD_INCOMPLETE, 409",
            "UPLOAD_CHECKSUM_MISMATCH, 409"
    })
    void reportsTheUploadProblemsFoundWhileQueueing(VideoUploadException.Code code, int status) throws Exception {
        var uploadId = UUID.randomUUID().toString();
        when(service.submit(new CreateRecipeImportJobRequest(null, uploadId)))
                .thenThrow(new VideoUploadException(code, "upload refused"));

        mockMvc.perform(create("{\"uploadId\":\"" + uploadId + "\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    void sharesTheLinkErrorsOfTheRecipeImports() throws Exception {
        when(service.submit(new CreateRecipeImportJobRequest("https://www.instagram.com/reel/C1a2b3c4d5e/", null)))
                .thenThrow(new UnsupportedVideoPlatformException("Instagram"));
        when(service.submit(new CreateRecipeImportJobRequest("https://example.com/", null)))
                .thenThrow(new InvalidRecipeUrlException("Only 10000recipe recipe pages and YouTube videos are supported"));

        mockMvc.perform(create("{\"url\":\"https://www.instagram.com/reel/C1a2b3c4d5e/\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VIDEO_LINK_NOT_SUPPORTED"));
        mockMvc.perform(create("{\"url\":\"https://example.com/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECIPE_URL"));
    }

    @Test
    void requiresAnAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/v1/recipe-import-jobs").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/recipe-import-jobs/{jobId}", JOB_ID))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/v1/recipe-import-jobs").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RecipeImportJobResponse queuedJob() {
        var created = Instant.parse("2026-11-05T09:00:00Z");
        return new RecipeImportJobResponse(JOB_ID, ImportJobStatus.QUEUED, ImportJobStage.QUEUED, null, null, null,
                ImportJobSourceKind.YOUTUBE, WATCH_URL, created, created);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("JWT decoding is not used by MockMvc jwt() tests");
            };
        }
    }
}
