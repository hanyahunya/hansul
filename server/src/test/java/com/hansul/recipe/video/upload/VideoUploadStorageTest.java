package com.hansul.recipe.video.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoUploadStorageTest {

    private static final UUID UPLOAD_ID = UUID.fromString("5b0f8a8e-6f5d-4c8a-9d51-3c1e2f7a9b10");

    @TempDir
    Path root;

    private VideoUploadStorage storage;

    @BeforeEach
    void setUp() {
        storage = new VideoUploadStorage(VideoUploadTestSupport.properties(root, true));
    }

    @Test
    void readsAtMostOneByteMoreThanTheChunkLengthFromAnEndlessBody() {
        var body = new CountingEndlessStream();

        var chunk = storage.receiveChunk(UPLOAD_ID, 0, 1024, body);

        assertThat(chunk.length()).isEqualTo(1025);
        assertThat(body.bytesRead).isEqualTo(1025);
        storage.discard(chunk);
        assertThat(chunk.file()).doesNotExist();
    }

    @Test
    void movesAReceivedChunkIntoPlaceAndReplacesAnEarlierCopy() throws Exception {
        storage.storeChunk(UPLOAD_ID, 1, storage.receiveChunk(UPLOAD_ID, 1, 4, stream("abcd")));
        var resent = storage.receiveChunk(UPLOAD_ID, 1, 4, stream("wxyz"));
        assertThat(resent.length()).isEqualTo(4);

        storage.storeChunk(UPLOAD_ID, 1, resent);

        var directory = root.resolve(UPLOAD_ID.toString());
        assertThat(Files.readString(directory.resolve("chunk-00001"))).isEqualTo("wxyz");
        try (var files = Files.list(directory)) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("chunk-00001");
        }
    }

    @Test
    void listsOnlyFinishedChunkFilesBelowTheChunkCount() throws Exception {
        var directory = Files.createDirectories(root.resolve(UPLOAD_ID.toString()));
        for (var name : new String[] {"chunk-00002", "chunk-00000", "chunk-00001-73.part", "chunk-00009", "video", "x"}) {
            Files.writeString(directory.resolve(name), "data");
        }

        assertThat(storage.receivedChunks(UPLOAD_ID, 3)).containsExactly(0, 2);
        assertThat(storage.receivedChunks(UUID.randomUUID(), 3)).isEmpty();
    }

    @Test
    void assemblesTheChunksInOrderAndDeletesThemWhenSizeAndHashMatch() throws Exception {
        writeChunks("0123", "4567", "89");

        var matches = storage.assemble(UPLOAD_ID, 3, 10, VideoUploadTestSupport.sha256(bytes("0123456789")));

        assertThat(matches).isTrue();
        assertThat(Files.readString(storage.videoFile(UPLOAD_ID))).isEqualTo("0123456789");
        try (var files = Files.list(root.resolve(UPLOAD_ID.toString()))) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("video");
        }
    }

    @Test
    void keepsNoVideoWhenTheHashOrTheSizeDiffers() throws Exception {
        writeChunks("0123", "4567", "89");
        assertThat(storage.assemble(UPLOAD_ID, 3, 10, VideoUploadTestSupport.sha256(bytes("9876543210")))).isFalse();
        assertThat(storage.videoFile(UPLOAD_ID)).doesNotExist();
        assertThat(storage.receivedChunks(UPLOAD_ID, 3)).isEmpty();

        writeChunks("0123", "4567", "89");
        assertThat(storage.assemble(UPLOAD_ID, 3, 11, VideoUploadTestSupport.sha256(bytes("0123456789")))).isFalse();
        assertThat(storage.videoFile(UPLOAD_ID)).doesNotExist();
    }

    @Test
    void keepsTheChunksForAnotherAttemptWhenAssemblyFailsOnIo() throws Exception {
        writeChunks("0123", "4567");

        assertThatThrownBy(() -> storage.assemble(UPLOAD_ID, 3, 10, VideoUploadTestSupport.sha256(bytes("0123456789"))))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(storage.receivedChunks(UPLOAD_ID, 3)).containsExactly(0, 1);
        try (var files = Files.list(root.resolve(UPLOAD_ID.toString()))) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactlyInAnyOrder("chunk-00000", "chunk-00001");
        }
    }

    @Test
    void deletesAnUploadDirectoryAndListsOnlyDirectoriesNamedByAnUploadId() throws Exception {
        writeChunks("0123");
        var other = UUID.randomUUID();
        Files.createDirectories(root.resolve(other.toString()));
        Files.createDirectories(root.resolve("not-an-upload"));
        Files.createDirectories(root.resolve(other.toString().toUpperCase()));
        Files.writeString(root.resolve(UUID.randomUUID().toString()), "a file, not a directory");

        assertThat(storage.storedUploads()).containsExactlyInAnyOrder(UPLOAD_ID, other);

        storage.delete(UPLOAD_ID);
        storage.delete(UUID.randomUUID());

        assertThat(root.resolve(UPLOAD_ID.toString())).doesNotExist();
        assertThat(storage.storedUploads()).containsExactly(other);
    }

    private void writeChunks(String... chunks) throws Exception {
        for (var index = 0; index < chunks.length; index++) {
            storage.storeChunk(UPLOAD_ID, index, storage.receiveChunk(UPLOAD_ID, index, chunks[index].length(), stream(chunks[index])));
        }
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(bytes(content));
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.US_ASCII);
    }

    /** A request body that never ends, counting what the storage pulled from it. */
    private static final class CountingEndlessStream extends InputStream {

        private long bytesRead;

        @Override
        public int read() {
            bytesRead++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            Arrays.fill(buffer, offset, offset + length, (byte) 'x');
            bytesRead += length;
            return length;
        }
    }
}
