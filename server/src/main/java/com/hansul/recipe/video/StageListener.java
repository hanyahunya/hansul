package com.hansul.recipe.video;

@FunctionalInterface
public interface StageListener {

    StageListener NONE = stage -> {
    };

    /** Called on the pipeline's thread just before the stage starts. */
    void onStage(VideoImportStage stage);
}
