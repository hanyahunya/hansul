package com.hansul.app.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hansul.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a user-installed verified model. Logs only authored fixtures and performance counters. */
@RunWith(AndroidJUnit4::class)
class LocalGemmaSmokeTest {
    @Test fun koreanCookingQuestionsHaveValidSourcePhotosAndNoInventedCompletion() = runBlocking {
        val manager = LocalModelManager(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("먼저 앱에서 공식 모델을 설치해주세요.", manager.state.value.status == LocalAiStatus.AVAILABLE)
        val recipe = Recipe("local-recipe-benchmark", "https://www.10000recipe.com/recipe/1", "한술 평가용 양파국", "1인분",
            listOf(Ingredient("양파", "1개"), Ingredient("물", "550ml"), Ingredient("간장", "1큰술")),
            listOf(RecipeStep(3, "양파를 반으로 나눠 얇게 채 썰어주세요.", "https://recipe1.ezmember.co.kr/fixture-onion.jpg"),
                RecipeStep(7, "양파와 물을 넣고 약 2분 끓여주세요.", "https://recipe1.ezmember.co.kr/fixture-pot.jpg"),
                RecipeStep(9, "간장을 넣고 간을 보며 마무리해주세요.", null)))
        val session = CookingSession("local-session-benchmark",
            mapOf("timer-7" to CookingTimerCue("timer-7", 120, 7, true)), emptyList(), CookingProgress(focusedSourceStepNumber = 7))
        val questions = listOf("양파 어떻게 잘라야 해?", "양파가 없는데 뭘 대신 쓰면 돼?", "2인분이면 물을 무조건 1100ml 넣으면 돼?")
        val failures = mutableListOf<String>()
        for (question in questions) {
            val context = LocalAgentContext(recipe, 2, session, emptySet(), question, diagnosticFixture = true)
            val decision = try {
                LocalGemmaAgent(manager).decide(context)
            } catch (error: Exception) {
                Log.i(TAG, "fixture=$question failed=${error.javaClass.simpleName} message=${error.message?.take(300)}")
                Log.i(TAG, "backend=${manager.state.value.backend} diagnostics=${manager.diagnostics.value}")
                failures.add("$question: ${error.javaClass.simpleName}: ${error.message}")
                continue
            }
            Log.i(TAG, "fixture=$question addressing=${decision.addressing} photos=${decision.photoSourceSteps} reply=${decision.replyText.take(500)}")
            Log.i(TAG, "backend=${manager.state.value.backend} diagnostics=${manager.diagnostics.value}")
            if (decision.addressing != Addressing.DIRECT) failures.add("$question: addressing must be DIRECT, got ${decision.addressing}")
            if (decision.replyText.isBlank()) failures.add("$question: reply must not be blank")
            if (decision.completedSteps.isNotEmpty()) failures.add("$question: a question cannot imply completion")
            if (decision.timerAcceptIds.isNotEmpty()) failures.add("$question: no timer start was accepted")
            if (decision.ingredientAdjustments.any { it.kind == "REPLACED" || it.kind == "ADDED" }) failures.add("$question: no performed replacement/addition was reported")
            if (question.startsWith("양파 어떻게") && decision.photoSourceSteps != listOf(3)) failures.add("$question: original photo step 3 required, got ${decision.photoSourceSteps}")
            if (decision.viewedSourceStepNumber != null) failures.add("$question: a photo question cannot change focused step")
            if (question.startsWith("양파가 없는데") && Regex("어떤\\s*(?:종류의\\s*)?요리|무슨\\s*요리|요리\\s*종류").containsMatchIn(decision.replyText)) {
                failures.add("$question: recipe context was already supplied; do not ask what dish it is")
            }
            if (question.startsWith("2인분이면") && !Regex("비례|확정|단순|제품|냄비|증발|정량이\\s*아니|무조건.*아니").containsMatchIn(decision.replyText)) {
                failures.add("$question: must distinguish mathematical reference from actual cooking water")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    companion object { private const val TAG = "HansulLocalBenchmark" }
}
