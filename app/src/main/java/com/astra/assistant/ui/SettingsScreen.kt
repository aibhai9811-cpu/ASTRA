package com.astra.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.assistant.ai.AstraAi
import com.astra.assistant.ai.GeminiSettings
import com.astra.assistant.model.UserPreferences
import com.astra.assistant.service.AssistantBridge
import com.astra.assistant.service.MicStatus
import com.astra.assistant.voice.VoiceSettings
import com.astra.assistant.voice.VoskModels
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentPrefs: UserPreferences,
    onSavePrefs: (UserPreferences) -> Unit,
    onTestVoice: (Float, Float) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AstraAi.settings(context)) }
    var urlInput by remember { mutableStateOf(settings.proxyUrl) }
    var tokenInput by remember { mutableStateOf("") }
    var historySlider by remember { mutableStateOf(settings.historyLimit.toFloat()) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    var useOffline by remember { mutableStateOf(VoiceSettings.useOffline(context)) }
    var language by remember { mutableStateOf(VoiceSettings.language(context)) }
    var micPref by remember { mutableStateOf(VoiceSettings.micPreference(context)) }
    var spoken by remember { mutableStateOf(VoiceSettings.spokenResponses(context)) }
    var continuous by remember { mutableStateOf(VoiceSettings.continuous(context)) }
    var refresh by remember { mutableStateOf(0) }

    val micStatus by AssistantBridge.micStatus.collectAsState()
    val inputDevice by AssistantBridge.inputDevice.collectAsState()
    val download by VoskModels.downloadState.collectAsState()

    fun update(updated: GeminiSettings) {
        settings = updated
        AstraAi.saveSettings(context, updated)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030712))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ASTRA CONFIGURATION", color = Color(0xFF06B6D4), fontSize = 18.sp)

        // ---------------- voice ----------------
        Text("VOICE AND MICROPHONE", color = Color(0xFF06B6D4), fontSize = 15.sp)

        val statusText = when (micStatus) {
            MicStatus.OFF -> "Microphone is OFF"
            MicStatus.LISTENING -> "Microphone is ON, listening"
            MicStatus.PAUSED_FOR_CALL -> "Paused for a phone call"
            MicStatus.UNAVAILABLE -> "Speech recognition is unavailable"
        }
        Text(statusText, color = Color.White, fontSize = 13.sp)
        Text("Active input: $inputDevice", color = Color.LightGray, fontSize = 12.sp)

        SettingToggle(
            title = "Offline voice engine (no beeps, no restarts)",
            subtitle = "Needs a voice model below. Applies the next time you turn the mic on. If it fails, ASTRA falls back to Google.",
            checked = useOffline,
            onChange = {
                useOffline = it
                VoiceSettings.setUseOffline(context, it)
            }
        )

        listOf(VoskModels.ENGLISH, VoskModels.HINDI).forEach { model ->
            val installed = remember(refresh, download) { VoskModels.isInstalled(context, model) }
            val busy = download.running
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("${model.label} voice (about ${model.sizeMb} MB)", color = Color.White, fontSize = 13.sp)
                    val line = when {
                        busy && download.modelId == model.id -> "${download.message} ${download.percent}%"
                        installed -> "Installed"
                        else -> "Not downloaded"
                    }
                    Text(line, color = Color.LightGray, fontSize = 11.sp)
                }
                if (installed) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            VoskModels.delete(context, model)
                            refresh++
                        }
                    ) { Text("Remove") }
                } else {
                    Button(
                        enabled = !busy,
                        onClick = { VoskModels.download(context, model) }
                    ) { Text("Download") }
                }
            }
        }
        if (download.message.isNotBlank() && !download.running) {
            Text(download.message, color = Color(0xFF22D3EE), fontSize = 12.sp)
        }
        Text("Download on Wi-Fi. Auto language uses the English voice.", color = Color.LightGray, fontSize = 11.sp)

        Text("Speech language", color = Color.White, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                VoiceSettings.LANG_AUTO to "Automatic",
                VoiceSettings.LANG_EN to "English",
                VoiceSettings.LANG_HI to "Hindi"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = language == value,
                    onClick = {
                        language = value
                        VoiceSettings.setLanguage(context, value)
                    },
                    label = { Text(label) }
                )
            }
        }

        Text("Preferred microphone", color = Color.White, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                VoiceSettings.MIC_AUTO to "Automatic",
                VoiceSettings.MIC_BLUETOOTH to "Bluetooth",
                VoiceSettings.MIC_PHONE to "Phone"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = micPref == value,
                    onClick = {
                        micPref = value
                        VoiceSettings.setMicPreference(context, value)
                    },
                    label = { Text(label) }
                )
            }
        }

        SettingToggle(
            title = "Keep listening after each command",
            subtitle = "Off means ASTRA listens for one command and then turns the microphone off.",
            checked = continuous,
            onChange = {
                continuous = it
                VoiceSettings.setContinuous(context, it)
            }
        )
        SettingToggle(
            title = "Spoken responses",
            subtitle = "Off means ASTRA answers only on screen.",
            checked = spoken,
            onChange = {
                spoken = it
                VoiceSettings.setSpokenResponses(context, it)
            }
        )
        OutlinedButton(onClick = { onTestVoice(currentPrefs.speechRate, currentPrefs.speechPitch) }) {
            Text("Test voice")
        }

        // ---------------- Gemini ----------------
        Text("GEMINI AI BRAIN", color = Color(0xFF06B6D4), fontSize = 15.sp)

        val connection = when {
            !settings.enabled -> "Gemini is turned off"
            settings.isConfigured -> "Configured"
            else -> "Not configured"
        }
        Text("Status: $connection", color = Color.White, fontSize = 13.sp)
        Text(
            "Model: ${settings.lastModel.ifBlank { "unknown (run a test)" }}",
            color = Color.LightGray,
            fontSize = 12.sp
        )
        if (settings.lastStatus.isNotBlank()) {
            Text("Last test: ${settings.lastStatus}", color = Color.LightGray, fontSize = 12.sp)
        }

        SettingToggle(
            title = "Enable Gemini",
            subtitle = "When off, ASTRA uses only local commands and Wikipedia.",
            checked = settings.enabled,
            onChange = { update(settings.copy(enabled = it)) }
        )

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("AI server address (https)") },
            colors = fieldColors
        )
        Button(onClick = {
            val cleaned = urlInput.trim()
            if (cleaned.isNotEmpty() && !cleaned.startsWith("https://")) {
                message = "The address must start with https://"
            } else {
                update(settings.copy(proxyUrl = cleaned))
                message = if (cleaned.isEmpty()) "Server address cleared." else "Server address saved."
            }
        }) { Text("Save address") }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (settings.hasToken) "Access token saved. Type to replace." else "Access token") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (tokenInput.trim().length < 20) {
                    message = "The token must be at least 20 characters."
                } else {
                    AstraAi.saveToken(context, tokenInput)
                    tokenInput = ""
                    settings = AstraAi.settings(context)
                    message = "Token saved and encrypted on this phone."
                }
            }) { Text("Save token") }
            if (settings.hasToken) {
                OutlinedButton(onClick = {
                    AstraAi.clearToken(context)
                    settings = AstraAi.settings(context)
                    message = "Token removed."
                }) { Text("Remove token") }
            }
        }

        Text("Answer length", color = Color.White, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                GeminiSettings.STYLE_SHORT to "Short",
                GeminiSettings.STYLE_BALANCED to "Balanced",
                GeminiSettings.STYLE_DETAILED to "Detailed"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = settings.style == value,
                    onClick = { update(settings.copy(style = value)) },
                    label = { Text(label) }
                )
            }
        }

        Text("Conversation memory: last ${historySlider.toInt()} messages", color = Color.White, fontSize = 13.sp)
        Slider(
            value = historySlider,
            onValueChange = { historySlider = it },
            onValueChangeFinished = { update(settings.copy(historyLimit = historySlider.toInt())) },
            valueRange = 2f..30f,
            steps = 27
        )

        Text("PRIVACY", color = Color(0xFF06B6D4), fontSize = 15.sp)
        SettingToggle(
            title = "Remove private details before sending",
            subtitle = "Hides OTPs, PINs, passwords, card and account numbers, phone numbers and emails.",
            checked = settings.redactPrivateInfo,
            onChange = { update(settings.copy(redactPrivateInfo = it)) }
        )
        SettingToggle(
            title = "Share my saved memories with Gemini",
            subtitle = "Off by default. When on, your approved memories are sent with each question.",
            checked = settings.shareMemory,
            onChange = { update(settings.copy(shareMemory = it)) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !testing,
                onClick = {
                    testing = true
                    message = "Testing..."
                    scope.launch {
                        message = AstraAi.testConnection(context)
                        settings = AstraAi.settings(context)
                        testing = false
                    }
                }
            ) { Text(if (testing) "Testing..." else "Test connection") }
            OutlinedButton(onClick = {
                AstraAi.clearHistory()
                message = "Temporary AI conversation cleared."
            }) { Text("Clear AI chat") }
        }

        if (message.isNotBlank()) {
            Text(message, color = Color(0xFF22D3EE), fontSize = 12.sp)
        }

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
        ) {
            Text("Back to Assistant", color = Color.Black)
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = Color.LightGray, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
