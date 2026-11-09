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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.astra.assistant.MainActivity
import com.astra.assistant.ai.AiOutcome
import com.astra.assistant.ai.AstraAi
import com.astra.assistant.audio.AudioRouteManager
import com.astra.assistant.commands.CommandExecutionResult
import com.astra.assistant.commands.CommandProcessor
import com.astra.assistant.data.AstraDatabase
import com.astra.assistant.data.repository.AstraMemoryRepository
import com.astra.assistant.model.AssistantState
import com.astra.assistant.model.PendingAction
import com.astra.assistant.network.ResearchService
import com.astra.assistant.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class ListenService : Service() {

    private var voice: VoiceManager? = null
    private var processor: CommandProcessor? = null
    private var memoryRepository: AstraMemoryRepository? = null
    private var audioRoute: AudioRouteManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var followUpUntil = 0L
    private var shownSetupHint = false
    private var lastSpoken: String? = null
    private var speechEndedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_HUSH) {
            voice?.stopSpeaking()
            return START_STICKY
        }
        if (!enterForeground()) {
            AssistantBridge.notices.tryEmit("Could not start background listening. Open ASTRA and tap the mic again.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (voice == null) setUp()
        voice?.continuousMode = true
        voice?.startListening()
        AssistantBridge.active.value = true
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ASTRA listening", NotificationManager.IMPORTANCE_LOW)
        )
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
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ASTRA is listening")
            .setContentText("Say \"ASTRA\" and then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
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
                    "Stop",
                    stopIntent
                ).build()
            )
            .build()
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

    private fun setUp() {
        processor = CommandProcessor(applicationContext)
        memoryRepository = AstraMemoryRepository(AstraDatabase.getDatabase(applicationContext, scope).memoryDao())
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astra:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
        voice = VoiceManager(
            context = applicationContext,
            onSpeechRecognized = { text -> handleSpeech(text) },
            onPartialSpeech = { text ->
                if (voice?.isSpeaking == true) {
                    if (isHushPhrase(text)) hush()
                } else {
                    AssistantBridge.partial.value = text
                }
            },
            onStateChange = { speaking ->
                if (speaking) {
                    AssistantBridge.phase.value = AssistantState.SPEAKING
                } else {
                    speechEndedAt = SystemClock.elapsedRealtime()
                    AssistantBridge.phase.value = AssistantState.LISTENING
                }
            },
            onEndOfSpeech = {
                if (voice?.isSpeaking != true) AssistantBridge.phase.value = AssistantState.PROCESSING
            },
            onError = { message ->
                AssistantBridge.notices.tryEmit(message)
                if (voice?.continuousMode != true) stopSelf()
            },
            onListeningStarted = {
                if (voice?.isSpeaking != true) {
                    AssistantBridge.partial.value = ""
                    AssistantBridge.phase.value = AssistantState.LISTENING
                }
            }
        )
        audioRoute = AudioRouteManager(applicationContext) { handleRouteChanged() }
        audioRoute?.start()
        scope.launch {
            AssistantBridge.asyncReplies.collect { text -> replyAsync(text) }
        }
    }

    private fun handleRouteChanged() {
        val engine = voice ?: return
        if (engine.continuousMode) engine.startListening()
    }

    private fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(NOISE, " ").replace(SPACES, " ").trim()

    private fun isHushPhrase(raw: String): Boolean {
        val base = WakeWord.strip(raw) ?: raw
        return normalize(base) in HUSH_PHRASES
    }

    private fun hush() {
        voice?.stopSpeaking()
        AssistantBridge.phase.value = AssistantState.LISTENING
    }

    // ASTRA's own voice can be picked up by the microphone. Ignore what it just said.
    private fun looksLikeEcho(text: String): Boolean {
        val spoken = lastSpoken ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - speechEndedAt > 8000) return false
        val words = normalize(text).split(" ").filter { it.isNotEmpty() }
        if (words.size < 2) return false
        val spokenWords = normalize(spoken).split(" ").toSet()
        return words.count { it in spokenWords } >= words.size * 0.8
    }

    private fun handleSpeech(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        // 0. While ASTRA talks only a stop phrase is accepted. Anything else is probably its own voice.
        if (voice?.isSpeaking == true) {
            if (isHushPhrase(text)) hush()
            return
        }
        if (looksLikeEcho(text)) return

        val stripped = WakeWord.strip(text)

        // 1. Confirmation has the highest priority and never reaches Gemini.
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

        // 2. The wake word is required when ASTRA is not on screen.
        var command = text
        if (!AssistantBridge.appVisible) {
            if (stripped == null) {
                if (now > followUpUntil) return
            } else {
                command = stripped
                if (command.isEmpty()) {
                    followUpUntil = now + FOLLOW_UP_MS
                    reply(text, "Yes, sir?")
                    return
                }
            }
        } else if (!stripped.isNullOrEmpty()) {
            command = stripped
        }
        followUpUntil = 0L

        // 3. A bare yes or no with nothing waiting is not a question for Gemini.
        if (ConfirmationManager.isBareAnswer(command) && !AstraAi.hasRecentConversation()) {
            reply(text, "There is nothing waiting for confirmation.")
            return
        }
        runCommand(command)
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

    private fun runCommand(command: String) {
        AssistantBridge.phase.value = AssistantState.PROCESSING
        scope.launch {
            try {
                val result = processor?.processCommand(command)
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
                    voice?.stopSpeaking()
                    AssistantBridge.phase.value = AssistantState.LISTENING
                    return
                }
                if (result.actionType == "USE_BT_MIC" || result.actionType == "USE_PHONE_MIC") {
                    AudioRouteManager.setPreferBluetooth(applicationContext, result.actionType == "USE_BT_MIC")
                    val status = audioRoute?.applyRoute().orEmpty()
                    handleRouteChanged()
                    reply(userText, "${result.message} $status".trim())
                    return
                }
                reply(userText, result.message)
                if (result.actionType == "STOP_LISTENING") {
                    voice?.continuousMode = false
                    delay(2500)
                    stopSelf()
                }
            }
            is CommandExecutionResult.RequiresConfirmation -> {
                ConfirmationManager.set(result.pendingAction)
                reply(userText, result.pendingAction.prompt + " Say yes or no.")
            }
            is CommandExecutionResult.Error -> reply(userText, result.error)
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
                val suggestion = outcome.suggestedCommand
                if (suggestion != null) {
                    val label = outcome.actionLabel ?: suggestion
                    ConfirmationManager.set(PendingAction("AI_COMMAND", suggestion, "Run: $label?", label))
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
        voice?.speak(text)
    }

    private fun replyAsync(text: String) {
        reply("(phone control)", text)
    }

    override fun onDestroy() {
        audioRoute?.stop()
        audioRoute = null
        voice?.shutdown()
        voice = null
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        ConfirmationManager.clear()
        AssistantBridge.active.value = false
        AssistantBridge.phase.value = AssistantState.IDLE
        AssistantBridge.partial.value = ""
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ASTRA_SERVICE"
        private const val CHANNEL_ID = "astra_listen"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.astra.assistant.STOP_LISTENING"
        private const val ACTION_HUSH = "com.astra.assistant.HUSH"
        private const val FOLLOW_UP_MS = 25000L

        private val NOISE = Regex("[^\\p{L}\\p{M}\\p{N} ]")
        private val SPACES = Regex("\\s+")

        private val HUSH_PHRASES = setOf(
            "stop", "quiet", "quite", "be quiet", "shut up", "silence", "stop talking", "stop speaking",
            "enough", "chup", "chup ho jao", "chup raho", "bas", "bas karo", "बस", "चुप", "चुप हो जाओ"
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }

        fun hush(context: Context) {
            if (AssistantBridge.active.value) {
                context.startService(Intent(context, ListenService::class.java).setAction(ACTION_HUSH))
            }
        }
    }
}
