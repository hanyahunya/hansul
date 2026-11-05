package com.hansul.recipe.video;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Plays scripted generateContent answers in order and records every call, Files API calls included. */
class FakeGeminiClient implements GeminiClient {

    static final UploadedFile PROCESSING_FILE = new UploadedFile(
            "files/abc123",
            "https://generativelanguage.googleapis.com/v1beta/files/abc123",
            "video/mp4",
            "PROCESSING"
    );

    final List<Request> requests = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final List<String> deletedFiles = new ArrayList<>();
    Path uploadedPath;
    String uploadedMimeType;
    String uploadedDisplayName;
    GeminiException uploadFailure;
    GeminiException processingFailure;
    String activeMimeType = "video/mp4";
    Integer activeDurationSeconds;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Deque<Object> answers = new ArrayDeque<>();

    FakeGeminiClient answer(String json, long inputTokens, long outputTokens) {
        answers.add(new Result(mapper.readTree(json), inputTokens, outputTokens));
        return this;
    }

    FakeGeminiClient answer(String json) {
        return answer(json, 100, 10);
    }

    FakeGeminiClient fail(GeminiException failure) {
        answers.add(failure);
        return this;
    }

    @Override
    public Result generateContent(Request request) {
        requests.add(request);
        events.add("generate");
        var next = answers.poll();
        if (next == null) {
            throw new AssertionError("Unexpected Gemini call #" + requests.size());
        }
        if (next instanceof GeminiException failure) {
            throw failure;
        }
        return (Result) next;
    }

    @Override
    public UploadedFile uploadFile(Path file, String mimeType, String displayName) {
        events.add("upload");
        uploadedPath = file;
        uploadedMimeType = mimeType;
        uploadedDisplayName = displayName;
        if (uploadFailure != null) {
            throw uploadFailure;
        }
        return PROCESSING_FILE;
    }

    @Override
    public UploadedFile awaitActive(UploadedFile file) {
        events.add("await " + file.name());
        if (processingFailure != null) {
            throw processingFailure;
        }
        return new UploadedFile(file.name(), file.uri(), activeMimeType, "ACTIVE", activeDurationSeconds);
    }

    @Override
    public void deleteFile(String name) {
        events.add("delete " + name);
        deletedFiles.add(name);
    }

    boolean hasUnusedAnswers() {
        return !answers.isEmpty();
    }
}
