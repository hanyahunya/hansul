package com.hansul.recipe.video.upload;

import static com.hansul.recipe.video.upload.VideoUploadTestSupport.VIDEO;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.request;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.sendAllChunks;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.sendChunk;
import static com.hansul.recipe.video.upload.VideoUploadTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hansul.PostgresTestcontainersConfiguration;
import com.hansul.recipe.video.VideoImportProperties;
import com.hansul.recipe.video.upload.VideoUploadException.Code;
import com.hansul.recipe.video.upload.VideoUploadTestSupport.MutableClock;
import com.hansul.recipe.video.upload.VideoUploadTestSupport.SwitchableUser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Against PostgreSQL without a test transaction: each call commits like it does behind the controller. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VideoUploadServiceTest {

    private static final Instant START = Instant.parse("2026-11-05T09:00:00Z");

    @Autowired
    private VideoUploadRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path root;

    private final SwitchableUser user = new SwitchableUser("cook-a");
    private final MutableClock clock = new MutableClock(START);
    private VideoUploadService uploads;

    @BeforeEach
    void setUp() {
        uploads = service(repository, true);
    }

    @AfterEach
    void deleteRows() {
        jdbc.update("DELETE FROM recipe_import_job");
        jdbc.update("DELETE FROM video_upload");
    }

    @Test
    void createsAnUploadSplitIntoFixedSizeChunksThatExpiresAfterSixHours() {
        var created = uploads.create(new CreateVideoUploadRequest(" Video/MP4 ", 10L, sha256(VIDEO), 612));

        assertThat(created.created()).isTrue();
        var upload = created.upload();
        assertThat(upload.status()).isEqualTo(VideoUploadStatus.RECEIVING);
        assertThat(upload.chunkSizeBytes()).isEqualTo(4);
        assertThat(upload.chunkCount()).isEqualTo(3);
        assertThat(upload.receivedChunks()).isEmpty();
        assertThat(upload.expiresAt()).isEqualTo(START.plus(Duration.ofHours(6)));

        var stored = repository.findById(upload.uploadId()).orElseThrow();
        assertThat(stored.getOwnerSubject()).isEqualTo("cook-a");
        assertThat(stored.getContentType()).isEqualTo("video/mp4");
        assertThat(stored.getSizeBytes()).isEqualTo(10);
        assertThat(stored.getSha256()).isEqualTo(sha256(VIDEO));
        assertThat(stored.getDurationSeconds()).isEqualTo(612);
        assertThat(stored.getCreatedAt()).isEqualTo(START);
        assertThat(stored.chunkLength(0)).isEqualTo(4);
        assertThat(stored.chunkLength(2)).isEqualTo(2);
    }

    @Test
    void resumesTheUnfinishedUploadOfTheSameFileWithTheChunksAlreadyReceived() {
        var first = uploads.create(request(VIDEO, 612)).upload();
        sendChunk(uploads, first.uploadId(), 0, VIDEO);
        sendChunk(uploads, first.uploadId(), 2, VIDEO);

        var resumed = uploads.create(request(VIDEO, 612));

        assertThat(resumed.created()).isFalse();
        assertThat(resumed.upload().uploadId()).isEqualTo(first.uploadId());
        assertThat(resumed.upload().receivedChunks()).containsExactly(0, 2);

        user.signIn("cook-b");
        var someoneElses = uploads.create(request(VIDEO, 612));
        assertThat(someoneElses.created()).isTrue();
        assertThat(someoneElses.upload().uploadId()).isNotEqualTo(first.uploadId());
    }

    @Test
    void refusesFilesOverTheSizeOrDurationLimit() {
        assertRefused(() -> uploads.create(new CreateVideoUploadRequest("video/mp4", 1_001L, sha256(VIDEO), null)),
                Code.VIDEO_UPLOAD_TOO_LARGE);
        assertRefused(() -> uploads.create(request(VIDEO, 1801)), Code.VIDEO_TOO_LONG);

        assertThat(uploads.create(request(VIDEO, 1800)).created()).isTrue();
    }

    @Test
    void allowsTwoActiveUploadsPerUser() {
        var receiving = uploads.create(request(bytes("first video"), null)).upload();
        var complete = uploads.create(request(VIDEO, null)).upload();
        sendAllChunks(uploads, complete.uploadId(), VIDEO);
        uploads.whileComplete(complete.uploadId(), () -> true);

        assertRefused(() -> uploads.create(request(bytes("third video"), null)), Code.VIDEO_UPLOAD_LIMIT);

        repository.markFailed(receiving.uploadId(), START);
        assertThat(uploads.create(request(bytes("third video"), null)).created()).isTrue();
        user.signIn("cook-b");
        assertThat(uploads.create(request(bytes("first video"), null)).created()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "video/avi, 10, 84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882, 60",
            "null, 10, 84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882, 60",
            "video/mp4, 0, 84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882, 60",
            "video/mp4, -1, 84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882, 60",
            "video/mp4, null, 84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882, 60",
            "video/mp4, 10, 84D89877F0D4041EFB6BF91A16F0248F2FD573E6AF05C19F96BEDB9F882F7882, 60",
            "video/mp4, 10, 84d89877f0d4041efb6bf91a16f0248f, 60",
            "video/mp4, 10, null, 60",
            "video/mp4, 10, 84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882, -1"
    })
    void rejectsInvalidDeclarations(String contentType, Long sizeBytes, String sha256, Integer durationSeconds) {
        assertRefused(() -> uploads.create(new CreateVideoUploadRequest(contentType, sizeBytes, sha256, durationSeconds)),
                Code.INVALID_REQUEST);
        assertThat(repository.count()).isZero();
    }

    @Test
    void acceptsEveryVideoTypeTheAnalysisReads() {
        for (var contentType : VideoUploadService.CONTENT_TYPES) {
            var video = bytes(contentType);
            assertThat(uploads.create(new CreateVideoUploadRequest(contentType, (long) video.length, sha256(video), 60))
                    .created()).isTrue();
            jdbc.update("DELETE FROM video_upload");
        }
    }

    @Test
    void refusesUploadsWhileVideoAnalysisIsNotConfigured() {
        var unconfigured = service(repository, false);

        assertRefused(() -> unconfigured.create(request(VIDEO, 60)), Code.VIDEO_ANALYSIS_UNAVAILABLE);
    }

    @Test
    void refusesPhoneVideosUntilTheOperatorConfirmsAPaidTierGeminiKey() {
        var unpaid = VideoUploadTestSupport.properties(root, true, false, 10, 2);
        var service = new VideoUploadService(repository, new VideoUploadStorage(unpaid), unpaid, user, clock);

        assertRefused(() -> service.create(request(VIDEO, 60)), Code.VIDEO_ANALYSIS_UNAVAILABLE);
        assertThat(repository.count()).isZero();
    }

    @Test
    void anIdleUploadOfTheSameOwnerMakesRoomForANewOne() {
        var abandoned = uploads.create(request(bytes("first video"), null)).upload();
        sendChunk(uploads, abandoned.uploadId(), 0, bytes("first video"));
        var uploading = uploads.create(request(bytes("second video"), null)).upload();
        clock.advance(Duration.ofMinutes(3));
        sendChunk(uploads, uploading.uploadId(), 0, bytes("second video"));
        clock.advance(Duration.ofMinutes(3));

        var third = uploads.create(request(bytes("third video"), null));

        assertThat(third.created()).isTrue();
        assertThat(status(abandoned.uploadId())).isEqualTo(VideoUploadStatus.EXPIRED);
        assertThat(root.resolve(abandoned.uploadId().toString())).doesNotExist();
        assertThat(status(uploading.uploadId())).isEqualTo(VideoUploadStatus.RECEIVING);
        assertThat(uploads.get(uploading.uploadId()).receivedChunks()).containsExactly(0);
        // Both remaining uploads moved within the idle timeout, so a fourth has to wait.
        assertRefused(() -> uploads.create(request(bytes("fourth video"), null)), Code.VIDEO_UPLOAD_LIMIT);
    }

    @Test
    void anUploadWhoseAnalysisIsQueuedNeverMakesRoom() {
        var queued = uploads.create(request(VIDEO, null)).upload();
        sendAllChunks(uploads, queued.uploadId(), VIDEO);
        uploads.whileComplete(queued.uploadId(), () -> insertQueuedJob(queued.uploadId()));
        var idle = uploads.create(request(bytes("second video"), null)).upload();
        clock.advance(Duration.ofHours(1));

        assertThat(uploads.create(request(bytes("third video"), null)).created()).isTrue();

        assertThat(status(queued.uploadId())).isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(uploads.videoFile(queued.uploadId())).hasBinaryContent(VIDEO);
        assertThat(status(idle.uploadId())).isEqualTo(VideoUploadStatus.EXPIRED);
    }

    @Test
    void refusesANewUploadWhileAllUploadsTogetherFillTheServerBudget() {
        var tight = properties(20, 1);
        var service = new VideoUploadService(repository, new VideoUploadStorage(tight), tight, user, clock);
        service.create(request(VIDEO, null));
        user.signIn("cook-b");
        service.create(request(bytes("0123456"), null));

        user.signIn("cook-c");
        assertRefused(() -> service.create(request(bytes("abcdefgh"), null)), Code.VIDEO_UPLOAD_LIMIT);
        assertThat(service.create(request(bytes("abc"), null)).created()).isTrue();

        // An upload nobody sends chunks to gives its share back once it is idle.
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.create(request(bytes("abcdefgh"), null)).created()).isTrue();
    }

    @Test
    void refusesChunksAndAssemblyWhenTheUploadDiskIsNearlyFull() {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendChunk(uploads, upload.uploadId(), 0, VIDEO);
        sendChunk(uploads, upload.uploadId(), 1, VIDEO);
        var full = properties(1_000, Long.MAX_VALUE / 4);
        var nearlyFull = new VideoUploadService(repository, new VideoUploadStorage(full), full, user, clock);

        assertRefused(() -> sendChunk(nearlyFull, upload.uploadId(), 2, VIDEO), Code.VIDEO_UPLOAD_LIMIT);
        assertThat(uploads.get(upload.uploadId()).receivedChunks()).containsExactly(0, 1);

        sendChunk(uploads, upload.uploadId(), 2, VIDEO);
        assertRefused(() -> nearlyFull.whileComplete(upload.uploadId(), () -> true), Code.VIDEO_UPLOAD_LIMIT);
        assertThat(status(upload.uploadId())).isEqualTo(VideoUploadStatus.RECEIVING);
        assertThat(uploads.get(upload.uploadId()).receivedChunks()).containsExactly(0, 1, 2);
    }

    @Test
    void anAssembledUploadWhoseVideoIsGoneFailsSoTheAppUploadsItAgain() throws Exception {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        uploads.whileComplete(upload.uploadId(), () -> true);
        Files.delete(uploads.videoFile(upload.uploadId()));

        var shown = uploads.get(upload.uploadId());
        assertThat(shown.status()).isEqualTo(VideoUploadStatus.FAILED);
        assertThat(shown.receivedChunks()).isEmpty();

        var again = uploads.create(request(VIDEO, 60));
        assertThat(again.created()).isTrue();
        assertThat(again.upload().uploadId()).isNotEqualTo(upload.uploadId());
        assertThat(again.upload().receivedChunks()).isEmpty();
    }

    @Test
    void resumingAnAssembledUploadWhoseVideoIsGoneStartsANewUpload() throws Exception {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        uploads.whileComplete(upload.uploadId(), () -> true);
        Files.delete(uploads.videoFile(upload.uploadId()));

        var resumed = uploads.create(request(VIDEO, 60));

        assertThat(resumed.created()).isTrue();
        assertThat(resumed.upload().status()).isEqualTo(VideoUploadStatus.RECEIVING);
        assertThat(status(upload.uploadId())).isEqualTo(VideoUploadStatus.FAILED);
        assertRefused(() -> uploads.whileComplete(upload.uploadId(), () -> true), Code.VIDEO_UPLOAD_CLOSED);
    }

    @Test
    void storesEachChunkAndReplacesOneThatIsSentAgain() throws Exception {
        var upload = uploads.create(request(VIDEO, 60)).upload();

        var afterFirst = uploads.receiveChunk(upload.uploadId(), 1, new ByteArrayInputStream(bytes("abcd")));
        var afterResend = uploads.receiveChunk(upload.uploadId(), 1, new ByteArrayInputStream(bytes("4567")));

        assertThat(afterFirst.receivedChunks()).containsExactly(1);
        assertThat(afterResend.receivedChunks()).containsExactly(1);
        assertThat(Files.readString(root.resolve(upload.uploadId().toString()).resolve("chunk-00001"))).isEqualTo("4567");
        assertThat(sendChunk(uploads, upload.uploadId(), 2, VIDEO).receivedChunks()).containsExactly(1, 2);
    }

    @Test
    void rejectsChunksOfTheWrongLengthOrIndexWithoutStoringThem() {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        var id = upload.uploadId();

        assertRefused(() -> uploads.receiveChunk(id, 0, new ByteArrayInputStream(bytes("012"))), Code.VIDEO_CHUNK_INVALID);
        assertRefused(() -> uploads.receiveChunk(id, 0, new ByteArrayInputStream(bytes("01234"))), Code.VIDEO_CHUNK_INVALID);
        assertRefused(() -> uploads.receiveChunk(id, 2, new ByteArrayInputStream(bytes("8"))), Code.VIDEO_CHUNK_INVALID);
        assertRefused(() -> uploads.receiveChunk(id, 3, new ByteArrayInputStream(bytes("89"))), Code.VIDEO_CHUNK_INVALID);
        assertRefused(() -> uploads.receiveChunk(id, -1, new ByteArrayInputStream(bytes("0123"))), Code.VIDEO_CHUNK_INVALID);

        assertThat(uploads.get(id).receivedChunks()).isEmpty();
        assertThat(uploads.receiveChunk(id, 2, new ByteArrayInputStream(bytes("89"))).receivedChunks()).containsExactly(2);
    }

    @Test
    void refusesChunksOnceTheUploadIsAssembled() {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        uploads.whileComplete(upload.uploadId(), () -> true);

        assertRefused(() -> sendChunk(uploads, upload.uploadId(), 0, VIDEO), Code.VIDEO_UPLOAD_CLOSED);
        var complete = uploads.get(upload.uploadId());
        assertThat(complete.status()).isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(complete.receivedChunks()).containsExactly(0, 1, 2);
    }

    @Test
    void assemblingRefusesAnUploadThatIsStillMissingChunks() {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendChunk(uploads, upload.uploadId(), 0, VIDEO);
        sendChunk(uploads, upload.uploadId(), 2, VIDEO);
        var ran = new AtomicBoolean();

        assertThatThrownBy(() -> uploads.whileComplete(upload.uploadId(), () -> ran.getAndSet(true)))
                .isInstanceOfSatisfying(VideoUploadException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(Code.UPLOAD_INCOMPLETE);
                    assertThat(exception.getMessage()).isEqualTo("1 of 3 chunks have not been uploaded yet");
                });

        assertThat(ran).isFalse();
        var stillReceiving = uploads.get(upload.uploadId());
        assertThat(stillReceiving.status()).isEqualTo(VideoUploadStatus.RECEIVING);
        assertThat(stillReceiving.receivedChunks()).containsExactly(0, 2);

        sendChunk(uploads, upload.uploadId(), 1, VIDEO);
        assertThat(uploads.whileComplete(upload.uploadId(), () -> status(upload.uploadId())))
                .isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(uploads.videoFile(upload.uploadId())).hasBinaryContent(VIDEO);
    }

    @Test
    void aChecksumMismatchFailsTheUploadAndDeletesWhatArrived() {
        var declared = request(bytes("9876543210"), 60);
        var upload = uploads.create(declared).upload();
        sendAllChunks(uploads, upload.uploadId(), VIDEO);
        var ran = new AtomicBoolean();

        assertRefused(() -> uploads.whileComplete(upload.uploadId(), () -> ran.getAndSet(true)),
                Code.UPLOAD_CHECKSUM_MISMATCH);

        assertThat(ran).isFalse();
        assertThat(status(upload.uploadId())).isEqualTo(VideoUploadStatus.FAILED);
        assertThat(root.resolve(upload.uploadId().toString())).doesNotExist();
        assertRefused(() -> sendChunk(uploads, upload.uploadId(), 0, VIDEO), Code.VIDEO_UPLOAD_CLOSED);
        var again = uploads.create(declared);
        assertThat(again.created()).isTrue();
        assertThat(again.upload().uploadId()).isNotEqualTo(upload.uploadId());
    }

    @Test
    void showsAnUploadOnlyToItsOwner() {
        var upload = uploads.create(request(VIDEO, 60)).upload();

        user.signIn("cook-b");

        assertRefused(() -> uploads.get(upload.uploadId()), Code.VIDEO_UPLOAD_NOT_FOUND);
        assertRefused(() -> sendChunk(uploads, upload.uploadId(), 0, VIDEO), Code.VIDEO_UPLOAD_NOT_FOUND);
        assertRefused(() -> uploads.findOwned(upload.uploadId(), "cook-b"), Code.VIDEO_UPLOAD_NOT_FOUND);
        assertRefused(() -> uploads.get(UUID.randomUUID()), Code.VIDEO_UPLOAD_NOT_FOUND);
        assertThat(root.resolve(upload.uploadId().toString())).doesNotExist();
    }

    @Test
    void anUploadExpiresAsSoonAsItIsLookedAtAfterItsTimeToLive() {
        var upload = uploads.create(request(VIDEO, 60)).upload();
        sendChunk(uploads, upload.uploadId(), 0, VIDEO);

        clock.advance(Duration.ofHours(6));

        var expired = uploads.get(upload.uploadId());
        assertThat(expired.status()).isEqualTo(VideoUploadStatus.EXPIRED);
        assertThat(expired.receivedChunks()).isEmpty();
        assertThat(root.resolve(upload.uploadId().toString())).doesNotExist();
        assertRefused(() -> sendChunk(uploads, upload.uploadId(), 1, VIDEO), Code.VIDEO_UPLOAD_CLOSED);

        var fresh = uploads.create(request(VIDEO, 60));
        assertThat(fresh.created()).isTrue();
        assertThat(fresh.upload().uploadId()).isNotEqualTo(upload.uploadId());
    }

    @Test
    void theSweeperExpiresDueUploadsButNotOneWhoseAnalysisIsStillQueued() {
        var receiving = uploads.create(request(bytes("first video"), null)).upload();
        sendChunk(uploads, receiving.uploadId(), 0, bytes("first video"));
        var queued = uploads.create(request(VIDEO, null)).upload();
        sendAllChunks(uploads, queued.uploadId(), VIDEO);
        uploads.whileComplete(queued.uploadId(), () -> insertQueuedJob(queued.uploadId()));

        clock.advance(Duration.ofHours(7));
        sweeper().sweep();

        assertThat(status(receiving.uploadId())).isEqualTo(VideoUploadStatus.EXPIRED);
        assertThat(root.resolve(receiving.uploadId().toString())).doesNotExist();
        assertThat(status(queued.uploadId())).isEqualTo(VideoUploadStatus.COMPLETE);
        assertThat(uploads.videoFile(queued.uploadId())).hasBinaryContent(VIDEO);

        jdbc.update("UPDATE recipe_import_job SET status = 'FAILED'");
        sweeper().sweep();
        assertThat(status(queued.uploadId())).isEqualTo(VideoUploadStatus.EXPIRED);
        assertThat(root.resolve(queued.uploadId().toString())).doesNotExist();
    }

    @Test
    void theSweeperRemovesTheFilesThatClosedOrUnknownUploadsLeftBehind() throws Exception {
        var active = uploads.create(request(VIDEO, null)).upload();
        sendChunk(uploads, active.uploadId(), 0, VIDEO);
        var failed = uploads.create(request(bytes("failed video"), null)).upload();
        sendChunk(uploads, failed.uploadId(), 0, bytes("failed video"));
        repository.markFailed(failed.uploadId(), START);
        var unknown = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        var notAnUpload = Files.createDirectories(root.resolve("keep-me"));

        sweeper().sweep();

        assertThat(root.resolve(active.uploadId().toString()).resolve("chunk-00000")).exists();
        assertThat(root.resolve(failed.uploadId().toString())).doesNotExist();
        assertThat(unknown).doesNotExist();
        assertThat(notAnUpload).exists();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLostCreateRaceResumesTheUploadThatWon(CapturedOutput output) {
        var winner = uploads.create(request(VIDEO, 60)).upload();
        var staleOnce = new AtomicBoolean(true);
        var racing = mock(VideoUploadRepository.class, delegatesTo(repository));
        doAnswer(invocation -> staleOnce.getAndSet(false)
                ? Optional.empty()
                : repository.findByOwnerSubjectAndSha256AndStatusIn(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
                .when(racing).findByOwnerSubjectAndSha256AndStatusIn(eq("cook-a"), eq(sha256(VIDEO)), any());

        var loser = service(racing, true).create(request(VIDEO, 60));

        assertThat(staleOnce).isFalse();
        assertThat(loser.created()).isFalse();
        assertThat(loser.upload().uploadId()).isEqualTo(winner.uploadId());
        assertThat(repository.count()).isOne();
        // A logged constraint violation would name the owner and the hash of their video.
        assertThat(output).doesNotContain("uq_video_upload_active_hash").doesNotContain(sha256(VIDEO));
    }

    @Test
    void theCreatingInsertSkipsOnlyAnActiveUploadOfTheSameOwnerAndFile() {
        var first = receiving("cook-a", null);

        assertThat(repository.insertUnlessActive(first)).isOne();
        assertThat(repository.findById(first.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(first);

        assertThat(repository.insertUnlessActive(receiving("cook-a", 60))).isZero();
        repository.markComplete(first.getId(), START);
        assertThat(repository.insertUnlessActive(receiving("cook-a", 60))).isZero();
        assertThat(repository.insertUnlessActive(receiving("cook-b", 60))).isOne();
        repository.markFailed(first.getId(), START);
        assertThat(repository.insertUnlessActive(receiving("cook-a", 60))).isOne();
        assertThat(repository.count()).isEqualTo(3);
    }

    private VideoUploadService service(VideoUploadRepository repository, boolean geminiConfigured) {
        var properties = VideoUploadTestSupport.properties(root, geminiConfigured);
        return new VideoUploadService(repository, new VideoUploadStorage(properties), properties, user, clock);
    }

    private VideoImportProperties properties(long maxTotalBytes, long minFreeBytes) {
        return VideoUploadTestSupport.properties(root, maxTotalBytes, minFreeBytes);
    }

    private VideoUploadSweeper sweeper() {
        return new VideoUploadSweeper(uploads);
    }

    private static VideoUploadEntity receiving(String owner, Integer durationSeconds) {
        return VideoUploadEntity.receiving(owner, "video/mp4", VIDEO.length, sha256(VIDEO), durationSeconds,
                VideoUploadTestSupport.CHUNK_BYTES, START, Duration.ofHours(6));
    }

    private VideoUploadStatus status(UUID uploadId) {
        return repository.findById(uploadId).orElseThrow().getStatus();
    }

    private int insertQueuedJob(UUID uploadId) {
        return jdbc.update("""
                INSERT INTO recipe_import_job (id, owner_subject, source_kind, source_provider, source_ref,
                    status, stage, created_at, updated_at)
                VALUES (?, 'cook-a', 'UPLOAD', 'upload', ?, 'QUEUED', 'QUEUED', ?, ?)
                """, UUID.randomUUID(), uploadId.toString(), Timestamp.from(START), Timestamp.from(START));
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRefused(ThrowingCallable call, Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(VideoUploadException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
