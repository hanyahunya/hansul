package com.hansul.app.data.local

import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import org.junit.Assert.*
import org.junit.Test

class LocalServingAndTimerTest {
    private fun recipe(ingredients: List<Ingredient> = emptyList(), text: String = "원문 단계") = Recipe(
        "local-recipe-test", "https://www.10000recipe.com/recipe/1", "테스트", "1인분", ingredients,
        listOf(RecipeStep(8, text, null)),
    )

    @Test fun sameServingsKeepOriginalAndAllChangedAmountsAreOnlyMathematicalReferences() {
        val recipe = recipe(listOf(Ingredient("물", "550ml"), Ingredient("양파", "1/2개"), Ingredient("간장", "1큰술"), Ingredient("나물", "100g")))
        val unchanged = LocalServingGuide.scale(recipe, 1)
        assertTrue(unchanged.ingredients.all { it.status == "SOURCE" })
        assertEquals("550ml", unchanged.ingredients[0].scaledQuantityText)
        val double = LocalServingGuide.scale(recipe, 2)
        assertTrue(double.ingredients.all { it.status == "LINEAR_REFERENCE" })
        assertEquals("1100ml (비례 참고)", double.ingredients[0].scaledQuantityText)
        assertEquals("1개 (비례 참고)", double.ingredients[1].scaledQuantityText)
        assertEquals("2큰술 (비례 참고)", double.ingredients[2].scaledQuantityText)
        assertEquals("200g (비례 참고)", double.ingredients[3].scaledQuantityText)
        assertTrue(double.warnings.any { "조리 정량이 아니" in it })
    }

    @Test fun uncertainOrRangedQuantityPreservesOriginal() {
        val scaled = LocalServingGuide.scale(recipe(listOf(Ingredient("양파", "1~2개"))), 3)
        assertEquals("UNSCALABLE", scaled.ingredients.single().status)
        assertEquals("1~2개", scaled.ingredients.single().scaledQuantityText)
    }

    @Test fun combinesHoursMinutesAndHandlesDecimalWithoutGuessingRanges() {
        val cues = LocalTimerExtractor.extract(recipe(text = "1시간 30분 숙성한 뒤, 약 0.5분 데워주세요. 5~10분은 상태를 보세요."))
        assertEquals(listOf(5400L, 30L), cues.values.map { it.durationSeconds })
        assertFalse(cues.values.first().approximate)
        assertTrue(cues.values.last().approximate)
        assertTrue(cues.values.all { it.sourceStepNumber == 8 })
        assertTrue(LocalTimerExtractor.extract(recipe(text = "1시간~2시간 숙성합니다.")).isEmpty())
    }

    @Test fun fractionalHalfNegativeBoundRangeAndTotalDurationsAreNeverExactCues() {
        listOf("1/2시간", "1시간 반", "-5분", "최소5분", "5분 이상", "전체 예상 조리30분",
            "5분에서10분", "5∼10분", "5분~10분", "1시간부터2시간", "최대 10분", "5분마다").forEach { text ->
            assertTrue("Ambiguous duration must not create a cue: $text", LocalTimerExtractor.extract(recipe(text = text)).isEmpty())
        }
    }
}
