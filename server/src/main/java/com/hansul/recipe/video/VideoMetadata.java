package com.hansul.recipe.video;

/** What YouTube says about a video; everything but the id and thumbnail may be unknown (null). */
public record VideoMetadata(
        String videoId,
        String title,
        String channel,
        String description,
        Integer durationSeconds,
        String thumbnailUrl,
        boolean embeddable
) {
}
