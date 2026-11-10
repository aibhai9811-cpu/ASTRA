package com.astra.assistant.service

import com.astra.assistant.model.AssistantState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class MicStatus { OFF, LISTENING, PAUSED_FOR_CALL, UNAVAILABLE }

object AssistantBridge {
    data class Exchange(val userText: String, val reply: String)

    // true while the user's microphone switch is ON (even while paused for a call)
    val active = MutableStateFlow(false)
    val phase = MutableStateFlow(AssistantState.IDLE)
    val partial = MutableStateFlow("")
    val exchanges = MutableSharedFlow<Exchange>(extraBufferCapacity = 16)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // Results of actions that finish a moment later, such as tapping Quick Settings tiles.
    val asyncReplies = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val micStatus = MutableStateFlow(MicStatus.OFF)
    val inputDevice = MutableStateFlow("Phone microphone")

    @Volatile
    var appVisible: Boolean = false
}

object WakeWord {
    private val fillers = listOf("hey ", "ok ", "okay ", "hello ", "hi ")
    private val words = listOf("astra", "astro", "aastra", "ostra", "अस्त्र", "एस्ट्रा")

    // Returns the command after the wake word, or null if the wake word was not said.
    fun strip(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        var offset = 0
        for (f in fillers) {
            if (lower.startsWith(f)) {
                offset = f.length
                break
            }
        }
        for (w in words) {
            if (lower.startsWith(w, offset)) {
                val next = lower.getOrNull(offset + w.length)
                if (next == null || !next.isLetter()) {
                    return text.substring(offset + w.length).trimStart(',', '.', ':', '!', ' ').trim()
                }
            }
        }
        return null
    }
}
