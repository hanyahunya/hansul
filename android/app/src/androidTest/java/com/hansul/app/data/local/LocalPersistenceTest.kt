package com.hansul.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hansul.app.ai.LocalDecisionSource
import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.StepProgressStatus
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPersistenceTest {
    @Test fun manualNavigationProgressAndPendingTimerSurviveRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalCookingStore(context)
        val recipe = Recipe("local-recipe-test-${UUID.randomUUID()}", "https://www.10000recipe.com/recipe/1", "기록 검증용", "1인분",
            listOf(Ingredient("양파", "1개")), listOf(RecipeStep(3, "양파를 썰어주세요.", null), RecipeStep(7, "약 2분 끓여주세요.", null)))
        store.saveRecipe(recipe)
        val noAi = LocalDecisionSource { error("수동 조리는 AI를 호출하지 않습니다.") }
        val first = OfflineCookingSessionRepository(store, noAi)
        val session = first.start(recipe.id, 1)
        first.navigate(session.id, "look-at-seven", 7)
        first.updateStepProgress(session.id, "skip-three", 3, StepProgressStatus.SKIPPED)
        val cue = session.timerCues.keys.single()
        assertTrue(runCatching { first.acknowledgeTimerStart(session.id, "unrequested-ack", cue) }.isFailure)
        first.requestTimerStart(session.id, "timer-request", cue)
        assertTrue(runCatching { first.requestTimerStart(session.id, "duplicate-request-new-id", cue) }.isFailure)
        val recreated = OfflineCookingSessionRepository(LocalCookingStore(context), noAi)
        val restored = recreated.start(recipe.id, 1)
        assertEquals(session.id, restored.id)
        assertEquals(7, restored.state.focusedSourceStepNumber)
        assertTrue(restored.state.completedSourceStepNumbers.isEmpty())
        assertEquals(setOf(3), restored.state.skippedSourceStepNumbers)
        assertEquals(setOf(cue), restored.pendingTimerCueIds)
        val ack = recreated.acknowledgeTimerStart(session.id, "stable-ack", cue)
        assertEquals(ack, recreated.acknowledgeTimerStart(session.id, "stable-ack", cue))
        assertTrue(runCatching { recreated.acknowledgeTimerStart(session.id, "new-ack-cannot-reset", cue) }.isFailure)
        assertTrue(runCatching { recreated.requestTimerStart(session.id, "new-request-cannot-reset", cue) }.isFailure)
        assertTrue(recreated.getSession(session.id).pendingTimerCueIds.isEmpty())
        assertEquals(1, recreated.getSession(session.id).timerDeadlines.size)
    }
}
