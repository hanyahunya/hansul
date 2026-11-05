package com.hansul.app.data.local

import com.hansul.app.data.RecipeRepository
import com.hansul.app.data.video.VideoImporter
import com.hansul.app.model.Recipe
import com.hansul.app.model.ScaledIngredient
import com.hansul.app.model.ScaledIngredients
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.share.RecipeSourceKind
import com.hansul.app.share.RecipeUrlParser
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class OfflineRecipeRepository(
    private val store: LocalRecipeStore,
    private val pendingRecipeStore: PendingRecipeUrls,
    private val client: OkHttpClient,
    private val videoImporter: VideoImporter,
    private val parser: LocalRecipeParser = LocalRecipeParser(),
) : RecipeRepository {
    override val pendingRecipeUrl: Flow<String?> = pendingRecipeStore.pendingUrl
    override val importProgress: StateFlow<VideoImportProgress?> = videoImporter.progress
    // Opening a saved recipe must not wait long on a bad network just to look for its video.
    private val refreshClient by lazy { client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    override suspend fun rememberRecipeUrl(url: String) = pendingRecipeStore.save(url)
    override suspend fun forgetRecipeUrl() = pendingRecipeStore.clear()
    override suspend fun importFailureCode(url: String) = pendingRecipeStore.failure(url)
    override suspend fun rememberImportFailure(url: String, code: String?) = pendingRecipeStore.rememberFailure(url, code)

    override suspend fun importRecipe(url: String): Recipe = withContext(Dispatchers.IO) {
        videoSource(url)?.let { return@withContext importVideo(it) }
        val canonical = requireNotNull(RecipeUrlParser.extract(url)) { "만개의레시피 공유 주소를 확인해주세요." }
        val id = LocalCookingStore.recipeId(canonical)
        store.findRecipe(id)?.let { return@withContext store.withCachedImages(withPageVideo(it, canonical)) }
        val html = fetchSource(canonical)
        val recipe = parser.parse(html, canonical)
        // Save text first. A failed photo download must not make the imported recipe disappear.
        store.saveRecipe(recipe)
        cachePhotos(recipe)
        store.withCachedImages(recipe)
    }

    /**
     * A page saved before the parser read recipe videos gets its video once, from the page as it is now. Only the
     * video is taken over, so steps a cooking session points at stay as saved; offline the saved copy opens as it is.
     */
    private suspend fun withPageVideo(saved: Recipe, canonical: String): Recipe {
        if (saved.parserVersion >= LocalRecipeParser.VERSION) return saved
        return try {
            val video = parser.parse(fetchSource(canonical, refreshClient), canonical).video
            saved.copy(video = video, parserVersion = LocalRecipeParser.VERSION).also { store.saveRecipe(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            saved
        }
    }

    /** Only a video that has not been analysed yet needs the server; afterwards the device copy is used. */
    override suspend fun needsServer(url: String): Boolean {
        val source = videoSource(url) ?: return false
        // The UI asks this while deciding what to show; an unreadable copy cannot be served offline either.
        return try {
            store.findRecipe(LocalCookingStore.recipeId(source)) == null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            true
        }
    }

    // The server writes the recipe once; the device keeps its own copy so cooking, timers and the
    // local agent keep working offline exactly as for 10000recipe pages.
    private suspend fun importVideo(source: String): Recipe {
        val id = LocalCookingStore.recipeId(source)
        store.findRecipe(id)?.let { return store.withCachedImages(it) }
        val analysed = if (RecipeUrlParser.kind(source) == RecipeSourceKind.YOUTUBE) {
            videoImporter.importYouTube(source)
        } else {
            videoImporter.importLocalVideo(source)
        }
        val recipe = analysed.copy(id = id, sourceUrl = source)
        store.saveRecipe(recipe)
        cachePhotos(recipe)
        return store.withCachedImages(recipe)
    }

    /** Canonical YouTube watch URL or local video pseudo URL; null for anything the server does not analyse. */
    private fun videoSource(url: String): String? = when (RecipeUrlParser.kind(url)) {
        RecipeSourceKind.LOCAL_VIDEO -> url
        RecipeSourceKind.YOUTUBE -> RecipeUrlParser.extract(url)
        else -> null
    }

    override suspend fun getScaledIngredients(recipeId: String, servings: Int): ScaledIngredients {
        require(servings in 1..99) { "인분은 1~99 사이로 정해주세요." }
        val recipe = requireNotNull(store.findRecipe(recipeId)) { "기기에 저장한 레시피를 찾지 못했어요." }
        return LocalServingGuide.scale(recipe, servings)
    }

    private fun fetchSource(initialUrl: String, http: OkHttpClient = client): String {
        var url = initialUrl
        repeat(4) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Hansul-Android/0.2 recipe-import")
                .header("Accept", "text/html").build()).execute().use { response ->
                if (response.isRedirect) {
                    val location = response.header("Location") ?: error("레시피 이동 주소가 없어요.")
                    val resolved = response.request.url.resolve(location) ?: error("레시피 이동 주소가 잘못됐어요.")
                    require(resolved.isHttps && resolved.port == 443 && resolved.username.isEmpty() && resolved.password.isEmpty()) {
                        "안전하지 않은 원문 이동 주소라 읽기를 중단했어요."
                    }
                    val normalized = RecipeUrlParser.extract(resolved.toString()) ?: error("원문 사이트 밖으로 이동해 읽기를 중단했어요.")
                    // Keep mobile/desktop redirects, but never forward authentication or allow another recipe.
                    require(normalized == initialUrl) { "다른 레시피로 이동해 읽기를 중단했어요." }
                    url = resolved.toString()
                } else {
                    check(response.isSuccessful) { "원문 사이트에서 레시피를 읽지 못했어요 (HTTP ${response.code})." }
                    val type = response.body.contentType()?.toString().orEmpty()
                    require(type.contains("html", true)) { "원문 페이지 형식이 HTML이 아니에요." }
                    val bytes = response.body.byteStream().use { it.readNBytesBounded(MAX_HTML_BYTES) }
                    return bytes.toString(response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                }
            }
        }
        error("원문 사이트의 이동이 반복되어 레시피를 가져오지 못했어요.")
    }

    private suspend fun cachePhotos(recipe: Recipe) = coroutineScope {
        val semaphore = Semaphore(3)
        // No background URL mutation after returning: displayed and agent photo URLs remain identical.
        val finishByNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // Video scene photos are already local files; only web photos (and video thumbnails) are downloaded.
        val urls = (listOfNotNull(recipe.imageUrl) + recipe.steps.mapNotNull { it.imageUrl })
            .filter { it.startsWith("https://") }.distinct().take(100)
        urls.map { sourceUrl ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(finishByNanos - System.nanoTime())
                        if (remainingMillis <= 0) return@runCatching
                        val target = store.imageFile(sourceUrl)
                        if (target.isFile && target.length() > 0) return@runCatching
                        if (store.imageDirectory.listFiles().orEmpty().sumOf(File::length) > MAX_IMAGE_CACHE_BYTES) return@runCatching
                        val request = Request.Builder().url(sourceUrl).build()
                        val photoClient = client.newBuilder().callTimeout(remainingMillis.coerceAtMost(5_000), TimeUnit.MILLISECONDS)
                            .readTimeout(5, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()
                        photoClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful || !response.body.contentType().toString().startsWith("image/")) return@use
                            val bytes = response.body.byteStream().use { it.readNBytesBounded(MAX_IMAGE_BYTES) }
                            val temporary = File(target.parentFile, "${target.name}.part")
                            temporary.outputStream().use { it.write(bytes) }
                            if (!temporary.renameTo(target)) temporary.delete()
                        }
                    }
                }
            }
        }.awaitAll()
    }

    private fun java.io.InputStream.readNBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= max) { "원문 파일이 너무 커서 읽기를 중단했어요." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_HTML_BYTES = 6 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
        private const val MAX_IMAGE_CACHE_BYTES = 256L * 1024 * 1024
    }
}

/** A multiplication is labelled a reference, not a proven cooking amount. */
object LocalServingGuide {
    private val amount = Regex("^((?:[0-9]+\\s+)?[0-9]+\\s*/\\s*[0-9]+|[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z가-힣]+)$")

    fun scale(recipe: Recipe, targetServings: Int): ScaledIngredients {
        require(targetServings in 1..99)
        val baseMatch = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(?:인분|명|servings?)?$", RegexOption.IGNORE_CASE)
            .matchEntire(recipe.baseServings.trim())
        val base = baseMatch?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val target = targetServings.toBigDecimal()
        val unchanged = base != null && target.compareTo(base) == 0
        val rows = recipe.ingredients.map { ingredient ->
            val match = amount.matchEntire(ingredient.quantityText.trim())
            val quantity = match?.groupValues?.get(1)?.let(::quantity)
            val status = when {
                unchanged -> "SOURCE"
                base == null || quantity == null -> "UNSCALABLE"
                else -> "LINEAR_REFERENCE"
            }
            val display = when (status) {
                "SOURCE" -> ingredient.quantityText
                "LINEAR_REFERENCE" -> quantity!!.multiply(target).divide(base!!, 2, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + match!!.groupValues[2] + " (비례 참고)"
                else -> ingredient.quantityText.ifBlank { "원문에서 수량을 확인해주세요" }
            }
            ScaledIngredient(ingredient.name, ingredient.quantityText, display, null, status)
        }
        return ScaledIngredients(recipe.id, recipe.title, recipe.baseServings, targetServings.toString(), rows,
            if (unchanged) emptyList() else listOf(
                "비례 참고값은 조리 정량이 아니에요. 물·양념·조리 시간은 냄비, 증발량, 식재료와 제품 설명에 따라 달라집니다.",
                "원문에 인분별 지침이 없으면 라면 물의 양처럼 정확한 새 정량을 확정할 수 없어요."
            ))
    }

    private fun quantity(value: String): BigDecimal? = runCatching {
        if ('/' !in value) value.toBigDecimal() else {
            val match = requireNotNull(Regex("^(?:([0-9]+)\\s+)?([0-9]+)\\s*/\\s*([0-9]+)$").matchEntire(value.trim()))
            val whole = match.groupValues[1].toBigDecimalOrNull() ?: BigDecimal.ZERO
            whole.add(match.groupValues[2].toBigDecimal().divide(match.groupValues[3].toBigDecimal(), 6, RoundingMode.HALF_UP))
        }
    }.getOrNull()
}
