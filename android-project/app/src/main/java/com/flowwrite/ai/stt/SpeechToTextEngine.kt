package com.flowwrite.ai.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.flowwrite.ai.audio.AudioCaptureManager
import com.flowwrite.ai.personalization.LanguageStyle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Section 3: On-Device Speech Recognition Interface & Hinglish/Kanglish Code-Mixed Architecture.
 *
 * CRITICAL TECHNICAL EVALUATION & LIMITATIONS FOR HINGLISH & KANGLISH (Section 3):
 * 1. Why standard Hindi (`hi-IN`) or Kannada (`kn-IN`) acoustic models fail on Hinglish/Kanglish:
 *    - When a speech recognizer is configured with `hi-IN` or `kn-IN`, its decoder vocabulary emits
 *      Devanagari (`कल कॉलेज में...`) or Kannada script (`ನಾಳೆ ಕಾಲೇಜ್...`) rather than Roman/Latin script.
 *    - Conversely, a pure US/UK English (`en-US`) acoustic model often misrecognizes Indian retroflex
 *      consonants and unaspirated stops (`barbeku`, `mujhe`, `naale`) as unrelated English words.
 * 2. Recommended Offline Engine Configuration:
 *    - Primary Android Route (`AndroidOnDeviceSttEngine`): Requests `en-IN` (Indian English) with
 *      `EXTRA_PREFER_OFFLINE = true`, which outputs Latin script and handles Indian English phonetics,
 *      followed by `PersonalDictionary` + `Qwen2.5-0.5B-Instruct` Romanized Hinglish/Kanglish cleanup.
 *    - Standalone Offline Route (`WhisperOrVoskOfflineSttEngine` via `AudioCaptureManager`):
 *      Uses 16 kHz mono PCM fed into `whisper.cpp` (`ggml-base.bin` / `ggml-small.bin`) with an initial
 *      prompt containing Romanized Hinglish/Kanglish vocabulary (`initial_prompt = "Kal college mein meeting hai, Naale college ge bega barbeku"`)
 *      so Whisper's BPE decoder outputs Latin-script transliteration instead of Devanagari/Kannada script.
 */
interface SpeechToTextEngine {
    val engineName: String
    val requiresNetworkConnection: Boolean
    fun isOfflineRecognitionSupported(): Boolean
    fun isListening(): Boolean
    suspend fun transcribeSpeech(languageStyle: LanguageStyle = LanguageStyle.AUTO_CODE_MIXED): Result<String>
    fun stopListening()
    fun release()
}

class AndroidOnDeviceSttEngine(
    private val context: Context,
    private val requireStrictOffline: Boolean = true
) : SpeechToTextEngine {

    override val engineName: String = "Android On-Device SpeechRecognizer (en-IN Latin Script)"

    override val requiresNetworkConnection: Boolean
        get() = !isOfflineRecognitionSupported() && !requireStrictOffline

    @Volatile
    private var activeListening: Boolean = false
    private var recognizer: SpeechRecognizer? = null

    override fun isOfflineRecognitionSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }
    }

    override fun isListening(): Boolean = activeListening

    override suspend fun transcribeSpeech(languageStyle: LanguageStyle): Result<String> =
        suspendCancellableCoroutine { cont ->
            val onDeviceAvailable = isOfflineRecognitionSupported()

            if (requireStrictOffline && !onDeviceAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cont.resume(
                    Result.failure(
                        IllegalStateException(
                            "Android on-device speech recognition is unavailable. Install the offline English (India) voice pack or import a local Whisper/Vosk model."
                        )
                    )
                )
                return@suspendCancellableCoroutine
            }

            val sr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = sr

            // Use en-IN (English - India) locale so the recognizer outputs Roman/Latin characters
            // while remaining acoustically tuned for Indian accents and code-mixed speech.
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    activeListening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    activeListening = false
                }

                override fun onError(error: Int) {
                    activeListening = false
                    val msg = mapSpeechRecognizerError(error)
                    if (cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException(msg)))
                    }
                    runCatching { sr.destroy() }
                }

                override fun onResults(results: Bundle?) {
                    activeListening = false
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = list?.firstOrNull()?.trim().orEmpty()
                    if (cont.isActive) {
                        if (transcript.isEmpty()) {
                            cont.resume(Result.failure(IllegalStateException("Silence or no speech detected.")))
                        } else {
                            cont.resume(Result.success(transcript))
                        }
                    }
                    runCatching { sr.destroy() }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            sr.startListening(intent)

            cont.invokeOnCancellation {
                activeListening = false
                runCatching {
                    sr.cancel()
                    sr.destroy()
                }
            }
        }

    override fun stopListening() {
        activeListening = false
        runCatching { recognizer?.stopListening() }
    }

    override fun release() {
        activeListening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun mapSpeechRecognizerError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "Client side speech recognition error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission (RECORD_AUDIO) missing."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network required by system SpeechRecognizer; install offline en-IN voice pack."
        SpeechRecognizer.ERROR_NO_MATCH -> "No recognizable speech matched."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "SpeechRecognizer is currently busy."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Silence detected: no speech heard before timeout."
        else -> "SpeechRecognizer failed with error code $code."
    }
}

/**
 * Standalone Offline PCM STT Adapter for `whisper.cpp` or `Vosk` (`vosk-model-small-en-in-0.4`).
 * Conditions the decoder with a Romanized Hinglish/Kanglish prompt so code-mixed utterances
 * are emitted in Latin characters rather than Devanagari or Kannada script.
 */
class LocalPcmSttAdapter(
    private val audioCaptureManager: AudioCaptureManager,
    private val pcmDecoder: (ShortArray, Int, String) -> Result<String>
) : SpeechToTextEngine {

    override val engineName: String = "Standalone Offline Whisper.cpp / Vosk (en-IN Romanized)"
    override val requiresNetworkConnection: Boolean = false

    override fun isOfflineRecognitionSupported(): Boolean = true
    override fun isListening(): Boolean = audioCaptureManager.isRecording()

    override suspend fun transcribeSpeech(languageStyle: LanguageStyle): Result<String> {
        val romanizedConditioningPrompt = when (languageStyle) {
            LanguageStyle.HINGLISH -> "Kal college mein meeting hai, toh mujhe time pe aana."
            LanguageStyle.KANGLISH -> "Naale college ge bega barbeku."
            else -> "Kal college mein meeting hai. Naale college ge bega barbeku."
        }
        val pcmResult = audioCaptureManager.recordPcmAudioUntilStopped()
        return pcmResult.fold(
            onSuccess = { samples ->
                if (samples.isEmpty()) {
                    Result.failure(IllegalStateException("Recorded audio buffer was empty."))
                } else {
                    pcmDecoder(samples, AudioCaptureManager.SAMPLE_RATE_HZ, romanizedConditioningPrompt)
                }
            },
            onFailure = { err -> Result.failure(err) }
        )
    }

    override fun stopListening() {
        audioCaptureManager.stopRecording()
    }

    override fun release() {
        audioCaptureManager.stopRecording()
    }
}
