package com.hansul.recipe.video.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hansul.auth.config.SecurityConfig;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

@WebMvcTest(controllers = VideoUploadController.class)
@Import({SecurityConfig.class, VideoUploadControllerTest.JwtTestConfiguration.class})
class VideoUploadControllerTest {

    private static final UUID UPLOAD_ID = UUID.fromString("5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10");
    private static final String SHA256 = "84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882";
    private static final CreateVideoUploadRequest REQUEST =
            new CreateVideoUploadRequest("video/mp4", 123_456_789L, SHA256, 612);
    private static final String BODY = """
            {"contentType": "video/mp4", "sizeBytes": 123456789, "sha256": "%s", "durationSeconds": 612}
            """.formatted(SHA256);

    // The Android app's RecipeImportModelsTest encodes this request and decodes this exact body; keep them identical.
    private static final String APP_REQUEST = """
            {"contentType":"video/mp4","sizeBytes":123456789,"sha256":"%s","durationSeconds":612}""".formatted(SHA256);
    private static final String UPLOAD_JSON = """
            {"uploadId": "5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10", "status": "RECEIVING", "chunkSizeBytes": 4194304,
             "chunkCount": 30, "receivedChunks": [0, 1, 2], "expiresAt": "2026-11-05T15:00:00Z"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoUploadService uploads;

    @Test
    void answers201WithTheChunkLayoutOfANewUpload() throws Exception {
        when(uploads.create(REQUEST)).thenReturn(new VideoUploadService.Created(upload(List.of()), true));

        mockMvc.perform(post("/v1/video-uploads").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID.toString()))
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.chunkSizeBytes").value(4_194_304))
                .andExpect(jsonPath("$.chunkCount").value(30))
                .andExpect(jsonPath("$.receivedChunks", empty()))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-05T15:00:00Z"));
    }

    @Test
    void answers200WhenAnUnfinishedUploadIsResumed() throws Exception {
        when(uploads.create(REQUEST)).thenReturn(new VideoUploadService.Created(upload(List.of(0, 1, 2)), false));

        mockMvc.perform(post("/v1/video-uploads").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedChunks", contains(0, 1, 2)));
    }

    @Test
    void readsTheRequestTheAndroidAppSendsAndWritesExactlyTheBodyItDecodes() throws Exception {
        when(uploads.create(REQUEST)).thenReturn(new VideoUploadService.Created(upload(List.of(0, 1, 2)), false));

        mockMvc.perform(post("/v1/video-uploads")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APP_REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().json(UPLOAD_JSON, JsonCompareMode.STRICT));
    }

    @Test
    void handsTheRawChunkBodyToTheServiceAndAnswersWithTheUploadStatus() throws Exception {
        var received = new AtomicReference<String>();
        when(uploads.receiveChunk(eq(UPLOAD_ID), eq(7), any(InputStream.class))).thenAnswer(invocation -> {
            received.set(new String(invocation.<InputStream>getArgument(2).readAllBytes(), StandardCharsets.US_ASCII));
            return upload(List.of(7));
        });

        mockMvc.perform(put("/v1/video-uploads/{uploadId}/chunks/{index}", UPLOAD_ID, 7)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("chunk bytes".getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedChunks", contains(7)));

        assertThat(received).hasValue("chunk bytes");
    }

    @Test
    void showsTheUploadStatus() throws Exception {
        when(uploads.get(UPLOAD_ID)).thenReturn(upload(List.of(0)));

        mockMvc.perform(get("/v1/video-uploads/{uploadId}", UPLOAD_ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID.toString()))
                .andExpect(jsonPath("$.receivedChunks", contains(0)));
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_REQUEST, 400",
            "VIDEO_UPLOAD_TOO_LARGE, 413",
            "VIDEO_TOO_LONG, 422",
            "VIDEO_UPLOAD_LIMIT, 429",
            "VIDEO_ANALYSIS_UNAVAILABLE, 503"
    })
    void mapsEachRefusedUploadToItsStatusAndCode(VideoUploadException.Code code, int status) throws Exception {
        when(uploads.create(REQUEST)).thenThrow(new VideoUploadException(code, "refused"));

        mockMvc.perform(post("/v1/video-uploads").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value("refused"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "VIDEO_CHUNK_INVALID, 400",
            "VIDEO_UPLOAD_NOT_FOUND, 404",
            "VIDEO_UPLOAD_CLOSED, 409"
    })
    void mapsEachRefusedChunkToItsStatusAndCode(VideoUploadException.Code code, int status) throws Exception {
        when(uploads.receiveChunk(eq(UPLOAD_ID), eq(0), any(InputStream.class)))
                .thenThrow(new VideoUploadException(code, "refused"));

        mockMvc.perform(put("/v1/video-uploads/{uploadId}/chunks/{index}", UPLOAD_ID, 0)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[] {1, 2, 3}))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    void requiresAnAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/v1/video-uploads").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/v1/video-uploads/{uploadId}/chunks/{index}", UPLOAD_ID, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[] {1}))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/video-uploads/{uploadId}", UPLOAD_ID))
                .andExpect(status().isUnauthorized());
    }

    private static VideoUploadResponse upload(List<Integer> receivedChunks) {
        return new VideoUploadResponse(UPLOAD_ID, VideoUploadStatus.RECEIVING, 4_194_304, 30, receivedChunks,
                Instant.parse("2026-11-05T15:00:00Z"));
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
