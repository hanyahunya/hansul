package com.hansul.recipe.video.upload;

import com.hansul.recipe.video.VideoImportProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One directory per upload, named by the upload id only: {@code chunk-00000}, {@code chunk-00001}, ... while
 * receiving, then a single {@code video} file. Files appear by atomic moves, so a listing never sees half a chunk.
 */
@Component
public class VideoUploadStorage {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadStorage.class);
    private static final String VIDEO_FILE = "video";
    private static final Pattern CHUNK_FILE = Pattern.compile("chunk-(\\d{5,9})");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final Path root;

    public VideoUploadStorage(VideoImportProperties properties) {
        this.root = properties.upload().directoryPath().toAbsolutePath().normalize();
    }

    /** A chunk body in a temporary file next to the chunks; {@code length} counts at most one byte too many. */
    record ReceivedChunk(Path file, long length) {
    }

    /** Never reads more than {@code expectedLength + 1} bytes, however long the body is. */
    ReceivedChunk receiveChunk(UUID uploadId, int index, long expectedLength, InputStream body) {
        Path part = null;
        try {
            var directory = Files.createDirectories(directory(uploadId));
            part = Files.createTempFile(directory, chunkName(index) + "-", ".part");
            var limit = expectedLength + 1;
            long length = 0;
            try (var out = Files.newOutputStream(part)) {
                var buffer = new byte[BUFFER_BYTES];
                while (length < limit) {
                    var read = body.read(buffer, 0, (int) Math.min(buffer.length, limit - length));
                    if (read < 0) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    length += read;
                }
            }
            return new ReceivedChunk(part, length);
        } catch (IOException exception) {
            deleteQuietly(part);
            throw new UncheckedIOException("Chunk " + index + " of video upload " + uploadId + " was not stored", exception);
        }
    }

    /** Replaces an earlier copy of the same chunk, so re-sending a chunk is idempotent. */
    void storeChunk(UUID uploadId, int index, ReceivedChunk chunk) {
        try {
            Files.move(
                    chunk.file(),
                    directory(uploadId).resolve(chunkName(index)),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException exception) {
            discard(chunk);
            throw new UncheckedIOException("Chunk " + index + " of video upload " + uploadId + " was not stored", exception);
        }
    }

    void discard(ReceivedChunk chunk) {
        deleteQuietly(chunk.file());
    }

    /** The indexes below {@code chunkCount} whose chunk files are in place, in ascending order. */
    List<Integer> receivedChunks(UUID uploadId, int chunkCount) {
        var directory = directory(uploadId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files
                    .map(file -> CHUNK_FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .filter(index -> index < chunkCount)
                    .sorted()
                    .toList();
        } catch (NoSuchFileException deletedMeanwhile) {
            return List.of();
        } catch (IOException exception) {
            throw new UncheckedIOException("Chunks of video upload " + uploadId + " could not be listed", exception);
        }
    }

    /**
     * Streams the chunks in order into the video file while hashing them. Once the outcome is known the
     * chunks are deleted; the video file is kept only when its size and sha256 match the declared ones.
     * On an I/O error the chunks stay, so the same upload can be assembled again.
     */
    boolean assemble(UUID uploadId, int chunkCount, long sizeBytes, String sha256) {
        var directory = directory(uploadId);
        var part = directory.resolve(VIDEO_FILE + ".part");
        var digest = sha256Digest();
        long size = 0;
        try {
            try (var out = new DigestOutputStream(Files.newOutputStream(part), digest)) {
                for (var index = 0; index < chunkCount; index++) {
                    size += Files.copy(directory.resolve(chunkName(index)), out);
                }
            }
            var matches = size == sizeBytes && HexFormat.of().formatHex(digest.digest()).equals(sha256);
            if (matches) {
                Files.move(part, videoFile(uploadId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            for (var index = 0; index < chunkCount; index++) {
                Files.deleteIfExists(directory.resolve(chunkName(index)));
            }
            return matches;
        } catch (IOException exception) {
            throw new UncheckedIOException("Video upload " + uploadId + " could not be assembled", exception);
        } finally {
            deleteQuietly(part);
        }
    }

    Path videoFile(UUID uploadId) {
        return directory(uploadId).resolve(VIDEO_FILE);
    }

    /** Bytes still free for this process on the disk that holds the uploads. */
    long usableSpace() {
        try {
            return Files.getFileStore(Files.createDirectories(root)).getUsableSpace();
        } catch (IOException exception) {
            throw new UncheckedIOException("The video upload directory is not usable", exception);
        }
    }

    /** Best effort: whatever cannot be deleted now is found again by the sweeper. */
    void delete(UUID uploadId) {
        var directory = directory(uploadId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException exception) {
            log.warn("Files of video upload {} were not deleted: {}", uploadId, exception.getClass().getSimpleName());
        }
    }

    /** The uploads that have a directory; anything under the root not named by an upload id is left alone. */
    List<UUID> storedUploads() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(path -> uploadId(path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("The video upload directory could not be listed", exception);
        }
    }

    private Path directory(UUID uploadId) {
        return root.resolve(uploadId.toString());
    }

    private static String chunkName(int index) {
        return "chunk-%05d".formatted(index);
    }

    private static Optional<UUID> uploadId(String name) {
        try {
            var id = UUID.fromString(name);
            return id.toString().equals(name) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException notAnUploadDirectory) {
            return Optional.empty();
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Temporary upload file {} was not deleted: {}", file.getFileName(), exception.getClass().getSimpleName());
        }
    }
}
