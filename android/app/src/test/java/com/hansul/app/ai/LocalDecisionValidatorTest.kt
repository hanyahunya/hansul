package com.hansul.app.ai

import com.hansul.app.model.*
import org.junit.Assert.*
import org.junit.Test

class LocalDecisionValidatorTest {
    private fun context(transcript: String) = LocalAgentContext(
        Recipe("local-recipe-test", "https://www.10000recipe.com/recipe/1", "요리", "1인분",
            listOf(Ingredient("양파", "1개")), listOf(RecipeStep(3, "양파를 얇게 썰어주세요.", "https://recipe1.ezmember.co.kr/onion.jpg"), RecipeStep(7, "끓여주세요.", null))),
        1, CookingSession("local-session-test", mapOf("timer-7" to CookingTimerCue("timer-7", 60, 7, false)), emptyList()),
        emptySet(), transcript,
    )

    @Test fun lookingAtAnotherStepDoesNotCompleteEarlierSteps() {
        val valid = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "양파를 얇게 썰어주세요.",
            viewedSourceStepNumber = 3, photoSourceSteps = listOf(3)), context("양파 어떻게 잘라?"))
        assertTrue(valid.completedSteps.isEmpty())
        assertNull(valid.viewedSourceStepNumber)
        assertEquals(listOf(3), valid.photoSourceSteps)
    }

    @Test fun rejectsNonexistentPhotoWithoutLosingUsefulAnswer() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "사진입니다.", photoSourceSteps = listOf(7)), context("사진 보여줘"))
        assertTrue(result.photoSourceSteps.isEmpty())
        assertTrue(result.replyText.isNotBlank())
    }

    @Test fun rejectsTimerAcceptanceThatWasNotOffered() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "요청할게요.", timerAcceptIds = listOf("timer-7")), context("타이머 켜줘"))
        assertTrue(result.timerAcceptIds.isEmpty())
        assertTrue(result.replyText.contains("아직 시작하지 않았어요"))
    }

    @Test fun unrelatedAndUncertainSpeechCannotMutateState() {
        val decision = LocalCookingDecision(Addressing.UNCERTAIN, "어느 단계를 마쳤나요?", viewedSourceStepNumber = 3,
            completedSteps = listOf(LocalCompletionReport(3, "다 했어")), timerOfferIds = listOf("timer-7"))
        val result = LocalDecisionValidator.validate(decision, context("다 했어"))
        assertTrue(result.completedSteps.isEmpty())
        assertNull(result.viewedSourceStepNumber)
        assertTrue(result.timerOfferIds.isEmpty())
    }

    @Test fun questionFragmentCannotBecomeAReportedSubstitution() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "대파를 사용할 수 있어요.",
            ingredientAdjustments = listOf(IngredientAdjustment("REPLACED", "양파", "대파", evidence = "양파 대신 대파"))),
            context("양파 대신 대파 써도 돼?"))
        assertTrue(result.ingredientAdjustments.isEmpty())
        assertTrue(result.replyText.startsWith("대파를 사용할 수 있어요."))
    }

    @Test fun recordsMissingIngredientFactEvenWhenAnotherQuestionFollows() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "대체 재료를 확인할게요.",
            ingredientAdjustments = listOf(IngredientAdjustment("MISSING", "양파", evidence = "양파가 아직 없어"))),
            context("양파가 아직 없어. 대파 써도 돼?"))
        assertEquals("MISSING", result.ingredientAdjustments.single().kind)
    }

    @Test fun missingIngredientConnectorKeepsFullEvidenceBeforeASeparateQuestion() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "대체 재료를 확인할게요.",
            ingredientAdjustments = listOf(IngredientAdjustment("MISSING", "양파", evidence = "양파가 없는데"))),
            context("양파가 없는데 뭘 대신 쓰면 돼?"))
        assertEquals("MISSING", result.ingredientAdjustments.single().kind)
    }

    @Test fun explicitNavigationMovesOnlyTheRequestedOriginalStep() {
        val valid = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "3단계를 보여드릴게요.",
            viewedSourceStepNumber = 3), context("3단계 보여줘"))
        assertEquals(3, valid.viewedSourceStepNumber)
    }

    @Test fun actionReportsNeverImplyWholeStepCompletion() {
        listOf("양파 썰었어", "양파 썰었어?", "양파 안 썰었어", "3단계 완료했어?", "3단계 다 완료한 건 아니야").forEach { transcript ->
            val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "확인했어요.",
                    completedSteps = listOf(LocalCompletionReport(3, transcript.trimEnd('?')))), context(transcript))
            assertTrue("No whole step completion may be invented: $transcript", result.completedSteps.isEmpty())
        }
    }

    @Test fun onlyExplicitWholeStepReportCanCompleteStep() {
        val valid = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "3단계 완료를 기록할게요.",
            completedSteps = listOf(LocalCompletionReport(3, "3단계 전체 완료했어"))), context("3단계 전체 완료했어"))
        assertEquals(3, valid.completedSteps.single().sourceStepNumber)
        val invalid = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "완료했어요.",
                completedSteps = listOf(LocalCompletionReport(3, "완료했어"))), context("3단계는 아직 안 완료했어"))
        assertTrue(invalid.completedSteps.isEmpty())
    }

    @Test fun ingredientFactMustNameSameIngredientInTheFullEvidenceClause() {
        listOf(
            "양파는 있고 대파는 없어" to IngredientAdjustment("MISSING", "양파", evidence = "대파는 없어"),
            "양파 안 넣었어" to IngredientAdjustment("ADDED", "양파", evidence = "넣었어"),
            "양파 대신 대파 넣었어?" to IngredientAdjustment("REPLACED", "양파", "대파", evidence = "양파 대신 대파 넣었어"),
            "양파 없어. 대파 두개 샀어" to IngredientAdjustment("MISSING", "양파", quantityText = "두개", evidence = "양파 없어"),
            "양파 없는 거 아냐" to IngredientAdjustment("MISSING", "양파", evidence = "양파 없는 거 아냐"),
            "양파 없다고는 안 했어" to IngredientAdjustment("MISSING", "양파", evidence = "양파 없다고는 안 했어"),
        ).forEach { (transcript, adjustment) ->
            val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "확인했어요.",
                    ingredientAdjustments = listOf(adjustment)), context(transcript))
            assertTrue("No ingredient fact may be invented: $transcript", result.ingredientAdjustments.isEmpty())
        }
    }

    @Test fun timerAcceptanceMustBeAnExplicitAffirmativeNotAQuestionOrNegation() {
        listOf("타이머 안 켜도 돼?", "타이머 켜줘?", "타이머 안 켜줘", "타이머 시작하려고", "물이 끓었어", "타이머 시작해줘, 아니 안 할래").forEach { transcript ->
            val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "요청할게요.", timerAcceptIds = listOf("timer-7")),
                    context(transcript).copy(offeredTimerCueIds = setOf("timer-7")))
            assertTrue("No timer may start from question/negation: $transcript", result.timerAcceptIds.isEmpty())
        }
        val valid = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "기기 예약을 요청할게요.", timerAcceptIds = listOf("timer-7")),
            context("타이머 켜줘").copy(offeredTimerCueIds = setOf("timer-7")))
        assertEquals(listOf("timer-7"), valid.timerAcceptIds)
    }

    @Test fun wrongFocusedPhotoIdIsRegroundedToUniqueOriginalInstruction() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "양파는 얇게 썰어주세요.",
            photoSourceSteps = listOf(7)), context("양파 어떻게 잘라?"))
        assertEquals(listOf(3), result.photoSourceSteps)
        assertNull(result.viewedSourceStepNumber)
    }

    @Test fun uniqueSourcePhotoForVisualQuestionDoesNotRequireTheModelToRememberPhotoFields() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "양파는 얇게 썰어주세요.",
            timerOfferIds = listOf("timer-7")), context("양파 어떻게 잘라?"))
        assertEquals(listOf(3), result.photoSourceSteps)
        assertTrue("A cutting answer must not offer an unrelated boiling timer", result.timerOfferIds.isEmpty())
        assertNull(result.viewedSourceStepNumber)
    }

    @Test fun exactSourceTimerMentionCanOfferButNeverStartATimer() {
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "1분 끓여주세요. 타이머를 맞출까요?",
            timerOfferIds = listOf("timer-7")), context("몇분 끓여?"))
        assertEquals(listOf("timer-7"), result.timerOfferIds)
        assertTrue(result.timerAcceptIds.isEmpty())
    }

    @Test fun sourceGroundingUsesContentNotRecipeNumberOrIngredientName() {
        val custom = context("어떻게 손질해?").copy(recipe = Recipe("any-recipe", "https://www.10000recipe.com/recipe/2", "임의 요리", "1인분",
            emptyList(), listOf(RecipeStep(42, "연근을 껍질 벗긴 뒤 얇게 동그랗게 썰어주세요.", "https://recipe1.ezmember.co.kr/a.jpg"),
                RecipeStep(99, "물이 끓으면 재료를 넣고 익혀주세요.", "https://recipe1.ezmember.co.kr/b.jpg"))))
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "연근은 껍질 벗긴 뒤 얇게 동그랗게 썰어주세요.",
            photoSourceSteps = listOf(99), photoSourceEvidence = listOf("껍질 벗긴 뒤 얇게 동그랗게 썰어주세요.")), custom)
        assertEquals(listOf(42), result.photoSourceSteps)
    }

    @Test fun commonIngredientWordsCannotProveAnUnrelatedPhotoAction() {
        val custom = context("양파 써도 돼?").copy(recipe = context("").recipe.copy(steps = listOf(
            RecipeStep(3, "양파를 얇게 썰어주세요.", "https://recipe1.ezmember.co.kr/a.jpg"),
            RecipeStep(7, "양파를 넣고 충분히 끓여주세요.", "https://recipe1.ezmember.co.kr/b.jpg"))))
        val result = LocalDecisionValidator.validate(LocalCookingDecision(Addressing.DIRECT, "양파는 사용할 수 있어요.",
            photoSourceSteps = listOf(7)), custom)
        assertTrue(result.photoSourceSteps.isEmpty())
    }

    @Test fun staleStateProposalsCannotOverwriteLatestManualNavigation() {
        val generated = LocalCookingDecision(Addressing.DIRECT, "얇게 썰어주세요.", viewedSourceStepNumber = 3,
            completedSteps = listOf(LocalCompletionReport(3, "3단계 전체 완료했어")), timerOfferIds = listOf("timer-7"),
            ingredientAdjustments = listOf(IngredientAdjustment("MISSING", "양파", evidence = "양파 없어")), photoSourceSteps = listOf(3))
        val rebased = LocalDecisionValidator.rebase(generated, 1, 2)
        assertNull(rebased.viewedSourceStepNumber)
        assertTrue(rebased.completedSteps.isEmpty())
        assertTrue(rebased.ingredientAdjustments.isEmpty())
        assertTrue(rebased.timerOfferIds.isEmpty())
        assertEquals(listOf(3), rebased.photoSourceSteps)
        assertTrue(rebased.replyText.contains("최신 단계를 유지"))
        assertEquals(generated, LocalDecisionValidator.rebase(generated, 1, 1))
    }
}
