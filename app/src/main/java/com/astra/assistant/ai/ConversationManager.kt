package com.astra.assistant.ai

class ConversationManager {

    data class Turn(val role: String, val text: String)

    private val turns = ArrayList<Turn>()

    @Synchronized
    fun add(role: String, text: String) {
        if (text.isBlank()) return
        turns.add(Turn(role, text))
        while (turns.size > MAX_STORED) turns.removeAt(0)
    }

    @Synchronized
    fun snapshot(limit: Int): List<Turn> = turns.takeLast(limit.coerceAtLeast(0))

    @Synchronized
    fun clear() {
        turns.clear()
    }

    companion object {
        private const val MAX_STORED = 60
    }
}
