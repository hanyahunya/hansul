package com.hansul.recipe.video.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.hansul.recipe.video.VideoImportProperties;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The chunk layout the Android app computes on its side: 0-based, full chunks, the rest in the last one. */
class VideoUploadEntityTest {

    private static final VideoImportProperties.Upload LIMITS =
            new VideoImportProperties(null, null, 0, 0, 0, null).upload();
    // infra/aws/hansul/origin-nginx.conf: client_max_body_size 5m, and each chunk is the body of one request.
    private static final long PROXY_MAX_BODY_BYTES = 5L * 1024 * 1024;

    @Test
    void cutsTheContractExampleIntoFullChunksAndAShorterLastOne() {
        var upload = receiving(123_456_789L);

        assertThat(upload.getChunkSizeBytes()).isEqualTo(4_194_304);
        assertThat(upload.getChunkCount()).isEqualTo(30);
        assertThat(upload.chunkLength(0)).isEqualTo(4_194_304);
        assertThat(upload.chunkLength(28)).isEqualTo(4_194_304);
        assertThat(upload.chunkLength(29)).isEqualTo(123_456_789L - 29L * 4_194_304);
        assertThat(IntStream.range(0, 30).mapToLong(upload::chunkLength).sum()).isEqualTo(123_456_789L);
    }

    @Test
    void theLargestUploadEndsOnAFullChunkAndATinyOneIsASingleChunk() {
        var largest = receiving(LIMITS.maxBytes());
        var tiny = receiving(1);

        assertThat(largest.getChunkCount()).isEqualTo(75);
        assertThat(largest.chunkLength(74)).isEqualTo(4_194_304);
        assertThat(tiny.getChunkCount()).isEqualTo(1);
        assertThat(tiny.chunkLength(0)).isEqualTo(1);
    }

    @Test
    void everyChunkFitsInOneRequestThroughTheProxy() {
        assertThat((long) LIMITS.chunkBytes()).isLessThanOrEqualTo(PROXY_MAX_BODY_BYTES);
    }

    private static VideoUploadEntity receiving(long sizeBytes) {
        return VideoUploadEntity.receiving("cook-a", "video/mp4", sizeBytes, "0".repeat(64), 612,
                LIMITS.chunkBytes(), Instant.EPOCH, LIMITS.ttl());
    }
}
