package com.astra.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.astra.assistant.AppNavTab
import com.astra.assistant.model.AssistantState
import com.astra.assistant.model.ChatMessage
import com.astra.assistant.model.Sender

@Composable
fun AstraMainApp(
    viewModel: ChatViewModel,
    onMicClick: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    onConfirmPendingAction: () -> Unit,
    onCancelPendingAction: () -> Unit,
    onTestVoice: (Float, Float) -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var tab by remember { mutableStateOf(AppNavTab.ASSISTANT) }
        val memories by viewModel.memories.collectAsState()
        val rules by viewModel.rules.collectAsState()
        val knowledge by viewModel.knowledge.collectAsState()
        val prefs by viewModel.preferences.collectAsState()

        BackHandler(enabled = tab != AppNavTab.ASSISTANT) {
            tab = AppNavTab.ASSISTANT
        }

        Scaffold(
            containerColor = Color(0xFF020617),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B1329)) {
                    AppNavTab.values().forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(iconFor(item), contentDescription = labelFor(item)) },
                            label = { Text(labelFor(item), fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when (tab) {
                    AppNavTab.ASSISTANT -> AssistantScreen(
                        viewModel = viewModel,
                        onMicClick = onMicClick,
                        onExecuteCommand = onExecuteCommand,
                        onConfirmPendingAction = onConfirmPendingAction,
                        onCancelPendingAction = onCancelPendingAction
                    )
                    AppNavTab.MEMORY -> MemoryScreen(
                        memories = memories,
                        onAddMemory = { key, value, category -> viewModel.addMemory(key, value, category) },
                        onDeleteMemory = { id -> viewModel.deleteMemory(id) },
                        onClearAll = { viewModel.clearAllMemories() }
                    )
                    AppNavTab.CORRECTIONS -> CorrectionsScreen(
                        rules = rules,
                        onAddRule = { trigger, type, target, label -> viewModel.addRule(trigger, type, target, label) },
                        onDeleteRule = { id -> viewModel.deleteRule(id) }
                    )
                    AppNavTab.KNOWLEDGE -> KnowledgeScreen(
                        knowledgeList = knowledge,
                        onAddKnowledge = { title, content, sourceType, sourceName, tags ->
                            viewModel.addKnowledge(title, content, sourceType, sourceName, tags)
                        },
                        onDeleteKnowledge = { id -> viewModel.deleteKnowledge(id) }
                    )
                    AppNavTab.SETTINGS -> SettingsScreen(
                        currentPrefs = prefs,
                        onSavePrefs = { newPrefs -> viewModel.savePreferences(newPrefs) },
                        onTestVoice = onTestVoice,
                        onBack = { tab = AppNavTab.ASSISTANT }
                    )
                }
            }
        }
    }
}

private fun iconFor(tab: AppNavTab): ImageVector = when (tab) {
    AppNavTab.ASSISTANT -> Icons.Default.Mic
    AppNavTab.MEMORY -> Icons.Default.Favorite
    AppNavTab.CORRECTIONS -> Icons.Default.Build
    AppNavTab.KNOWLEDGE -> Icons.Default.Info
    AppNavTab.SETTINGS -> Icons.Default.Settings
}

private fun labelFor(tab: AppNavTab): String = when (tab) {
    AppNavTab.ASSISTANT -> "Assistant"
    AppNavTab.MEMORY -> "Memory"
    AppNavTab.CORRECTIONS -> "Corrections"
    AppNavTab.KNOWLEDGE -> "Knowledge"
    AppNavTab.SETTINGS -> "Settings"
}

@Composable
private fun AssistantScreen(
    viewModel: ChatViewModel,
    onMicClick: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    onConfirmPendingAction: () -> Unit,
    onCancelPendingAction: () -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val state by viewModel.assistantState.collectAsState()
    val partial by viewModel.partialTranscript.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val pending by viewModel.pendingAction.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isNotEmpty()) {
            onExecuteCommand(text)
            input = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .padding(12.dp)
    ) {
        Text("ASTRA", color = Color(0xFF06B6D4), fontSize = 20.sp)
        Text(
            text = when (state) {
                AssistantState.IDLE -> "Ready"
                AssistantState.LISTENING -> "Listening..."
                AssistantState.PROCESSING -> "Thinking..."
                AssistantState.SPEAKING -> "Speaking..."
            },
            color = Color.LightGray,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.padding(4.dp))

        notice?.let { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF7F1D1D), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.clearNotice() }) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.padding(4.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message -> MessageBubble(message) }
        }

        if (partial.isNotBlank()) {
            Text(partial, color = Color(0xFF22D3EE), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        pending?.let { action ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(action.prompt, color = Color.White, fontSize = 13.sp)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirmPendingAction) { Text("Confirm") }
                        Button(
                            onClick = onCancelPendingAction,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569))
                        ) { Text("Cancel") }
                    }
                }
            }
        }

        if (!hasMic) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text("Grant Permission") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMicClick) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Microphone",
                    tint = if (state == AssistantState.LISTENING) Color(0xFFF87171) else Color(0xFF22D3EE)
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type a command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { submit() }) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (isUser) Color(0xFF0E7490) else Color(0xFF0F172A),
                    RoundedCornerShape(12.dp)
                )
                .padding(10.dp)
        ) {
            Text(message.text, color = Color.White, fontSize = 14.sp)
        }
    }
}
