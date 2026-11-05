package com.hansul.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceCapturePhase {
    OFF,
    READY,
    LISTENING,
    PROCESSING,
    SPEAKING,
    PAUSED_BACKGROUND,
    ERROR,
}

data class VoiceCaptureState(
    val phase: VoiceCapturePhase = VoiceCapturePhase.OFF,
    val message: String = "음성 요리가 꺼져 있어요.",
)

/**
 * Foreground-only speech loop. Android's recognizer is utterance based, so this controller
 * explicitly restarts it after each result, timeout, and TTS completion.
 */
class VoiceCookingController(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val requireOnDevice: Boolean = false,
) : RecognitionListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, requireOnDevice)
    }
    private val recognizer: SpeechRecognizer? = when {
        requireOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) ->
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        requireOnDevice -> null
        SpeechRecognizer.isRecognitionAvailable(appContext) -> SpeechRecognizer.createSpeechRecognizer(appContext)
        else -> null
    }?.also { it.setRecognitionListener(this) }
    private val textToSpeech: TextToSpeech
    private var ttsReady = false
    private var enabled = false
    private var foreground = true
    private var waitingForTurn = false
    private var listening = false
    private var speaking = false
    private var suppressNextRecognizerError = false
    private var pendingSpeech: String? = null

    private val _state = MutableStateFlow(VoiceCaptureState())
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    if (requireOnDevice) {
                        val localVoice = textToSpeech.voices.orEmpty().filter {
                            it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired &&
                                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                        }.maxByOrNull { it.quality }
                        ttsReady = localVoice != null && textToSpeech.setVoice(localVoice) == TextToSpeech.SUCCESS
                    } else {
                        val languageResult = textToSpeech.setLanguage(Locale.KOREAN)
                        ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                    }
                }
                if (!ttsReady) {
                    pendingSpeech = null
                    onError(if (requireOnDevice) "한국어 기기 내 TTS가 필요해요. 기기의 음성 합성 설정에서 한국어 음성을 준비해 주세요. 글로도 질문할 수 있어요."
                        else "한국어 음성 합성을 사용할 수 없어요. 기기의 TTS 설정을 확인해주세요.")
                    resumeListening()
                } else {
                    pendingSpeech?.let { text ->
                        pendingSpeech = null
                        speak(text)
                    }
                }
            }
        }
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        speaking = true
                        updateState(VoiceCapturePhase.SPEAKING, "답변을 읽고 있어요.")
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        speaking = false
                        waitingForTurn = false
                        resumeListening()
                    }
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    onTtsError()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onTtsError()
                }

                private fun onTtsError() {
                    mainHandler.post {
                        speaking = false
                        waitingForTurn = false
                        onError("답변을 소리 내어 읽지 못했어요.")
                        resumeListening()
                    }
                }
            },
        )
    }

    fun enable() {
        enabled = true
        waitingForTurn = false
        if (recognizer == null) {
            updateState(VoiceCapturePhase.ERROR, "이 기기에서는 음성 인식을 사용할 수 없어요.")
            onError(if (requireOnDevice) "이 기기의 오프라인 음성 인식을 사용할 수 없어요. 한국어 음성 모델을 준비하거나 글로 질문해 주세요."
                else "이 기기에서는 음성 인식을 사용할 수 없어요.")
            return
        }
        resumeListening()
    }

    fun disable() {
        enabled = false
        waitingForTurn = false
        speaking = false
        pendingSpeech = null
        textToSpeech.stop()
        cancelListening()
        updateState(VoiceCapturePhase.OFF, "음성 요리가 꺼져 있어요.")
    }

    fun onForegroundChanged(isForeground: Boolean) {
        foreground = isForeground
        if (!isForeground) {
            cancelListening()
            textToSpeech.stop()
            if (speaking) waitingForTurn = false
            speaking = false
            if (enabled) {
                updateState(
                    VoiceCapturePhase.PAUSED_BACKGROUND,
                    "앱이 화면에 보일 때만 들을 수 있어요.",
                )
            }
        } else if (enabled && !waitingForTurn) {
            pendingSpeech?.let { text ->
                pendingSpeech = null
                speak(text)
            } ?: resumeListening()
        } else if (enabled && pendingSpeech != null) {
            val text = pendingSpeech
            pendingSpeech = null
            text?.let(::speak)
        }
    }

    fun speak(text: String) {
        if (!enabled || text.isBlank()) {
            waitingForTurn = false
            resumeListening()
            return
        }
        waitingForTurn = true
        cancelListening()
        if (!foreground) {
            pendingSpeech = text
            updateState(
                VoiceCapturePhase.PAUSED_BACKGROUND,
                "화면으로 돌아오면 답변을 읽어드릴게요.",
            )
            return
        }
        if (!ttsReady) {
            pendingSpeech = text
            updateState(VoiceCapturePhase.PROCESSING, "답변 음성을 준비하고 있어요.")
            return
        }

        val result = textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UUID.randomUUID().toString(),
        )
        if (result == TextToSpeech.ERROR) {
            speaking = false
            waitingForTurn = false
            onError("답변을 소리 내어 읽지 못했어요.")
            resumeListening()
        }
    }

    fun resumeListening() {
        waitingForTurn = false
        if (!enabled || !foreground || speaking || listening) return
        val availableRecognizer = recognizer ?: return
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        runCatching {
            availableRecognizer.startListening(recognizerIntent)
            listening = true
            updateState(VoiceCapturePhase.READY, "말씀을 기다리고 있어요.")
        }.onFailure {
            updateState(VoiceCapturePhase.ERROR, "음성 인식을 시작하지 못했어요.")
            onError("음성 인식을 시작하지 못했어요. 다시 시작을 눌러주세요.")
        }
    }

    fun destroy() {
        enabled = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        textToSpeech.shutdown()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (!enabled || !foreground) return
        updateState(VoiceCapturePhase.LISTENING, "듣고 있어요…")
    }

    override fun onBeginningOfSpeech() {
        if (!enabled || !foreground) return
        updateState(VoiceCapturePhase.LISTENING, "말씀을 듣고 있어요…")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (!enabled || !foreground) return
        updateState(VoiceCapturePhase.PROCESSING, "말씀을 확인하고 있어요.")
    }

    override fun onError(error: Int) {
        listening = false
        if (suppressNextRecognizerError) {
            suppressNextRecognizerError = false
            return
        }
        if (!enabled || !foreground || waitingForTurn || speaking) return

        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> scheduleRestart(450)

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(900)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                updateState(VoiceCapturePhase.ERROR, "마이크 권한이 필요해요.")
                onError("마이크 권한이 없어 음성 요리를 시작할 수 없어요.")
            }

            else -> {
                updateState(VoiceCapturePhase.ERROR, "음성 인식 연결을 다시 시도할게요.")
                onError("음성 인식이 잠시 중단됐어요. 자동으로 다시 시도합니다.")
                scheduleRestart(1_200)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        if (!enabled || !foreground) {
            waitingForTurn = false
            return
        }
        waitingForTurn = true
        val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isBlank()) {
            waitingForTurn = false
            scheduleRestart(350)
            return
        }
        updateState(VoiceCapturePhase.PROCESSING, "“$transcript” 처리 중")
        onTranscript(transcript)
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun cancelListening() {
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        if (listening) {
            suppressNextRecognizerError = true
            recognizer?.cancel()
            mainHandler.postDelayed({ suppressNextRecognizerError = false }, 500)
        }
        listening = false
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!enabled || !foreground || waitingForTurn || speaking) return
        updateState(VoiceCapturePhase.READY, "다시 듣기를 준비하고 있어요.")
        mainHandler.postAtTime(
            { resumeListening() },
            RESTART_TOKEN,
            SystemClock.uptimeMillis() + delayMillis,
        )
    }

    private fun updateState(phase: VoiceCapturePhase, message: String) {
        _state.value = VoiceCaptureState(phase, message)
    }

    private companion object {
        val RESTART_TOKEN = Any()
    }
}
