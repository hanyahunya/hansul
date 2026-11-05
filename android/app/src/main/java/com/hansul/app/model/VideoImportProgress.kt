package com.hansul.app.model

/** What a video import is doing right now. [fraction] is 0..1 when the stage can measure it. */
data class VideoImportProgress(
    val stage: VideoImportStage,
    val fraction: Float? = null,
)

/** Device stages first, then the server job stages (contract 2.2), then the device frame step. */
enum class VideoImportStage {
    PREPARING_VIDEO,
    COMPRESSING_VIDEO,
    UPLOADING_VIDEO,
    QUEUED,
    FETCHING_METADATA,
    UPLOADING_TO_AI,
    WATCHING_VIDEO,
    WRITING_RECIPE,
    SAVING,
    EXTRACTING_FRAMES,
}
