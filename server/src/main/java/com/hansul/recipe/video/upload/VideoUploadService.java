package com.hansul.recipe.video.upload;

import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.video.VideoImportProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phone videos arrive in fixed-size chunks through the API. Chunk commits, assembly, expiry and consumption of
 * an upload hold that upload's lock (one server process, like the import worker), so a chunk can never land in
 * an upload that is being assembled or expired, and no job is queued for an upload that is being consumed.
 */
@Service
public class VideoUploadService {

    static final List<String> CONTENT_TYPES = List.of("video/mp4", "video/quicktime", "video/webm", "video/3gpp");
    private static final Logger log = LoggerFactory.getLogger(VideoUploadService.class);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int LOCK_STRIPES = 64;

    private final VideoUploadRepository uploads;
    private final VideoUploadStorage storage;
    private final VideoImportProperties properties;
    private final CurrentUserProvider users;
    private final Clock clock;
    private final Lock[] locks = new Lock[LOCK_STRIPES];

    public VideoUploadService(
            VideoUploadRepository uploads,
            VideoUploadStorage storage,
            VideoImportProperties properties,
            CurrentUserProvider users,
            Clock clock
    ) {
        this.uploads = uploads;
        this.storage = storage;
        this.properties = properties;
        this.users = users;
        this.clock = clock;
        Arrays.setAll(locks, stripe -> new ReentrantLock());
    }

    public record Created(VideoUploadResponse upload, boolean created) {
    }

    /** The same owner sending the same sha256 again resumes the upload that is still RECEIVING or COMPLETE. */
    public Created create(CreateVideoUploadRequest request) {
        var owner = currentOwner();
        if (!properties.isConfiguredForUploads()) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_ANALYSIS_UNAVAILABLE,
                    "Phone video analysis is not enabled on this server"
            );
        }
        var limits = properties.upload();
        var contentType = request.contentType() == null ? "" : request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            throw invalid("contentType must be one of " + String.join(", ", CONTENT_TYPES));
        }
        if (request.sizeBytes() == null || request.sizeBytes() <= 0) {
            throw invalid("sizeBytes must be a positive number of bytes");
        }
        if (request.sizeBytes() > limits.maxBytes()) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_UPLOAD_TOO_LARGE,
                    "Video uploads are limited to " + limits.maxBytes() + " bytes"
            );
        }
        if (request.sha256() == null || !SHA256.matcher(request.sha256()).matches()) {
            throw invalid("sha256 must be 64 lowercase hexadecimal characters");
        }
        var duration = request.durationSeconds();
        if (duration != null && duration < 0) {
            throw invalid("durationSeconds must not be negative");
        }
        if (duration != null && duration > properties.maxVideoSeconds()) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_TOO_LONG,
                    "Videos longer than " + properties.maxVideoSeconds() + " seconds are not analyzed"
            );
        }

        var resumable = findActive(owner, request.sha256());
        if (resumable.isPresent()) {
            return new Created(response(resumable.get()), false);
        }
        requireRoomFor(owner, request.sizeBytes());

        var upload = VideoUploadEntity.receiving(
                owner,
                contentType,
                request.sizeBytes(),
                request.sha256(),
                duration,
                limits.chunkBytes(),
                now(),
                limits.ttl()
        );
        if (uploads.insertUnlessActive(upload) == 1) {
            return new Created(response(upload), true);
        }
        // uq_video_upload_active_hash: a concurrent request created the same upload first.
        return findActive(owner, request.sha256())
                .map(existing -> new Created(response(existing), false))
                .orElseThrow(() -> new IllegalStateException("A concurrent video upload of the same file closed at once"));
    }

    public VideoUploadResponse get(UUID uploadId) {
        return response(findOwned(uploadId, currentOwner()));
    }

    public VideoUploadResponse receiveChunk(UUID uploadId, int index, InputStream body) {
        var upload = findOwned(uploadId, currentOwner());
        if (upload.getStatus() != VideoUploadStatus.RECEIVING) {
            throw notReceiving(upload);
        }
        if (index < 0 || index >= upload.getChunkCount()) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_CHUNK_INVALID,
                    "Chunk index must be between 0 and " + (upload.getChunkCount() - 1)
            );
        }
        var expectedLength = upload.chunkLength(index);
        requireSpaceFor(expectedLength);
        var chunk = storage.receiveChunk(uploadId, index, expectedLength, body);
        if (chunk.length() != expectedLength) {
            storage.discard(chunk);
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_CHUNK_INVALID,
                    "Chunk " + index + " must be exactly " + expectedLength + " bytes"
            );
        }
        // The body was read without the lock; whether the upload still takes chunks is decided under it.
        return locked(uploadId, () -> {
            var current = current(require(uploadId));
            if (current.getStatus() != VideoUploadStatus.RECEIVING) {
                storage.discard(chunk);
                throw notReceiving(current);
            }
            storage.storeChunk(uploadId, index, chunk);
            uploads.touch(uploadId, now());
            return response(current);
        });
    }

    /** The upload if it belongs to {@code ownerSubject}, brought up to date first (see current); otherwise 404. */
    public VideoUploadEntity findOwned(UUID uploadId, String ownerSubject) {
        return uploads.findById(uploadId)
                .filter(upload -> upload.getOwnerSubject().equals(ownerSubject))
                .map(this::current)
                .orElseThrow(() -> notFound(uploadId));
    }

    public <T> T whileComplete(UUID uploadId, Supplier<T> action) {
        return whileComplete(uploadId, action, null);
    }

    /**
     * Runs {@code action} while the upload is COMPLETE, first assembling and verifying it when every chunk has
     * arrived. Nothing can expire or close the upload before the action returns, so a job queued by the action
     * is guaranteed to find the video file. An upload that an analysis has consumed since the caller last looked
     * answers through {@code analysed} with its recipe instead, when given.
     */
    public <T> T whileComplete(UUID uploadId, Supplier<T> action, Function<UUID, T> analysed) {
        return locked(uploadId, () -> {
            var upload = current(require(uploadId));
            switch (upload.getStatus()) {
                case RECEIVING -> assemble(upload);
                case COMPLETE -> {
                }
                case CONSUMED, EXPIRED, FAILED -> {
                    if (analysed != null && upload.getStatus() == VideoUploadStatus.CONSUMED
                            && upload.getRecipeId() != null) {
                        return analysed.apply(upload.getRecipeId());
                    }
                    throw new VideoUploadException(
                            VideoUploadException.Code.VIDEO_UPLOAD_CLOSED,
                            "Video upload is " + upload.getStatus() + "; upload the video again"
                    );
                }
            }
            return action.get();
        });
    }

    /**
     * Runs {@code consume}, the transaction that stores an analysis and marks the upload CONSUMED, under the
     * upload's lock: a request for another job of the upload then sees it COMPLETE with this job still in flight,
     * or CONSUMED with the recipe, and never the moment in between.
     */
    public void whileConsuming(UUID uploadId, Runnable consume) {
        locked(uploadId, () -> {
            consume.run();
            return null;
        });
    }

    /** The upload an analysis reads: COMPLETE, with its assembled video file on disk. */
    public VideoUploadEntity requireAnalysable(UUID uploadId) {
        var upload = require(uploadId);
        if (upload.getStatus() != VideoUploadStatus.COMPLETE) {
            throw new IllegalStateException("Video upload " + uploadId + " is " + upload.getStatus() + ", not COMPLETE");
        }
        if (!Files.isRegularFile(storage.videoFile(uploadId))) {
            // Nothing is left to analyse, so a retry has to upload the video again.
            markFailed(uploadId);
            throw new IllegalStateException("The video file of upload " + uploadId + " is missing");
        }
        return upload;
    }

    public Path videoFile(UUID uploadId) {
        return storage.videoFile(uploadId);
    }

    /** Joins the caller's transaction, so the recipe and the CONSUMED upload are stored together. */
    public void markConsumed(UUID uploadId, UUID recipeId) {
        uploads.markConsumed(uploadId, recipeId, now());
    }

    public void markFailed(UUID uploadId) {
        locked(uploadId, () -> {
            uploads.markFailed(uploadId, now());
            storage.delete(uploadId);
            return null;
        });
    }

    public void deleteFiles(UUID uploadId) {
        storage.delete(uploadId);
    }

    int expireDueUploads() {
        var expired = 0;
        for (var upload : uploads.findByStatusInAndExpiresAtLessThanEqual(VideoUploadStatus.ACTIVE, now())) {
            if (expire(upload.getId())) {
                expired++;
            }
        }
        return expired;
    }

    /** Closed uploads normally lose their files at once; this removes what a failed delete or a crash left. */
    int deleteLeftoverFiles() {
        var removed = 0;
        for (var uploadId : storage.storedUploads()) {
            var active = uploads.findById(uploadId).map(upload -> upload.getStatus().isActive()).orElse(false);
            if (!active) {
                storage.delete(uploadId);
                removed++;
            }
        }
        return removed;
    }

    private void assemble(VideoUploadEntity upload) {
        var uploadId = upload.getId();
        var chunkCount = upload.getChunkCount();
        var missing = chunkCount - storage.receivedChunks(uploadId, chunkCount).size();
        if (missing > 0) {
            throw new VideoUploadException(
                    VideoUploadException.Code.UPLOAD_INCOMPLETE,
                    missing + " of " + chunkCount + " chunks have not been uploaded yet"
            );
        }
        // The chunks stay until the video is written, so assembling needs the whole size once more.
        requireSpaceFor(upload.getSizeBytes());
        if (!storage.assemble(uploadId, chunkCount, upload.getSizeBytes(), upload.getSha256())) {
            uploads.markFailed(uploadId, now());
            storage.delete(uploadId);
            log.info("Video upload {} did not match its declared size and sha256", uploadId);
            throw new VideoUploadException(
                    VideoUploadException.Code.UPLOAD_CHECKSUM_MISMATCH,
                    "The uploaded chunks do not match the declared sizeBytes and sha256; upload the video again"
            );
        }
        uploads.markComplete(uploadId, now());
    }

    private Optional<VideoUploadEntity> findActive(String owner, String sha256) {
        return uploads.findByOwnerSubjectAndSha256AndStatusIn(owner, sha256, VideoUploadStatus.ACTIVE)
                .map(this::current)
                .filter(upload -> upload.getStatus().isActive());
    }

    /**
     * The app never cancels the upload of a video it gave up on, so at a limit the uploads nobody has sent a chunk
     * to for the idle timeout give their place up, longest idle first; an upload being analysed never does.
     */
    private void requireRoomFor(String owner, long sizeBytes) {
        var limits = properties.upload();
        var own = activeUploads(uploads.findByOwnerSubjectAndStatusIn(owner, VideoUploadStatus.ACTIVE));
        var tooMany = own.size() + 1L - limits.maxActivePerUser();
        if (tooMany > 0 && releaseIdle(own, upload -> 1, tooMany) < tooMany) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_UPLOAD_LIMIT,
                    "At most " + limits.maxActivePerUser() + " video uploads can be in progress at a time"
            );
        }
        // The upload disk is shared, so all users' unfinished uploads together stay within a budget.
        var all = activeUploads(uploads.findByStatusIn(VideoUploadStatus.ACTIVE));
        var tooLarge = all.stream().mapToLong(VideoUploadEntity::getSizeBytes).sum() + sizeBytes
                - limits.maxTotalBytes();
        if (tooLarge > 0 && releaseIdle(all, VideoUploadEntity::getSizeBytes, tooLarge) < tooLarge) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_UPLOAD_LIMIT,
                    "The server holds as many video uploads as it can; try again later"
            );
        }
    }

    /** Expires idle uploads, longest idle first, until {@code needed} of {@code measure} is released. */
    private long releaseIdle(
            List<VideoUploadEntity> candidates,
            ToLongFunction<VideoUploadEntity> measure,
            long needed
    ) {
        var idleSince = now().minus(properties.upload().idleTimeout());
        long released = 0;
        for (var upload : candidates.stream().sorted(Comparator.comparing(VideoUploadEntity::getUpdatedAt)).toList()) {
            if (released >= needed || upload.getUpdatedAt().isAfter(idleSince)) {
                break;
            }
            var expired = locked(upload.getId(), () -> {
                if (uploads.expireIdle(upload.getId(), idleSince, now()) == 0) {
                    return false;
                }
                storage.delete(upload.getId());
                return true;
            });
            if (expired) {
                released += measure.applyAsLong(upload);
            }
        }
        return released;
    }

    // Whatever else lives on the upload disk (the system, logs, the next release) keeps min-free-bytes of it.
    private void requireSpaceFor(long bytes) {
        if (storage.usableSpace() - bytes < properties.upload().minFreeBytes()) {
            throw new VideoUploadException(
                    VideoUploadException.Code.VIDEO_UPLOAD_LIMIT,
                    "The server is short of disk space for video uploads; try again later"
            );
        }
    }

    private List<VideoUploadEntity> activeUploads(List<VideoUploadEntity> candidates) {
        return candidates.stream()
                .map(this::current)
                .filter(upload -> upload.getStatus().isActive())
                .toList();
    }

    /**
     * An upload past its expiry is expired as soon as anyone looks at it, not only when the sweeper runs. An
     * assembled upload whose video is gone (a lost disk, a restored database) fails the same way: no job of it
     * could ever run, so the app has to upload the video again.
     */
    private VideoUploadEntity current(VideoUploadEntity upload) {
        if (upload.getStatus().isActive() && !upload.getExpiresAt().isAfter(now())) {
            expire(upload.getId());
            return require(upload.getId());
        }
        var videoGone = upload.getStatus() == VideoUploadStatus.COMPLETE
                && !Files.isRegularFile(storage.videoFile(upload.getId()));
        if (videoGone) {
            markFailed(upload.getId());
            return require(upload.getId());
        }
        return upload;
    }

    private boolean expire(UUID uploadId) {
        return locked(uploadId, () -> {
            if (uploads.expire(uploadId, now()) == 0) {
                return false;
            }
            storage.delete(uploadId);
            return true;
        });
    }

    private VideoUploadResponse response(VideoUploadEntity upload) {
        return new VideoUploadResponse(
                upload.getId(),
                upload.getStatus(),
                upload.getChunkSizeBytes(),
                upload.getChunkCount(),
                heldChunks(upload),
                upload.getExpiresAt()
        );
    }

    // What the server holds now: the chunk files while receiving, every chunk once assembled, nothing after.
    private List<Integer> heldChunks(VideoUploadEntity upload) {
        return switch (upload.getStatus()) {
            case RECEIVING -> storage.receivedChunks(upload.getId(), upload.getChunkCount());
            case COMPLETE -> IntStream.range(0, upload.getChunkCount()).boxed().toList();
            case CONSUMED, EXPIRED, FAILED -> List.of();
        };
    }

    private <T> T locked(UUID uploadId, Supplier<T> action) {
        var lock = locks[Math.floorMod(uploadId.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private VideoUploadEntity require(UUID uploadId) {
        return uploads.findById(uploadId).orElseThrow(() -> notFound(uploadId));
    }

    private String currentOwner() {
        return users.requireCurrentUser().id().value();
    }

    private Instant now() {
        // PostgreSQL keeps microseconds; the API then shows the same instant before and after a reload.
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static VideoUploadException notReceiving(VideoUploadEntity upload) {
        return new VideoUploadException(
                VideoUploadException.Code.VIDEO_UPLOAD_CLOSED,
                "Video upload is " + upload.getStatus() + " and no longer accepts chunks"
        );
    }

    private static VideoUploadException notFound(UUID uploadId) {
        return new VideoUploadException(
                VideoUploadException.Code.VIDEO_UPLOAD_NOT_FOUND,
                "Video upload not found: " + uploadId
        );
    }

    private static VideoUploadException invalid(String message) {
        return new VideoUploadException(VideoUploadException.Code.INVALID_REQUEST, message);
    }
}
