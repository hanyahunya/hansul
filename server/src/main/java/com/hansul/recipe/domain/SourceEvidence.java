package com.hansul.recipe.domain;

public record SourceEvidence(
        SourceType type,
        String locator
) {
    public enum SourceType {
        JSON_LD,
        DOM,
        VIDEO_DESCRIPTION,
        VIDEO_SEGMENT
    }
}
