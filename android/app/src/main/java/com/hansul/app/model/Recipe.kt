package com.hansul.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Recipe(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val baseServings: String,
    val ingredients: List<Ingredient>,
    val steps: List<RecipeStep>,
    val imageUrl: String? = null,
    val video: RecipeVideo? = null,
    val origin: RecipeOrigin = RecipeOrigin.WEB_PAGE,
    /** Version of the device parser that read a page saved on this phone; 0 for older saves and server recipes. */
    val parserVersion: Int = 0,
)

@Serializable
data class Ingredient(
    val name: String,
    val quantityText: String,
)

@Serializable
data class RecipeStep(
    val number: Int,
    val text: String,
    val imageUrl: String?,
    val originalText: String? = null,
    val videoStartSeconds: Int? = null,
    val videoEndSeconds: Int? = null,
    val keyFrameSeconds: Int? = null,
)

/**
 * The video a recipe came from or embeds. A LOCAL_FILE video plays from [localFilePath]; YouTube plays in the app
 * only when [embeddable], and everything else opens [pageUrl] outside the app.
 */
@Serializable
data class RecipeVideo(
    val provider: VideoProvider,
    val videoId: String? = null,
    val pageUrl: String? = null,
    val localFilePath: String? = null,
    val durationSeconds: Int? = null,
    val channel: String? = null,
    val embeddable: Boolean = false,
)

@Serializable
enum class VideoProvider { YOUTUBE, NAVER_TV, LOCAL_FILE }

/** VIDEO_AI recipes were written by the server AI from a video's speech and on-screen text. */
@Serializable
enum class RecipeOrigin { WEB_PAGE, VIDEO_AI }

@Serializable
data class ScaledIngredients(
    val recipeId: String,
    val title: String,
    val baseServings: String,
    val targetServings: String,
    val ingredients: List<ScaledIngredient>,
    val warnings: List<String>,
)

@Serializable
data class ScaledIngredient(
    val name: String,
    val originalQuantityText: String,
    val scaledQuantityText: String,
    val originalNote: String?,
    val status: String,
)

/** Preserve source yield text, but only accept an unambiguous single numeric serving count. */
fun Recipe.baseServingCount(): Double? = Regex(
    "^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:인분|명|servings?)?\\s*$", RegexOption.IGNORE_CASE,
).matchEntire(baseServings)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 && it <= 99 }

fun Recipe.sourceServingLabel(): String = baseServingCount()?.let {
    "${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()}인분"
} ?: baseServings.ifBlank { "분량 미상" }
