package com.hansul.app.ui

import android.content.Intent
import com.hansul.app.auth.AuthRepository
import com.hansul.app.auth.AuthSessionState
import com.hansul.app.data.CookingSessionRepository
import com.hansul.app.data.ExecutionMode
import com.hansul.app.data.RecipeRepository
import com.hansul.app.data.video.InMemoryVideoUploadConsent
import com.hansul.app.data.video.VideoImportException
import com.hansul.app.data.video.VideoIntake
import com.hansul.app.data.video.VideoLoginRequiredException
import com.hansul.app.model.Addressing
import com.hansul.app.model.CookingEvent
import com.hansul.app.model.CookingNavigation
import com.hansul.app.model.CookingPhotoCue
import com.hansul.app.model.CookingProgress
import com.hansul.app.model.CookingSession
import com.hansul.app.model.CookingTimerCue
import com.hansul.app.model.CookingTimerDeadline
import com.hansul.app.model.CookingTurn
import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.ScaledIngredient
import com.hansul.app.model.ScaledIngredients
import com.hansul.app.model.StepProgressStatus
import com.hansul.app.model.TimerAcknowledgement
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import com.hansul.app.share.RecipeUrlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeImportViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `shared url is persisted before recipe is shown`() = runTest {
        val repository = FakeRecipeRepository()
        val viewModel = RecipeImportViewModel(
            repository,
            FakeAuthRepository(),
            FakeCookingSessionRepository(),
        )

        viewModel.acceptSharedText("https://m.10000recipe.com/recipe/777")

        assertEquals("https://www.10000recipe.com/recipe/777", repository.rememberedUrl)
        assertEquals("동적 제목", viewModel.uiState.value.recipe?.title)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `shared url waits for authentication then resumes import`() = runTest {
        val repository = FakeRecipeRepository()
        val authRepository = FakeAuthRepository(initiallyAuthorized = false)
        val viewModel = RecipeImportViewModel(
            repository,
            authRepository,
            FakeCookingSessionRepository(),
        )

        viewModel.acceptSharedText("https://m.10000recipe.com/recipe/888")

        assertEquals("https://www.10000recipe.com/recipe/888", repository.rememberedUrl)
        assertEquals(0, repository.importCount)
        assertNull(viewModel.uiState.value.recipe)

        authRepository.authorize()

        assertEquals(1, repository.importCount)
        assertEquals("동적 제목", viewModel.uiState.value.recipe?.title)
    }

    @Test
    fun `voice turn retry reuses the same id and transcript`() = runTest {
        val repository = FakeRecipeRepository()
        val cooking = FakeCookingSessionRepository().apply { failNextTurn = true }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://m.10000recipe.com/recipe/999")
        viewModel.startCooking()
        viewModel.setVoiceEnabled(true)

        viewModel.submitVoiceTranscript("물 올렸어")
        viewModel.retryFailedVoiceTurn()

        assertEquals(2, cooking.turnRequests.size)
        assertEquals(cooking.turnRequests[0], cooking.turnRequests[1])
        assertNull(viewModel.uiState.value.failedVoiceTurn)
    }

    @Test
    fun `timer acknowledgement retry keeps its idempotency id`() = runTest {
        val repository = FakeRecipeRepository()
        val cooking = FakeCookingSessionRepository().apply {
            returnTimerRequest = true
            failNextTimerAck = true
        }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://m.10000recipe.com/recipe/1000")
        viewModel.startCooking()
        viewModel.setVoiceEnabled(true)
        viewModel.submitVoiceTranscript("응 타이머 맞춰줘")

        val timer = viewModel.uiState.value.pendingDeviceTimers.single()
        viewModel.onDeviceTimerScheduled(timer.cueId)
        viewModel.retryTimerAcknowledgement(timer.cueId)

        assertEquals(2, cooking.timerAckIds.size)
        assertEquals(cooking.timerAckIds[0], cooking.timerAckIds[1])
        assertTrue(viewModel.uiState.value.pendingDeviceTimers.isEmpty())
    }

    @Test
    fun `manual navigation sends source number without completing skipped over work`() = runTest {
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1101")
        viewModel.startCooking()

        viewModel.showStep(2)
        viewModel.submitTextQuestion("지금 넣어?")

        assertEquals(7, cooking.navigationRequests.single().sourceStepNumber)
        assertEquals(7, cooking.turnFocuses.single())
        assertEquals(2, viewModel.uiState.value.currentStepIndex)
        assertEquals(7, viewModel.uiState.value.progress.focusedSourceStepNumber)
        assertTrue(viewModel.uiState.value.progress.completedSourceStepNumbers.isEmpty())
        assertTrue(viewModel.uiState.value.progress.skippedSourceStepNumbers.isEmpty())
        assertFalse(viewModel.uiState.value.isStepSyncing)
    }

    @Test
    fun `completion skipping and undo only affect the selected source step`() = runTest {
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1102")
        viewModel.startCooking()
        viewModel.showStep(1)

        viewModel.updateCurrentStep(StepProgressStatus.COMPLETED)
        assertEquals(setOf(3), viewModel.uiState.value.progress.completedSourceStepNumbers)

        viewModel.updateCurrentStep(StepProgressStatus.SKIPPED)
        assertTrue(viewModel.uiState.value.progress.completedSourceStepNumbers.isEmpty())
        assertEquals(setOf(3), viewModel.uiState.value.progress.skippedSourceStepNumbers)

        viewModel.updateCurrentStep(StepProgressStatus.PENDING)
        assertTrue(viewModel.uiState.value.progress.completedSourceStepNumbers.isEmpty())
        assertTrue(viewModel.uiState.value.progress.skippedSourceStepNumbers.isEmpty())
        assertEquals(listOf(3, 3, 3), cooking.progressRequests.map { it.sourceStepNumber })
    }

    @Test
    fun `failed focus sync is retried with its id before a text question`() = runTest {
        val cooking = FakeCookingSessionRepository().apply { failNextNavigation = true }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1103")
        viewModel.startCooking()

        viewModel.showStep(2)
        assertEquals(2, viewModel.uiState.value.currentStepIndex)
        assertTrue(viewModel.uiState.value.stepSyncError != null)
        viewModel.submitTextQuestion("이 단계에서 필요한 재료가 뭐야?")

        assertEquals(2, cooking.navigationRequests.size)
        assertEquals(cooking.navigationRequests[0], cooking.navigationRequests[1])
        assertEquals(listOf("navigate", "navigate", "turn"), cooking.commandOrder)
        assertEquals(7, cooking.turnFocuses.single())
        assertNull(viewModel.uiState.value.stepSyncError)
        assertFalse(viewModel.uiState.value.isStepSyncing)
    }

    @Test
    fun `text questions work with microphone disabled and do not emit speech effects`() = runTest {
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        val effects = mutableListOf<VoiceEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.voiceEffects.collect { effects += it }
        }
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1104")
        viewModel.startCooking()

        viewModel.submitTextQuestion("  국간장 대신 뭘 써?  ")

        assertFalse(viewModel.uiState.value.isVoiceEnabled)
        assertEquals("국간장 대신 뭘 써?", cooking.turnRequests.single().second)
        assertEquals("알겠어요.", viewModel.uiState.value.assistantReply)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `failed text turn retries with the same id while microphone remains disabled`() = runTest {
        val cooking = FakeCookingSessionRepository().apply { failNextTurn = true }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1105")
        viewModel.startCooking()

        viewModel.submitTextQuestion("양파가 없어")
        viewModel.retryFailedVoiceTurn()

        assertFalse(viewModel.uiState.value.isVoiceEnabled)
        assertEquals(2, cooking.turnRequests.size)
        assertEquals(cooking.turnRequests[0], cooking.turnRequests[1])
        assertNull(viewModel.uiState.value.failedVoiceTurn)
    }

    @Test
    fun `blank and overlapping text questions do not create extra requests`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply { nextTurnGate = release }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1106")
        viewModel.startCooking()

        viewModel.submitTextQuestion("  \n  ")
        assertTrue(cooking.turnRequests.isEmpty())
        viewModel.submitTextQuestion("얼마나 끓여?")
        viewModel.submitTextQuestion("얼마나 끓여?")
        assertEquals(1, cooking.turnRequests.size)

        release.complete(Unit)
        runCurrent()
        assertFalse(viewModel.uiState.value.isTurnPending)
    }

    @Test
    fun `unrelated or unknown turns cannot apply photos progress or timer events`() = runTest {
        for (addressing in listOf(Addressing.UNRELATED, Addressing.UNKNOWN)) {
            val cooking = FakeCookingSessionRepository().apply {
                transformTurn = { turn -> turn.copy(
                    addressing = addressing,
                    photos = listOf(CookingPhotoCue(3, "https://example.com/step-3.jpg")),
                    events = listOf(CookingEvent("TIMER_START_REQUESTED", "timer-cue")),
                    state = CookingProgress(version = 99, focusedSourceStepNumber = 7,
                        completedSourceStepNumbers = setOf(1, 3)),
                ) }
            }
            val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
            viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1107")
            viewModel.startCooking()
            viewModel.setVoiceEnabled(true)

            viewModel.submitVoiceTranscript("옆 사람에게 하는 말")

            assertNull(viewModel.uiState.value.assistantReply)
            assertTrue(viewModel.uiState.value.voicePhotos.isEmpty())
            assertTrue(viewModel.uiState.value.pendingDeviceTimers.isEmpty())
            assertTrue(viewModel.uiState.value.progress.completedSourceStepNumbers.isEmpty())
            assertEquals(0, viewModel.uiState.value.currentStepIndex)
        }
    }

    @Test
    fun `only exact original step photo references are shown`() = runTest {
        val cooking = FakeCookingSessionRepository().apply {
            transformTurn = { turn -> turn.copy(photos = listOf(
                CookingPhotoCue(3, "https://example.com/step-3.jpg"),
                CookingPhotoCue(3, "https://example.com/unrelated.jpg"),
                CookingPhotoCue(99, "https://example.com/step-3.jpg"),
            )) }
        }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1108")
        viewModel.startCooking()
        viewModel.submitTextQuestion("양파 어떻게 잘라?")

        assertEquals(listOf(CookingPhotoCue(3, "https://example.com/step-3.jpg")),
            viewModel.uiState.value.voicePhotos)
        assertEquals(0, viewModel.uiState.value.currentStepIndex)
    }

    @Test
    fun `local recipe import and cooking do not require login`() = runTest {
        val repository = FakeRecipeRepository()
        val viewModel = RecipeImportViewModel(repository,
            FakeAuthRepository(initiallyAuthorized = false), FakeCookingSessionRepository(),
            executionMode = MutableStateFlow(ExecutionMode.LOCAL))

        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1109")
        viewModel.startCooking()

        assertEquals(1, repository.importCount)
        assertFalse(viewModel.uiState.value.isAuthenticated)
        assertEquals(ExecutionMode.LOCAL, viewModel.uiState.value.executionMode)
        assertTrue(viewModel.uiState.value.isCooking)
        assertTrue(viewModel.uiState.value.cookingSession != null)
    }

    @Test
    fun `local authentication changes preserve the active recipe progress and timers`() = runTest {
        val repository = FakeRecipeRepository()
        val auth = FakeAuthRepository(initiallyAuthorized = false)
        val cooking = FakeCookingSessionRepository().apply { returnTimerRequest = true }
        val viewModel = RecipeImportViewModel(repository, auth, cooking,
            executionMode = MutableStateFlow(ExecutionMode.LOCAL))
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1110")
        viewModel.startCooking()
        viewModel.showStep(2)
        viewModel.updateCurrentStep(StepProgressStatus.COMPLETED)
        viewModel.submitTextQuestion("타이머 맞춰줘")
        val originalSessionId = viewModel.uiState.value.cookingSession?.id

        auth.authorize()
        auth.logout()

        assertEquals(1, repository.importCount)
        assertTrue(viewModel.uiState.value.isCooking)
        assertEquals(originalSessionId, viewModel.uiState.value.cookingSession?.id)
        assertEquals(2, viewModel.uiState.value.currentStepIndex)
        assertEquals(setOf(7), viewModel.uiState.value.progress.completedSourceStepNumbers)
        assertEquals(1, viewModel.uiState.value.pendingDeviceTimers.size)
    }

    @Test
    fun `changing to local mode resumes a recipe waiting for login`() = runTest {
        val repository = FakeRecipeRepository()
        val mode = MutableStateFlow(ExecutionMode.CLOUD)
        val viewModel = RecipeImportViewModel(repository,
            FakeAuthRepository(initiallyAuthorized = false), FakeCookingSessionRepository(),
            executionMode = mode, changeExecutionMode = { mode.value = it })
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1111")
        assertEquals(0, repository.importCount)

        viewModel.selectExecutionMode(ExecutionMode.LOCAL)

        assertEquals(1, repository.importCount)
        assertEquals(ExecutionMode.LOCAL, viewModel.uiState.value.executionMode)
        assertTrue(viewModel.uiState.value.recipe != null)
    }

    @Test
    fun `mode cannot change while a cooking session is active`() = runTest {
        val mode = MutableStateFlow(ExecutionMode.CLOUD)
        var modeChanges = 0
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(),
            FakeCookingSessionRepository(), mode, changeExecutionMode = { modeChanges++; mode.value = it })
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1112")
        viewModel.startCooking()

        viewModel.selectExecutionMode(ExecutionMode.LOCAL)

        assertEquals(0, modeChanges)
        assertEquals(ExecutionMode.CLOUD, mode.value)
        assertTrue(viewModel.uiState.value.errorMessage != null)
    }

    @Test
    fun `preview and cooking return reuse the session and selected progress`() = runTest {
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1113")
        viewModel.startCooking()
        viewModel.showStep(1)
        viewModel.updateCurrentStep(StepProgressStatus.COMPLETED)
        val sessionId = viewModel.uiState.value.cookingSession?.id

        viewModel.showRecipePreview()
        viewModel.startCooking()

        assertEquals(1, cooking.startCount)
        assertEquals(sessionId, viewModel.uiState.value.cookingSession?.id)
        assertEquals(1, viewModel.uiState.value.currentStepIndex)
        assertEquals(setOf(3), viewModel.uiState.value.progress.completedSourceStepNumbers)
    }

    @Test
    fun `late failure from a replaced session does not poison the new session`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply {
            nextTurnGate = release
            failNextTurn = true
        }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1114")
        viewModel.startCooking()
        viewModel.submitTextQuestion("이전 요리 질문")

        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1115")
        viewModel.startCooking()
        val newSessionId = viewModel.uiState.value.cookingSession?.id
        release.complete(Unit)
        runCurrent()

        assertEquals(newSessionId, viewModel.uiState.value.cookingSession?.id)
        assertTrue(viewModel.uiState.value.isCooking)
        assertFalse(viewModel.uiState.value.isTurnPending)
        assertNull(viewModel.uiState.value.failedVoiceTurn)
        assertNull(viewModel.uiState.value.voiceErrorMessage)
        assertNull(viewModel.uiState.value.assistantReply)
    }

    @Test
    fun `a late reply cannot move the screen away from a newer manual selection`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply { nextTurnGate = release }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1116")
        viewModel.startCooking()
        viewModel.submitTextQuestion("첫 단계 질문")

        viewModel.showStep(2)
        assertEquals(2, viewModel.uiState.value.currentStepIndex)
        release.complete(Unit)
        runCurrent()

        assertEquals(2, viewModel.uiState.value.currentStepIndex)
        assertEquals(7, viewModel.uiState.value.progress.focusedSourceStepNumber)
    }

    @Test
    fun `mismatched response identifiers cannot apply another turn`() = runTest {
        for (mismatchSession in listOf(true, false)) {
            val cooking = FakeCookingSessionRepository().apply {
                transformTurn = { turn -> turn.copy(
                    sessionId = if (mismatchSession) "another-session" else turn.sessionId,
                    clientTurnId = if (!mismatchSession) "another-turn" else turn.clientTurnId,
                    photos = listOf(CookingPhotoCue(3, "https://example.com/step-3.jpg")),
                    events = listOf(CookingEvent("TIMER_START_REQUESTED", "timer-cue")),
                    state = CookingProgress(version = 99, focusedSourceStepNumber = 7),
                ) }
            }
            val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
            viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1117")
            viewModel.startCooking()

            viewModel.submitTextQuestion("현재 요리 질문")

            assertNull(viewModel.uiState.value.assistantReply)
            assertTrue(viewModel.uiState.value.voicePhotos.isEmpty())
            assertTrue(viewModel.uiState.value.pendingDeviceTimers.isEmpty())
            assertEquals(0, viewModel.uiState.value.currentStepIndex)
            assertFalse(viewModel.uiState.value.isTurnPending)
            assertTrue(viewModel.uiState.value.failedVoiceTurn != null)
        }
    }

    @Test
    fun `source yield with servings suffix selects the correct initial portion count`() = runTest {
        val repository = FakeRecipeRepository().apply { sourceYield = "2 servings" }
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), cooking)

        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1201")

        assertEquals("2 servings", viewModel.uiState.value.recipe?.baseServings)
        assertEquals(2, viewModel.uiState.value.targetServings)
        viewModel.startCooking()
        assertEquals(listOf(2), cooking.startedServings)
    }

    @Test
    fun `recipe question creates one silent session without entering cooking mode`() = runTest {
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        val effects = mutableListOf<VoiceEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.voiceEffects.collect { effects += it }
        }
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1202")

        viewModel.askAboutRecipe("  양파 대신 뭘 써?  ")
        viewModel.askAboutRecipe("매운맛을 줄일 수 있어?")

        assertEquals(1, cooking.startCount)
        assertEquals(listOf("양파 대신 뭘 써?", "매운맛을 줄일 수 있어?"),
            cooking.turnRequests.map { it.second })
        assertFalse(viewModel.uiState.value.isCooking)
        assertFalse(viewModel.uiState.value.isVoiceEnabled)
        assertFalse(viewModel.uiState.value.isStartingCooking)
        assertTrue(viewModel.uiState.value.cookingSession != null)
        assertEquals("알겠어요.", viewModel.uiState.value.assistantReply)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `session pending timer recovery only queues known positive duration cues`() = runTest {
        val cooking = FakeCookingSessionRepository().apply {
            initialPendingTimerCueIds = setOf("timer-cue", "missing-cue", "invalid-cue")
            extraTimerCues = mapOf("invalid-cue" to CookingTimerCue(
                id = "invalid-cue", durationSeconds = 0, sourceStepNumber = 3, approximate = false))
        }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1203")

        viewModel.startCooking()

        val pending = viewModel.uiState.value.pendingDeviceTimers.single()
        assertEquals("timer-cue", pending.cueId)
        assertEquals(300, pending.durationSeconds)
        assertEquals(TimerScheduleStage.NEEDS_DEVICE_SCHEDULE, pending.stage)
        assertTrue(pending.clientAckId.isNotBlank())
        assertTrue(cooking.timerAckIds.isEmpty())
    }

    @Test
    fun `actual device deadline survives ack failure and retry reuses the saved id`() = runTest {
        val actualDeadline = CookingTimerDeadline("timer-cue", "2026-10-13T01:00:00Z",
            "2026-10-13T01:05:00Z", null)
        val serverDeadline = CookingTimerDeadline("timer-cue", "2026-10-13T01:02:00Z",
            "2026-10-13T01:07:00Z", null)
        val cooking = FakeCookingSessionRepository().apply {
            returnTimerRequest = true
            failNextTimerAck = true
            timerAckDeadline = serverDeadline
        }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1204")
        viewModel.startCooking()
        viewModel.submitTextQuestion("타이머 맞춰줘")

        viewModel.onDeviceTimerScheduled("timer-cue", actualDeadline, "saved-device-ack")

        assertEquals(actualDeadline, viewModel.uiState.value.timerDeadlines.single())
        val failed = viewModel.uiState.value.pendingDeviceTimers.single()
        assertEquals(TimerScheduleStage.ACK_FAILED, failed.stage)
        assertEquals(actualDeadline, failed.deviceDeadline)
        assertEquals("saved-device-ack", failed.clientAckId)
        assertTrue(viewModel.uiState.value.voiceErrorMessage != null)

        viewModel.retryTimerAcknowledgement("timer-cue")

        assertEquals(listOf("saved-device-ack", "saved-device-ack"), cooking.timerAckIds)
        assertEquals(actualDeadline, viewModel.uiState.value.timerDeadlines.single())
        assertTrue(viewModel.uiState.value.pendingDeviceTimers.isEmpty())
        assertNull(viewModel.uiState.value.voiceErrorMessage)
    }

    @Test
    fun `duplicate turn timer requests never requeue a cue with an existing deadline`() = runTest {
        val runningDeadline = CookingTimerDeadline("timer-cue", "2026-10-13T01:00:00Z",
            "2026-10-13T01:05:00Z", null)
        val cooking = FakeCookingSessionRepository().apply {
            initialTimerDeadlines = listOf(runningDeadline)
            returnTimerRequest = true
        }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1205")
        viewModel.startCooking()

        viewModel.submitTextQuestion("타이머 맞춰줘")
        viewModel.submitTextQuestion("아까 타이머 맞췄지?")

        assertEquals(2, cooking.turnRequests.size)
        assertTrue(viewModel.uiState.value.pendingDeviceTimers.isEmpty())
        assertEquals(listOf(runningDeadline), viewModel.uiState.value.timerDeadlines)
        assertTrue(cooking.timerAckIds.isEmpty())
    }

    @Test
    fun `uncertain addressed reply is visible without changing progress or timers`() = runTest {
        val cooking = FakeCookingSessionRepository().apply {
            transformTurn = { turn -> turn.copy(
                addressing = Addressing.UNCERTAIN,
                replyText = "양파를 말씀하시는 건가요?",
                events = listOf(CookingEvent("TIMER_START_REQUESTED", "timer-cue")),
                state = CookingProgress(version = 99, focusedSourceStepNumber = 7,
                    completedSourceStepNumbers = setOf(1, 3), requestedTimerCueIds = setOf("timer-cue")),
            ) }
        }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1206")
        viewModel.startCooking()
        val originalProgress = viewModel.uiState.value.progress

        viewModel.submitTextQuestion("그거 넣었나?")

        assertEquals("양파를 말씀하시는 건가요?", viewModel.uiState.value.assistantReply)
        assertEquals(originalProgress, viewModel.uiState.value.progress)
        assertEquals(originalProgress, viewModel.uiState.value.cookingSession?.state)
        assertEquals(0, viewModel.uiState.value.currentStepIndex)
        assertTrue(viewModel.uiState.value.pendingDeviceTimers.isEmpty())
        assertTrue(viewModel.uiState.value.timerDeadlines.isEmpty())
    }

    @Test
    fun `source timer button creates one scheduling request even when tapped again`() = runTest {
        val cooking = FakeCookingSessionRepository()
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1207")
        viewModel.startCooking()

        viewModel.startSourceTimer("timer-cue")
        viewModel.startSourceTimer("timer-cue")

        assertEquals(1, cooking.timerStartRequests.size)
        assertEquals("timer-cue", cooking.timerStartRequests.single().cueId)
        assertTrue(cooking.timerStartRequests.single().id.isNotBlank())
        assertEquals(setOf("timer-cue"), viewModel.uiState.value.progress.requestedTimerCueIds)
        assertEquals("timer-cue", viewModel.uiState.value.pendingDeviceTimers.single().cueId)
        assertTrue(cooking.timerAckIds.isEmpty())
    }

    @Test
    fun `repeated timer taps during a pending request do not create a second command`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply { nextTimerStartGate = release }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1301")
        viewModel.startCooking()

        try {
            viewModel.startSourceTimer("timer-cue")
            viewModel.startSourceTimer("timer-cue")
            assertEquals(1, cooking.timerStartRequests.size)
        } finally {
            release.complete(Unit)
            runCurrent()
        }

        assertEquals(1, cooking.timerStartRequests.size)
        assertEquals(1, viewModel.uiState.value.pendingDeviceTimers.size)
        assertEquals(setOf("timer-cue"), viewModel.uiState.value.progress.requestedTimerCueIds)
        assertTrue(cooking.timerAckIds.isEmpty())
    }

    @Test
    fun `portion controls cannot change servings while a cooking session is starting`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply { nextStartGate = release }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1302")

        try {
            viewModel.startCooking()
            assertTrue(viewModel.uiState.value.isStartingCooking)
            viewModel.increaseServings()
            assertEquals(2, viewModel.uiState.value.targetServings)
            viewModel.decreaseServings()
            assertEquals(2, viewModel.uiState.value.targetServings)
        } finally {
            release.complete(Unit)
            runCurrent()
        }

        assertEquals(listOf(2), cooking.startedServings)
        assertFalse(viewModel.uiState.value.isStartingCooking)
        assertTrue(viewModel.uiState.value.isCooking)
        assertEquals(1, cooking.startCount)
    }

    @Test
    fun `portion changes cannot strand recipe question session preparation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply { nextStartGate = release }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1303")

        try {
            viewModel.askAboutRecipe("재료를 빼도 돼?")
            assertTrue(viewModel.uiState.value.isStartingCooking)
            viewModel.increaseServings()
            assertEquals(2, viewModel.uiState.value.targetServings)
            viewModel.decreaseServings()
            assertEquals(2, viewModel.uiState.value.targetServings)
        } finally {
            release.complete(Unit)
            runCurrent()
        }

        assertEquals(listOf(2), cooking.startedServings)
        assertFalse(viewModel.uiState.value.isStartingCooking)
        assertFalse(viewModel.uiState.value.isCooking)
        assertEquals("재료를 빼도 돼?", cooking.turnRequests.single().second)
        assertEquals("알겠어요.", viewModel.uiState.value.assistantReply)
    }

    @Test
    fun `model wait does not block manual navigation completion or source timers`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cooking = FakeCookingSessionRepository().apply { nextTurnGate = release }
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), cooking)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1304")
        viewModel.startCooking()
        viewModel.submitTextQuestion("첫 단계 질문")

        try {
            assertTrue(viewModel.uiState.value.isTurnPending)
            viewModel.showStep(2)
            viewModel.updateCurrentStep(StepProgressStatus.COMPLETED)
            viewModel.startSourceTimer("timer-cue")

            assertEquals(7, cooking.navigationRequests.single().sourceStepNumber)
            assertEquals(7, cooking.progressRequests.single().sourceStepNumber)
            assertEquals("timer-cue", cooking.timerStartRequests.single().cueId)
            assertEquals(setOf(7), viewModel.uiState.value.progress.completedSourceStepNumbers)
            assertEquals(setOf("timer-cue"), viewModel.uiState.value.progress.requestedTimerCueIds)
            assertTrue(viewModel.uiState.value.isTurnPending)
        } finally {
            release.complete(Unit)
            runCurrent()
        }

        assertEquals(2, viewModel.uiState.value.currentStepIndex)
        assertEquals(7, viewModel.uiState.value.progress.focusedSourceStepNumber)
        assertEquals(setOf(7), viewModel.uiState.value.progress.completedSourceStepNumbers)
        assertEquals(setOf("timer-cue"), viewModel.uiState.value.progress.requestedTimerCueIds)
        assertEquals(1, viewModel.uiState.value.pendingDeviceTimers.size)
        assertFalse(viewModel.uiState.value.isTurnPending)
    }

    @Test
    fun `shared YouTube link is imported as its canonical watch url`() = runTest {
        val repository = FakeRecipeRepository()
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())

        viewModel.acceptSharedText("이 영상 보세요 https://youtu.be/AbCdEfGhIjK?si=share-token")

        assertEquals(YOUTUBE_WATCH, repository.rememberedUrl)
        assertEquals(listOf(YOUTUBE_WATCH), repository.importedUrls)
        assertEquals("동적 제목", viewModel.uiState.value.recipe?.title)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a supported link wins over an unsupported platform in the same text`() = runTest {
        val repository = FakeRecipeRepository()
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())

        viewModel.acceptSharedText("https://www.instagram.com/reel/C0abc/ 원본 https://www.youtube.com/shorts/AbCdEfGhIjK")

        assertEquals(YOUTUBE_WATCH, repository.rememberedUrl)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `unsupported video platforms explain how to share the video file instead`() = runTest {
        val repository = FakeRecipeRepository()
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())
        val links = mapOf(
            "https://www.instagram.com/reel/C0abc/?igsh=x" to "인스타그램",
            "https://vt.tiktok.com/ZS123abc/" to "틱톡",
            "https://naver.me/5abcDEF" to "네이버TV",
            "https://tv.naver.com/v/52277225" to "네이버TV",
            "https://fb.watch/abc123/" to "페이스북",
        )

        for ((link, platform) in links) {
            viewModel.acceptSharedText("레시피 영상 $link")
            assertEquals("$platform 링크는 바로 분석할 수 없어요. 앱에서 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요.",
                viewModel.uiState.value.errorMessage)
        }
        viewModel.acceptSharedText("https://example.com/recipe/1 맛있어요")

        assertEquals("지원하는 만개의레시피 또는 YouTube 링크를 찾지 못했어요.", viewModel.uiState.value.errorMessage)
        assertNull(repository.rememberedUrl)
        assertEquals(0, repository.importCount)
    }

    @Test
    fun `a share rejected before the stored url is read still shows its message`() = runTest {
        val repository = FakeRecipeRepository(storeReadyAtStart = false)
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())

        viewModel.acceptSharedText("https://www.instagram.com/reel/C0abc/")
        repository.storeReady.value = true

        assertEquals("인스타그램 링크는 바로 분석할 수 없어요. 앱에서 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요.",
            viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `local mode asks for login before analysing a video and resumes after sign-in`() = runTest {
        val repository = FakeRecipeRepository()
        val auth = FakeAuthRepository(initiallyAuthorized = false)
        val viewModel = RecipeImportViewModel(repository, auth, FakeCookingSessionRepository(),
            executionMode = MutableStateFlow(ExecutionMode.LOCAL))

        viewModel.acceptSharedText("https://www.youtube.com/shorts/AbCdEfGhIjK")

        assertTrue(viewModel.uiState.value.loginRequiredForVideo)
        assertEquals(YOUTUBE_WATCH, viewModel.uiState.value.pendingUrl)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(0, repository.importCount)
        viewModel.acceptSharedText(YOUTUBE_WATCH)
        assertEquals("Sharing the same video again cannot skip the login", 0, repository.importCount)

        auth.authorize()

        assertFalse(viewModel.uiState.value.loginRequiredForVideo)
        assertEquals(listOf(YOUTUBE_WATCH), repository.importedUrls)
        assertEquals("동적 제목", viewModel.uiState.value.recipe?.title)
    }

    @Test
    fun `local mode opens a video recipe already on the device without login`() = runTest {
        val repository = FakeRecipeRepository().apply { analysedOnDevice += YOUTUBE_WATCH }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(initiallyAuthorized = false),
            FakeCookingSessionRepository(), executionMode = MutableStateFlow(ExecutionMode.LOCAL))

        viewModel.acceptSharedText(YOUTUBE_WATCH)

        assertFalse(viewModel.uiState.value.loginRequiredForVideo)
        assertEquals(1, repository.importCount)
        assertTrue(viewModel.uiState.value.recipe != null)
    }

    @Test
    fun `cloud mode keeps its own sign-in screen for videos`() = runTest {
        val repository = FakeRecipeRepository()
        val auth = FakeAuthRepository(initiallyAuthorized = false)
        val viewModel = RecipeImportViewModel(repository, auth, FakeCookingSessionRepository())

        viewModel.acceptSharedText(YOUTUBE_WATCH)

        assertFalse(viewModel.uiState.value.loginRequiredForVideo)
        assertEquals(0, repository.importCount)
        auth.authorize()
        assertEquals(1, repository.importCount)
    }

    @Test
    fun `signing out during a local video analysis stops it and asks for login again`() = runTest {
        val repository = FakeRecipeRepository().apply { importGate = CompletableDeferred() }
        val auth = FakeAuthRepository()
        val viewModel = RecipeImportViewModel(repository, auth, FakeCookingSessionRepository(),
            executionMode = MutableStateFlow(ExecutionMode.LOCAL))
        viewModel.acceptSharedText(YOUTUBE_WATCH)
        assertTrue(viewModel.uiState.value.isLoading)

        auth.logout()

        assertEquals(1, repository.cancelledImports)
        assertTrue(viewModel.uiState.value.loginRequiredForVideo)
        assertFalse(viewModel.uiState.value.isLoading)

        repository.importGate = null
        auth.authorize()

        assertEquals(2, repository.importCount)
        assertFalse(viewModel.uiState.value.loginRequiredForVideo)
        assertTrue(viewModel.uiState.value.recipe != null)
    }

    @Test
    fun `import progress is passed through to the screen`() = runTest {
        val repository = FakeRecipeRepository().apply { importGate = CompletableDeferred() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())
        viewModel.acceptSharedText(YOUTUBE_WATCH)

        repository.progress.value = VideoImportProgress(VideoImportStage.UPLOADING_VIDEO, .45f)
        assertEquals(VideoImportProgress(VideoImportStage.UPLOADING_VIDEO, .45f), viewModel.uiState.value.importProgress)
        repository.progress.value = VideoImportProgress(VideoImportStage.WATCHING_VIDEO)
        assertEquals(VideoImportStage.WATCHING_VIDEO, viewModel.uiState.value.importProgress?.stage)

        repository.progress.value = null
        repository.importGate?.complete(Unit)

        assertNull(viewModel.uiState.value.importProgress)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.recipe != null)
    }

    @Test
    fun `cancelling stops a running video analysis even when it was a retry`() = runTest {
        val repository = FakeRecipeRepository().apply { importFailure = VideoImportException("AI_QUOTA_EXCEEDED") }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())
        viewModel.acceptSharedText(YOUTUBE_WATCH)
        assertEquals("AI 사용량 한도에 도달했어요. 잠시 후 다시 시도해주세요.", viewModel.uiState.value.errorMessage)

        repository.importFailure = null
        repository.importGate = CompletableDeferred()
        viewModel.retry()
        assertTrue(viewModel.uiState.value.isLoading)
        viewModel.clearRecipe()

        assertEquals(1, repository.cancelledImports)
        assertNull(viewModel.uiState.value.pendingUrl)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `picked video is copied first and then imported through its pseudo url`() = runTest {
        val repository = FakeRecipeRepository()
        val intake = FakeVideoIntake().apply { gate = CompletableDeferred() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = intake, videoUploadConsent = InMemoryVideoUploadConsent(given = true))

        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(VideoImportProgress(VideoImportStage.PREPARING_VIDEO), viewModel.uiState.value.importProgress)
        assertNull(repository.rememberedUrl)

        intake.gate?.complete(Unit)

        assertEquals(listOf(PICKED_VIDEO), intake.requests)
        assertEquals(PHONE_VIDEO, repository.rememberedUrl)
        assertEquals(listOf(PHONE_VIDEO), repository.importedUrls)
        assertNull(viewModel.uiState.value.importProgress)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("동적 제목", viewModel.uiState.value.recipe?.title)
    }

    @Test
    fun `a video copy started before the stored url is read keeps the preparing screen`() = runTest {
        val repository = FakeRecipeRepository(storeReadyAtStart = false)
        val intake = FakeVideoIntake().apply { gate = CompletableDeferred() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = intake, videoUploadConsent = InMemoryVideoUploadConsent(given = true))
        viewModel.acceptVideoUri(PICKED_VIDEO)

        repository.storeReady.value = true

        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(VideoImportStage.PREPARING_VIDEO, viewModel.uiState.value.importProgress?.stage)
        intake.gate?.complete(Unit)
        assertEquals(listOf(PHONE_VIDEO), repository.importedUrls)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `local mode phone video waits for login once it is copied`() = runTest {
        val repository = FakeRecipeRepository()
        val auth = FakeAuthRepository(initiallyAuthorized = false)
        val viewModel = RecipeImportViewModel(repository, auth, FakeCookingSessionRepository(),
            executionMode = MutableStateFlow(ExecutionMode.LOCAL), videoIntake = FakeVideoIntake(),
            videoUploadConsent = InMemoryVideoUploadConsent(given = true))

        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertEquals(PHONE_VIDEO, viewModel.uiState.value.pendingUrl)
        assertTrue(viewModel.uiState.value.loginRequiredForVideo)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.importProgress)
        assertEquals(0, repository.importCount)

        auth.authorize()

        assertEquals(listOf(PHONE_VIDEO), repository.importedUrls)
    }

    @Test
    fun `a video that cannot be copied explains why and keeps the recipe being cooked`() = runTest {
        val repository = FakeRecipeRepository()
        val intake = FakeVideoIntake().apply {
            failure = VideoImportException(VideoImportException.VIDEO_TOO_LONG, "Video is longer than 1800 seconds")
        }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = intake)
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1401")
        viewModel.startCooking()

        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertEquals("30분이 넘는 영상은 아직 분석하지 않아요.", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.importProgress)
        assertEquals("https://www.10000recipe.com/recipe/1401", repository.rememberedUrl)
        assertEquals("동적 제목", viewModel.uiState.value.recipe?.title)
        assertTrue("The session can be resumed from the recipe screen", viewModel.uiState.value.cookingSession != null)

        intake.failure = IOException("Cannot create the video directory")
        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertEquals("영상 파일을 읽지 못했어요. 휴대폰에 저장된 영상을 다시 골라주세요.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `cancelling while a video is copied keeps nothing`() = runTest {
        val repository = FakeRecipeRepository()
        val intake = FakeVideoIntake().apply { gate = CompletableDeferred() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = intake)
        viewModel.acceptVideoUri(PICKED_VIDEO)

        viewModel.clearRecipe()
        intake.gate?.complete(Unit)

        assertNull(repository.rememberedUrl)
        assertEquals(0, repository.importCount)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.importProgress)
    }

    @Test
    fun `sharing a link while a video is still copying replaces the video`() = runTest {
        val repository = FakeRecipeRepository()
        val intake = FakeVideoIntake().apply { gate = CompletableDeferred() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = intake)
        viewModel.acceptVideoUri(PICKED_VIDEO)

        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1402")
        intake.gate?.complete(Unit)

        assertEquals("https://www.10000recipe.com/recipe/1402", repository.rememberedUrl)
        assertEquals(listOf("https://www.10000recipe.com/recipe/1402"), repository.importedUrls)
        assertNull(viewModel.uiState.value.importProgress)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `video failures are explained in Korean for every code`() = runTest {
        val repository = FakeRecipeRepository().apply { importFailure = VideoImportException("TIMEOUT") }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())
        val tryAgain = "영상 분석 중 문제가 생겼어요. 다시 시도해주세요."
        val unfinishedUpload = "영상 업로드가 완료되지 않았어요. 다시 시도해주세요."
        val messages = mapOf(
            "VIDEO_LINK_NOT_SUPPORTED" to "인스타그램·틱톡·네이버TV 링크는 바로 분석할 수 없어요. 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요.",
            "VIDEO_IMPORT_DAILY_LIMIT" to "오늘 영상 분석 횟수를 모두 사용했어요. 내일 다시 시도해주세요.",
            "VIDEO_ANALYSIS_UNAVAILABLE" to "지금은 영상 분석을 사용할 수 없어요. 서버의 AI 설정을 확인해주세요.",
            "VIDEO_UNAVAILABLE" to "영상을 열 수 없어요. 공개된 영상인지 확인해주세요.",
            "VIDEO_TOO_LONG" to "30분이 넘는 영상은 아직 분석하지 않아요.",
            "NOT_A_RECIPE_VIDEO" to "요리 과정이 나오는 영상이 아닌 것 같아요.",
            "RECIPE_EXTRACTION_FAILED" to "영상에서 조리 단계를 정확히 정리하지 못했어요.",
            "AI_QUOTA_EXCEEDED" to "AI 사용량 한도에 도달했어요. 잠시 후 다시 시도해주세요.",
            "AI_BLOCKED" to tryAgain,
            "AI_UNAVAILABLE" to tryAgain,
            "SERVER_RESTARTED" to tryAgain,
            "INTERNAL_ERROR" to tryAgain,
            "TIMEOUT" to tryAgain,
            "A_CODE_FROM_A_NEWER_SERVER" to tryAgain,
            "VIDEO_UPLOAD_TOO_LARGE" to "영상 파일이 너무 커요. 더 짧은 영상으로 시도해주세요.",
            "VIDEO_UPLOAD_LIMIT" to "진행 중인 영상 업로드가 많아요. 잠시 후 다시 시도해주세요.",
            "UPLOAD_INCOMPLETE" to unfinishedUpload,
            "UPLOAD_CHECKSUM_MISMATCH" to unfinishedUpload,
            "VIDEO_CHUNK_INVALID" to unfinishedUpload,
            "VIDEO_UPLOAD_CLOSED" to unfinishedUpload,
            "LOCAL_VIDEO_UNREADABLE" to "영상 파일을 읽지 못했어요. 휴대폰에 저장된 영상을 다시 골라주세요.",
            "VIDEO_COMPRESSION_FAILED" to "영상을 올릴 수 있는 크기로 줄이지 못했어요. 더 짧은 영상으로 시도해주세요.",
            "LOCAL_STORAGE_FULL" to "휴대폰 저장 공간이 부족해요. 공간을 비운 뒤 다시 시도해주세요.",
        )
        viewModel.acceptSharedText(YOUTUBE_WATCH)

        for ((code, message) in messages) {
            repository.importFailure = VideoImportException(code, "English diagnostics for $code")
            viewModel.retry()
            assertEquals(code, message, viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.errorNeedsSignIn)
            assertFalse(viewModel.uiState.value.isLoading)
        }
        val plainHttp = mapOf(
            413 to "영상 파일이 너무 커요. 더 짧은 영상으로 시도해주세요.",
            502 to "서버에서 레시피를 준비하지 못했어요. 잠시 후 다시 시도해주세요.",
        )
        for ((status, message) in plainHttp) {
            repository.importFailure = HttpException(Response.error<Unit>(status, "".toResponseBody()))
            viewModel.retry()
            assertEquals("HTTP $status", message, viewModel.uiState.value.errorMessage)
        }
        repository.importFailure = IOException("timeout")
        viewModel.retry()
        assertEquals("서버에 연결하지 못했어요. 네트워크와 서버 주소를 확인해주세요.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a session the server rejects asks to sign in and retries the video afterwards`() = runTest {
        val repository = FakeRecipeRepository().apply { importFailure = VideoLoginRequiredException() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())
        viewModel.acceptSharedText(YOUTUBE_WATCH)

        assertEquals("영상 레시피는 한술 서버의 AI가 분석해요. 로그인해주세요.", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorNeedsSignIn)

        repository.importFailure = null
        viewModel.completeSignIn(null)

        assertEquals(2, repository.importCount)
        assertTrue(viewModel.uiState.value.recipe != null)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.errorNeedsSignIn)

        repository.importFailure = HttpException(Response.error<Unit>(401, "".toResponseBody()))
        viewModel.retry()
        assertEquals("로그인 세션이 만료됐어요. 다시 로그인해주세요.", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorNeedsSignIn)
    }

    @Test
    fun `a video whose analysis failed waits for a retry instead of running again on the next start`() = runTest {
        val repository = FakeRecipeRepository().apply { importFailure = VideoImportException("NOT_A_RECIPE_VIDEO") }
        RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository()).acceptSharedText(YOUTUBE_WATCH)
        assertEquals(1, repository.importCount)

        // The process is gone; the next start reads the same stored link.
        val restarted = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())

        assertEquals("No new analysis, upload or daily-limit use without a tap", 1, repository.importCount)
        assertEquals(YOUTUBE_WATCH, restarted.uiState.value.pendingUrl)
        assertEquals("요리 과정이 나오는 영상이 아닌 것 같아요.", restarted.uiState.value.errorMessage)
        assertFalse(restarted.uiState.value.isLoading)

        repository.importFailure = null
        restarted.retry()

        assertEquals(2, repository.importCount)
        assertTrue(restarted.uiState.value.recipe != null)
        assertNull(restarted.uiState.value.errorMessage)
    }

    @Test
    fun `a video analysis that was cut short resumes on the next start`() = runTest {
        for (failure in listOf(VideoImportException(VideoImportException.TIMEOUT), IOException("offline"))) {
            val repository = FakeRecipeRepository().apply { importFailure = failure }
            RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository()).acceptSharedText(YOUTUBE_WATCH)

            // Still running on the server, or never reached it: picking the job up again is what the user wants.
            repository.importFailure = null
            val restarted = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())

            assertEquals("$failure", 2, repository.importCount)
            assertTrue("$failure", restarted.uiState.value.recipe != null)
        }
    }

    @Test
    fun `a link rejected while a recipe loads is still shown once the recipe arrives`() = runTest {
        val repository = FakeRecipeRepository().apply { importGate = CompletableDeferred() }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository())
        viewModel.acceptSharedText("https://www.10000recipe.com/recipe/1501")

        viewModel.acceptSharedText("https://www.instagram.com/reel/C0abc/")
        repository.importGate?.complete(Unit)

        assertTrue(viewModel.uiState.value.recipe != null)
        assertEquals("인스타그램 링크는 바로 분석할 수 없어요. 앱에서 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요.",
            viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `picking the same video again starts its import again`() = runTest {
        val repository = FakeRecipeRepository().apply { importFailure = VideoImportException("AI_QUOTA_EXCEEDED") }
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = FakeVideoIntake(), videoUploadConsent = InMemoryVideoUploadConsent(given = true))
        viewModel.acceptVideoUri(PICKED_VIDEO)
        assertEquals(1, repository.importCount)

        // The library hands back the copy it already has, so the pending link does not change.
        repository.importFailure = null
        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertEquals(listOf(PHONE_VIDEO, PHONE_VIDEO), repository.importedUrls)
        assertTrue(viewModel.uiState.value.recipe != null)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.importProgress)
    }

    @Test
    fun `a phone video waits for the one-time agreement before anything leaves the device`() = runTest {
        val repository = FakeRecipeRepository()
        val consent = InMemoryVideoUploadConsent()
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = FakeVideoIntake(), videoUploadConsent = consent)

        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertTrue(viewModel.uiState.value.consentRequiredForVideo)
        assertEquals(PHONE_VIDEO, viewModel.uiState.value.pendingUrl)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.importProgress)
        assertEquals("Nothing is uploaded or analysed yet", 0, repository.importCount)

        viewModel.acceptVideoUpload()

        assertTrue(consent.isGiven())
        assertFalse(viewModel.uiState.value.consentRequiredForVideo)
        assertEquals(listOf(PHONE_VIDEO), repository.importedUrls)
        assertTrue(viewModel.uiState.value.recipe != null)

        // Asked once: the next phone video goes straight to the analysis.
        viewModel.clearRecipe()
        viewModel.acceptVideoUri(PICKED_VIDEO)
        assertFalse(viewModel.uiState.value.consentRequiredForVideo)
        assertEquals(listOf(PHONE_VIDEO, PHONE_VIDEO), repository.importedUrls)
    }

    @Test
    fun `declining the video agreement keeps the video on the phone`() = runTest {
        val repository = FakeRecipeRepository()
        val consent = InMemoryVideoUploadConsent()
        val viewModel = RecipeImportViewModel(repository, FakeAuthRepository(), FakeCookingSessionRepository(),
            videoIntake = FakeVideoIntake(), videoUploadConsent = consent)
        viewModel.acceptVideoUri(PICKED_VIDEO)

        viewModel.clearRecipe()

        assertFalse(viewModel.uiState.value.consentRequiredForVideo)
        assertNull(viewModel.uiState.value.pendingUrl)
        assertFalse(consent.isGiven())
        assertEquals(0, repository.importCount)
    }

    @Test
    fun `links and videos already analysed on the device need no video agreement`() = runTest {
        // A YouTube link sends only its address; Gemini watches the public video itself.
        val links = FakeRecipeRepository()
        RecipeImportViewModel(links, FakeAuthRepository(), FakeCookingSessionRepository()).acceptSharedText(YOUTUBE_WATCH)
        assertEquals(listOf(YOUTUBE_WATCH), links.importedUrls)

        val stored = FakeRecipeRepository().apply { analysedOnDevice += PHONE_VIDEO }
        val viewModel = RecipeImportViewModel(stored, FakeAuthRepository(initiallyAuthorized = false),
            FakeCookingSessionRepository(), executionMode = MutableStateFlow(ExecutionMode.LOCAL), videoIntake = FakeVideoIntake())
        viewModel.acceptVideoUri(PICKED_VIDEO)

        assertFalse(viewModel.uiState.value.consentRequiredForVideo)
        assertEquals(listOf(PHONE_VIDEO), stored.importedUrls)
    }

    @Test
    fun `dismissing a message that was shown keeps a newer one`() = runTest {
        val viewModel = RecipeImportViewModel(FakeRecipeRepository(), FakeAuthRepository(), FakeCookingSessionRepository())
        viewModel.acceptSharedText("https://www.instagram.com/reel/C0abc/")
        val shown = viewModel.uiState.value.errorMessage
        viewModel.acceptSharedText("https://example.com/recipe/1")

        viewModel.dismissError(shown)
        assertEquals("지원하는 만개의레시피 또는 YouTube 링크를 찾지 못했어요.", viewModel.uiState.value.errorMessage)

        viewModel.dismissError("지원하는 만개의레시피 또는 YouTube 링크를 찾지 못했어요.")
        assertNull(viewModel.uiState.value.errorMessage)
    }

    /** [storeReadyAtStart] false models the app start, when the stored pending URL is read from disk only later. */
    private class FakeRecipeRepository(storeReadyAtStart: Boolean = true) : RecipeRepository {
        private val pending = MutableStateFlow<String?>(null)
        val storeReady = MutableStateFlow(storeReadyAtStart)
        override val pendingRecipeUrl: Flow<String?> = flow {
            storeReady.first { it }
            emitAll(pending)
        }
        val progress = MutableStateFlow<VideoImportProgress?>(null)
        override val importProgress: StateFlow<VideoImportProgress?> = progress
        var rememberedUrl: String? = null
        var importCount: Int = 0
        var sourceYield = "2"
        val importedUrls = mutableListOf<String>()
        /** Videos that already have a recipe on the device, as the offline repository keeps them. */
        val analysedOnDevice = mutableSetOf<String>()
        var importFailure: Throwable? = null
        var importGate: CompletableDeferred<Unit>? = null
        var cancelledImports = 0
        /** How the last import of a link ended, kept with the pending link across process death like the real store. */
        val failureCodes = mutableMapOf<String, String>()

        override suspend fun rememberRecipeUrl(url: String) {
            rememberedUrl = url
            failureCodes.clear()
            pending.value = url
        }

        override suspend fun forgetRecipeUrl() {
            failureCodes.clear()
            pending.value = null
        }

        override suspend fun importFailureCode(url: String): String? = failureCodes[url]

        override suspend fun rememberImportFailure(url: String, code: String?) {
            if (code == null) failureCodes.remove(url) else failureCodes[url] = code
        }

        override suspend fun needsServer(url: String): Boolean =
            RecipeUrlParser.requiresServerAnalysis(url) && url !in analysedOnDevice

        override suspend fun importRecipe(url: String): Recipe {
            importCount += 1
            importedUrls += url
            try {
                importGate?.await()
            } catch (error: CancellationException) {
                cancelledImports += 1
                throw error
            }
            importFailure?.let { throw it }
            return Recipe(
                id = "recipe-${url.substringAfterLast('/')}",
                sourceUrl = url,
                title = "동적 제목",
                baseServings = sourceYield,
                ingredients = listOf(Ingredient("동적 재료", "1개")),
                steps = listOf(
                    RecipeStep(1, "동적 첫 단계", null),
                    RecipeStep(3, "동적 손질 단계", "https://example.com/step-3.jpg"),
                    RecipeStep(7, "동적 마지막 단계", null),
                ),
            )
        }

        override suspend fun getScaledIngredients(
            recipeId: String,
            servings: Int,
        ): ScaledIngredients = ScaledIngredients(
            recipeId = recipeId,
            title = "동적 제목",
            baseServings = "2",
            targetServings = servings.toString(),
            ingredients = listOf(
                ScaledIngredient(
                    name = "동적 재료",
                    originalQuantityText = "1개",
                    scaledQuantityText = "${servings}개",
                    originalNote = null,
                    status = "SCALED",
                ),
            ),
            warnings = emptyList(),
        )
    }

    private class FakeAuthRepository(initiallyAuthorized: Boolean = true) : AuthRepository {
        override val sessionState = MutableStateFlow(
            AuthSessionState(isInitializing = false, isAuthorized = initiallyAuthorized),
        )

        fun authorize() {
            sessionState.value = AuthSessionState(
                isInitializing = false,
                isAuthorized = true,
            )
        }

        override suspend fun createAuthorizationIntent(): Intent =
            error("Not used in this test")

        override suspend fun completeAuthorization(intent: Intent?): Boolean = true

        override suspend fun getFreshAccessToken(): String = "test-token"

        override suspend fun logout() {
            sessionState.value = AuthSessionState(
                isInitializing = false,
                isAuthorized = false,
            )
        }
    }

    private class FakeCookingSessionRepository : CookingSessionRepository {
        var failNextTurn = false
        var failNextNavigation = false
        var failNextTimerAck = false
        var returnTimerRequest = false
        var startCount = 0
        var initialPendingTimerCueIds: Set<String> = emptySet()
        var initialTimerDeadlines: List<CookingTimerDeadline> = emptyList()
        var extraTimerCues: Map<String, CookingTimerCue> = emptyMap()
        var timerAckDeadline: CookingTimerDeadline? = null
        var nextStartGate: CompletableDeferred<Unit>? = null
        var nextTimerStartGate: CompletableDeferred<Unit>? = null
        var nextTurnGate: CompletableDeferred<Unit>? = null
        var transformTurn: (CookingTurn) -> CookingTurn = { it }
        val turnRequests = mutableListOf<Pair<String, String>>()
        val turnFocuses = mutableListOf<Int?>()
        val timerAckIds = mutableListOf<String>()
        val startedServings = mutableListOf<Int>()
        val timerStartRequests = mutableListOf<TimerStartCommand>()
        val navigationRequests = mutableListOf<NavigationRequest>()
        val progressRequests = mutableListOf<ProgressRequest>()
        val commandOrder = mutableListOf<String>()
        private val progressBySession = mutableMapOf<String, CookingProgress>()

        override suspend fun start(recipeId: String, targetServings: Int): CookingSession {
            startedServings += targetServings
            val sessionId = "session-${++startCount}"
            val gate = nextStartGate
            nextStartGate = null
            gate?.await()
            val progress = CookingProgress(focusedSourceStepNumber = 1)
            progressBySession[sessionId] = progress
            return CookingSession(
                id = sessionId,
                timerCues = mapOf(
                    "timer-cue" to CookingTimerCue(
                        id = "timer-cue",
                        durationSeconds = 300,
                        sourceStepNumber = 1,
                        approximate = true,
                    ),
                ) + extraTimerCues,
                timerDeadlines = initialTimerDeadlines,
                state = progress,
                pendingTimerCueIds = initialPendingTimerCueIds,
            )
        }

        override suspend fun requestTimerStart(sessionId: String, clientCommandId: String,
            timerCueId: String): CookingProgress {
            timerStartRequests += TimerStartCommand(sessionId, clientCommandId, timerCueId)
            val gate = nextTimerStartGate
            nextTimerStartGate = null
            gate?.await()
            val previous = progressBySession.getValue(sessionId)
            return previous.copy(version = previous.version + 1,
                requestedTimerCueIds = previous.requestedTimerCueIds + timerCueId).also {
                progressBySession[sessionId] = it
            }
        }

        override suspend fun navigate(sessionId: String, clientNavigationId: String,
            sourceStepNumber: Int): CookingNavigation {
            commandOrder += "navigate"
            navigationRequests += NavigationRequest(sessionId, clientNavigationId, sourceStepNumber)
            if (failNextNavigation) {
                failNextNavigation = false
                throw IOException("navigation response lost")
            }
            val previous = progressBySession.getValue(sessionId)
            val progress = previous.copy(version = previous.version + 1,
                focusedSourceStepNumber = sourceStepNumber)
            progressBySession[sessionId] = progress
            return CookingNavigation(sessionId, sourceStepNumber, progress)
        }

        override suspend fun updateStepProgress(sessionId: String, clientCommandId: String,
            sourceStepNumber: Int, status: StepProgressStatus): CookingNavigation {
            progressRequests += ProgressRequest(sessionId, clientCommandId, sourceStepNumber, status)
            val previous = progressBySession.getValue(sessionId)
            val completed = previous.completedSourceStepNumbers - sourceStepNumber
            val skipped = previous.skippedSourceStepNumbers - sourceStepNumber
            val progress = previous.copy(version = previous.version + 1,
                completedSourceStepNumbers = if (status == StepProgressStatus.COMPLETED)
                    completed + sourceStepNumber else completed,
                skippedSourceStepNumbers = if (status == StepProgressStatus.SKIPPED)
                    skipped + sourceStepNumber else skipped)
            progressBySession[sessionId] = progress
            return CookingNavigation(sessionId, sourceStepNumber, progress)
        }

        override suspend fun submitTurn(
            sessionId: String,
            clientTurnId: String,
            transcript: String,
        ): CookingTurn {
            commandOrder += "turn"
            turnRequests += clientTurnId to transcript
            val requestProgress = progressBySession.getValue(sessionId)
            turnFocuses += requestProgress.focusedSourceStepNumber
            val gate = nextTurnGate
            nextTurnGate = null
            gate?.await()
            if (failNextTurn) {
                failNextTurn = false
                throw IOException("response lost")
            }
            return transformTurn(CookingTurn(
                sessionId = sessionId,
                clientTurnId = clientTurnId,
                addressing = Addressing.DIRECT,
                replyText = "알겠어요.",
                photos = emptyList(),
                events = if (returnTimerRequest) {
                    listOf(CookingEvent("TIMER_START_REQUESTED", "timer-cue"))
                } else {
                    emptyList()
                },
                state = requestProgress,
            ))
        }

        override suspend fun acknowledgeTimerStart(
            sessionId: String,
            clientAckId: String,
            timerCueId: String,
        ): TimerAcknowledgement {
            timerAckIds += clientAckId
            if (failNextTimerAck) {
                failNextTimerAck = false
                throw IOException("response lost")
            }
            return TimerAcknowledgement(sessionId, clientAckId, timerAckDeadline)
        }
    }

    private class FakeVideoIntake(private val pseudoUrl: String = PHONE_VIDEO) : VideoIntake {
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        val requests = mutableListOf<String>()

        override suspend fun intake(uriString: String): String {
            requests += uriString
            gate?.await()
            failure?.let { throw it }
            return pseudoUrl
        }
    }

    private data class NavigationRequest(val sessionId: String, val id: String,
        val sourceStepNumber: Int)

    private data class ProgressRequest(val sessionId: String, val id: String,
        val sourceStepNumber: Int, val status: StepProgressStatus)

    private data class TimerStartCommand(val sessionId: String, val id: String, val cueId: String)

    private companion object {
        const val YOUTUBE_WATCH = "https://www.youtube.com/watch?v=AbCdEfGhIjK"
        const val PHONE_VIDEO = "hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e"
        const val PICKED_VIDEO = "content://media/picker/0/com.android.providers.media.photopicker/media/42"
    }
}
