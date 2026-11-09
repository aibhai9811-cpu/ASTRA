package com.astra.assistant.voice

import android.content.Context
import android.content.Intent
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

class VoiceManager(
    private val context: Context,
    private val onSpeechRecognized: (String) -> Unit,
    private val onPartialSpeech: (String) -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onStateChange: (Boolean) -> Unit = {},
    private val onEndOfSpeech: () -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onListeningStarted: () -> Unit = {}
) {
    // Aliases. Inside the RecognitionListener object, a call like onRmsChanged(x)
    // would call the listener's own method (endless loop = crash), so we use these.
    private val recognizedCallback: (String) -> Unit = onSpeechRecognized
    private val partialCallback: (String) -> Unit = onPartialSpeech
    private val rmsCallback: (Float) -> Unit = onRmsChanged
    private val endOfSpeechCallback: () -> Unit = onEndOfSpeech
    private val errorCallback: (String) -> Unit = onError

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeakingNow = false
    private var isShutDown = false
    private var consecutiveErrors = 0

    var recognitionLanguage: String = "en-IN"
    var preferOnDevice: Boolean = false
    var speechRate: Float = 1.0f
    var speechPitch: Float = 0.95f

    // When true the microphone stays on while ASTRA talks, so "ASTRA stop" can interrupt it.
    var allowBargeIn: Boolean = true

    val isSpeaking: Boolean
        get() = isSpeakingNow

    var continuousMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                mainHandler.removeCallbacks(restartRunnable)
            }
        }

    private val restartRunnable = Runnable {
        if (continuousMode && (!isSpeakingNow || allowBargeIn) && !isShutDown) {
            startListening()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            consecutiveErrors = 0
        }

        override fun onRmsChanged(rmsdB: Float) {
            rmsCallback(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            endOfSpeechCallback()
        }

        override fun onError(error: Int) {
            handleRecognitionError(error)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                consecutiveErrors = 0
                recognizedCallback(matches[0])
            }
            scheduleRestart(600)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                partialCallback(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                textToSpeech?.language = Locale("en", "IN")
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { onStateChange(true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        finishSpeaking()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finishSpeaking()
                    }
                })
            } else {
                Log.w(TAG, "Text-to-speech failed to start, status=$status")
            }
        }
    }

    private fun finishSpeaking() {
        mainHandler.post {
            isSpeakingNow = false
            onStateChange(false)
            scheduleRestart(400)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!continuousMode || isShutDown) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun handleRecognitionError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (continuousMode) {
                    scheduleRestart(300)
                } else {
                    errorCallback("I did not catch that. Tap the mic and try again.")
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                continuousMode = false
                errorCallback("Microphone permission is missing.")
            }
            else -> {
                consecutiveErrors++
                if (continuousMode && consecutiveErrors <= 5) {
                    scheduleRestart(1500)
                } else {
                    continuousMode = false
                    errorCallback("Speech recognition error (code $error). Tap the mic to try again.")
                }
            }
        }
    }

    fun startListening() {
        if (isShutDown) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            continuousMode = false
            errorCallback("Speech recognition is not available. Check that Google speech services are enabled.")
            return
        }
        mainHandler.removeCallbacks(restartRunnable)
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Could not destroy previous recognizer", e)
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (recognitionLanguage == "hinglish") "en-IN" else recognitionLanguage
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
        }
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)
        onListeningStarted()
    }

    fun stopListening() {
        continuousMode = false
        mainHandler.removeCallbacks(restartRunnable)
        speechRecognizer?.cancel()
    }

    fun speak(text: String) {
        if (!ttsReady) {
            onStateChange(false)
            scheduleRestart(300)
            return
        }
        isSpeakingNow = true
        if (!allowBargeIn) {
            mainHandler.removeCallbacks(restartRunnable)
            speechRecognizer?.cancel()
        }
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        textToSpeech?.language = if (hasDevanagari) Locale("hi", "IN") else Locale("en", "IN")
        textToSpeech?.setSpeechRate(speechRate)
        textToSpeech?.setPitch(speechPitch)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ASTRA_TTS")
    }

    fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeakingNow = false
        onStateChange(false)
        scheduleRestart(300)
    }

    fun shutdown() {
        isShutDown = true
        mainHandler.removeCallbacks(restartRunnable)
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Could not destroy recognizer", e)
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    companion object {
        private const val TAG = "ASTRA_VOICE"
    }
}
