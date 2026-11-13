package com.astra.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val onListeningStarted: () -> Unit = {},
    private val onAvailabilityChanged: (Boolean) -> Unit = {},
    private val onInputDeviceChanged: (String) -> Unit = {}
) {
    // Aliases. Inside the RecognitionListener object, a call like onRmsChanged(x)
    // would call the listener's own method (endless loop = crash), so we use these.
    private val recognizedCallback: (String) -> Unit = onSpeechRecognized
    private val partialCallback: (String) -> Unit = onPartialSpeech
    private val rmsCallback: (Float) -> Unit = onRmsChanged
    private val endOfSpeechCallback: () -> Unit = onEndOfSpeech
    private val errorCallback: (String) -> Unit = onError

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var offline: OfflineRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeakingNow = false
    private var isShutDown = false

    private var desired = false
    private var suspended = false
    private var sessionActive = false
    private var expectCancel = false
    private var failures = 0
    private var busyCount = 0
    private var available = true
    private var lastNoticeAt = 0L
    private var lastFinal = ""
    private var lastFinalAt = 0L
    private var lastActivityAt = 0L
    private var preferOnDeviceValue = false

    // "auto", or a language tag such as "en-IN" or "hi-IN" (Google engine only)
    var recognitionLanguage: String = "auto"

    // Folder of a downloaded offline model. When set, the offline engine is used.
    var offlineModelPath: String? = null

    // The earbud input to ask for (offline engine only). null means the default microphone.
    var preferredInput: AudioDeviceInfo? = null

    var preferOnDevice: Boolean
        get() = preferOnDeviceValue
        set(value) {
            if (value != preferOnDeviceValue) {
                preferOnDeviceValue = value
                if (offlineModelPath == null) recreateRecognizer()
            }
        }

    var speechRate: Float = 1.0f
    var speechPitch: Float = 0.95f

    // When true the microphone stays on while ASTRA talks, so a stop phrase can interrupt it.
    var allowBargeIn: Boolean = true

    val isSpeaking: Boolean
        get() = isSpeakingNow

    var continuousMode: Boolean
        get() = desired
        set(value) {
            desired = value
            if (!value) {
                mainHandler.removeCallbacks(beginRunnable)
                mainHandler.removeCallbacks(watchdog)
                cancelSession()
                offline?.stopCapture()
            }
        }

    private val beginRunnable = Runnable { beginSession() }

    // Restarts the Google recognizer if it silently stops reporting anything.
    private val watchdog = object : Runnable {
        override fun run() {
            if (isShutDown || !desired) return
            if (offlineModelPath == null && !suspended && sessionActive &&
                SystemClock.elapsedRealtime() - lastActivityAt > 20_000L
            ) {
                Log.w(TAG, "Recognizer stalled, recreating it")
                recreateRecognizer()
            }
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastActivityAt = SystemClock.elapsedRealtime()
            setAvailable(true)
        }

        override fun onBeginningOfSpeech() {
            lastActivityAt = SystemClock.elapsedRealtime()
            failures = 0
        }

        override fun onRmsChanged(rmsdB: Float) {
            lastActivityAt = SystemClock.elapsedRealtime()
            rmsCallback(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            endOfSpeechCallback()
        }

        override fun onError(error: Int) {
            sessionActive = false
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            sessionActive = false
            failures = 0
            busyCount = 0
            setAvailable(true)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            deliverFinal(text)
            scheduleBegin(200)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            lastActivityAt = SystemClock.elapsedRealtime()
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrEmpty()) partialCallback(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        initTts()
    }

    // ---------------- shared ----------------

    // Duplicate protection: the same sentence twice within 1.5 seconds is one command.
    private fun deliverFinal(raw: String) {
        val text = raw.trim()
        val now = SystemClock.elapsedRealtime()
        if (text.isNotEmpty() && !(text == lastFinal && now - lastFinalAt < 1500L)) {
            lastFinal = text
            lastFinalAt = now
            recognizedCallback(text)
        }
    }

    private fun setAvailable(value: Boolean) {
        if (available != value) {
            available = value
            onAvailabilityChanged(value)
        }
    }

    private fun notifyOnce(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoticeAt > 60_000L) {
            lastNoticeAt = now
            errorCallback(message)
        }
    }

    // ---------------- offline engine ----------------

    private fun startOffline(path: String) {
        val engine = offline ?: OfflineRecognizer(
            onPartial = { text -> partialCallback(text) },
            onFinal = { text -> deliverFinal(text) },
            onFailure = { message -> handleOfflineFailure(message) },
            onInput = { label -> onInputDeviceChanged(label) },
            onCaptureStarted = { setAvailable(true) }
        ).also { offline = it }
        engine.start(path, preferredInput)
    }

    private fun handleOfflineFailure(message: String) {
        Log.w(TAG, message)
        offline?.release()
        offline = null
        offlineModelPath = null
        notifyOnce("$message Switching to Google speech recognition.")
        if (desired && !suspended) scheduleBegin(300)
    }

    // ---------------- Google engine ----------------

    private fun scheduleBegin(delayMs: Long) {
        if (isShutDown || !desired) return
        mainHandler.removeCallbacks(beginRunnable)
        mainHandler.postDelayed(beginRunnable, delayMs)
    }

    private fun createRecognizer(): SpeechRecognizer? {
        return try {
            val rec = if (Build.VERSION.SDK_INT >= 31 && preferOnDeviceValue &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            rec.setRecognitionListener(listener)
            recognizer = rec
            rec
        } catch (e: Exception) {
            Log.w(TAG, "Could not create the recognizer", e)
            null
        }
    }

    private fun destroyRecognizer() {
        sessionActive = false
        expectCancel = false
        val rec = recognizer
        recognizer = null
        try {
            rec?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Could not destroy the recognizer", e)
        }
    }

    private fun recreateRecognizer() {
        mainHandler.removeCallbacks(beginRunnable)
        destroyRecognizer()
        if (desired && !suspended) scheduleBegin(300)
    }

    private fun cancelSession() {
        if (!sessionActive) return
        sessionActive = false
        expectCancel = true
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the session", e)
        }
        mainHandler.postDelayed({ expectCancel = false }, 600)
    }

    private fun buildIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDeviceValue)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        if (recognitionLanguage == "auto") {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            intent.putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("hi-IN"))
        } else {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
        }
        return intent
    }

    private fun beginSession() {
        if (isShutDown || !desired || suspended || sessionActive) return
        if (offlineModelPath != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            setAvailable(false)
            notifyOnce("Speech recognition is not available. Check that Google speech services are enabled.")
            scheduleBegin(15_000L)
            return
        }
        val rec = recognizer ?: createRecognizer()
        if (rec == null) {
            failures++
            scheduleBegin(backoff())
            return
        }
        try {
            lastActivityAt = SystemClock.elapsedRealtime()
            sessionActive = true
            rec.startListening(buildIntent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            sessionActive = false
            failures++
            destroyRecognizer()
            scheduleBegin(backoff())
        }
    }

    private fun backoff(): Long {
        val step = minOf(failures, 5)
        return minOf(15_000L, 500L * (1 shl step))
    }

    private fun handleError(error: Int) {
        if (expectCancel && error == SpeechRecognizer.ERROR_CLIENT) {
            expectCancel = false
            return
        }
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                failures = 0
                busyCount = 0
                scheduleBegin(250)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                busyCount++
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "cancel on busy failed", e)
                }
                if (busyCount >= 3) {
                    busyCount = 0
                    destroyRecognizer()
                }
                scheduleBegin(800)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                desired = false
                setAvailable(false)
                errorCallback("Microphone permission is missing.")
            }
            else -> {
                failures++
                if (failures >= 3) destroyRecognizer()
                if (failures >= 5) {
                    setAvailable(false)
                    notifyOnce("Speech recognition keeps failing (code $error). I will keep trying.")
                }
                scheduleBegin(backoff())
            }
        }
    }

    // ---------------- public control ----------------

    fun startListening() {
        if (isShutDown) return
        desired = true
        suspended = false
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, 10_000L)
        val path = offlineModelPath
        if (path != null) startOffline(path) else beginSession()
        onListeningStarted()
    }

    fun stopListening() {
        continuousMode = false
    }

    // Used while a phone call is ringing or active. The user's ON choice is kept.
    fun suspendListening() {
        suspended = true
        mainHandler.removeCallbacks(beginRunnable)
        cancelSession()
        offline?.stopCapture()
    }

    fun resumeListening() {
        suspended = false
        if (!desired) return
        val path = offlineModelPath
        if (path != null) startOffline(path) else scheduleBegin(500)
    }

    // Starts one fresh session, for example after the microphone route changed.
    fun restartSession() {
        if (!desired || suspended) return
        if (offlineModelPath != null) {
            offline?.restartCapture(preferredInput)
            return
        }
        cancelSession()
        scheduleBegin(400)
    }

    // ---------------- speech ----------------

    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                textToSpeech?.language = Locale("en", "IN")
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
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
            if (!allowBargeIn) {
                if (offlineModelPath == null) scheduleBegin(400)
                mainHandler.postDelayed({ offline?.setMuted(false) }, 500)
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) {
            onStateChange(false)
            return
        }
        isSpeakingNow = true
        if (!allowBargeIn) {
            mainHandler.removeCallbacks(beginRunnable)
            cancelSession()
            offline?.setMuted(true)
        }
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        textToSpeech?.language = if (hasDevanagari) Locale("hi", "IN") else Locale("en", "IN")
        textToSpeech?.setSpeechRate(speechRate)
        textToSpeech?.setPitch(speechPitch)
        // QUEUE_FLUSH clears anything still waiting to be spoken.
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ASTRA_TTS")
    }

    fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeakingNow = false
        onStateChange(false)
        if (!allowBargeIn) {
            if (offlineModelPath == null) scheduleBegin(300)
            mainHandler.postDelayed({ offline?.setMuted(false) }, 300)
        }
    }

    fun shutdown() {
        isShutDown = true
        desired = false
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        offline?.release()
        offline = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    companion object {
        private const val TAG = "ASTRA_VOICE"
    }
}
