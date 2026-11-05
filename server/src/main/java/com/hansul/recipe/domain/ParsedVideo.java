package com.hansul.recipe.domain;

import java.util.Objects;

public record ParsedVideo(
        String provider,
        String videoId,
        String pageUrl,
        Integer durationSeconds,
        String channel,
        boolean embeddable
) {
    public static final String YOUTUBE = "YOUTUBE";
    public static final String NAVER_TV = "NAVER_TV";
    public static final String UPLOAD = "UPLOAD";

    public ParsedVideo {
        Objects.requireNonNull(provider, "provider");
    }
}
