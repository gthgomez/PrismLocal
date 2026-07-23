package com.prismai.llmhost.tools

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manages offline speech-to-text (STT) and text-to-speech (TTS).
 *
 * STT: Uses Android SpeechRecognizer with EXTRA_PREFER_OFFLINE for on-device recognition.
 * Works on API 31+ with offline language packs installed.
 *
 * TTS: Uses Android TextToSpeech, which is already offline-capable on most devices.
 */
class VoiceIoManager(private val context: Context) {
    companion object {
        private const val TAG = "VoiceIoManager"
        private const val DEFAULT_LANGUAGE = "en-US"
        private const val TTS_UTTERANCE_ID = "llmhost_tts"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private var ttsInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Callbacks — posted to main thread by listener implementations
    var onSpeechResult: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onSpeechPartialResult: ((String) -> Unit)? = null
    var onTtsDone: (() -> Unit)? = null

    /** Initialize TTS engine. Returns true if initialization succeeded or is pending. */
    fun initTts(): Boolean {
        if (ttsInitialized && tts != null) return true
        tts = TextToSpeech(context) { status ->
            val success = status == TextToSpeech.SUCCESS
            ttsInitialized = success
            if (success) {
                tts?.language = Locale.US
                Log.d(TAG, "TTS initialized, language=${tts?.language}")
            } else {
                Log.w(TAG, "TTS initialization failed: status=$status")
            }
        }
        return true
    }

    /** Start listening for speech. Returns false if SpeechRecognizer unavailable. */
    fun startListening(): Boolean {
        if (isListening) {
            Log.d(TAG, "Already listening")
            return true
        }

        val recognizer: SpeechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                ?: SpeechRecognizer.createSpeechRecognizer(context)
                ?: return false
        } else {
            SpeechRecognizer.createSpeechRecognizer(context) ?: return false
        }

        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Not used
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Not used
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing audio permission"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language pack not available"
                    else -> "Unknown error ($error)"
                }
                Log.w(TAG, "onError: $errorMessage")
                mainHandler.post { onSpeechError?.invoke(errorMessage) }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (text != null) {
                    mainHandler.post { onSpeechResult?.invoke(text) }
                } else {
                    mainHandler.post { onSpeechError?.invoke("No recognition results") }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (text != null) {
                    mainHandler.post { onSpeechPartialResult?.invoke(text) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Not used
            }
        })

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, DEFAULT_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Prefer offline recognition on API 31+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        isListening = true
        recognizer.startListening(intent)
        Log.d(TAG, "startListening")
        return true
    }

    /** Stop listening */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "stopListening")
    }

    /** Speak text aloud. Returns false if TTS unavailable. */
    fun speak(text: String): Boolean {
        val engine = tts
        if (!ttsInitialized || engine == null) {
            Log.w(TAG, "TTS not initialized")
            return false
        }

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS onStart: $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS onDone: $utteranceId")
                mainHandler.post { onTtsDone?.invoke() }
            }
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS onError: $utteranceId")
                mainHandler.post { onTtsDone?.invoke() }
            }
        }
        engine.setOnUtteranceProgressListener(listener)

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS speak returned: $result")
            return false
        }
        Log.d(TAG, "speak: ${text.take(80)}")
        return true
    }

    /** Stop any active speech output */
    fun stopSpeaking() {
        tts?.stop()
        Log.d(TAG, "stopSpeaking")
    }

    /** Check if on-device STT is available */
    fun isSttAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            SpeechRecognizer.isRecognitionAvailable(context)
        }

    /** Check if TTS engine is ready */
    fun isTtsAvailable(): Boolean = ttsInitialized && tts != null

    /** Shutdown and release resources */
    fun shutdown() {
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
        Log.d(TAG, "shutdown")
    }
}

/** Phase 4 — Voice I/O state exposed to the UI. */
data class VoiceState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val sttAvailable: Boolean = false,
    val ttsAvailable: Boolean = false,
    val partialTranscript: String? = null,
)
