package com.astra.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.astra.assistant.commands.CommandProcessor
import com.astra.assistant.model.*
import com.astra.assistant.ui.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commandProcessor = CommandProcessor(this)

        voiceManager = VoiceManager(
            context = this,
            onSpeechRecognized = { recognizedText ->
                Log.d(TAG, "SpeechRecognizer delivered: '$recognizedText'")
                executeCommand(recognizedText)
            },
            onPartialSpeech = { partialText ->
                viewModel.updatePartialTranscript(partialText)
            },
            onRmsChanged = { rms ->
                viewModel.updateRms(rms)
            },
            onStateChange = { isSpeaking ->
                if (!isSpeaking && viewModel.assistantState.value == AssistantState.SPEAKING) {
                    viewModel.setAssistantState(AssistantState.IDLE)
                }
            },
            onEndOfSpeech = {
                if (viewModel.assistantState.value == AssistantState.LISTENING) {
                    viewModel.setAssistantState(AssistantState.PROCESSING)
                }
            },
            onError = { errorMsg ->
                viewModel.clearPartialTranscript()
                viewModel.setAssistantState(AssistantState.IDLE)
                viewModel.setNotice(errorMsg)
            }
        )

        setContent {
            AstraMainApp(
                viewModel = viewModel,
                onMicClick = { checkAndStartVoiceRecognition() },
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

    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        Log.i(TAG, "Central executeCommand received: '$trimmed'")
        viewModel.executeCommand(
            command = trimmed,
            commandProcessor = commandProcessor,
            speakCallback = { reply -> speakOut(reply) }
        )
    }

    private fun checkAndStartVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            when (viewModel.assistantState.value) {
                AssistantState.LISTENING -> {
                    voiceManager?.stopListening()
                    viewModel.setAssistantState(AssistantState.IDLE)
                    viewModel.clearPartialTranscript()
                }
                AssistantState.SPEAKING -> {
                    voiceManager?.stopSpeaking()
                    viewModel.setAssistantState(AssistantState.IDLE)
                }
                else -> {
                    viewModel.setAssistantState(AssistantState.LISTENING)
                    viewModel.clearPartialTranscript()
                    voiceManager?.recognitionLanguage = viewModel.preferences.value.recognitionLanguage
                    voiceManager?.preferOnDevice = viewModel.preferences.value.preferOnDeviceRecognition
                    voiceManager?.startListening()
                }
            }
        } else {
            viewModel.setNotice("Microphone permission required. Tap Grant Permission below.")
        }
    }

    private fun speakOut(text: String) {
        if (viewModel.preferences.value.autoSpeakResponses) {
            voiceManager?.speechRate = viewModel.preferences.value.speechRate
            voiceManager?.speechPitch = viewModel.preferences.value.speechPitch
            voiceManager?.speak(text)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager?.shutdown()
    }
}
