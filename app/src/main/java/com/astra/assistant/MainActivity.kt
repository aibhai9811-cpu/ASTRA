package com.astra.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.astra.assistant.commands.CommandProcessor
import com.astra.assistant.model.AssistantState
import com.astra.assistant.service.AssistantBridge
import com.astra.assistant.service.AstraAccessibilityService
import com.astra.assistant.service.ListenService
import com.astra.assistant.ui.AstraMainApp
import com.astra.assistant.ui.ChatViewModel
import com.astra.assistant.voice.VoiceManager
import kotlinx.coroutines.launch

enum class AppNavTab {
    ASSISTANT,
    MEMORY,
    CORRECTIONS,
    KNOWLEDGE,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ASTRA"
    }

    private val viewModel: ChatViewModel by viewModels()
    private var voiceManager: VoiceManager? = null
    private lateinit var commandProcessor: CommandProcessor
    private var askedPermissions = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startListenService()
        } else {
            viewModel.setNotice("Microphone permission is required for voice commands.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commandProcessor = CommandProcessor(this)

        // This voice manager only speaks replies to typed commands. Listening is done by ListenService.
        voiceManager = VoiceManager(
            context = this,
            onSpeechRecognized = { },
            onStateChange = { isSpeaking ->
                if (!isSpeaking && viewModel.assistantState.value == AssistantState.SPEAKING) {
                    viewModel.setAssistantState(
                        if (AssistantBridge.active.value) AssistantState.LISTENING else AssistantState.IDLE
                    )
                }
            }
        )

        lifecycleScope.launch {
            AssistantBridge.phase.collect { phase ->
                if (AssistantBridge.active.value) viewModel.setAssistantState(phase)
            }
        }
        lifecycleScope.launch {
            AssistantBridge.active.collect { active ->
                if (active) {
                    viewModel.setAssistantState(AssistantState.LISTENING)
                } else {
                    viewModel.setAssistantState(AssistantState.IDLE)
                    viewModel.clearPartialTranscript()
                }
            }
        }
        lifecycleScope.launch {
            AssistantBridge.partial.collect { viewModel.updatePartialTranscript(it) }
        }
        lifecycleScope.launch {
            AssistantBridge.notices.collect { viewModel.setNotice(it) }
        }
        lifecycleScope.launch {
            AssistantBridge.exchanges.collect { viewModel.addExchange(it.userText, it.reply) }
        }

        setContent {
            AstraMainApp(
                viewModel = viewModel,
                onMicClick = { onMicToggle() },
                onExecuteCommand = { command -> executeCommand(command) },
                onConfirmPendingAction = {
                    viewModel.confirmPendingAction(
                        commandProcessor = commandProcessor,
                        speakCallback = { reply -> speakOut(reply) }
                    )
                },
                onCancelPendingAction = {
                    viewModel.cancelPendingAction(
                        speakCallback = { reply -> speakOut(reply) }
                    )
                },
                onTestVoice = { rate, pitch ->
                    voiceManager?.speechRate = rate
                    voiceManager?.speechPitch = pitch
                    voiceManager?.speak("ASTRA vocal synthesis recalibrated. All systems nominal, sir.")
                }
            )
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    if (viewModel.assistantState.value == AssistantState.SPEAKING) {
            voiceManager?.stopSpeaking()
            ListenService.hush(this)
            viewModel.setAssistantState(
                if (AssistantBridge.active.value) AssistantState.LISTENING else AssistantState.IDLE
            )
            return
    }
    
    private fun onMicToggle() {
        if (AssistantBridge.active.value) {
            ListenService.stop(this)
            return
        }
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { !hasPermission(it) }
        if (missing.isNotEmpty() && (!askedPermissions || !hasPermission(Manifest.permission.RECORD_AUDIO))) {
            askedPermissions = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        startListenService()
    }

    private fun startListenService() {
        ListenService.start(this)
        if (!AstraAccessibilityService.isEnabled()) {
            viewModel.setNotice("Tip: turn on ASTRA in Settings, Accessibility, to use Back, Home and Recents by voice.")
        }
    }

    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase().trimEnd('.', '!', '?', ' ')
        if (lower == "stop listening" || lower == "ruk jao" || lower == "so jao") {
            ListenService.stop(this)
        }

        Log.i(TAG, "Central executeCommand received: '$trimmed'")
        viewModel.executeCommand(
            command = trimmed,
            commandProcessor = commandProcessor,
            speakCallback = { reply -> speakOut(reply) }
        )
    }

    private fun speakOut(text: String) {
        if (viewModel.preferences.value.autoSpeakResponses) {
            voiceManager?.speechRate = viewModel.preferences.value.speechRate
            voiceManager?.speechPitch = viewModel.preferences.value.speechPitch
            voiceManager?.speak(text)
        }
    }

    override fun onStart() {
        super.onStart()
        AssistantBridge.appVisible = true
    }

    override fun onStop() {
        AssistantBridge.appVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager?.shutdown()
    }
}
