package com.hansul.app.data.local

import android.content.Context
import android.util.AtomicFile
import com.hansul.app.model.CookingNavigation
import com.hansul.app.model.CookingSession
import com.hansul.app.model.CookingTurn
import com.hansul.app.model.Recipe
import com.hansul.app.model.TimerAcknowledgement
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LocalSessionSnapshot(
    val recipeId: String,
    val targetServings: Int,
    val session: CookingSession,
    val offeredTimerCueIds: Set<String> = emptySet(),
    val requestedTimerCueIds: Set<String> = emptySet(),
    val turns: Map<String, CookingTurn> = emptyMap(),
    val turnInputs: Map<String, String> = emptyMap(),
    val acknowledgements: Map<String, TimerAcknowledgement> = emptyMap(),
    val navigationCommands: Map<String, CookingNavigation> = emptyMap(),
    val commandPayloads: Map<String, String> = emptyMap(),
)

/** The recipe half of [LocalCookingStore]: all the offline recipe repository needs. */
interface LocalRecipeStore {
    val imageDirectory: File

    suspend fun saveRecipe(recipe: Recipe)

    suspend fun findRecipe(id: String): Recipe?

    fun imageFile(sourceUrl: String): File

    fun withCachedImages(recipe: Recipe): Recipe
}

/** Full snapshots use AtomicFile so a killed process cannot silently leave half a session. */
class LocalCookingStore(context: Context) : LocalRecipeStore {
    private val directory = File(context.filesDir, "local-cooking").apply { mkdirs() }
    override val imageDirectory = File(directory, "images").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    override suspend fun saveRecipe(recipe: Recipe) = withContext(Dispatchers.IO) {
        mutex.withLock { write(File(directory, "recipe-${safeId(recipe.id)}.json"), json.encodeToString(recipe)) }
    }

    override suspend fun findRecipe(id: String): Recipe? = withContext(Dispatchers.IO) {
        mutex.withLock {
            read(File(directory, "recipe-${safeId(id)}.json"))?.let { json.decodeFromString<Recipe>(it) }
        }
    }

    internal suspend fun findSession(id: String): LocalSessionSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            read(File(directory, "session-${safeId(id)}.json"))?.let { json.decodeFromString<LocalSessionSnapshot>(it) }
        }
    }

    internal suspend fun latestSession(recipeId: String): LocalSessionSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = read(File(directory, "latest-${safeId(recipeId)}.txt")) ?: return@withLock null
            read(File(directory, "session-${safeId(id)}.json"))?.let { json.decodeFromString<LocalSessionSnapshot>(it) }
        }
    }

    internal suspend fun saveSession(snapshot: LocalSessionSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(File(directory, "session-${safeId(snapshot.session.id)}.json"), json.encodeToString(snapshot))
            write(File(directory, "latest-${safeId(snapshot.recipeId)}.txt"), snapshot.session.id)
        }
    }

    override fun imageFile(sourceUrl: String): File = File(imageDirectory, "${digest(sourceUrl)}.image")

    override fun withCachedImages(recipe: Recipe): Recipe = recipe.copy(
        imageUrl = cachedImage(recipe.imageUrl),
        steps = recipe.steps.map { it.copy(imageUrl = cachedImage(it.imageUrl)) },
    )

    private fun cachedImage(sourceUrl: String?): String? = sourceUrl?.let { url ->
        imageFile(url).takeIf { it.isFile && it.length() > 0 }?.toURI()?.toString() ?: url
    }

    private fun safeId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "올바르지 않은 로컬 저장 ID입니다." }
        return value
    }

    private fun read(file: File): String? {
        val atomic = AtomicFile(file)
        if (!atomic.baseFile.exists() && !File("${file.path}.bak").exists()) return null
        require(file.length() <= MAX_SNAPSHOT_BYTES) { "로컬 조리 기록이 너무 큽니다." }
        return atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun write(file: File, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "로컬 조리 기록이 너무 큽니다." }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
        fun recipeId(url: String): String = "local-recipe-${digest(url)}"
        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
