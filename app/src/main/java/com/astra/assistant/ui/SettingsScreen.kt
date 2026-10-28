package com.astra.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.assistant.model.UserPreferences

@Composable
fun SettingsScreen(
    currentPrefs: UserPreferences,
    onSavePrefs: (UserPreferences) -> Unit,
    onTestVoice: (Float, Float) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF030712)).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ASTRA CONFIGURATION", color = Color(0xFF06B6D4), fontSize = 18.sp)
        Text("Languages: English (India), Hinglish, Hindi (India), English (US)", color = Color.White, fontSize = 12.sp)
        Text("Backend Server Proxy: ${currentPrefs.backendServerUrl}", color = Color.LightGray, fontSize = 12.sp)
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
        ) {
            Text("Back to Assistant", color = Color.Black)
        }
    }
}
