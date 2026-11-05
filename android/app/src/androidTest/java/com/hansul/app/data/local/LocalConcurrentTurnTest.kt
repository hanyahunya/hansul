package com.hansul.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hansul.app.ai.LocalCompletionReport
import com.hansul.app.ai.LocalCookingDecision
import com.hansul.app.ai.LocalDecisionSource
import com.hansul.app.model.Addressing
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalConcurrentTurnTest {
    @Test fun inferenceDoesNotLockManualNavigationAndStaleCompletionIsDiscarded() = runBlocking {
        val store = LocalCookingStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val recipe = Recipe("local-recipe-test-${UUID.randomUUID()}", "https://www.10000recipe.com/recipe/1", "동시 이동 검증", "1인분",
            emptyList(), listOf(RecipeStep(3, "재료를 손질합니다.", null), RecipeStep(7, "재료를 익힙니다.", null)))
        store.saveRecipe(recipe)
        val inferenceEntered = CompletableDeferred<Unit>()
        val releaseInference = CompletableDeferred<Unit>()
        val repository = OfflineCookingSessionRepository(store, LocalDecisionSource {
            inferenceEntered.complete(Unit)
            releaseInference.await()
            LocalCookingDecision(Addressing.DIRECT, "완료 보고를 확인했어요.",
                completedSteps = listOf(LocalCompletionReport(3, "3단계 전체 완료했어")))
        })
        val session = repository.start(recipe.id, 1)
        val turn = async { repository.submitTurn(session.id, "turn-stale", "3단계 전체 완료했어") }
        inferenceEntered.await()
        val navigated = withTimeout(1_000) { repository.navigate(session.id, "manual-navigation", 7) }
        assertEquals(7, navigated.sourceStepNumber)
        releaseInference.complete(Unit)
        val response = turn.await()
        assertEquals(7, response.state?.focusedSourceStepNumber)
        assertTrue(response.state?.completedSourceStepNumbers.orEmpty().isEmpty())
        assertTrue(response.replyText.contains("최신 단계를 유지"))
        assertEquals(response, repository.submitTurn(session.id, "turn-stale", "3단계 전체 완료했어"))
        assertTrue(runCatching { repository.submitTurn(session.id, "turn-stale", "다른 내용") }.isFailure)
    }
}
