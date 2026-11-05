package com.hansul.app.ai

import com.hansul.app.data.local.LocalServingGuide
import com.hansul.app.model.Recipe
import com.hansul.app.model.baseServingCount
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Source amounts belong to SOURCE servings; references are never confirmed TARGET cooking amounts. */
internal object LocalServingContext {
    fun build(recipe: Recipe, targetServings: Int) = buildJsonObject {
        val sourceCount = recipe.baseServingCount()
        put("sourceServingText", recipe.baseServings)
        sourceCount?.let { put("sourceServingCount", it) }
        put("requestedTargetServingCount", targetServings)
        put("sourceAndTargetDiffer", sourceCount == null || sourceCount != targetServings.toDouble())
        put("originalAmountsAreForSourceServingsOnly", true)
        put("mathematicalReferenceIsNotConfirmedCookingQuantity", true)
        put("confirmedTargetCookingQuantityAvailable", sourceCount == targetServings.toDouble())
        put("ingredientAmounts", buildJsonArray {
            LocalServingGuide.scale(recipe, targetServings).ingredients.take(80).forEach { row ->
                add(buildJsonObject {
                    put("ingredientName", row.name)
                    put("originalAmountForSourceServings", row.originalQuantityText)
                    if (row.status == "LINEAR_REFERENCE") put("mathematicalReferenceForTargetServings", row.scaledQuantityText)
                    put("referenceStatus", row.status)
                    put("confirmedTargetCookingQuantity", if (row.status == "SOURCE") row.originalQuantityText else "정보 없음: 실제 조리량 확인 필요")
                })
            }
        })
    }
}
