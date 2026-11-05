package com.hansul.app.model

import kotlinx.serialization.Serializable

@Serializable
data class CookingSession(
    val id: String,
    val timerCues: Map<String, CookingTimerCue>,
    val timerDeadlines: List<CookingTimerDeadline>,
    val state: CookingProgress = CookingProgress(),
    val pendingTimerCueIds: Set<String> = emptySet(),
)

@Serializable
data class CookingTimerCue(
    val id: String,
    val durationSeconds: Long,
    val sourceStepNumber: Int,
    val approximate: Boolean,
)

@Serializable
data class CookingTimerDeadline(
    val timerCueId: String,
    val startedAt: String,
    val endsAt: String,
    val elapsedAt: String?,
)

@Serializable
data class CookingTurn(
    val sessionId: String,
    val clientTurnId: String,
    val addressing: Addressing,
    val replyText: String,
    val photos: List<CookingPhotoCue>,
    val events: List<CookingEvent>,
    val state: CookingProgress? = null,
)

@Serializable
enum class Addressing {
    DIRECT,
    UNCERTAIN,
    UNRELATED,
    UNKNOWN,
}

@Serializable
data class CookingPhotoCue(
    val sourceStepNumber: Int,
    val imageUrl: String,
)

@Serializable
data class CookingEvent(
    val type: String,
    val targetId: String,
)

@Serializable
data class TimerAcknowledgement(
    val sessionId: String,
    val clientAckId: String,
    val timerDeadline: CookingTimerDeadline?,
)

/** Looking at a step is not proof that earlier work is complete. */
@Serializable
data class CookingProgress(
    val version: Long = 0,
    val focusedSourceStepNumber: Int? = null,
    val completedActionIds: Set<String> = emptySet(),
    val requestedTimerCueIds: Set<String> = emptySet(),
    val completedSourceStepNumbers: Set<Int> = emptySet(),
    val skippedSourceStepNumbers: Set<Int> = emptySet(),
    val ingredientAdjustments: List<IngredientAdjustment> = emptyList(),
)

@Serializable
data class IngredientAdjustment(
    val kind: String,
    val ingredientName: String,
    val replacementName: String = "",
    val quantityText: String = "",
    val evidence: String = "",
)

@Serializable
data class CookingNavigation(
    val sessionId: String,
    val sourceStepNumber: Int,
    val state: CookingProgress = CookingProgress(),
)

@Serializable
enum class StepProgressStatus { COMPLETED, SKIPPED, PENDING }
