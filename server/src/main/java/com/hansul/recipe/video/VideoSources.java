package com.hansul.recipe.video;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The evidence a video recipe may cite: description lines {@code d1..} and transcript segments {@code s1..}. */
final class VideoSources {

    static final int MAX_DESCRIPTION_LINES = 300;
    static final int MAX_DESCRIPTION_LINE_LENGTH = 300;

    private final List<VideoTranscript.Segment> segments;
    private final List<Source> descriptionLines;
    private final Map<String, Integer> segmentPositions = new HashMap<>();
    private final Map<String, Source> sourcesByRef = new LinkedHashMap<>();

    VideoSources(List<VideoTranscript.Segment> segments, String description) {
        this.segments = List.copyOf(segments);
        this.descriptionLines = descriptionLines(description);
        descriptionLines.forEach(line -> sourcesByRef.put(line.ref(), line));
        for (var position = 0; position < this.segments.size(); position++) {
            var segment = this.segments.get(position);
            segmentPositions.put(segment.id(), position);
            sourcesByRef.put(segment.id(), new Source(segment.id(), false, segment.sourceText()));
        }
    }

    record Source(String ref, boolean fromDescription, String text) {
    }

    List<VideoTranscript.Segment> segments() {
        return segments;
    }

    List<Source> descriptionLines() {
        return descriptionLines;
    }

    Optional<VideoTranscript.Segment> segment(String id) {
        var position = segmentPositions.get(id);
        return position == null ? Optional.empty() : Optional.of(segments.get(position));
    }

    /** Position in time order; segment ids are only unique labels. */
    int position(VideoTranscript.Segment segment) {
        return segmentPositions.get(segment.id());
    }

    Optional<Source> source(String ref) {
        return Optional.ofNullable(sourcesByRef.get(ref));
    }

    /** The first description line, then segment, whose compacted text contains {@code compactText}. */
    Optional<Source> firstContaining(String compactText) {
        if (compactText.isEmpty()) {
            return Optional.empty();
        }
        return sourcesByRef.values().stream()
                .filter(source -> VideoText.compact(source.text()).contains(compactText))
                .findFirst();
    }

    /** Split on line breaks, normalized, blank lines dropped, at most 300 lines of at most 300 chars. */
    static List<Source> descriptionLines(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        var lines = new ArrayList<Source>();
        for (var line : description.split("\\R")) {
            var text = VideoText.clamp(VideoText.normalize(line), MAX_DESCRIPTION_LINE_LENGTH);
            if (text.isEmpty()) {
                continue;
            }
            lines.add(new Source("d" + (lines.size() + 1), true, text));
            if (lines.size() == MAX_DESCRIPTION_LINES) {
                break;
            }
        }
        return List.copyOf(lines);
    }
}
