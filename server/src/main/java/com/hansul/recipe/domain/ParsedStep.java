package com.hansul.recipe.domain;

public record ParsedStep(
        int number,
        String rawText,
        String text,
        String imageUrl,
        SourceEvidence evidence,
        Integer videoStartSeconds,
        Integer videoEndSeconds,
        Integer keyFrameSeconds
) {
    public ParsedStep(int number, String rawText, String text, String imageUrl, SourceEvidence evidence) {
        this(number, rawText, text, imageUrl, evidence, null, null, null);
    }
}
