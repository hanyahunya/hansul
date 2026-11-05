package com.hansul.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hansul.app.auth.AuthRepository
import com.hansul.app.data.CookingSessionRepository
import com.hansul.app.data.RecipeRepository
import com.hansul.app.model.Addressing
import com.hansul.app.model.CookingPhotoCue
import com.hansul.app.model.CookingSession
import com.hansul.app.model.CookingTimerDeadline
import com.hansul.app.model.Recipe
import com.hansul.app.model.ScaledIngredients
import com.hansul.app.model.CookingProgress
import com.hansul.app.model.StepProgressStatus
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.model.baseServingCount
import com.hansul.app.data.ExecutionMode
import com.hansul.app.data.video.InMemoryVideoUploadConsent
import com.hansul.app.data.video.VideoImportException
import com.hansul.app.data.video.VideoIntake
import com.hansul.app.data.video.VideoLoginRequiredException
import com.hansul.app.data.video.VideoUploadConsent
import com.hansul.app.share.RecipeUrlParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

enum class TimerScheduleStage {
    NEEDS_DEVICE_SCHEDULE,
    ACKNOWLEDGING,
    ACK_FAILED,
}

data class PendingDeviceTimer(
    val cueId: String,
    val durationSeconds: Long,
    val approximate: Boolean,
    val clientAckId: String,
    val stage: TimerScheduleStage = TimerScheduleStage.NEEDS_DEVICE_SCHEDULE,
    val deviceDeadline: CookingTimerDeadline? = null,
)

data class FailedVoiceTurn(
    val clientTurnId: String,
    val transcript: String,
)

sealed interface VoiceEffect {
    data class Speak(val text: String) : VoiceEffect

    data object ResumeListening : VoiceEffect
}

data class RecipeImportUiState(
    val executionMode: ExecutionMode = ExecutionMode.CLOUD,
    val pendingUrl: String? = null,
    val recipe: Recipe? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isCooking: Boolean = false,
    val currentStepIndex: Int = 0,
    val targetServings: Int = 1,
    val scaledIngredients: ScaledIngredients? = null,
    val isScalingIngredients: Boolean = false,
    val isAuthInitializing: Boolean = true,
    val isAuthenticated: Boolean = false,
    val isAuthBusy: Boolean = false,
    val authErrorMessage: String? = null,
    val isStartingCooking: Boolean = false,
    val cookingSession: CookingSession? = null,
    val isVoiceEnabled: Boolean = false,
    val isTurnPending: Boolean = false,
    val failedVoiceTurn: FailedVoiceTurn? = null,
    val lastTranscript: String? = null,
    val assistantReply: String? = null,
    val voicePhotos: List<CookingPhotoCue> = emptyList(),
    val voiceErrorMessage: String? = null,
    val pendingDeviceTimers: List<PendingDeviceTimer> = emptyList(),
    val timerDeadlines: List<CookingTimerDeadline> = emptyList(),
    val progress: CookingProgress = CookingProgress(),
    val isStepSyncing: Boolean = false,
    val stepSyncError: String? = null,
    /** Copying a picked video or the server analysis of one; null when no video work is running. */
    val importProgress: VideoImportProgress? = null,
    /** Local mode: this video still has to be analysed by the Hansul server, which needs a signed-in user. */
    val loginRequiredForVideo: Boolean = false,
    /** A phone video waits for the one-time agreement that it goes to Google's Gemini for analysis. */
    val consentRequiredForVideo: Boolean = false,
    /** The server rejected the session, so signing in again is the way forward. */
    val errorNeedsSignIn: Boolean = false,
)

class RecipeImportViewModel(
    private val repository: RecipeRepository,
    private val authRepository: AuthRepository,
    private val cookingSessionRepository: CookingSessionRepository,
    private val executionMode: StateFlow<ExecutionMode> = MutableStateFlow(ExecutionMode.CLOUD),
    private val changeExecutionMode: suspend (ExecutionMode) -> Unit = {},
    private val videoIntake: VideoIntake = NoVideoIntake,
    private val videoUploadConsent: VideoUploadConsent = InMemoryVideoUploadConsent(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecipeImportUiState())
    val uiState: StateFlow<RecipeImportUiState> = _uiState.asStateFlow()
    private val _authorizationIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val authorizationIntents: SharedFlow<Intent> = _authorizationIntents.asSharedFlow()
    private val _voiceEffects = MutableSharedFlow<VoiceEffect>(extraBufferCapacity = 4)
    val voiceEffects: SharedFlow<VoiceEffect> = _voiceEffects.asSharedFlow()
    private var scalingJob: Job? = null
    private var loadJob: Job? = null
    private var intakeJob: Job? = null
    private val intakeProgress = MutableStateFlow<VideoImportProgress?>(null)
    // The pseudo URL a finished intake handed to the pending-URL collector, which then ends the preparing stage.
    private var handedOffVideoUrl: String? = null
    private val commandMutex = Mutex()
    private var navigationRevision = 0L
    private var pendingNavigation: Pair<String, Int>? = null

    init {
        viewModelScope.launch {
            combine(repository.importProgress, intakeProgress) { imported, intake -> imported ?: intake }
                .distinctUntilChanged()
                .collect { progress -> _uiState.update { it.copy(importProgress = progress) } }
        }

        viewModelScope.launch {
            authRepository.sessionState.collect { auth ->
                _uiState.update {
                    it.copy(
                        isAuthInitializing = auth.isInitializing,
                        isAuthenticated = auth.isAuthorized,
                        isAuthBusy = auth.isBusy,
                        authErrorMessage = auth.errorMessage,
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                repository.pendingRecipeUrl.distinctUntilChanged(),
                authRepository.sessionState
                    .map { auth -> auth.isInitializing to auth.isAuthorized }
                    .distinctUntilChanged(),
                executionMode,
            ) { url, (isAuthInitializing, isAuthorized), mode ->
                val needsServer = url != null && needsServer(url, mode)
                AccessState(url, needsServer && isAuthInitializing, !needsServer || isAuthorized, mode,
                    mode == ExecutionMode.LOCAL && needsServer && !isAuthorized)
            }.distinctUntilChanged()
                .collectLatest { (url, isAuthInitializing, isAuthorized, mode, loginRequiredForVideo) ->
                    _uiState.update { it.copy(executionMode = mode) }
                    when {
                        url == null -> {
                            loadJob?.cancel()
                            // The error stays: a share rejected before the stored URL was first read must still show.
                            _uiState.update {
                                it.copy(
                                    pendingUrl = null,
                                    recipe = null,
                                    isLoading = isPreparingVideo(),
                                    loginRequiredForVideo = false,
                                    consentRequiredForVideo = false,
                                    isCooking = false,
                                    currentStepIndex = 0,
                                    scaledIngredients = null,
                                    isScalingIngredients = false,
                                    isStartingCooking = false,
                                    cookingSession = null,
                                    isVoiceEnabled = false,
                                    isTurnPending = false,
                                    failedVoiceTurn = null,
                                    lastTranscript = null,
                                    assistantReply = null,
                                    voicePhotos = emptyList(),
                                    voiceErrorMessage = null,
                                    pendingDeviceTimers = emptyList(),
                                    timerDeadlines = emptyList(),
                                )
                            }
                        }

                        isAuthInitializing || !isAuthorized -> {
                            loadJob?.cancel()
                            if (url == handedOffVideoUrl) finishVideoIntake()
                            _uiState.update {
                                it.copy(
                                    pendingUrl = url,
                                    recipe = null,
                                    isLoading = isPreparingVideo(),
                                    errorMessage = null,
                                    errorNeedsSignIn = false,
                                    loginRequiredForVideo = loginRequiredForVideo,
                                    consentRequiredForVideo = false,
                                    isCooking = false,
                                    currentStepIndex = 0,
                                    scaledIngredients = null,
                                    isScalingIngredients = false,
                                    isStartingCooking = false,
                                    cookingSession = null,
                                    isVoiceEnabled = false,
                                    isTurnPending = false,
                                    failedVoiceTurn = null,
                                    lastTranscript = null,
                                    assistantReply = null,
                                    voicePhotos = emptyList(),
                                    voiceErrorMessage = null,
                                    pendingDeviceTimers = emptyList(),
                                    timerDeadlines = emptyList(),
                                )
                            }
                        }

                        else -> {
                            _uiState.update { current ->
                                current.copy(
                                    pendingUrl = url,
                                    recipe = current.recipe?.takeIf { it.sourceUrl == url },
                                    consentRequiredForVideo = false,
                                    isCooking = false,
                                    currentStepIndex = 0,
                                    targetServings = 1,
                                    scaledIngredients = null,
                                    isScalingIngredients = false,
                                    isStartingCooking = false,
                                    cookingSession = null,
                                    isVoiceEnabled = false,
                                    isTurnPending = false,
                                    failedVoiceTurn = null,
                                    lastTranscript = null,
                                    assistantReply = null,
                                    voicePhotos = emptyList(),
                                    voiceErrorMessage = null,
                                    pendingDeviceTimers = emptyList(),
                                    timerDeadlines = emptyList(),
                                )
                            }
                            // A video whose analysis already failed would be uploaded and analysed again on every
                            // start, using up the daily limit; it waits for the user's retry instead.
                            val failure = importFailureCode(url)
                            if (failure == null) startLoad(url) else showImportFailure(url, failure)
                        }
                    }
                }
        }
    }

    fun acceptSharedText(sharedText: String?) {
        val url = RecipeUrlParser.extract(sharedText)
        if (url == null) {
            val platform = RecipeUrlParser.unsupportedVideoPlatform(sharedText)
            _uiState.update {
                it.copy(
                    errorMessage = if (platform != null) {
                        "$platform 링크는 바로 분석할 수 없어요. 앱에서 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요."
                    } else {
                        "지원하는 만개의레시피 또는 YouTube 링크를 찾지 못했어요."
                    },
                    errorNeedsSignIn = false,
                )
            }
            return
        }

        cancelVideoIntake()
        viewModelScope.launch {
            val isSameUrl = _uiState.value.pendingUrl == url
            repository.rememberRecipeUrl(url)
            // A new URL reaches the pending-URL collector; sharing the same one again means "try once more".
            if (isSameUrl && loadJob?.isActive != true && canLoadNow(url)) startLoad(url)
        }
    }

    /** Copies a shared or picked video into app storage, then imports it like a shared link. */
    fun acceptVideoUri(uriString: String) {
        cancelVideoIntake()
        loadJob?.cancel()
        intakeProgress.value = VideoImportProgress(VideoImportStage.PREPARING_VIDEO)
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, errorNeedsSignIn = false, isCooking = false, isVoiceEnabled = false)
        }
        intakeJob = viewModelScope.launch {
            try {
                val pseudoUrl = videoIntake.intake(uriString)
                // Picking the same video again returns its stored link; the pending-URL collector would see no change.
                val isSameUrl = _uiState.value.pendingUrl == pseudoUrl
                handedOffVideoUrl = pseudoUrl
                repository.rememberRecipeUrl(pseudoUrl)
                if (isSameUrl) {
                    if (canLoadNow(pseudoUrl)) startLoad(pseudoUrl) else {
                        finishVideoIntake()
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                finishVideoIntake()
                _uiState.update { it.copy(isLoading = false, errorMessage = error.toIntakeMessage()) }
            }
        }
    }

    fun selectExecutionMode(mode: ExecutionMode) {
        if (mode == _uiState.value.executionMode) return
        if (_uiState.value.isCooking || _uiState.value.isTurnPending) {
            _uiState.update { it.copy(errorMessage = "진행 중인 요리는 현재 모드로 이어갑니다. 레시피 화면에서 모드를 바꿔주세요.") }
            return
        }
        viewModelScope.launch { changeExecutionMode(mode) }
    }

    fun signIn() {
        viewModelScope.launch {
            runCatching { authRepository.createAuthorizationIntent() }
                .onSuccess { intent -> _authorizationIntents.emit(intent) }
        }
    }

    fun completeSignIn(intent: Intent?) {
        viewModelScope.launch {
            // A session the server rejected still looks signed in, so the login itself does not restart the import.
            if (authRepository.completeAuthorization(intent) && _uiState.value.errorNeedsSignIn) retry()
        }
    }

    fun logout() {
        scalingJob?.cancel()
        viewModelScope.launch { authRepository.logout() }
    }

    fun retry() {
        _uiState.value.pendingUrl?.let(::startLoad)
    }

    /** The user agreed that phone videos are analysed by Google's Gemini; the waiting video starts right away. */
    fun acceptVideoUpload() {
        val url = _uiState.value.pendingUrl ?: return
        viewModelScope.launch {
            videoUploadConsent.give()
            if (_uiState.value.pendingUrl == url) startLoad(url)
        }
    }

    fun startCooking() {
        val snapshot = _uiState.value
        val recipe = snapshot.recipe ?: return
        if (snapshot.isStartingCooking) return
        if (snapshot.cookingSession != null) {
            _uiState.update { it.copy(isCooking = true) }
            return
        }
        if (recipe.id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "요리를 시작할 레시피 ID가 없어요.") }
            return
        }

        _uiState.update { it.copy(isStartingCooking = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                cookingSessionRepository.start(recipe.id, snapshot.targetServings)
            }.onSuccess { session ->
                if (_uiState.value.recipe?.id == recipe.id) {
                    _uiState.update {
                        it.copy(
                            isStartingCooking = false,
                            isCooking = true,
                            cookingSession = session,
                            timerDeadlines = session.timerDeadlines,
                            pendingDeviceTimers = recoverPendingTimers(session),
                            progress = session.state,
                            currentStepIndex = recipe.steps.indexOfFirst { step ->
                                step.number == session.state.focusedSourceStepNumber
                            }.coerceAtLeast(0),
                            voiceErrorMessage = null,
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.recipe?.id != recipe.id) return@onFailure
                _uiState.update {
                    it.copy(
                        isStartingCooking = false,
                        errorMessage = error.toCookingMessage("요리 세션을 시작하지 못했어요."),
                    )
                }
            }
        }
    }

    fun decreaseServings() {
        setTargetServings((_uiState.value.targetServings - 1).coerceAtLeast(1))
    }

    fun increaseServings() {
        setTargetServings((_uiState.value.targetServings + 1).coerceAtMost(99))
    }

    fun showRecipePreview() {
        _uiState.update {
            it.copy(
                isCooking = false,
                isVoiceEnabled = false,
            )
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        if (_uiState.value.cookingSession == null) return
        _uiState.update {
            it.copy(
                isVoiceEnabled = enabled,
                voiceErrorMessage = if (enabled) null else it.voiceErrorMessage,
            )
        }
    }

    fun submitVoiceTranscript(transcript: String) {
        val normalized = transcript.trim()
        val snapshot = _uiState.value
        if (!snapshot.isVoiceEnabled || snapshot.isTurnPending || normalized.isBlank()) return

        submitVoiceTurn(
            FailedVoiceTurn(
                clientTurnId = UUID.randomUUID().toString(),
                transcript = normalized,
            ),
        )
    }

    fun retryFailedVoiceTurn() {
        val snapshot = _uiState.value
        val failedTurn = snapshot.failedVoiceTurn ?: return
        if (snapshot.isTurnPending) return
        submitVoiceTurn(failedTurn)
    }

    private fun submitVoiceTurn(command: FailedVoiceTurn) {
        val session = _uiState.value.cookingSession ?: return
        val submittedNavigationRevision = navigationRevision
        _uiState.update {
            it.copy(
                isTurnPending = true,
                failedVoiceTurn = null,
                lastTranscript = command.transcript,
                voiceErrorMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                commandMutex.withLock { syncFocus(session.id) }
                // Model inference must not block touch navigation or explicit timer commands.
                cookingSessionRepository.submitTurn(session.id, command.clientTurnId, command.transcript).also {
                    require(it.sessionId == session.id && it.clientTurnId == command.clientTurnId) {
                        "응답의 조리 세션이 일치하지 않아요."
                    }
                }
            }.onSuccess { turn ->
                if (_uiState.value.cookingSession?.id != session.id) return@onSuccess

                val isAddressed = turn.addressing == Addressing.DIRECT || turn.addressing == Addressing.UNCERTAIN
                val canChangeState = turn.addressing == Addressing.DIRECT

                val timersById = session.timerCues
                val newTimers = turn.events.takeIf { canChangeState }.orEmpty()
                    .asSequence()
                    .filter { event -> event.type == TIMER_START_REQUESTED }
                    .mapNotNull { event -> timersById[event.targetId] }
                    .filter { cue -> cue.durationSeconds > 0 }
                    .filter { cue -> _uiState.value.timerDeadlines.none { it.timerCueId == cue.id } }
                    .map { cue ->
                        PendingDeviceTimer(
                            cueId = cue.id,
                            durationSeconds = cue.durationSeconds,
                            approximate = cue.approximate,
                            clientAckId = UUID.randomUUID().toString(),
                        )
                    }
                    .toList()
                val reply = turn.replyText.takeIf {
                    turn.addressing == Addressing.DIRECT || turn.addressing == Addressing.UNCERTAIN
                }?.takeIf(String::isNotBlank)

                _uiState.update { current ->
                    current.copy(
                        isTurnPending = false,
                        failedVoiceTurn = null,
                        assistantReply = reply,
                        voicePhotos = if (isAddressed) turn.photos.filter { photo ->
                            current.recipe?.steps?.any { it.number == photo.sourceStepNumber && it.imageUrl == photo.imageUrl } == true
                        } else emptyList(),
                        progress = turn.state?.takeIf { canChangeState && it.version >= current.progress.version } ?: current.progress,
                        cookingSession = current.cookingSession?.copy(state =
                            turn.state?.takeIf { canChangeState && it.version >= current.progress.version } ?: current.progress),
                        currentStepIndex = if (canChangeState && submittedNavigationRevision == navigationRevision)
                            current.recipe?.steps?.indexOfFirst { it.number == turn.state?.focusedSourceStepNumber }
                                ?.takeIf { it >= 0 } ?: current.currentStepIndex else current.currentStepIndex,
                        pendingDeviceTimers = (current.pendingDeviceTimers + newTimers)
                            .distinctBy(PendingDeviceTimer::cueId),
                    )
                }

                if (reply != null && _uiState.value.isVoiceEnabled) {
                    _voiceEffects.emit(VoiceEffect.Speak(reply))
                } else if (_uiState.value.isVoiceEnabled) {
                    _voiceEffects.emit(VoiceEffect.ResumeListening)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.cookingSession?.id != session.id) return@onFailure
                _uiState.update {
                    it.copy(
                        isTurnPending = false,
                        failedVoiceTurn = command,
                        voiceErrorMessage = error.toCookingMessage("말씀을 처리하지 못했어요."),
                    )
                }
                if (_uiState.value.isVoiceEnabled) _voiceEffects.emit(VoiceEffect.ResumeListening)
            }
        }
    }

    fun onDeviceTimerScheduled(timerCueId: String, deviceDeadline: CookingTimerDeadline? = null,
        savedClientAckId: String? = null) {
        val snapshot = _uiState.value
        val sessionId = snapshot.cookingSession?.id ?: return
        val pending = snapshot.pendingDeviceTimers.firstOrNull { it.cueId == timerCueId } ?: return
        if (pending.stage != TimerScheduleStage.NEEDS_DEVICE_SCHEDULE) return

        _uiState.update { current ->
            current.copy(
                pendingDeviceTimers = current.pendingDeviceTimers.map { timer ->
                    if (timer.cueId == timerCueId) {
                        timer.copy(stage = TimerScheduleStage.ACKNOWLEDGING, deviceDeadline = deviceDeadline,
                            clientAckId = savedClientAckId ?: timer.clientAckId)
                    } else {
                        timer
                    }
                },
                timerDeadlines = if (deviceDeadline != null)
                    (current.timerDeadlines.filterNot { it.timerCueId == timerCueId } + deviceDeadline) else current.timerDeadlines,
            )
        }
        acknowledgeScheduledTimer(sessionId, pending.copy(deviceDeadline = deviceDeadline,
            clientAckId = savedClientAckId ?: pending.clientAckId))
    }

    fun reconcileDeviceTimerDeadlines(deadlines: List<CookingTimerDeadline>) {
        _uiState.update { current ->
            val merged = current.timerDeadlines.map { original -> deadlines.firstOrNull { it.timerCueId == original.timerCueId } ?: original }
            if (merged == current.timerDeadlines) current else current.copy(timerDeadlines = merged)
        }
    }

    fun retryTimerAcknowledgement(timerCueId: String) {
        val sessionId = _uiState.value.cookingSession?.id ?: return
        val pending = _uiState.value.pendingDeviceTimers.firstOrNull { it.cueId == timerCueId }
            ?: return
        if (pending.stage != TimerScheduleStage.ACK_FAILED) return

        _uiState.update { current ->
            current.copy(
                pendingDeviceTimers = current.pendingDeviceTimers.map { timer ->
                    if (timer.cueId == timerCueId) {
                        timer.copy(stage = TimerScheduleStage.ACKNOWLEDGING)
                    } else {
                        timer
                    }
                },
            )
        }
        acknowledgeScheduledTimer(sessionId, pending)
    }

    fun reportVoiceError(message: String) {
        _uiState.update { it.copy(voiceErrorMessage = message) }
    }

    fun dismissVoiceError() {
        _uiState.update { it.copy(voiceErrorMessage = null) }
    }

    fun showPreviousStep() {
        showStep((_uiState.value.currentStepIndex - 1).coerceAtLeast(0))
    }

    fun showNextStep() {
        val lastIndex = (_uiState.value.recipe?.steps?.lastIndex ?: 0).coerceAtLeast(0)
        showStep((_uiState.value.currentStepIndex + 1).coerceAtMost(lastIndex))
    }

    fun showStep(index: Int) {
        val current = _uiState.value
        val step = current.recipe?.steps?.getOrNull(index) ?: return
        val sessionId = current.cookingSession?.id
        if (sessionId == null) {
            _uiState.update { it.copy(currentStepIndex = index) }
            return
        }
        if (index == current.currentStepIndex && pendingNavigation == null) return
        navigationRevision += 1
        pendingNavigation = UUID.randomUUID().toString() to step.number
        _uiState.update { it.copy(currentStepIndex = index, isStepSyncing = true, stepSyncError = null) }
        viewModelScope.launch {
            runCatching { commandMutex.withLock { syncFocus(sessionId) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (_uiState.value.cookingSession?.id == sessionId) _uiState.update {
                        it.copy(isStepSyncing = false, stepSyncError = error.toCookingMessage("단계 위치를 동기화하지 못했어요."))
                    }
                }
        }
    }

    private suspend fun syncFocus(sessionId: String) {
        while (_uiState.value.cookingSession?.id == sessionId) {
            val command = pendingNavigation ?: return
            val response = cookingSessionRepository.navigate(sessionId, command.first, command.second)
            require(response.sessionId == sessionId && response.sourceStepNumber == command.second)
            if (_uiState.value.cookingSession?.id != sessionId) return
            if (pendingNavigation == command) pendingNavigation = null
            _uiState.update { it.copy(progress = response.state.takeIf { state -> state.version >= it.progress.version } ?: it.progress,
                cookingSession = it.cookingSession?.copy(state = response.state.takeIf { state -> state.version >= it.progress.version } ?: it.progress),
                isStepSyncing = pendingNavigation != null, stepSyncError = null) }
        }
    }

    fun updateCurrentStep(status: StepProgressStatus) {
        val snapshot = _uiState.value
        val session = snapshot.cookingSession ?: return
        val step = snapshot.recipe?.steps?.getOrNull(snapshot.currentStepIndex) ?: return
        val commandId = UUID.randomUUID().toString()
        viewModelScope.launch {
            runCatching { commandMutex.withLock {
                syncFocus(session.id)
                cookingSessionRepository.updateStepProgress(session.id, commandId, step.number, status).also {
                    require(it.sessionId == session.id && it.sourceStepNumber == step.number)
                }
            } }.onSuccess { response ->
                if (_uiState.value.cookingSession?.id == session.id) _uiState.update {
                    val progress = response.state.takeIf { state -> state.version >= it.progress.version } ?: it.progress
                    it.copy(progress = progress, cookingSession = it.cookingSession?.copy(state = progress))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.cookingSession?.id == session.id) reportVoiceError(error.toCookingMessage("완료 표시를 저장하지 못했어요."))
            }
        }
    }

    fun submitTextQuestion(text: String) {
        val normalized = text.trim().take(3000)
        val snapshot = _uiState.value
        if (normalized.isBlank() || snapshot.isTurnPending || snapshot.cookingSession == null) return
        submitVoiceTurn(FailedVoiceTurn(UUID.randomUUID().toString(), normalized))
    }

    fun startSourceTimer(timerCueId: String) {
        val snapshot = _uiState.value
        val session = snapshot.cookingSession ?: return
        val cue = session.timerCues[timerCueId]?.takeIf { it.durationSeconds > 0 } ?: return
        if (snapshot.pendingDeviceTimers.any { it.cueId == timerCueId } || snapshot.timerDeadlines.any { it.timerCueId == timerCueId }) return
        val commandId = UUID.randomUUID().toString()
        viewModelScope.launch {
            runCatching { commandMutex.withLock {
                val current = _uiState.value
                if (current.cookingSession?.id != session.id ||
                    current.pendingDeviceTimers.any { it.cueId == timerCueId } ||
                    current.timerDeadlines.any { it.timerCueId == timerCueId }) return@withLock null
                val progress = cookingSessionRepository.requestTimerStart(session.id, commandId, timerCueId)
                if (_uiState.value.cookingSession?.id == session.id) _uiState.update {
                    val newest = progress.takeIf { state -> state.version >= it.progress.version } ?: it.progress
                    it.copy(progress = newest, cookingSession = it.cookingSession?.copy(state = newest),
                        pendingDeviceTimers = (it.pendingDeviceTimers + PendingDeviceTimer(cue.id, cue.durationSeconds,
                            cue.approximate, UUID.randomUUID().toString())).distinctBy(PendingDeviceTimer::cueId))
                }
            } }.onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.cookingSession?.id == session.id) reportVoiceError(error.toCookingMessage("타이머를 시작하지 못했어요."))
            }
        }
    }

    private fun recoverPendingTimers(session: CookingSession) = session.pendingTimerCueIds.mapNotNull { id ->
        session.timerCues[id]?.takeIf { it.durationSeconds > 0 }?.let {
            PendingDeviceTimer(it.id, it.durationSeconds, it.approximate, UUID.randomUUID().toString())
        }
    }

    fun askAboutRecipe(text: String) {
        val question = text.trim().take(3000)
        if (question.isBlank()) return
        val snapshot = _uiState.value
        if (snapshot.cookingSession != null) { submitTextQuestion(question); return }
        val recipe = snapshot.recipe ?: return
        if (snapshot.isStartingCooking || snapshot.isTurnPending) return
        _uiState.update { it.copy(isStartingCooking = true) }
        viewModelScope.launch {
            runCatching { cookingSessionRepository.start(recipe.id, snapshot.targetServings) }
                .onSuccess { session ->
                    if (_uiState.value.recipe?.id != recipe.id || _uiState.value.targetServings != snapshot.targetServings) return@onSuccess
                    _uiState.update { it.copy(isStartingCooking = false, cookingSession = session,
                        progress = session.state, timerDeadlines = session.timerDeadlines) }
                    submitTextQuestion(question)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    if (_uiState.value.recipe?.id == recipe.id) _uiState.update { it.copy(isStartingCooking = false,
                        voiceErrorMessage = error.toCookingMessage("레시피 질문을 준비하지 못했어요.")) }
                }
        }
    }

    /** With a [message], only that message is dismissed; a newer error that replaced it stays. */
    fun dismissError(message: String? = null) {
        _uiState.update {
            if (message == null || it.errorMessage == message) it.copy(errorMessage = null, errorNeedsSignIn = false) else it
        }
    }

    fun clearRecipe() {
        scalingJob?.cancel()
        cancelVideoIntake()
        // Also stops an upload or analysis poll, which would otherwise keep running for minutes.
        loadJob?.cancel()
        pendingNavigation = null
        navigationRevision += 1
        _uiState.update {
            RecipeImportUiState(
                isAuthInitializing = it.isAuthInitializing,
                isAuthenticated = it.isAuthenticated,
                isAuthBusy = it.isAuthBusy,
                authErrorMessage = it.authErrorMessage,
                executionMode = it.executionMode,
                importProgress = it.importProgress,
            )
        }
        viewModelScope.launch {
            repository.forgetRecipeUrl()
        }
    }

    private fun acknowledgeScheduledTimer(sessionId: String, pendingTimer: PendingDeviceTimer) {
        viewModelScope.launch {
            runCatching {
                cookingSessionRepository.acknowledgeTimerStart(
                    sessionId = sessionId,
                    clientAckId = pendingTimer.clientAckId,
                    timerCueId = pendingTimer.cueId,
                )
            }.onSuccess { acknowledgement ->
                if (_uiState.value.cookingSession?.id != sessionId) return@onSuccess
                _uiState.update { current ->
                    current.copy(
                        pendingDeviceTimers = current.pendingDeviceTimers
                            .filterNot { pending -> pending.cueId == pendingTimer.cueId },
                        timerDeadlines = (pendingTimer.deviceDeadline ?: acknowledgement.timerDeadline)?.let { deadline ->
                            (current.timerDeadlines + deadline)
                                .distinctBy(CookingTimerDeadline::timerCueId)
                        } ?: current.timerDeadlines,
                        voiceErrorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.cookingSession?.id != sessionId) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        pendingDeviceTimers = current.pendingDeviceTimers.map { timer ->
                            if (timer.cueId == pendingTimer.cueId) {
                                timer.copy(stage = TimerScheduleStage.ACK_FAILED)
                            } else {
                                timer
                            }
                        },
                        voiceErrorMessage = error.toCookingMessage(
                            "타이머는 기기에 설정됐지만 서버 확인에 실패했어요.",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadRecipe(url: String) {
        scalingJob?.cancel()
        pendingNavigation = null
        navigationRevision += 1
        _uiState.update {
            it.copy(
                pendingUrl = url,
                recipe = null,
                isLoading = true,
                errorMessage = null,
                errorNeedsSignIn = false,
                loginRequiredForVideo = false,
                consentRequiredForVideo = false,
                isCooking = false,
                currentStepIndex = 0,
                targetServings = 1,
                scaledIngredients = null,
                isScalingIngredients = false,
                isStartingCooking = false,
                cookingSession = null,
                isVoiceEnabled = false,
                isTurnPending = false,
                lastTranscript = null,
                assistantReply = null,
                voicePhotos = emptyList(),
                voiceErrorMessage = null,
                pendingDeviceTimers = emptyList(),
                timerDeadlines = emptyList(),
                progress = CookingProgress(),
                isStepSyncing = false,
                stepSyncError = null,
            )
        }
        if (url == handedOffVideoUrl) finishVideoIntake()
        // Nothing of a phone video leaves the device before the user agrees that Google's Gemini analyses it.
        if (RecipeUrlParser.localVideoId(url) != null && needsServer(url, _uiState.value.executionMode) && !hasVideoConsent()) {
            _uiState.update { it.copy(isLoading = isPreparingVideo(), consentRequiredForVideo = true) }
            return
        }
        // A new attempt: if the process dies during it, the next start resumes it instead of showing an older failure.
        rememberImportFailure(url, null)

        runCatching { repository.importRecipe(url) }
            .onSuccess { recipe ->
                if (_uiState.value.pendingUrl == url) {
                    val baseServings = recipe.baseServingCount()
                        ?.roundToInt()
                        ?.coerceAtLeast(1)
                        ?: 1
                    // The error was cleared when this load began; one set since (a link rejected meanwhile) is kept.
                    _uiState.update {
                        it.copy(
                            recipe = recipe,
                            isLoading = isPreparingVideo(),
                            targetServings = baseServings,
                        )
                    }
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.pendingUrl == url) {
                    // A video import that ran to a failure is shown, not re-run, when the app starts again; a timeout
                    // or a lost connection may still finish, so those resume.
                    if (error is VideoImportException && error.code != VideoImportException.TIMEOUT) {
                        rememberImportFailure(url, error.code)
                    }
                    _uiState.update {
                        it.copy(isLoading = isPreparingVideo(), errorMessage = error.toUserMessage(),
                            errorNeedsSignIn = error.needsSignIn())
                    }
                }
            }
    }

    /** All recipe loads share one job, so a cancel, logout or newer load also stops a long video upload. */
    private fun startLoad(url: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadRecipe(url) }
    }

    /** Cloud mode always works through the server; local mode only for a video it has not analysed yet. */
    private suspend fun needsServer(url: String, mode: ExecutionMode): Boolean =
        mode == ExecutionMode.CLOUD || try {
            repository.needsServer(url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            true
        }

    private suspend fun canLoadNow(url: String): Boolean =
        _uiState.value.isAuthenticated || !needsServer(url, _uiState.value.executionMode)

    private suspend fun hasVideoConsent(): Boolean = try {
        videoUploadConsent.isGiven()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        false
    }

    private suspend fun importFailureCode(url: String): String? = try {
        repository.importFailureCode(url)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    // Losing this note only means one more automatic attempt, so a storage error does not fail the import.
    private suspend fun rememberImportFailure(url: String, code: String?) {
        try {
            repository.rememberImportFailure(url, code)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /** The pending link's last import failed for good: show why, with a retry, instead of starting it again. */
    private fun showImportFailure(url: String, code: String) {
        loadJob?.cancel()
        if (url == handedOffVideoUrl) finishVideoIntake()
        _uiState.update {
            it.copy(pendingUrl = url, recipe = null, isLoading = isPreparingVideo(), errorMessage = videoImportMessage(code),
                errorNeedsSignIn = false, loginRequiredForVideo = false, consentRequiredForVideo = false)
        }
    }

    private fun isPreparingVideo() = intakeProgress.value != null

    private fun finishVideoIntake() {
        handedOffVideoUrl = null
        intakeProgress.value = null
    }

    private fun cancelVideoIntake() {
        val wasPreparing = isPreparingVideo()
        intakeJob?.cancel()
        intakeJob = null
        finishVideoIntake()
        if (wasPreparing) _uiState.update { it.copy(isLoading = loadJob?.isActive == true) }
    }

    private fun setTargetServings(servings: Int) {
        val recipe = _uiState.value.recipe ?: return
        if (_uiState.value.isStartingCooking) return
        if (_uiState.value.targetServings == servings) return
        if (_uiState.value.cookingSession != null) {
            _uiState.update { it.copy(errorMessage = "진행 중인 요리의 분량 변경은 한술에게 물어보세요. 이미 넣은 재료부터 확인할게요.") }
            return
        }

        val baseServings = recipe.baseServingCount()?.roundToInt()?.coerceAtLeast(1)
        _uiState.update {
            it.copy(
                targetServings = servings,
                scaledIngredients = null,
                isScalingIngredients = servings != baseServings,
                errorMessage = null,
            )
        }

        scalingJob?.cancel()
        if (servings == baseServings) return
        if (recipe.id.isBlank()) {
            _uiState.update {
                it.copy(
                    isScalingIngredients = false,
                    errorMessage = "인분 계산에 필요한 레시피 ID가 없어요.",
                )
            }
            return
        }

        scalingJob = viewModelScope.launch {
            delay(250)
            runCatching { repository.getScaledIngredients(recipe.id, servings) }
                .onSuccess { scaled ->
                    val current = _uiState.value
                    if (current.recipe?.id == recipe.id && current.targetServings == servings) {
                        _uiState.update {
                            it.copy(
                                scaledIngredients = scaled,
                                isScalingIngredients = false,
                                errorMessage = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val current = _uiState.value
                    if (current.recipe?.id == recipe.id && current.targetServings == servings) {
                        _uiState.update {
                            it.copy(
                                isScalingIngredients = false,
                                errorMessage = "인분에 맞춘 재료 양을 계산하지 못했어요.",
                            )
                        }
                    }
                }
        }
    }

    companion object {
        fun factory(
            repository: RecipeRepository,
            authRepository: AuthRepository,
            cookingSessionRepository: CookingSessionRepository,
            executionMode: StateFlow<ExecutionMode> = MutableStateFlow(ExecutionMode.CLOUD),
            changeExecutionMode: suspend (ExecutionMode) -> Unit = {},
            videoIntake: VideoIntake = NoVideoIntake,
            videoUploadConsent: VideoUploadConsent = InMemoryVideoUploadConsent(),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(RecipeImportViewModel::class.java))
                    return RecipeImportViewModel(
                        repository,
                        authRepository,
                        cookingSessionRepository,
                        executionMode,
                        changeExecutionMode,
                        videoIntake,
                        videoUploadConsent,
                    ) as T
                }
            }
    }
}

private data class AccessState(val url: String?, val initializing: Boolean, val authorized: Boolean, val mode: ExecutionMode,
    val loginRequiredForVideo: Boolean)

private const val TIMER_START_REQUESTED = "TIMER_START_REQUESTED"

private val NoVideoIntake = VideoIntake {
    throw VideoImportException(VideoImportException.LOCAL_VIDEO_UNREADABLE, "No video intake is configured")
}

private fun Throwable.toUserMessage(): String = when (this) {
    is VideoLoginRequiredException -> VIDEO_LOGIN_MESSAGE
    is VideoImportException -> videoImportMessage(code)
    is IOException -> "서버에 연결하지 못했어요. 네트워크와 서버 주소를 확인해주세요."
    is HttpException -> when (code()) {
        400 -> "레시피 링크를 읽을 수 없어요. 원문 링크를 다시 공유해주세요."
        401 -> "로그인 세션이 만료됐어요. 다시 로그인해주세요."
        404 -> "레시피를 찾지 못했어요."
        // Only a video chunk can exceed the proxy's request limit.
        413 -> videoImportMessage(VideoImportException.VIDEO_UPLOAD_TOO_LARGE)
        in 500..599 -> "서버에서 레시피를 준비하지 못했어요. 잠시 후 다시 시도해주세요."
        else -> "레시피를 가져오지 못했어요. (HTTP ${code()})"
    }
    else -> "레시피를 가져오는 중 문제가 생겼어요."
}

/** Copying never touches the network, so anything but a coded failure means the file could not be read. */
private fun Throwable.toIntakeMessage(): String =
    videoImportMessage((this as? VideoImportException)?.code ?: VideoImportException.LOCAL_VIDEO_UNREADABLE)

private fun Throwable.needsSignIn(): Boolean =
    this is VideoLoginRequiredException || this is HttpException && code() == 401

private fun Throwable.toCookingMessage(fallback: String): String = when (this) {
    is com.hansul.app.ai.LocalAiUnavailableException -> message ?: "설정에서 기기 내 AI 모델을 준비해 주세요. 수동 조리는 계속할 수 있어요."
    is IOException -> "서버에 연결하지 못했어요. 네트워크를 확인해주세요."
    is HttpException -> when (code()) {
        401 -> "로그인 세션이 만료됐어요. 다시 로그인해주세요."
        404 -> "요리 세션을 찾지 못했어요. 레시피에서 다시 시작해주세요."
        409 -> "요리 상태가 달라 요청을 적용하지 못했어요."
        in 500..599 -> "$fallback 잠시 후 다시 시도해주세요."
        else -> "$fallback (HTTP ${code()})"
    }
    else -> fallback
}
