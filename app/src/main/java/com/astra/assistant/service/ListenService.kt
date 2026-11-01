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
import com.astra.assistant.commands.CommandExecutionResult
import com.astra.assistant.commands.CommandProcessor
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var pending: PendingAction? = null
    private var pendingExpiresAt = 0L
    private var followUpUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
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
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ASTRA is listening")
            .setContentText("Say \"ASTRA\" and then your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
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
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astra:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
        voice = VoiceManager(
            context = applicationContext,
            onSpeechRecognized = { text -> handleSpeech(text) },
            onPartialSpeech = { text -> AssistantBridge.partial.value = text },
            onStateChange = { speaking ->
                AssistantBridge.phase.value = if (speaking) AssistantState.SPEAKING else AssistantState.LISTENING
            },
            onEndOfSpeech = { AssistantBridge.phase.value = AssistantState.PROCESSING },
            onError = { message ->
                AssistantBridge.notices.tryEmit(message)
                if (voice?.continuousMode != true) stopSelf()
            },
            onListeningStarted = {
                AssistantBridge.partial.value = ""
                AssistantBridge.phase.value = AssistantState.LISTENING
            }
        )
    }

    private fun handleSpeech(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        // 1. Waiting for a yes/no (call or text message)
        val waiting = pending
        if (waiting != null) {
            pending = null
            if (now <= pendingExpiresAt) {
                val answer = text.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ' ')
                if (answer in YES_WORDS) {
                    scope.launch {
                        val result = processor?.executeConfirmedAction(waiting)
                        reply(text, resultText(result))
                    }
                    return
                }
                if (answer in NO_WORDS) {
                    reply(text, "Cancelled.")
                    return
                }
            }
        }

        // 2. Wake word is required when ASTRA is not on screen
        var command = text
        val stripped = WakeWord.strip(text)
        if (!AssistantBridge.appVisible) {
            if (stripped == null) {
                if (now > followUpUntil) return
            } else {
                command = stripped
                if (command.isEmpty()) {
                    followUpUntil = now + 8000
                    reply(text, "Yes, sir?")
                    return
                }
            }
        } else if (!stripped.isNullOrEmpty()) {
            command = stripped
        }
        followUpUntil = 0L
        runCommand(command)
    }

    private fun runCommand(command: String) {
        AssistantBridge.phase.value = AssistantState.PROCESSING
        scope.launch {
            try {
                val result = processor?.processCommand(command)
                when (result) {
                    is CommandExecutionResult.Handled -> {
                        reply(command, result.message)
                        if (result.actionType == "STOP_LISTENING") {
                            voice?.continuousMode = false
                            delay(2500)
                            stopSelf()
                        }
                    }
                    is CommandExecutionResult.RequiresConfirmation -> {
                        pending = result.pendingAction
                        pendingExpiresAt = SystemClock.elapsedRealtime() + 20000
                        reply(command, result.pendingAction.prompt + " Say yes or no.")
                    }
                    is CommandExecutionResult.RouteToAi -> reply(command, ResearchService.lookup(result.query))
                    is CommandExecutionResult.Error -> reply(command, result.error)
                    null -> reply(command, "ASTRA is not ready yet.")
                }
            } catch (e: Exception) {
                reply(command, "Something went wrong: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun resultText(result: CommandExecutionResult?): String = when (result) {
        is CommandExecutionResult.Handled -> result.message
        is CommandExecutionResult.Error -> result.error
        is CommandExecutionResult.RequiresConfirmation -> result.pendingAction.prompt
        is CommandExecutionResult.RouteToAi -> "Done."
        null -> "Done."
    }

    private fun reply(userText: String, text: String) {
        AssistantBridge.exchanges.tryEmit(AssistantBridge.Exchange(userText, text))
        voice?.speak(text)
    }

    override fun onDestroy() {
        voice?.shutdown()
        voice = null
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
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

        private val YES_WORDS = setOf(
            "yes", "yeah", "yep", "confirm", "ok", "okay", "sure", "go ahead", "do it",
            "haan", "han", "ha", "kar do", "हाँ", "हां", "ठीक है"
        )
        private val NO_WORDS = setOf(
            "no", "nope", "cancel", "stop", "don't", "nahi", "nahin", "mat karo", "नहीं"
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}
