package com.hansul.app.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class LocalAiStatus { NOT_CONFIGURED, IMPORTING, DOWNLOADING, AVAILABLE, LOADING, READY, GENERATING, ERROR }

data class LocalAiState(
    val status: LocalAiStatus = LocalAiStatus.NOT_CONFIGURED,
    val message: String = "로컬 AI 모델이 없어요. 사진과 단계를 보며 수동으로 요리할 수 있어요.",
    val modelName: String? = null,
    val progress: Float? = null,
    val backend: String? = null,
)

data class LocalAiDiagnostics(
    val loadMillis: Long? = null,
    val inferenceMillis: Long? = null,
    val peakRssKb: Long? = null,
    val constrainedDecoding: Boolean? = null,
)

/** Model acquisition only happens through an explicit user action. No startup download. */
class LocalModelManager(context: Context) {
    private val context = context.applicationContext
    private val modelDirectory = File(context.noBackupFilesDir, "hansul-models").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("local-ai-model", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hansul-local-inference").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val downloadClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()
    private var engine: Engine? = null
    private var engineFile: String? = null
    private var engineBackend: String? = null
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<LocalAiState> = _state.asStateFlow()
    private val _diagnostics = MutableStateFlow(LocalAiDiagnostics())
    val diagnostics: StateFlow<LocalAiDiagnostics> = _diagnostics.asStateFlow()

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            acquire(LocalAiStatus.IMPORTING, "선택한 모델을 복사하고 무결성을 확인하고 있어요.") {
                val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                }
                if (size != null) require(artifacts.any { it.bytes == size }) {
                    "공식 Gemma 4 E2B LiteRT-LM 범용 또는 GPU 모델 파일을 선택해주세요."
                }
                context.contentResolver.openInputStream(uri)?.use { copyAndVerify(it, size) }
                    ?: error("선택한 모델 파일을 열 수 없어요.")
            }
        }
    }

    /** Explicit download button only. About 2.59 GB; never called by importRecipe/startCooking. */
    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            acquire(LocalAiStatus.DOWNLOADING, "Gemma 4 E2B 2.59 GB를 다운로드하고 있어요.") {
                val artifact = artifacts.first()
                val url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REVISION/${artifact.fileName}"
                downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "모델 다운로드가 실패했어요 (HTTP ${response.code})." }
                    val length = response.body.contentLength().takeIf { it >= 0 }
                    require(length == null || length == artifact.bytes) { "모델 다운로드 파일 크기가 예상과 달라요." }
                    response.body.byteStream().use { copyAndVerify(it, artifact.bytes) }
                }
            }
        }
    }

    suspend fun removeModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeNativeEngine()
            // Only the known model files managed by this class are removed, never a user-selected URI.
            artifacts.forEach { artifact ->
                val file = File(modelDirectory, artifact.fileName)
                if (file.exists() && !file.delete()) throw IOException("로컬 모델을 삭제하지 못했어요.")
            }
            val partial = File(modelDirectory, "import.part")
            if (partial.exists() && !partial.delete()) throw IOException("미완료 모델 파일을 삭제하지 못했어요.")
            check(preferences.edit().remove("selected").commit()) { "모델 설정을 저장하지 못했어요." }
            _state.value = LocalAiState()
        }
    }

    internal suspend fun generateJson(systemInstruction: String, prompt: String, schema: String): String =
        mutex.withLock {
            withContext(nativeDispatcher) {
                try {
                    val file = selectedModel() ?: throw LocalAiUnavailableException(
                        "로컬 AI 모델이 설정되지 않았어요. 모델을 가져오거나 다운로드한 뒤 AI에게 물어볼 수 있어요. 수동 조리는 계속 가능합니다."
                    )
                    val readyEngine = ensureEngine(file)
                    _state.value = LocalAiState(LocalAiStatus.GENERATING, "기기에서 답변을 생각하고 있어요.", file.name, backend = engineBackend)
                    fun conversationConfig(constrained: Boolean) = ConversationConfig(
                        systemInstruction = Contents.of(systemInstruction),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2),
                        automaticToolCalling = false,
                        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                        maxOutputToken = 1024,
                        enableResponseFormat = constrained,
                    )
                    val started = SystemClock.elapsedRealtime()
                    var constrained = true
                    val response = try {
                        readyEngine.createConversation(conversationConfig(true)).use { conversation ->
                            conversation.sendMessage(prompt, responseFormat = ResponseFormat.json(schema)).toString()
                        }
                    } catch (error: Exception) {
                        if (!Regex("constraint|grammar|response.format|llguidance|constrained", RegexOption.IGNORE_CASE)
                                .containsMatchIn(error.message.orEmpty())) throw error
                        constrained = false
                        // Only decoder compatibility errors use a plain JSON attempt. Validation still applies.
                        readyEngine.createConversation(conversationConfig(false)).use { conversation ->
                            conversation.sendMessage("$prompt\n출력 JSON schema: $schema").toString()
                        }
                    }
                    _diagnostics.value = _diagnostics.value.copy(inferenceMillis = SystemClock.elapsedRealtime() - started,
                        peakRssKb = peakRssKb(), constrainedDecoding = constrained)
                    _state.value = LocalAiState(LocalAiStatus.READY, "로컬 AI가 준비됐어요. 응답은 이 기기에서 처리합니다.", file.name, backend = engineBackend)
                    response
                } catch (error: CancellationException) {
                    _state.value = initialState()
                    throw error
                } catch (error: Exception) {
                    _state.value = LocalAiState(LocalAiStatus.ERROR, error.message ?: "로컬 AI 실행을 실패했어요. 수동 조리는 계속 가능합니다.")
                    throw error
                } catch (error: LinkageError) {
                    _state.value = LocalAiState(LocalAiStatus.ERROR, "이 기기에서 로컬 AI 실행 라이브러리를 불러오지 못했어요. 수동 조리는 가능합니다.")
                    throw LocalAiUnavailableException(_state.value.message, error)
                }
            }
        }

    private suspend fun acquire(status: LocalAiStatus, message: String, operation: suspend () -> Unit) {
        _state.value = LocalAiState(status, message, progress = 0f)
        try {
            operation()
            _state.value = initialState()
        } catch (error: CancellationException) {
            File(modelDirectory, "import.part").delete()
            _state.value = initialState()
            throw error
        } catch (error: Exception) {
            File(modelDirectory, "import.part").delete()
            _state.value = LocalAiState(LocalAiStatus.ERROR, error.message ?: "모델을 가져오지 못했어요.")
            throw error
        }
    }

    private suspend fun copyAndVerify(input: InputStream, expectedBytes: Long?) {
        val requiredSpace = (expectedBytes ?: artifacts.first().bytes) + 300L * 1024 * 1024
        require(modelDirectory.usableSpace >= requiredSpace) { "모델을 저장할 공간이 부족해요. 약 3 GB 이상을 비워주세요." }
        val temporary = File(modelDirectory, "import.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var lastUpdate = 0L
        temporary.outputStream().use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= artifacts.maxOf { it.bytes }) { "모델 파일이 예상보다 커서 가져오기를 중단했어요." }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 250) {
                    lastUpdate = now
                    _state.value = _state.value.copy(progress = expectedBytes?.let { (copied.toDouble() / it).toFloat().coerceIn(0f, 1f) })
                }
            }
            output.fd.sync()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val artifact = artifacts.singleOrNull { it.bytes == copied && it.sha256 == sha }
            ?: error("모델 크기·SHA-256 검증에 실패했어요. 공식 Gemma 4 E2B 파일인지 확인해주세요.")
        closeNativeEngine()
        val destination = File(modelDirectory, artifact.fileName)
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        check(preferences.edit().putString("selected", artifact.fileName).commit()) { "모델 선택을 저장하지 못했어요." }
    }

    private fun selectedModel(): File? {
        val name = preferences.getString("selected", null) ?: return null
        val artifact = artifacts.singleOrNull { it.fileName == name } ?: return null
        return File(modelDirectory, name).takeIf { it.isFile && it.length() == artifact.bytes }
    }

    private fun initialState(): LocalAiState = selectedModel()?.let {
        LocalAiState(LocalAiStatus.AVAILABLE, "Gemma 4 E2B가 저장됐어요. 처음 질문할 때 모델을 불러옵니다.", it.name)
    } ?: LocalAiState()

    private fun ensureEngine(file: File): Engine {
        if (engineFile == file.path) engine?.let { return it }
        engine?.close()
        engine = null
        _state.value = LocalAiState(LocalAiStatus.LOADING, "로컬 모델을 불러오고 있어요. 첫 실행은 시간이 걸릴 수 있어요.", file.name)
        fun create(backend: Backend): Engine = Engine(EngineConfig(
            modelPath = file.path, backend = backend, maxNumTokens = 8192,
            cacheDir = File(context.cacheDir, "litert-lm").apply { mkdirs() }.path,
        )).also { candidate ->
            try { candidate.initialize() } catch (error: Exception) { candidate.close(); throw error }
        }
        val started = SystemClock.elapsedRealtime()
        val candidate = try {
            create(Backend.GPU()).also { engineBackend = "GPU" }
        } catch (error: Exception) {
            // The known GPU-only artifact cannot be assumed to support CPU fallback.
            if (file.name.contains("-gpu")) throw LocalAiUnavailableException(
                "이 기기에서 GPU 모델을 실행하지 못했어요. 범용 Gemma 4 E2B 모델로 다시 시도해주세요.", error)
            create(Backend.CPU()).also { engineBackend = "CPU" }
        }
        engineFile = file.path
        engine = candidate
        _diagnostics.value = LocalAiDiagnostics(loadMillis = SystemClock.elapsedRealtime() - started, peakRssKb = peakRssKb())
        return candidate
    }

    private fun peakRssKb(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }?.let { Regex("[0-9]+").find(it)?.value?.toLongOrNull() }
        }
    }.getOrNull()

    private suspend fun closeNativeEngine() = withContext(nativeDispatcher) {
        engine?.close()
        engine = null
        engineFile = null
        engineBackend = null
    }

    private data class Artifact(val fileName: String, val bytes: Long, val sha256: String)

    companion object {
        const val MODEL_DOWNLOAD_BYTES = 2_588_147_712L
        const val MODEL_LICENSE_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        private const val REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
        private val artifacts = listOf(
            Artifact("gemma-4-E2B-it.litertlm", MODEL_DOWNLOAD_BYTES,
                "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
            Artifact("gemma-4-E2B-it-gpu.litertlm", 2_008_432_640L,
                "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"),
        )
    }
}

class LocalAiUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
