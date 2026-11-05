package com.hansul.app.data.network

import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeOrigin
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoProvider
import com.hansul.app.model.ScaledIngredient
import com.hansul.app.model.ScaledIngredients
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class RecipeImportRequest(
    val url: String,
)

@Serializable
data class RecipeImportResponse(
    val id: JsonElement = JsonNull,
    val sourceUrl: String,
    val title: String,
    val baseServings: JsonElement = JsonNull,
    val ingredients: List<IngredientResponse> = emptyList(),
    val steps: List<RecipeStepResponse> = emptyList(),
    val imageUrl: String? = null,
    val primaryImageUrl: String? = null,
    val baseServingsText: String? = null,
    val sourceKind: String? = null,
    val video: RecipeVideoResponse? = null,
)

@Serializable
data class IngredientResponse(
    val name: String,
    val quantityText: String? = null,
)

@Serializable
data class RecipeStepResponse(
    val number: Int,
    val text: String,
    val imageUrl: String? = null,
    val originalText: String? = null,
    val videoStartSeconds: Int? = null,
    val videoEndSeconds: Int? = null,
    val keyFrameSeconds: Int? = null,
)

@Serializable
data class RecipeVideoResponse(
    val provider: String = "",
    val videoId: String? = null,
    val pageUrl: String? = null,
    val durationSeconds: Int? = null,
    val channel: String? = null,
    val embeddable: Boolean = false,
)

@Serializable
data class ScaledIngredientsResponse(
    val recipeId: JsonElement = JsonNull,
    val title: String = "",
    val baseServings: JsonElement = JsonNull,
    val targetServings: JsonElement = JsonNull,
    val ingredients: List<ScaledIngredientResponse> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ScaledIngredientResponse(
    val name: String,
    val originalQuantityText: String = "",
    val scaledQuantityText: String = "",
    val originalNote: String? = null,
    val status: String = "",
)

internal fun RecipeImportResponse.toDomain(): Recipe = Recipe(
    id = id.displayText(),
    sourceUrl = sourceUrl,
    title = title,
    baseServings = baseServings.displayText().ifBlank { baseServingsText.orEmpty() },
    // The server names the hero photo primaryImageUrl; imageUrl is kept for older payloads.
    imageUrl = primaryImageUrl?.takeIf(String::isNotBlank) ?: imageUrl?.takeIf(String::isNotBlank),
    ingredients = ingredients.map { ingredient ->
        Ingredient(
            name = ingredient.name,
            quantityText = ingredient.quantityText.orEmpty(),
        )
    },
    steps = steps.sortedBy(RecipeStepResponse::number).map { step ->
        RecipeStep(
            number = step.number,
            text = step.text,
            imageUrl = step.imageUrl?.takeIf(String::isNotBlank),
            originalText = step.originalText?.takeIf(String::isNotBlank),
            videoStartSeconds = step.videoStartSeconds?.takeIf { it >= 0 },
            videoEndSeconds = step.videoEndSeconds?.takeIf { it >= 0 },
            keyFrameSeconds = step.keyFrameSeconds?.takeIf { it >= 0 },
        )
    },
    video = video?.toDomain(),
    origin = if (sourceKind == "VIDEO_AI") RecipeOrigin.VIDEO_AI else RecipeOrigin.WEB_PAGE,
)

private val youTubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")

private fun RecipeVideoResponse.toDomain(): RecipeVideo? {
    val provider = when (provider) {
        "YOUTUBE" -> VideoProvider.YOUTUBE
        "NAVER_TV" -> VideoProvider.NAVER_TV
        "UPLOAD" -> VideoProvider.LOCAL_FILE
        else -> return null
    }
    val id = videoId?.takeIf(String::isNotBlank)
    return RecipeVideo(
        provider = provider,
        videoId = id,
        // Opened outside the app, so only an https page address is accepted.
        pageUrl = pageUrl?.takeIf { it.startsWith("https://") },
        durationSeconds = durationSeconds?.takeIf { it > 0 },
        channel = channel?.takeIf(String::isNotBlank),
        embeddable = provider == VideoProvider.YOUTUBE && embeddable && id?.matches(youTubeVideoId) == true,
    )
}

internal fun ScaledIngredientsResponse.toDomain(): ScaledIngredients = ScaledIngredients(
    recipeId = recipeId.displayText(),
    title = title,
    baseServings = baseServings.displayText(),
    targetServings = targetServings.displayText(),
    ingredients = ingredients.map { ingredient ->
        ScaledIngredient(
            name = ingredient.name,
            originalQuantityText = ingredient.originalQuantityText,
            scaledQuantityText = ingredient.scaledQuantityText,
            originalNote = ingredient.originalNote,
            status = ingredient.status,
        )
    },
    warnings = warnings,
)

private fun JsonElement.displayText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    JsonNull -> ""
    else -> toString()
}
