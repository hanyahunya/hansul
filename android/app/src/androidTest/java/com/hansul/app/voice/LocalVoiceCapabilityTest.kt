package com.hansul.app.voice

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only device capabilities, not recognition or playback accuracy tests.
 *
 * Never records audio, speaks, requests permissions, or starts a model download. Missing support,
 * an engine error, and a timeout are useful capability results rather than test assertion failures.
 */
@RunWith(AndroidJUnit4::class)
class LocalVoiceCapabilityTest {
    @Test
    fun reportsKoreanOnDeviceRecognitionWithoutRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val completed = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        var recognizer: SpeechRecognizer? = null

        try {
            instrumentation.runOnMainSync {
                try {
                    Log.i(TAG, "STT device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}; audioCapture=false")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Log.i(TAG, "STT onDeviceRecognizer=API_UNSUPPORTED; koreanInstalled=UNKNOWN")
                        completed.countDown()
                        return@runOnMainSync
                    }

                    val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    Log.i(TAG, "STT onDeviceRecognizerAvailable=$available")
                    if (!available) {
                        Log.i(TAG, "STT recognitionSupport=NO_ON_DEVICE_SERVICE; koreanInstalled=UNKNOWN")
                        completed.countDown()
                        return@runOnMainSync
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Log.i(TAG, "STT recognitionSupport=API_UNSUPPORTED; koreanInstalled=UNKNOWN")
                        completed.countDown()
                        return@runOnMainSync
                    }

                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    // This asks the service for metadata only; it does not start listening.
                    recognizer!!.checkRecognitionSupport(request, context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (closed.get()) return
                            try {
                                val installed = support.installedOnDeviceLanguages.sorted()
                                val pending = support.pendingOnDeviceLanguages.sorted()
                                val downloadable = support.supportedOnDeviceLanguages.sorted()
                                val online = support.onlineLanguages.sorted()
                                Log.i(TAG, "STT recognitionSupport=REPORTED; installed=$installed; pending=$pending; downloadable=$downloadable; online=$online")
                                Log.i(TAG, "STT koreanInstalled=${installed.any(::isKoreanLanguageTag)}; koreanPending=${pending.any(::isKoreanLanguageTag)}; koreanDownloadable=${downloadable.any(::isKoreanLanguageTag)}; recognitionAccuracy=NOT_TESTED")
                            } catch (exception: Exception) {
                                Log.w(TAG, "STT recognitionSupport=METADATA_ERROR; koreanInstalled=UNKNOWN", exception)
                            } finally {
                                completed.countDown()
                            }
                        }

                        override fun onError(error: Int) {
                            if (closed.get()) return
                            Log.i(TAG, "STT recognitionSupport=ERROR; errorCode=$error; koreanInstalled=UNKNOWN")
                            completed.countDown()
                        }
                    })
                } catch (exception: Exception) {
                    Log.w(TAG, "STT recognitionSupport=QUERY_ERROR; koreanInstalled=UNKNOWN", exception)
                    completed.countDown()
                }
            }

            if (!completed.await(CAPABILITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.i(TAG, "STT recognitionSupport=TIMEOUT; koreanInstalled=UNKNOWN; timeoutSeconds=$CAPABILITY_TIMEOUT_SECONDS")
            }
        } finally {
            closed.set(true)
            instrumentation.runOnMainSync {
                try {
                    recognizer?.destroy()
                } catch (exception: Exception) {
                    Log.w(TAG, "STT recognizerCleanup=ERROR", exception)
                }
            }
        }
    }

    @Test
    fun reportsKoreanOfflineTtsMetadataWithoutSpeaking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val initialized = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        var tts: TextToSpeech? = null

        try {
            instrumentation.runOnMainSync {
                try {
                    tts = TextToSpeech(context) { status ->
                        initStatus.set(status)
                        initialized.countDown()
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "TTS initialization=CONSTRUCTOR_ERROR; offlineKoreanVoice=UNKNOWN", exception)
                    initialized.countDown()
                }
            }

            if (!initialized.await(CAPABILITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.i(TAG, "TTS initialization=TIMEOUT; offlineKoreanVoice=UNKNOWN; timeoutSeconds=$CAPABILITY_TIMEOUT_SECONDS")
                return
            }
            if (initStatus.get() != TextToSpeech.SUCCESS) {
                Log.i(TAG, "TTS initialization=ERROR; status=${initStatus.get()}; offlineKoreanVoice=UNKNOWN")
                return
            }

            instrumentation.runOnMainSync {
                try {
                    val engine = tts ?: return@runOnMainSync
                    // setLanguage/setVoice may download data, so inspect metadata without selecting.
                    val koreanAvailability = engine.isLanguageAvailable(Locale.KOREAN)
                    val koreaAvailability = engine.isLanguageAvailable(Locale.KOREA)
                    val koreanVoices = engine.voices.orEmpty()
                        .filter { it.locale.language == "ko" }
                        .sortedBy { it.name }
                    val offlineCandidates = koreanVoices.filter { !it.isNetworkConnectionRequired }
                    val installedOfflineCandidates = offlineCandidates.filter {
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                    }
                    Log.i(TAG, "TTS initialization=SUCCESS; engine=${engine.defaultEngine}; koAvailability=$koreanAvailability; koKrAvailability=$koreaAvailability")
                    koreanVoices.forEach { voice ->
                        Log.i(TAG, "TTS voice=${voice.name}; locale=${voice.locale.toLanguageTag()}; networkRequired=${voice.isNetworkConnectionRequired}; notInstalled=${TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty()}")
                    }
                    Log.i(TAG, "TTS offlineKoreanCandidates=${offlineCandidates.size}; installedOfflineKoreanCandidates=${installedOfflineCandidates.size}; offlineKoreanVoiceReported=${installedOfflineCandidates.isNotEmpty()}; playback=NOT_TESTED; downloadRequested=false")
                } catch (exception: Exception) {
                    Log.w(TAG, "TTS metadata=QUERY_ERROR; offlineKoreanVoice=UNKNOWN", exception)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                try {
                    tts?.shutdown()
                } catch (exception: Exception) {
                    Log.w(TAG, "TTS engineCleanup=ERROR", exception)
                }
            }
        }
    }

    private fun isKoreanLanguageTag(tag: String): Boolean =
        Locale.forLanguageTag(tag.replace('_', '-')).language == "ko"

    private companion object {
        const val TAG = "HansulDeviceCapability"
        const val CAPABILITY_TIMEOUT_SECONDS = 8L
    }
}
