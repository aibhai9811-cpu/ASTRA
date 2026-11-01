package com.astra.assistant.model

import java.util.UUID

enum class AssistantState { IDLE, LISTENING, PROCESSING, SPEAKING }

enum class Sender { USER, ASSISTANT }

enum class MessageSource { USER, LOCAL_ROUTER, ONLINE_AI }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val source: MessageSource = MessageSource.LOCAL_ROUTER,
    val timestamp: Long = System.currentTimeMillis()
)

data class PendingAction(
    val actionType: String,
    val target: String,
    val prompt: String,
    val label: String
)

data class UserPreferences(
    val backendServerUrl: String = "",
    val assistantName: String = "ASTRA",
    val recognitionLanguage: String = "en-IN",
    val preferOnDeviceRecognition: Boolean = false,
    val autoSpeakResponses: Boolean = true,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 0.95f
)
