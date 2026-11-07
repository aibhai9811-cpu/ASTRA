package com.astra.assistant.ai

import android.content.Context
import android.os.SystemClock
import com.astra.assistant.commands.CommandExecutionResult
import com.astra.assistant.commands.CommandProcessor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

sealed class AiOutcome {
    data class Reply(
        val text: String,
        val suggestedCommand: String?,
        val actionLabel: String?,
        val redactions: Int
    ) : AiOutcome()

    data class Failure(val message: String) : AiOutcome()
    object NotConfigured : AiOutcome()
    object Busy : AiOutcome()
    object Ignored : AiOutcome()
}

object AstraAi {

    private val conversation = ConversationManager()
    private val repository = GeminiRepository()
    private val mutex = Mutex()

    @Volatile private var lastText = ""
    @Volatile private var lastAt = 0L
    @Volatile private var lastReplyAt = 0L
    @Volatile private var store: GeminiSettingsStore? = null

    private const val DUPLICATE_WINDOW_MS = 3000L
    private const val QUEUE_WAIT_MS = 20_000L

    private fun store(context: Context): GeminiSettingsStore {
        val existing = store
        if (existing != null) return existing
        return synchronized(this) {
            store ?: GeminiSettingsStore(context.applicationContext).also { store = it }
        }
    }

    fun settings(context: Context): GeminiSettings = store(context).load()

    fun saveSettings(context: Context, settings: GeminiSettings) {
        store(context).save(settings)
    }

    fun saveToken(context: Context, token: String) {
        store(context).saveToken(token)
    }

    fun clearToken(context: Context) {
        store(context).clearToken()
    }

    fun clearHistory() {
        conversation.clear()
        lastReplyAt = 0L
    }

    // True when Gemini answered recently, so a bare "yes" may be a follow-up to it.
    fun hasRecentConversation(windowMs: Long = 90_000L): Boolean {
        val at = lastReplyAt
        return at != 0L && SystemClock.elapsedRealtime() - at < windowMs
    }

    suspend fun ask(
        context: Context,
        text: String,
        memoryProvider: suspend () -> String = { "" }
    ): AiOutcome {
        val settingsStore = store(context)
        val settings = settingsStore.load()
        if (!settings.enabled || !settings.isConfigured) return AiOutcome.NotConfigured
        val message = text.trim()
        if (message.isEmpty()) return AiOutcome.Ignored
        val now = SystemClock.elapsedRealtime()
        if (message == lastText && now - lastAt < DUPLICATE_WINDOW_MS) return AiOutcome.Ignored

        // Requests wait in line instead of being rejected as busy.
        val acquired = withTimeoutOrNull(QUEUE_WAIT_MS) {
            mutex.lock()
            true
        } ?: false
        if (!acquired) return AiOutcome.Busy
        try {
            lastText = message
            lastAt = SystemClock.elapsedRealtime()
            return perform(settingsStore, settings, message, memoryProvider)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun perform(
        settingsStore: GeminiSettingsStore,
        settings: GeminiSettings,
        message: String,
        memoryProvider: suspend () -> String
    ): AiOutcome {
        val url = settings.proxyUrl.trim()
        if (!url.startsWith("https://")) {
            return AiOutcome.Failure("The AI server address must start with https. Check Settings.")
        }
        val token = settingsStore.readToken()
        if (token.isBlank()) {
            return AiOutcome.Failure("The AI access token is missing. Add it in Settings.")
        }

        val redact = settings.redactPrivateInfo
        val filteredMessage = if (redact) PrivacyFilter.filter(message, true) else PrivacyFilter.Result(message, 0)

        val history = JSONArray()
        for (turn in conversation.snapshot(settings.historyLimit)) {
            val safe = if (redact) PrivacyFilter.filter(turn.text, true).text else turn.text
            history.put(JSONObject().put("role", turn.role).put("text", safe))
        }

        var memory = ""
        if (settings.shareMemory) {
            memory = try {
                memoryProvider().take(1500)
            } catch (e: Exception) {
                ""
            }
            if (redact && memory.isNotEmpty()) {
                memory = PrivacyFilter.filter(memory, true).text
            }
        }

        val payload = JSONObject()
            .put("message", filteredMessage.text)
            .put("history", history)
            .put("style", settings.style)
            .put("memoryContext", memory)
            .put("assistantName", "ASTRA")

        return when (val result = repository.chat(url, token, payload)) {
            is GeminiResult.Success -> {
                val reply = speakable(result.reply)
                conversation.add("user", filteredMessage.text)
                conversation.add("assistant", reply)
                lastReplyAt = SystemClock.elapsedRealtime()
                result.model?.let { settingsStore.saveStatus(it, settings.lastStatus) }
                AiOutcome.Reply(reply, cleanSuggestion(result.suggestedCommand), result.actionLabel, filteredMessage.redactions)
            }
            is GeminiResult.Failure -> AiOutcome.Failure(result.message)
        }
    }

    suspend fun testConnection(context: Context): String {
        val settingsStore = store(context)
        val settings = settingsStore.load()
        if (!settings.isConfigured) return "Not configured. Add the server address and the access token."
        val url = settings.proxyUrl.trim()
        if (!url.startsWith("https://")) return "The server address must start with https."
        val token = settingsStore.readToken()

        val health = repository.health(url, token)
        if (!health.reachable) {
            settingsStore.saveStatus("", health.message)
            return health.message
        }
        if (!health.geminiKeyPresent) {
            val msg = "Server reachable, but it has no Gemini key yet. Add GEMINI_API_KEY in Cloudflare."
            settingsStore.saveStatus(health.model, msg)
            return msg
        }

        val payload = JSONObject()
            .put("message", "Reply with the single word: ready")
            .put("history", JSONArray())
            .put("style", "short")
            .put("memoryContext", "")
        val summary = when (val result = repository.chat(url, token, payload)) {
            is GeminiResult.Success -> "Connected. Gemini answered. Model: ${result.model ?: health.model.ifBlank { "unknown" }}."
            is GeminiResult.Failure -> result.message
        }
        settingsStore.saveStatus(health.model, summary)
        return summary
    }

    // Runs a command that Gemini suggested, only through the local engine.
    fun runSuggested(processor: CommandProcessor, command: String): CommandExecutionResult {
        val result = processor.processCommand(command)
        return if (result is CommandExecutionResult.RouteToAi) {
            CommandExecutionResult.Error("I could not run that action locally.")
        } else {
            result
        }
    }

    private fun cleanSuggestion(raw: String?): String? {
        val command = raw?.trim()?.replace('\n', ' ') ?: return null
        if (command.isEmpty() || command.length > 120) return null
        return command
    }

    private fun speakable(text: String): String {
        return text
            .replace("```", " ")
            .replace("`", "")
            .replace("**", "")
            .replace("*", "")
            .replace(Regex("(?m)^\\s*#+\\s*"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), ", ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
