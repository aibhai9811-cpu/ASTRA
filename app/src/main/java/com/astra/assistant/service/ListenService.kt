package com.astra.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.astra.assistant.MainActivity
import com.astra.assistant.ai.AiOutcome
import com.astra.assistant.ai.AstraAi
import com.astra.assistant.audio.AudioRouteManager
import com.astra.assistant.audio.CallStateMonitor
import com.astra.assistant.commands.CommandExecutionResult
import com.astra.assistant.commands.CommandProcessor
import com.astra.assistant.commands.CommandVocabulary
import com.astra.assistant.commands.ContactPicker
import com.astra.assistant.commands.VoiceCommandRouter
import com.astra.assistant.data.AstraDatabase
import com.astra.assistant.data.repository.AstraMemoryRepository
import com.astra.assistant.model.AssistantState
import com.astra.assistant.model.PendingAction
import com.astra.assistant.network.ResearchService
import com.astra.assistant.voice.VoiceManager
import com.astra.assistant.voice.VoiceSettings
import com.astra.assistant.voice.VoskModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class ListenService : Service() {

    private class PendingDictation(val name: String, val viaWhatsApp: Boolean, val createdAt: Long)

    private class PendingPick(
        val original: String,
        val needle: String,
        val options: List<String>,
        val createdAt: Long
    )

    private var voice: VoiceManager? = null
    private var processor: CommandProcessor? = null
    private var router: VoiceCommandRouter? = null
    private var memoryRepository: AstraMemoryRepository? = null
    private var audioRoute: AudioRouteManager? = null
    private var callMonitor: CallStateMonitor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var followUpUntil = 0L
    private var shownSetupHint = false
    private var lastSpoken: String? = null
    private var speechEndedAt = 0L
    private var callPaused = false
    private var recognitionUnavailable = false
    private var commandMode = false
    private var endAfterReply = false
    private var lastStatusText = ""
    private var inputLabel = ""
    private var currentRoute = AudioRouteManager.Route(false, "Phone microphone")
    private var dictation: PendingDictation? = null
    private var pick: PendingPick? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                turnOffByUser()
                return START_NOT_STICKY
            }
            ACTION_HUSH -> {
                voice?.stopSpeaking()
                return START_STICKY
            }
        }
        // Restarted by Android after a kill: only continue if the user's switch is still ON.
        if (intent == null && !VoiceSettings.listeningOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        VoiceSettings.setListeningOn(this, true)
        if (!enterForeground()) {
            AssistantBridge.notices.tryEmit("Could not start background listening. Open ASTRA and tap the mic again.")
            VoiceSettings.setListeningOn(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (voice == null) setUp()
        AssistantBridge.active.value = true
        val engine = voice
        engine?.continuousMode = true
        if (!callPaused) {
            applyRoute(false)
            applySettings(true)
            engine?.startListening()
        }
        updateStatus()
        return START_STICKY
    }

    // ---------------- notification ----------------

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hushIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ListenService::class.java).setAction(ACTION_HUSH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ASTRA microphone is on")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_lock_silent_mode),
                    "Hush",
                    hushIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Turn off",
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun enterForeground(): Boolean {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ASTRA listening", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification(lastStatusText.ifBlank { "Starting..." })
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter foreground", e)
            false
        }
    }

    private fun engineLabel(): String =
        if (voice?.offlineModelPath != null) "offline voice" else "Google voice"

    private fun inputText(): String = inputLabel.ifBlank { currentRoute.description }

    // Updates the existing notification and the app's microphone state. Only changes when the status changes.
    private fun updateStatus() {
        val status = when {
            callPaused -> MicStatus.PAUSED_FOR_CALL
            recognitionUnavailable -> MicStatus.UNAVAILABLE
            else -> MicStatus.LISTENING
        }
        AssistantBridge.micStatus.value = status
        AssistantBridge.inputDevice.value = inputText()
        val text = when {
            status == MicStatus.PAUSED_FOR_CALL -> "Paused for a phone call. I will resume afterwards."
            status == MicStatus.UNAVAILABLE -> "Speech recognition is unavailable. Retrying..."
            commandMode -> "Listening for your command..."
            else -> "Say ASTRA, then your command. (${engineLabel()}, ${inputText()})"
        }
        if (text != lastStatusText) {
            lastStatusText = text
            notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration not available", e)
        }
    }

    // ---------------- setup and lifecycle ----------------

    private fun setUp() {
        processor = CommandProcessor(applicationContext)
        router = VoiceCommandRouter(applicationContext)
        memoryRepository = AstraMemoryRepository(AstraDatabase.getDatabase(applicationContext, scope).memoryDao())
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astra:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
        voice = VoiceManager(
            context = applicationContext,
            onSpeechRecognized = { text -> handleSpeech(text) },
            onPartialSpeech = { text -> onPartial(text) },
            onStateChange = { speaking -> onSpeakingChanged(speaking) },
            onError = { message -> AssistantBridge.notices.tryEmit(message) },
            onAvailabilityChanged = { available ->
                recognitionUnavailable = !available
                updateStatus()
            },
            onInputDeviceChanged = { label ->
                inputLabel = label
                updateStatus()
            },
            onWakeHeard = { onWakeHeard() },
            onCommandWindowClosed = { onWindowClosed() },
            onBargeIn = { hush() }
        )
        voice?.requireWakeProvider = { !AssistantBridge.appVisible }
        audioRoute = AudioRouteManager(applicationContext) { onAudioDevicesChanged() }
        audioRoute?.start()
        callMonitor = CallStateMonitor(applicationContext) { inCall -> onCallChanged(inCall) }
        callMonitor?.start()
        scope.launch {
            AssistantBridge.asyncReplies.collect { text -> reply("(phone control)", text) }
        }
    }

    private fun bluetoothInputDevice(): AudioDeviceInfo? {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    // initial = true is used when the microphone is switched ON. Only then can the engine change.
    private fun applySettings(initial: Boolean) {
        val engine = voice ?: return
        val hindi = VoiceSettings.language(this) == VoiceSettings.LANG_HI
        engine.recognitionLanguage = when (VoiceSettings.language(this)) {
            VoiceSettings.LANG_HI -> "hi-IN"
            VoiceSettings.LANG_EN -> "en-IN"
            else -> "auto"
        }
        engine.preferOnDevice = VoiceSettings.onDevice(this)
        engine.allowBargeIn = currentRoute.usingBluetooth
        engine.preferredInput = if (currentRoute.usingBluetooth) bluetoothInputDevice() else null
        if (initial) {
            engine.hindiModel = hindi
            engine.vocabulary = CommandVocabulary.build(applicationContext, hindi)
            val model = VoskModels.forLanguage(VoiceSettings.language(this))
            engine.offlineModelPath =
                if (VoiceSettings.useOffline(this) && VoskModels.isInstalled(this, model)) {
                    VoskModels.folder(this, model).absolutePath
                } else {
                    null
                }
        }
    }

    private fun applyRoute(restartIfChanged: Boolean) {
        val manager = audioRoute ?: return
        val before = currentRoute
        val after = manager.apply(VoiceSettings.micPreference(this))
        currentRoute = after
        if (restartIfChanged && before.usingBluetooth != after.usingBluetooth) {
            voice?.allowBargeIn = after.usingBluetooth
            voice?.preferredInput = if (after.usingBluetooth) bluetoothInputDevice() else null
            voice?.restartSession()
        }
    }

    private fun onAudioDevicesChanged() {
        if (callPaused) return
        applyRoute(true)
        updateStatus()
    }

    private fun onCallChanged(inCall: Boolean) {
        callPaused = inCall
        if (inCall) {
            voice?.suspendListening()
            voice?.stopSpeaking()
            audioRoute?.release()
            AssistantBridge.partial.value = ""
            AssistantBridge.phase.value = AssistantState.IDLE
        } else {
            applyRoute(false)
            applySettings(false)
            voice?.resumeListening()
            AssistantBridge.phase.value = AssistantState.LISTENING
        }
        updateStatus()
    }

    private fun onWakeHeard() {
        commandMode = true
        vibrateShort()
        AssistantBridge.partial.value = "Listening for your command..."
        updateStatus()
    }

    private fun onWindowClosed() {
        commandMode = false
        AssistantBridge.partial.value = ""
        updateStatus()
    }

    private fun onPartial(text: String) {
        if (voice?.isSpeaking == true) {
            if (isHushPhrase(text)) hush()
        } else if (text.isNotBlank()) {
            AssistantBridge.partial.value = text
        }
    }

    private fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) {
            AssistantBridge.phase.value = AssistantState.SPEAKING
        } else {
            speechEndedAt = SystemClock.elapsedRealtime()
            AssistantBridge.phase.value = if (callPaused) AssistantState.IDLE else AssistantState.LISTENING
            if (endAfterReply) finishSingleShot()
        }
    }

    private fun turnOffByUser() {
        VoiceSettings.setListeningOn(this, false)
        voice?.continuousMode = false
        stopSelf()
    }

    private fun finishSingleShot() {
        // Keep listening if ASTRA is waiting for an answer.
        if (ConfirmationManager.peek() != null || pick != null || dictation != null) {
            endAfterReply = false
            return
        }
        endAfterReply = false
        turnOffByUser()
    }

    // ---------------- stop speaking ----------------

    private fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(NOISE, " ").replace(SPACES, " ").trim()

    private fun isHushPhrase(raw: String): Boolean {
        val withoutWake = WakeWord.strip(raw) ?: raw
        val n = normalize(withoutWake).removeSuffix(" astra").removeSuffix(" अस्त्र").trim()
        if (n.isEmpty()) return false
        if (n in HUSH_PHRASES) return true
        return n.split(" ").size <= 5 && HUSH_CONTAINS.any { n.contains(it) }
    }

    private fun isCancelWord(raw: String): Boolean = normalize(raw) in CANCEL_WORDS

    private fun hush() {
        voice?.stopSpeaking()
        AssistantBridge.phase.value = AssistantState.LISTENING
    }

    // The Google engine can pick up ASTRA's own voice. Ignore a long repeat of what it just said.
    private fun looksLikeEcho(text: String): Boolean {
        val spoken = lastSpoken ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - speechEndedAt > 8000) return false
        val words = normalize(text).split(" ").filter { it.isNotEmpty() }
        if (words.size < 3) return false
        val spokenWords = normalize(spoken).split(" ").toSet()
        return words.count { it in spokenWords } >= words.size * 0.8
    }

    // ---------------- speech handling ----------------

    private fun handleSpeech(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || callPaused) return
        applySettings(false)
        val now = SystemClock.elapsedRealtime()
        val engineDecidesWake = voice?.engineHandlesWake == true

        // 0. While ASTRA talks only a stop phrase is accepted.
        if (voice?.isSpeaking == true) {
            if (isHushPhrase(text)) hush()
            return
        }
        if (looksLikeEcho(text)) return

        if (commandMode) {
            commandMode = false
            updateStatus()
        }

        val stripped = WakeWord.strip(text)

        // 1. A pending yes or no has the highest priority and never reaches Gemini.
        if (ConfirmationManager.peek() != null) {
            when (ConfirmationManager.interpret(text)) {
                ConfirmationManager.Answer.YES -> {
                    val action = ConfirmationManager.take()
                    if (action != null) {
                        confirmAction(text, action)
                        return
                    }
                }
                ConfirmationManager.Answer.NO -> {
                    ConfirmationManager.take()
                    reply(text, "Cancelled.")
                    return
                }
                ConfirmationManager.Answer.UNCLEAR -> {
                    val looksLikeNewCommand = stripped != null || text.split(" ").size > 7
                    if (looksLikeNewCommand || ConfirmationManager.noteUnclear() >= 2) {
                        ConfirmationManager.clear()
                    } else {
                        reply(text, "I am waiting for a yes or no.")
                        return
                    }
                }
            }
        }

        // 1b. Answers to ASTRA's own questions: which contact, and what a message should say.
        val choosing = pick
        if (choosing != null) {
            pick = null
            if (now - choosing.createdAt <= ASK_TIMEOUT_MS) {
                if (isCancelWord(text)) {
                    reply(text, "Cancelled.")
                    return
                }
                val chosen = ContactPicker.choose(choosing.options, choosing.needle, text)
                if (chosen == null) {
                    reply(text, "I did not catch which one, so I cancelled.")
                    return
                }
                runCommand(ContactPicker.replaceName(choosing.original, choosing.needle, chosen))
                return
            }
        }
        val dictating = dictation
        if (dictating != null) {
            dictation = null
            if (now - dictating.createdAt <= ASK_TIMEOUT_MS) {
                if (isCancelWord(text)) {
                    reply(text, "Cancelled.")
                    return
                }
                val body = text.trim()
                val command = if (dictating.viaWhatsApp) {
                    "send a whatsapp message to ${dictating.name} saying $body"
                } else {
                    "send a text message to ${dictating.name} saying $body"
                }
                runCommand(command)
                return
            }
        }

        // 2. A stop phrase with nothing to stop is ignored. It is never sent to Gemini or the command engine.
        if (isHushPhrase(text)) return

        // 3. The offline engine already handled the wake word. The Google engine needs it checked here.
        var command = text
        if (engineDecidesWake) {
            if (!stripped.isNullOrEmpty()) command = stripped
        } else if (!AssistantBridge.appVisible) {
            if (stripped == null) {
                if (now > followUpUntil) return
            } else {
                command = stripped
                if (command.isEmpty()) {
                    followUpUntil = now + FOLLOW_UP_MS
                    return
                }
            }
        } else if (!stripped.isNullOrEmpty()) {
            command = stripped
        }
        followUpUntil = 0L

        // 4. A bare yes or no with nothing waiting is not a question for Gemini.
        if (ConfirmationManager.isBareAnswer(command) && !AstraAi.hasRecentConversation()) {
            reply(text, "There is nothing waiting for confirmation.")
            return
        }

        // 5. Microphone questions and choices
        val norm = normalize(command)
        if (norm in WHICH_MIC) {
            reply(text, describeRoute())
            return
        }
        if (norm in AUTO_MIC) {
            VoiceSettings.setMicPreference(this, VoiceSettings.MIC_AUTO)
            applyRoute(true)
            updateStatus()
            reply(text, "Okay, I will pick the microphone automatically. ${describeRoute()}")
            return
        }
        runCommand(command)
    }

    private fun describeRoute(): String =
        if (voice?.offlineModelPath != null) {
            "Android reports that I am using: ${inputText()}."
        } else if (currentRoute.usingBluetooth) {
            "I asked Android to use your earbud microphone. ${currentRoute.description}. " +
                "Google's speech service makes the final choice, so if I mishear you, say use phone mic."
        } else {
            "${currentRoute.description}."
        }

    private fun confirmAction(userText: String, action: PendingAction) {
        val engine = processor
        if (engine == null) {
            reply(userText, "ASTRA is not ready yet.")
            return
        }
        scope.launch {
            val result: CommandExecutionResult = try {
                if (action.actionType == "AI_COMMAND") {
                    AstraAi.runSuggested(engine, action.target)
                } else {
                    engine.executeConfirmedAction(action)
                }
            } catch (e: Exception) {
                CommandExecutionResult.Error("Could not complete the action: ${e.message ?: "unknown error"}")
            }
            presentResult(userText, result)
        }
    }

    private fun askWhichContact(command: String, ambiguity: ContactPicker.Ambiguity) {
        pick = PendingPick(command, ambiguity.needle, ambiguity.options, SystemClock.elapsedRealtime())
        voice?.openCommandWindow(ASK_WINDOW_MS)
        reply(
            command,
            "I found ${ambiguity.options.size} contacts named ${ambiguity.needle}: " +
                "${ambiguity.options.joinToString(", ")}. Say the name or the number."
        )
    }

    private fun runCommand(command: String) {
        val ambiguity = ContactPicker.find(applicationContext, command)
        if (ambiguity != null) {
            askWhichContact(command, ambiguity)
            return
        }
        AssistantBridge.phase.value = AssistantState.PROCESSING
        scope.launch {
            try {
                val engine = processor
                val smart = router
                val result: CommandExecutionResult? = when {
                    engine == null -> null
                    smart != null -> smart.route(engine, command)
                    else -> engine.processCommand(command)
                }
                if (result is CommandExecutionResult.RouteToAi) {
                    askAi(command, result.query)
                } else {
                    presentResult(command, result)
                }
            } catch (e: Exception) {
                reply(command, "Something went wrong: ${e.message ?: "unknown error"}")
            }
        }
    }

    private suspend fun presentResult(userText: String, result: CommandExecutionResult?) {
        when (result) {
            is CommandExecutionResult.Handled -> {
                if (result.actionType == "HUSH") {
                    hush()
                    return
                }
                if (result.actionType == "USE_BT_MIC" || result.actionType == "USE_PHONE_MIC") {
                    VoiceSettings.setMicPreference(
                        this,
                        if (result.actionType == "USE_BT_MIC") VoiceSettings.MIC_BLUETOOTH else VoiceSettings.MIC_PHONE
                    )
                    applyRoute(true)
                    updateStatus()
                    reply(userText, "${result.message} ${describeRoute()}")
                    return
                }
                reply(userText, result.message)
                if (result.actionType == "STOP_LISTENING") {
                    delay(2500)
                    turnOffByUser()
                }
            }
            is CommandExecutionResult.RequiresConfirmation -> {
                ConfirmationManager.set(result.pendingAction)
                voice?.openCommandWindow(CONFIRM_WINDOW_MS)
                reply(userText, result.pendingAction.prompt + " Say yes or no.")
            }
            is CommandExecutionResult.Error -> {
                val ask = ASK_FOR_BODY.matchEntire(result.error)
                if (ask != null) {
                    dictation = PendingDictation(
                        ask.groupValues[1],
                        WHATSAPP.containsMatchIn(userText),
                        SystemClock.elapsedRealtime()
                    )
                    voice?.openCommandWindow(ASK_WINDOW_MS)
                    reply(userText, "What should the message say?")
                } else {
                    reply(userText, result.error)
                }
            }
            is CommandExecutionResult.RouteToAi -> reply(userText, "Done.")
            null -> reply(userText, "ASTRA is not ready yet.")
        }
    }

    private suspend fun askAi(spoken: String, query: String) {
        val outcome = AstraAi.ask(applicationContext, query) {
            memoryRepository?.getApprovedContextSummary().orEmpty()
        }
        when (outcome) {
            is AiOutcome.Reply -> {
                followUpUntil = SystemClock.elapsedRealtime() + FOLLOW_UP_MS
                voice?.openCommandWindow(FOLLOW_UP_MS)
                val suggestion = outcome.suggestedCommand
                if (suggestion != null) {
                    val label = outcome.actionLabel ?: suggestion
                    ConfirmationManager.set(PendingAction("AI_COMMAND", suggestion, "Run: $label?", label))
                    voice?.openCommandWindow(CONFIRM_WINDOW_MS)
                    reply(spoken, "${outcome.text} Should I run: $label? Say yes or no.")
                } else {
                    reply(spoken, outcome.text)
                }
            }
            is AiOutcome.Failure -> reply(spoken, outcome.message)
            AiOutcome.Busy -> reply(spoken, "The AI is still working on earlier questions. Please try again in a moment.")
            AiOutcome.Ignored -> AssistantBridge.phase.value = AssistantState.LISTENING
            AiOutcome.NotConfigured -> {
                if (!shownSetupHint) {
                    shownSetupHint = true
                    AssistantBridge.notices.tryEmit("Gemini is not set up yet. Open Settings to connect it.")
                }
                reply(spoken, ResearchService.lookup(query))
            }
        }
    }

    private fun reply(userText: String, text: String) {
        lastSpoken = text
        AssistantBridge.exchanges.tryEmit(AssistantBridge.Exchange(userText, text))
        if (!VoiceSettings.continuous(this)) endAfterReply = true
        if (VoiceSettings.spokenResponses(this)) {
            voice?.speak(text)
        } else {
            AssistantBridge.phase.value = AssistantState.LISTENING
            if (endAfterReply) finishSingleShot()
        }
    }

    override fun onDestroy() {
        callMonitor?.stop()
        callMonitor = null
        audioRoute?.stop()
        audioRoute = null
        voice?.shutdown()
        voice = null
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        ConfirmationManager.clear()
        pick = null
        dictation = null
        AssistantBridge.active.value = false
        AssistantBridge.phase.value = AssistantState.IDLE
        AssistantBridge.partial.value = ""
        AssistantBridge.micStatus.value = MicStatus.OFF
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ASTRA_SERVICE"
        private const val CHANNEL_ID = "astra_listen"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.astra.assistant.STOP_LISTENING"
        private const val ACTION_HUSH = "com.astra.assistant.HUSH"
        private const val FOLLOW_UP_MS = 25000L
        private const val CONFIRM_WINDOW_MS = 30000L
        private const val ASK_WINDOW_MS = 25000L
        private const val ASK_TIMEOUT_MS = 30000L

        private val NOISE = Regex("[^\\p{L}\\p{M}\\p{N} ]")
        private val SPACES = Regex("\\s+")
        private val WHATSAPP = Regex("whats\\s?app", RegexOption.IGNORE_CASE)
        private val ASK_FOR_BODY = Regex("^What should the message to (.+) say\\?$")

        private val CANCEL_WORDS = setOf(
            "cancel", "never mind", "nevermind", "stop", "no", "nahi", "nahin", "rehne do", "rahne do",
            "रहने दो", "नहीं"
        )
        private val HUSH_PHRASES = setOf(
            "stop", "quiet", "quite", "silence", "enough", "shut up", "be quiet", "stop speaking",
            "stop talking", "stop voice", "cancel speech", "chup", "chup ho jao", "chup raho",
            "bolna band karo", "bas karo", "bas", "ruk jao", "ruk ja", "awaaz band karo", "awaz band karo",
            "avaaz band karo", "आवाज बंद करो", "चुप हो जाओ", "चुप", "बोलना बंद करो", "बस करो", "बस", "रुक जाओ"
        )
        private val HUSH_CONTAINS = listOf(
            "stop speaking", "stop talking", "stop voice", "cancel speech", "be quiet", "shut up",
            "bolna band", "awaaz band", "awaz band", "avaaz band", "bas karo", "chup ho", "chup raho",
            "बोलना बंद", "आवाज बंद", "चुप हो", "बस करो"
        )
        private val WHICH_MIC = setOf(
            "which mic", "which microphone", "which mic are you using", "which microphone are you using",
            "mic status", "microphone status", "kaun sa mic"
        )
        private val AUTO_MIC = setOf(
            "automatic mic", "use automatic mic", "auto mic", "use auto mic", "mic automatic"
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenService::class.java))
        }

        // The user's OFF choice is saved first, so nothing restarts the microphone afterwards.
        fun stop(context: Context) {
            VoiceSettings.setListeningOn(context, false)
            context.stopService(Intent(context, ListenService::class.java))
        }

        fun hush(context: Context) {
            if (AssistantBridge.active.value) {
                context.startService(Intent(context, ListenService::class.java).setAction(ACTION_HUSH))
            }
        }
    }
}
