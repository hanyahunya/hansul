package com.hansul.app.ai

import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalServingContextTest {
    private fun recipe() = Recipe("any-recipe", "https://www.10000recipe.com/recipe/1", "임의 요리", "3인분",
        listOf(Ingredient("어떤재료", "150g")), listOf(RecipeStep(42, "원문 조리 설명", null)))

    @Test fun sourceAndTargetAmountsAreSeparateWithoutAnyIngredientNameRule() {
        val context = LocalServingContext.build(recipe(), 6)
        assertEquals(3.0, context["sourceServingCount"]!!.jsonPrimitive.double, 0.0)
        assertEquals(6, context["requestedTargetServingCount"]!!.jsonPrimitive.int)
        assertTrue(context["sourceAndTargetDiffer"]!!.jsonPrimitive.boolean)
        assertFalse(context["confirmedTargetCookingQuantityAvailable"]!!.jsonPrimitive.boolean)
        val amounts = context["ingredientAmounts"]!!.jsonArray.single().jsonObject
        assertEquals("150g", amounts["originalAmountForSourceServings"]!!.jsonPrimitive.content)
        assertEquals("300g (비례 참고)", amounts["mathematicalReferenceForTargetServings"]!!.jsonPrimitive.content)
        assertTrue(amounts["confirmedTargetCookingQuantity"]!!.jsonPrimitive.content.startsWith("정보 없음"))
    }

    @Test fun identicalSourcePortionsPreserveSourceWhileUnknownSourceCountNeverInventsATarget() {
        val identical = LocalServingContext.build(recipe(), 3)
        assertTrue(identical["confirmedTargetCookingQuantityAvailable"]!!.jsonPrimitive.boolean)
        assertFalse(identical["ingredientAmounts"]!!.jsonArray.single().jsonObject.containsKey("mathematicalReferenceForTargetServings"))
        val unknown = LocalServingContext.build(recipe().copy(baseServings = "2~3인분"), 4)
        assertFalse(unknown.containsKey("sourceServingCount"))
        assertFalse(unknown["confirmedTargetCookingQuantityAvailable"]!!.jsonPrimitive.boolean)
        assertFalse(unknown["ingredientAmounts"]!!.jsonArray.single().jsonObject.containsKey("mathematicalReferenceForTargetServings"))
    }
}
