package com.hansul.recipe.video.upload;

import com.hansul.auth.api.AuthenticatedUser;
import com.hansul.auth.api.AuthenticatedUserId;
import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.video.VideoImportProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Shared by the upload and import job tests: tiny chunks, a clock that can jump, a user that can switch. */
public final class VideoUploadTestSupport {

    public static final int CHUNK_BYTES = 4;
    /** Three chunks: 4 + 4 + 2 bytes. */
    public static final byte[] VIDEO = "0123456789".getBytes(StandardCharsets.US_ASCII);

    private VideoUploadTestSupport() {
    }

    public static VideoImportProperties properties(Path uploadDirectory, boolean geminiConfigured) {
        return properties(uploadDirectory, geminiConfigured, 10, 2);
    }

    /** A configured Gemini key counts as paid tier here, so phone videos may be analysed. */
    public static VideoImportProperties properties(
            Path uploadDirectory,
            boolean geminiConfigured,
            int dailyLimitPerUser,
            int workerConcurrency
    ) {
        return properties(uploadDirectory, geminiConfigured, geminiConfigured, dailyLimitPerUser, workerConcurrency);
    }

    public static VideoImportProperties properties(
            Path uploadDirectory,
            boolean geminiConfigured,
            boolean paidTier,
            int dailyLimitPerUser,
            int workerConcurrency
    ) {
        return properties(
                uploadDirectory, geminiConfigured, paidTier, dailyLimitPerUser, workerConcurrency, 1_000_000, 1);
    }

    /** {@code minFreeBytes} 1 lets any test disk take the chunks; a huge value makes the disk look full. */
    public static VideoImportProperties properties(Path uploadDirectory, long maxTotalBytes, long minFreeBytes) {
        return properties(uploadDirectory, true, true, 10, 2, maxTotalBytes, minFreeBytes);
    }

    private static VideoImportProperties properties(
            Path uploadDirectory,
            boolean geminiConfigured,
            boolean paidTier,
            int dailyLimitPerUser,
            int workerConcurrency,
            long maxTotalBytes,
            long minFreeBytes
    ) {
        var gemini = new VideoImportProperties.Gemini(
                null, geminiConfigured ? "test-key" : "", null, null, null, 0, 0, null, paidTier);
        var upload = new VideoImportProperties.Upload(uploadDirectory.toString(), 1_000, CHUNK_BYTES, 2,
                Duration.ofHours(6), maxTotalBytes, minFreeBytes, Duration.ofMinutes(5));
        return new VideoImportProperties(gemini, null, 1800, dailyLimitPerUser, workerConcurrency, upload);
    }

    public static CreateVideoUploadRequest request(byte[] video, Integer durationSeconds) {
        return new CreateVideoUploadRequest("video/mp4", (long) video.length, sha256(video), durationSeconds);
    }

    /** Sends every chunk of {@code video} in order, as the app does. */
    public static VideoUploadResponse sendAllChunks(VideoUploadService uploads, UUID uploadId, byte[] video) {
        VideoUploadResponse last = null;
        for (var index = 0; index * CHUNK_BYTES < video.length; index++) {
            last = sendChunk(uploads, uploadId, index, video);
        }
        return last;
    }

    public static VideoUploadResponse sendChunk(VideoUploadService uploads, UUID uploadId, int index, byte[] video) {
        var from = index * CHUNK_BYTES;
        var chunk = Arrays.copyOfRange(video, from, Math.min(video.length, from + CHUNK_BYTES));
        return uploads.receiveChunk(uploadId, index, new ByteArrayInputStream(chunk));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class SwitchableUser implements CurrentUserProvider {

        private final AtomicReference<String> subject;

        public SwitchableUser(String subject) {
            this.subject = new AtomicReference<>(subject);
        }

        public void signIn(String subject) {
            this.subject.set(subject);
        }

        @Override
        public Optional<AuthenticatedUser> findCurrentUser() {
            return Optional.ofNullable(subject.get())
                    .map(value -> new AuthenticatedUser(new AuthenticatedUserId(value), null, null));
        }
    }

    public static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        public MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
