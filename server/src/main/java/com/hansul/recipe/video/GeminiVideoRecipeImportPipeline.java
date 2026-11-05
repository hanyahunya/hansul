package com.hansul.recipe.video;

import com.hansul.recipe.domain.ParsedVideo;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class GeminiVideoRecipeImportPipeline implements VideoRecipeImportPipeline {

    private static final int MAX_CHANNEL = 300;

    private final GeminiClient gemini;
    private final VideoMetadataSource metadataSource;
    private final VideoTranscriptPass transcriptPass;
    private final VideoRecipeStructuringPass structuringPass;
    private final VideoRecipeValidator validator;
    private final JsonMapper jsonMapper;
    private final VideoImportProperties properties;

    GeminiVideoRecipeImportPipeline(
            GeminiClient gemini,
            VideoMetadataSource metadataSource,
            VideoTranscriptPass transcriptPass,
            VideoRecipeStructuringPass structuringPass,
            VideoRecipeValidator validator,
            JsonMapper jsonMapper,
            VideoImportProperties properties
    ) {
        this.gemini = gemini;
        this.metadataSource = metadataSource;
        this.transcriptPass = transcriptPass;
        this.structuringPass = structuringPass;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public VideoImportResult importYouTube(String videoId, StageListener listener) {
        var stages = Objects.requireNonNullElse(listener, StageListener.NONE);
        requireConfigured();
        var watchUrl = YouTubeUrlPolicy.watchUrl(videoId);

        stages.onStage(VideoImportStage.FETCHING_METADATA);
        var metadata = metadataSource.fetch(videoId);

        stages.onStage(VideoImportStage.WATCHING_VIDEO);
        var transcript = watch(GeminiClient.Media.youTube(watchUrl));

        var channel = VideoText.clamp(VideoText.normalize(metadata.channel()), MAX_CHANNEL);
        var video = new ParsedVideo(
                ParsedVideo.YOUTUBE,
                videoId,
                watchUrl,
                metadata.durationSeconds() == null ? lastSecond(transcript) : metadata.durationSeconds(),
                channel.isEmpty() ? null : channel,
                metadata.embeddable()
        );
        var subject = new Subject(
                YOUTUBE_PARSER_VERSION,
                "youtube:" + videoId,
                metadata.title(),
                metadata.channel(),
                metadata.description(),
                metadata.thumbnailUrl(),
                video
        );
        return writeRecipe(subject, transcript, stages);
    }

    @Override
    public VideoImportResult importUpload(
            UUID uploadId,
            Path file,
            String contentType,
            Integer durationSeconds,
            StageListener listener
    ) {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(contentType, "contentType");
        var stages = Objects.requireNonNullElse(listener, StageListener.NONE);
        requireConfigured();
        if (!properties.isConfiguredForUploads()) {
            throw new VideoImportFailure(
                    VideoImportFailure.AI_UNAVAILABLE,
                    "Phone videos are analyzed only with a paid-tier Gemini key"
            );
        }
        requireAtMostMaxLength(durationSeconds);

        GeminiClient.UploadedFile uploaded = null;
        VideoTranscript transcript;
        GeminiClient.UploadedFile active;
        try {
            stages.onStage(VideoImportStage.UPLOADING_TO_AI);
            try {
                uploaded = gemini.uploadFile(file, contentType, "hansul-upload-" + uploadId);
                active = gemini.awaitActive(uploaded);
            } catch (GeminiException exception) {
                throw failure(exception, true);
            }
            // The app declares the length; what Gemini measured is what the transcript would be billed for.
            requireAtMostMaxLength(active.durationSeconds());
            stages.onStage(VideoImportStage.WATCHING_VIDEO);
            var mimeType = active.mimeType() == null || active.mimeType().isBlank()
                    ? contentType
                    : active.mimeType();
            transcript = watch(new GeminiClient.Media(active.uri(), mimeType));
        } finally {
            // The copy on Gemini is only needed for the transcript; pass 2 reads text alone.
            if (uploaded != null) {
                deleteQuietly(uploaded.name());
            }
        }

        var length = durationSeconds == null || durationSeconds <= 0 ? active.durationSeconds() : durationSeconds;
        var video = new ParsedVideo(
                ParsedVideo.UPLOAD,
                null,
                null,
                length == null || length <= 0 ? lastSecond(transcript) : length,
                null,
                false
        );
        var subject = new Subject(UPLOAD_PARSER_VERSION, "upload:" + uploadId, null, null, null, null, video);
        return writeRecipe(subject, transcript, stages);
    }

    private VideoTranscript watch(GeminiClient.Media media) {
        final VideoTranscript transcript;
        try {
            transcript = transcriptPass.transcribe(media);
        } catch (GeminiException exception) {
            throw failure(exception, true);
        }
        if (!transcript.cookingVideo() || transcript.segments().isEmpty()) {
            throw new VideoImportFailure(
                    VideoImportFailure.NOT_A_RECIPE_VIDEO,
                    "The video does not show cooking that can be written down as a recipe"
            );
        }
        return transcript;
    }

    private VideoImportResult writeRecipe(Subject subject, VideoTranscript transcript, StageListener stages) {
        stages.onStage(VideoImportStage.WRITING_RECIPE);
        var sources = new VideoSources(transcript.segments(), subject.description());
        var inputTokens = transcript.inputTokens();
        var outputTokens = transcript.outputTokens();

        final VideoRecipeStructuringPass.Result first;
        try {
            first = structuringPass.structure(subject.title(), subject.channel(), sources, List.of());
        } catch (GeminiException exception) {
            throw failure(exception, false);
        }
        inputTokens += first.inputTokens();
        outputTokens += first.outputTokens();

        var draft = first.draft();
        var issues = validator.issues(draft, sources);
        if (!issues.isEmpty()) {
            try {
                var second = structuringPass.structure(subject.title(), subject.channel(), sources, issues);
                inputTokens += second.inputTokens();
                outputTokens += second.outputTokens();
                draft = second.draft();
            } catch (GeminiException exception) {
                // The first draft goes through the same fallbacks, so it is still safe to use.
                inputTokens += exception.inputTokens();
                outputTokens += exception.outputTokens();
            }
        }

        var envelope = new VideoRecipeValidator.Envelope(
                subject.sourceEvidenceLocator(),
                subject.title(),
                subject.description(),
                subject.thumbnailUrl(),
                subject.video(),
                rawStructuredData(subject.parserVersion(), transcript),
                transcript.warnings()
        );
        var recipe = validator.toRecipe(draft, sources, envelope);
        return new VideoImportResult(recipe, subject.parserVersion(), inputTokens, outputTokens);
    }

    private String rawStructuredData(String parserVersion, VideoTranscript transcript) {
        var data = new LinkedHashMap<String, Object>();
        data.put("pipelineVersion", parserVersion);
        data.put("model", properties.gemini().model());
        data.put("language", transcript.language());
        data.put("segments", transcript.segments().stream().map(VideoTranscript.Segment::toJson).toList());
        return jsonMapper.writeValueAsString(data);
    }

    private void deleteQuietly(String fileName) {
        try {
            gemini.deleteFile(fileName);
        } catch (RuntimeException exception) {
            // Cleanup must never replace the import's own outcome; Gemini expires the file anyway.
        }
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new VideoImportFailure(VideoImportFailure.AI_UNAVAILABLE, "Video analysis is not configured");
        }
    }

    private void requireAtMostMaxLength(Integer durationSeconds) {
        if (durationSeconds != null && durationSeconds > properties.maxVideoSeconds()) {
            throw new VideoImportFailure(
                    VideoImportFailure.VIDEO_TOO_LONG,
                    "The video is longer than " + properties.maxVideoSeconds() + " seconds"
            );
        }
    }

    private static int lastSecond(VideoTranscript transcript) {
        var end = transcript.segments().stream()
                .mapToDouble(VideoTranscript.Segment::endSeconds)
                .max()
                .orElse(0);
        return (int) Math.ceil(end);
    }

    // Invalid arguments while Gemini reads the video mean it could not open it; later they are our fault.
    private static VideoImportFailure failure(GeminiException exception, boolean readingVideo) {
        var code = switch (exception.kind()) {
            case QUOTA -> VideoImportFailure.AI_QUOTA_EXCEEDED;
            case BLOCKED, RECITATION -> VideoImportFailure.AI_BLOCKED;
            case INVALID_ARGUMENT -> readingVideo
                    ? VideoImportFailure.VIDEO_UNAVAILABLE
                    : VideoImportFailure.AI_UNAVAILABLE;
            case CONFIGURATION, TRANSIENT, TRUNCATED, MALFORMED, FILE_PROCESSING_FAILED ->
                    VideoImportFailure.AI_UNAVAILABLE;
        };
        return new VideoImportFailure(code, exception.getMessage(), exception);
    }

    private record Subject(
            String parserVersion,
            String sourceEvidenceLocator,
            String title,
            String channel,
            String description,
            String thumbnailUrl,
            ParsedVideo video
    ) {
    }
}
