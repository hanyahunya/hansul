package com.hansul.recipe.video;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pass 1 output after sanitising: segments in time order with ids {@code s1..sN}. */
record VideoTranscript(
        boolean cookingVideo,
        String language,
        List<Segment> segments,
        List<String> warnings,
        long inputTokens,
        long outputTokens
) {
    VideoTranscript {
        segments = List.copyOf(segments);
        warnings = List.copyOf(warnings);
    }

    record Segment(String id, double startSeconds, double endSeconds, String speech, String screenText) {

        /** What a quote or a number of this segment is checked against. */
        String sourceText() {
            return speech + "\n" + screenText;
        }

        Map<String, Object> toJson() {
            var json = new LinkedHashMap<String, Object>();
            json.put("id", id);
            json.put("startSeconds", seconds(startSeconds));
            json.put("endSeconds", seconds(endSeconds));
            json.put("speech", speech);
            json.put("screenText", screenText);
            return json;
        }

        private static Number seconds(double value) {
            if (value == Math.rint(value)) {
                return (long) value;
            }
            return value;
        }
    }
}
