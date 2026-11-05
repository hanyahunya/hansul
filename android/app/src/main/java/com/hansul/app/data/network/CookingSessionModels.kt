package com.hansul.app.data.network

import com.hansul.app.model.Addressing
import com.hansul.app.model.CookingEvent
import com.hansul.app.model.CookingPhotoCue
import com.hansul.app.model.CookingSession
import com.hansul.app.model.CookingTimerCue
import com.hansul.app.model.CookingTimerDeadline
import com.hansul.app.model.CookingTurn
import com.hansul.app.model.CookingProgress
import com.hansul.app.model.CookingNavigation
import com.hansul.app.model.TimerAcknowledgement
import java.time.Duration
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class CreateCookingSessionRequest(
    val recipeId: String,
    val targetServings: Int,
)

@Serializable
data class CookingSessionResponse(
    val id: String,
    val plan: CookingPlanResponse = CookingPlanResponse(),
    val timerDeadlines: List<TimerDeadlineResponse> = emptyList(),
    val state: CookingProgress = CookingProgress(),
    val pendingTimerCueIds: Set<String> = emptySet(),
)

@Serializable
data class CookingPlanResponse(
    val timerCues: Map<String, TimerCueResponse> = emptyMap(),
)

@Serializable
data class TimerCueResponse(
    val id: String = "",
    val duration: JsonElement = JsonNull,
    val durationSeconds: Long? = null,
    val sourceStepNumber: Int = 0,
    val approximate: Boolean = false,
)

@Serializable
data class CookingTurnRequest(
    val clientTurnId: String,
    val transcript: String,
)

@Serializable
data class CookingTurnResponse(
    val sessionId: String,
    val clientTurnId: String,
    val addressing: String,
    val replyText: String = "",
    val photos: List<PhotoCueResponse> = emptyList(),
    val events: List<CookingEventResponse> = emptyList(),
    val state: CookingProgress? = null,
)

@Serializable
data class CookingNavigationRequest(val clientNavigationId: String, val sourceStepNumber: Int)

@Serializable
data class StepProgressRequest(val clientCommandId: String, val sourceStepNumber: Int, val status: String)

@Serializable
data class CookingNavigationResponse(
    val sessionId: String,
    val sourceStepNumber: Int,
    val state: CookingProgress = CookingProgress(),
)

@Serializable
data class PhotoCueResponse(
    val sourceStepNumber: Int,
    val imageUrl: String,
)

@Serializable
data class CookingEventResponse(
    val type: String,
    val targetId: String,
)

@Serializable
data class TimerStartAckRequest(
    val clientAckId: String,
    val timerCueId: String,
)

@Serializable
data class TimerStartRequest(val clientCommandId: String, val timerCueId: String)

@Serializable
data class TimerStartRequestResponse(
    val sessionId: String,
    val clientCommandId: String,
    val timerCueId: String,
    val state: CookingProgress = CookingProgress(),
    val duplicate: Boolean = false,
)

@Serializable
data class TimerStartAckResponse(
    val sessionId: String,
    val clientAckId: String,
    val timerDeadline: TimerDeadlineResponse? = null,
)

@Serializable
data class TimerDeadlineResponse(
    val timerCueId: String,
    val startedAt: String = "",
    val endsAt: String = "",
    val elapsedAt: String? = null,
)

internal fun CookingSessionResponse.toDomain(): CookingSession = CookingSession(
    id = id,
    timerCues = plan.timerCues.mapValues { (mapId, cue) ->
        CookingTimerCue(
            id = cue.id.ifBlank { mapId },
            durationSeconds = cue.durationSeconds ?: cue.duration.durationSeconds(),
            sourceStepNumber = cue.sourceStepNumber,
            approximate = cue.approximate,
        )
    },
    timerDeadlines = timerDeadlines.map(TimerDeadlineResponse::toDomain),
    state = state,
    pendingTimerCueIds = pendingTimerCueIds + state.requestedTimerCueIds,
)

internal fun CookingTurnResponse.toDomain(): CookingTurn = CookingTurn(
    sessionId = sessionId,
    clientTurnId = clientTurnId,
    addressing = runCatching { Addressing.valueOf(addressing) }.getOrDefault(Addressing.UNKNOWN),
    replyText = replyText,
    photos = photos.map { cue -> CookingPhotoCue(cue.sourceStepNumber, cue.imageUrl) },
    events = events.map { event -> CookingEvent(event.type, event.targetId) },
    state = state,
)

internal fun CookingNavigationResponse.toDomain() = CookingNavigation(sessionId, sourceStepNumber, state)

internal fun TimerStartAckResponse.toDomain(): TimerAcknowledgement = TimerAcknowledgement(
    sessionId = sessionId,
    clientAckId = clientAckId,
    timerDeadline = timerDeadline?.toDomain(),
)

private fun TimerDeadlineResponse.toDomain(): CookingTimerDeadline = CookingTimerDeadline(
    timerCueId = timerCueId,
    startedAt = startedAt,
    endsAt = endsAt,
    elapsedAt = elapsedAt,
)

private fun JsonElement.durationSeconds(): Long = when (this) {
    is JsonPrimitive -> longOrNull
        ?: doubleOrNull?.roundToLong()
        ?: contentOrNull?.let { value ->
            runCatching { Duration.parse(value).seconds }.getOrNull()
        }

    is JsonObject -> this["seconds"]?.let(JsonElement::durationSeconds)
    else -> null
}?.coerceAtLeast(0) ?: 0
