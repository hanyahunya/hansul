package com.hansul.app.data

import com.hansul.app.data.network.CookingTurnRequest
import com.hansul.app.data.network.CookingNavigationRequest
import com.hansul.app.data.network.StepProgressRequest
import com.hansul.app.data.network.CreateCookingSessionRequest
import com.hansul.app.data.network.RecipeImportApi
import com.hansul.app.data.network.TimerStartAckRequest
import com.hansul.app.data.network.toDomain
import com.hansul.app.model.CookingSession
import com.hansul.app.model.CookingTurn
import com.hansul.app.model.CookingNavigation
import com.hansul.app.model.StepProgressStatus
import com.hansul.app.model.CookingProgress
import com.hansul.app.data.network.TimerStartRequest
import com.hansul.app.model.TimerAcknowledgement

interface CookingSessionRepository {
    suspend fun requestTimerStart(sessionId: String, clientCommandId: String, timerCueId: String): CookingProgress =
        throw UnsupportedOperationException("이 조리 모드는 터치 타이머를 지원하지 않아요.")

    suspend fun getSession(sessionId: String): CookingSession =
        throw UnsupportedOperationException("저장된 조리 세션을 불러올 수 없어요.")

    suspend fun navigate(sessionId: String, clientNavigationId: String, sourceStepNumber: Int): CookingNavigation =
        throw UnsupportedOperationException("이 조리 모드는 단계 동기화를 지원하지 않아요.")

    suspend fun updateStepProgress(sessionId: String, clientCommandId: String, sourceStepNumber: Int,
        status: StepProgressStatus): CookingNavigation =
        throw UnsupportedOperationException("이 조리 모드는 완료 표시를 지원하지 않아요.")

    suspend fun start(recipeId: String, targetServings: Int): CookingSession

    suspend fun submitTurn(
        sessionId: String,
        clientTurnId: String,
        transcript: String,
    ): CookingTurn

    suspend fun acknowledgeTimerStart(
        sessionId: String,
        clientAckId: String,
        timerCueId: String,
    ): TimerAcknowledgement
}

class DefaultCookingSessionRepository(
    private val api: RecipeImportApi,
) : CookingSessionRepository {
    override suspend fun requestTimerStart(sessionId: String, clientCommandId: String, timerCueId: String): CookingProgress {
        val response = api.requestTimerStart(sessionId, TimerStartRequest(clientCommandId, timerCueId))
        require(response.sessionId == sessionId && response.clientCommandId == clientCommandId && response.timerCueId == timerCueId)
        return response.state
    }

    override suspend fun getSession(sessionId: String) = api.getCookingSession(sessionId).toDomain()

    override suspend fun navigate(sessionId: String, clientNavigationId: String, sourceStepNumber: Int) =
        api.navigateCookingStep(sessionId, CookingNavigationRequest(clientNavigationId, sourceStepNumber)).toDomain()

    override suspend fun updateStepProgress(sessionId: String, clientCommandId: String, sourceStepNumber: Int,
        status: StepProgressStatus) =
        api.updateStepProgress(sessionId, StepProgressRequest(clientCommandId, sourceStepNumber, status.name)).toDomain()

    override suspend fun start(recipeId: String, targetServings: Int): CookingSession =
        api.startCookingSession(CreateCookingSessionRequest(recipeId, targetServings)).toDomain()

    override suspend fun submitTurn(
        sessionId: String,
        clientTurnId: String,
        transcript: String,
    ): CookingTurn = api.submitCookingTurn(
        sessionId,
        CookingTurnRequest(clientTurnId, transcript),
    ).toDomain()

    override suspend fun acknowledgeTimerStart(
        sessionId: String,
        clientAckId: String,
        timerCueId: String,
    ): TimerAcknowledgement = api.acknowledgeTimerStart(
        sessionId,
        TimerStartAckRequest(clientAckId, timerCueId),
    ).toDomain()
}

