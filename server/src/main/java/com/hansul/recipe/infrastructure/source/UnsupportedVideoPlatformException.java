package com.hansul.recipe.infrastructure.source;

/** A video link the server cannot analyse; the app asks the user to share the saved video file instead. */
public class UnsupportedVideoPlatformException extends RuntimeException {

    private final String platform;

    public UnsupportedVideoPlatformException(String platform) {
        super(platform + " links cannot be analyzed; save the video on the phone and share the file instead");
        this.platform = platform;
    }

    public String platform() {
        return platform;
    }
}
