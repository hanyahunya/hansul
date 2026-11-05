package com.hansul.app.ui

import com.hansul.app.data.video.VideoImportException
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeOrigin
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.model.VideoProvider
import com.hansul.app.share.RecipeUrlParser

internal const val VIDEO_LOGIN_MESSAGE = "영상 레시피는 한술 서버의 AI가 분석해요. 로그인해주세요."

/** Shown once, before the first phone video leaves the device. */
internal const val VIDEO_UPLOAD_NOTICE = "휴대폰 영상은 레시피로 정리하기 위해 한술 서버를 거쳐 Google의 Gemini AI로 보내요. " +
    "영상에 담긴 얼굴, 목소리, 집 안 모습도 함께 전달될 수 있어요. 보낸 영상은 분석이 끝나면 서버와 Gemini에서 지우고, " +
    "다시 시도할 수 있게 남겨 둔 영상도 약 6시간 뒤 지워요. 동의하지 않으면 영상은 휴대폰 밖으로 나가지 않아요."
private const val VIDEO_RETRY_MESSAGE = "영상 분석 중 문제가 생겼어요. 다시 시도해주세요."

/** Korean text for a video import code (contract section 5). The exception message is English and never shown. */
internal fun videoImportMessage(code: String): String = when (code) {
    "VIDEO_LINK_NOT_SUPPORTED" -> "인스타그램·틱톡·네이버TV 링크는 바로 분석할 수 없어요. 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요."
    "VIDEO_IMPORT_DAILY_LIMIT" -> "오늘 영상 분석 횟수를 모두 사용했어요. 내일 다시 시도해주세요."
    "VIDEO_ANALYSIS_UNAVAILABLE" -> "지금은 영상 분석을 사용할 수 없어요. 서버의 AI 설정을 확인해주세요."
    "VIDEO_UNAVAILABLE" -> "영상을 열 수 없어요. 공개된 영상인지 확인해주세요."
    VideoImportException.VIDEO_TOO_LONG -> "30분이 넘는 영상은 아직 분석하지 않아요."
    "NOT_A_RECIPE_VIDEO" -> "요리 과정이 나오는 영상이 아닌 것 같아요."
    "RECIPE_EXTRACTION_FAILED" -> "영상에서 조리 단계를 정확히 정리하지 못했어요."
    "AI_QUOTA_EXCEEDED" -> "AI 사용량 한도에 도달했어요. 잠시 후 다시 시도해주세요."
    "AI_BLOCKED", "AI_UNAVAILABLE", "SERVER_RESTARTED", VideoImportException.INTERNAL_ERROR,
    VideoImportException.TIMEOUT -> VIDEO_RETRY_MESSAGE
    VideoImportException.VIDEO_UPLOAD_TOO_LARGE -> "영상 파일이 너무 커요. 더 짧은 영상으로 시도해주세요."
    "VIDEO_UPLOAD_LIMIT" -> "진행 중인 영상 업로드가 많아요. 잠시 후 다시 시도해주세요."
    VideoImportException.UPLOAD_INCOMPLETE, "UPLOAD_CHECKSUM_MISMATCH", VideoImportException.VIDEO_CHUNK_INVALID,
    "VIDEO_UPLOAD_CLOSED" -> "영상 업로드가 완료되지 않았어요. 다시 시도해주세요."
    // Device-only codes; the contract table has no text for them.
    VideoImportException.LOCAL_VIDEO_UNREADABLE -> "영상 파일을 읽지 못했어요. 휴대폰에 저장된 영상을 다시 골라주세요."
    VideoImportException.VIDEO_COMPRESSION_FAILED -> "영상을 올릴 수 있는 크기로 줄이지 못했어요. 더 짧은 영상으로 시도해주세요."
    VideoImportException.LOCAL_STORAGE_FULL -> "휴대폰 저장 공간이 부족해요. 공간을 비운 뒤 다시 시도해주세요."
    VideoImportException.INVALID_RECIPE_URL -> "레시피 링크를 읽을 수 없어요. 원문 링크를 다시 공유해주세요."
    "RECIPE_NOT_FOUND" -> "레시피를 찾지 못했어요."
    else -> VIDEO_RETRY_MESSAGE
}

internal fun VideoImportProgress.stageLabel(): String = when (stage) {
    VideoImportStage.PREPARING_VIDEO -> "영상 준비 중"
    VideoImportStage.COMPRESSING_VIDEO -> "영상 크기 줄이는 중"
    VideoImportStage.UPLOADING_VIDEO -> "영상 올리는 중 ${((fraction ?: 0f).coerceIn(0f, 1f) * 100).toInt()}%"
    VideoImportStage.QUEUED -> "분석 차례를 기다리는 중"
    VideoImportStage.FETCHING_METADATA -> "영상 정보 확인 중"
    VideoImportStage.UPLOADING_TO_AI -> "AI에게 영상 전달 중"
    VideoImportStage.WATCHING_VIDEO -> "AI가 영상을 보는 중"
    VideoImportStage.WRITING_RECIPE -> "레시피 정리 중"
    VideoImportStage.SAVING -> "저장 중"
    VideoImportStage.EXTRACTING_FRAMES -> "단계 장면 만드는 중"
}

/** `01:23`, or `1:02:03` from an hour on. */
internal fun videoTime(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}

/** `01:23–02:05`; a missing or inverted end leaves only the start. */
internal fun videoRange(start: Int, end: Int?): String =
    if (end != null && end > start) "${videoTime(start)}–${videoTime(end)}" else videoTime(start)

internal fun Recipe.sourceLabel(): String = when {
    origin != RecipeOrigin.VIDEO_AI -> "만개의레시피"
    video?.provider == VideoProvider.LOCAL_FILE || RecipeUrlParser.localVideoId(sourceUrl) != null -> "내 영상 · AI 정리"
    else -> "YouTube · AI 정리"
}
