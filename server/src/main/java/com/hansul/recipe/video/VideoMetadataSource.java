package com.hansul.recipe.video;

/** Video facts the pipeline needs before analysis; {@link YouTubeMetadataClient} reads them from YouTube. */
@FunctionalInterface
public interface VideoMetadataSource {

    /** Throws {@link VideoImportFailure} VIDEO_UNAVAILABLE or VIDEO_TOO_LONG when analysis must not start. */
    VideoMetadata fetch(String videoId);
}
