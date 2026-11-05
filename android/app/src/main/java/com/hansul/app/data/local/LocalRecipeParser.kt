package com.hansul.app.data.local

import com.hansul.app.model.Ingredient
import com.hansul.app.model.Recipe
import com.hansul.app.model.RecipeStep
import com.hansul.app.model.RecipeVideo
import com.hansul.app.model.VideoProvider
import com.hansul.app.share.RecipeUrlParser
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Parses source content. No recipe ID or cooking instruction is embedded in this parser. */
class LocalRecipeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(html: String, sourceUrl: String): Recipe {
        val canonical = requireNotNull(RecipeUrlParser.extract(sourceUrl)) { "만개의레시피 주소를 확인해주세요." }
        val document = Jsoup.parse(html, canonical)
        val recipeNode = document.select("script[type=application/ld+json]")
            .firstNotNullOfOrNull { script ->
                runCatching { findRecipe(json.parseToJsonElement(script.data().ifBlank { script.html() })) }.getOrNull()
            }
        val domSteps = document.select("div.view_step_cont").mapNotNull { element ->
            val number = Regex("^stepDiv([1-9][0-9]*)$").matchEntire(element.id())
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val text = normalize(element.selectFirst("[id=stepdescr$number]")?.wholeText()
                ?: element.selectFirst(".media-body")?.wholeText())
            if (text.isBlank()) return@mapNotNull null
            val img = element.selectFirst("[id=stepimg$number] img")
            val image = img?.let { listOf("data-src", "src").firstNotNullOfOrNull { name ->
                verifiedImage(it.attr(name), canonical)
            } }
            RecipeStep(number, text, image)
        }.sortedBy { it.number }.distinctBy { it.number }
        val structuredSteps = buildList { collectSteps(recipeNode?.get("recipeInstructions"), canonical, this) }
        val steps = if (structuredSteps.isEmpty()) domSteps else structuredSteps.mapIndexed { index, value ->
            val matching = domSteps.filter { normalize(it.text) == normalize(value.first) }
                .singleOrNull()
            // A DOM photo is merged only when the source text matches, never merely by list position.
            RecipeStep(matching?.number ?: index + 1, value.first, value.second ?: matching?.imageUrl)
        }
        require(steps.isNotEmpty() && steps.size <= 300 && steps.map { it.number }.distinct().size == steps.size) {
            "이 페이지에서 조리 단계를 정확히 읽지 못했어요. 원문을 확인해주세요."
        }
        val domIngredients = document.select("#divConfirmedMaterialArea li").take(300).mapNotNull { element ->
            val name = normalize(element.selectFirst(".ingre_list_name")?.text())
            if (name.isBlank()) null else Ingredient(name, normalize(element.selectFirst(".ingre_list_ea")?.text()))
        }
        val structuredIngredients = buildList { collectIngredients(recipeNode?.get("recipeIngredient"), this) }
            .map(::parseIngredient)
        val title = normalize(text(recipeNode?.get("name"))).ifBlank {
            normalize(document.selectFirst(".view2_summary h3")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content"))
        }
        require(title.isNotBlank()) { "레시피 제목을 읽지 못했어요." }
        return Recipe(
            id = LocalCookingStore.recipeId(canonical), sourceUrl = canonical, title = title,
            baseServings = normalize(text(recipeNode?.get("recipeYield"))).ifBlank {
                normalize(document.selectFirst(".view2_summary_info1")?.text()).ifBlank { "인분 미상" }
            },
            ingredients = domIngredients.ifEmpty { structuredIngredients }, steps = steps,
            imageUrl = image(recipeNode?.get("image"), canonical)
                ?: verifiedImage(document.selectFirst("meta[property=og:image]")?.attr("content"), canonical),
            video = embeddedVideo(document),
            parserVersion = VERSION,
        )
    }

    /** Same rule as the server parser: the page's recipe video iframe keeps its real address in org_src. */
    private fun embeddedVideo(document: Document): RecipeVideo? {
        val frame = document.selectFirst("iframe#ifrmRecipeVideo") ?: return null
        val address = listOf("org_src", "src").firstNotNullOfOrNull { name ->
            val value = frame.attr(name).trim()
            if (value.isEmpty() || value.equals("about:blank", ignoreCase = true)) null
            else frame.absUrl(name).takeIf(String::isNotBlank)
        } ?: return null
        RecipeUrlParser.youTubeEmbedVideoId(address)?.let { id ->
            return RecipeVideo(VideoProvider.YOUTUBE, id, "https://www.youtube.com/watch?v=$id", embeddable = true)
        }
        return naverTvVideoId(address)?.let { RecipeVideo(VideoProvider.NAVER_TV, it, "https://tv.naver.com/v/$it") }
    }

    private fun naverTvVideoId(address: String): String? {
        val uri = runCatching { URI(address) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.lowercase() != "tv.naver.com" ||
            uri.rawUserInfo != null || uri.port != -1) return null
        return Regex("^/embed/([0-9]{1,20})/?$").matchEntire(uri.rawPath ?: return null)?.groupValues?.get(1)
    }

    private fun findRecipe(value: JsonElement, depth: Int = 0): JsonObject? {
        if (depth > 24) return null
        if (value is JsonObject && types(value["@type"]).any { it.equals("Recipe", true) }) return value
        val children = when (value) { is JsonObject -> value.values; is JsonArray -> value; else -> emptyList() }
        return children.firstNotNullOfOrNull { findRecipe(it, depth + 1) }
    }

    private fun types(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonArray -> value.flatMap(::types)
        else -> emptyList()
    }

    private fun collectSteps(value: JsonElement?, base: String, result: MutableList<Pair<String, String?>>, depth: Int = 0) {
        if (depth > 24 || result.size > 300) return
        when (value) {
            is JsonArray -> value.forEach { collectSteps(it, base, result, depth + 1) }
            is JsonPrimitive -> normalize(value.contentOrNull).takeIf { it.isNotBlank() }?.let { result.add(it to null) }
            is JsonObject -> {
                val children = listOf("itemListElement", "steps", "recipeInstructions").firstNotNullOfOrNull { value[it] }
                if (children != null) collectSteps(children, base, result, depth + 1)
                else normalize(text(value["text"]) ?: text(value["name"])).takeIf { it.isNotBlank() }
                    ?.let { result.add(it to image(value["image"], base)) }
            }
            else -> Unit
        }
    }

    private fun collectIngredients(value: JsonElement?, result: MutableList<String>, depth: Int = 0) {
        if (depth > 24 || result.size >= 300) return
        when (value) {
            is JsonArray -> value.take(300).forEach { collectIngredients(it, result, depth + 1) }
            is JsonPrimitive -> normalize(value.contentOrNull).takeIf { it.isNotBlank() }?.let(result::add)
            is JsonObject -> text(value["name"])?.let { result.add("$it ${text(value["amount"]) ?: text(value["requiredQuantity"]).orEmpty()}".trim()) }
            else -> Unit
        }
    }

    private fun parseIngredient(raw: String): Ingredient {
        val match = Regex("^(.+?)\\s+((?:약\\s*)?(?:[0-9].*|약간|적당량|조금|취향껏|필요량))$").matchEntire(raw)
        return if (match == null) Ingredient(raw, "") else Ingredient(match.groupValues[1], match.groupValues[2])
    }

    private fun text(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.firstNotNullOfOrNull(::text)
        else -> null
    }

    private fun image(value: JsonElement?, base: String): String? = when (value) {
        is JsonPrimitive -> verifiedImage(value.contentOrNull, base)
        is JsonArray -> value.firstNotNullOfOrNull { image(it, base) }
        is JsonObject -> image(value["url"] ?: value["contentUrl"], base)
        else -> null
    }

    private fun normalize(value: String?): String = Jsoup.parseBodyFragment(value.orEmpty()).text()
        .replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    companion object {
        /** 2 reads the page's recipe video; pages saved by version 1 (stored as 0) have none. */
        const val VERSION = 2

        fun verifiedImage(value: String?, base: String): String? {
            if (value.isNullOrBlank()) return null
            val uri = runCatching { URI(base).resolve(value.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
            if (host != "10000recipe.com" && !host.endsWith(".10000recipe.com") &&
                host != "ezmember.co.kr" && !host.endsWith(".ezmember.co.kr")) return null
            return uri.toASCIIString()
        }
    }
}
