package com.astra.assistant.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.astra.assistant.model.PendingAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

// One shared place for "waiting for yes or no", used by the screen and the background service.
object ConfirmationManager {

    enum class Answer { YES, NO, UNCLEAR }

    private const val TIMEOUT_MS = 30_000L
    private const val MAX_WORDS = 7

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: PendingAction? = null
    private var createdAt = 0L
    private var unclearCount = 0

    private val pendingState = MutableStateFlow<PendingAction?>(null)
    val pendingFlow: StateFlow<PendingAction?> = pendingState.asStateFlow()

    private val expireRunnable = Runnable { clear() }

    private val NOISE = Regex("[^\\p{L}\\p{M}\\p{N}' ]")
    private val SPACES = Regex("\\s+")

    private val NO_PHRASES = listOf(
        "no", "nope", "nah", "cancel", "stop", "don't", "dont", "do not", "never mind", "nevermind",
        "nahi", "nahin", "nai", "mat karo", "mat kar", "matt karo", "rehne do", "rahne do",
        "नहीं", "नही", "मत करो", "रहने दो"
    )
    private val YES_PHRASES = listOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed", "do it", "go ahead",
        "proceed", "absolutely", "please do", "correct", "right",
        "haan", "han", "ha", "haanji", "haan ji", "ji haan", "ji han", "kar do", "kardo", "bilkul",
        "theek hai", "thik hai",
        "हाँ", "हां", "हा", "जी हाँ", "जी हां", "कर दो", "करदो", "ठीक है", "बिलकुल"
    )

    fun set(action: PendingAction) {
        synchronized(lock) {
            pending = action
            createdAt = SystemClock.elapsedRealtime()
            unclearCount = 0
            pendingState.value = action
        }
        handler.removeCallbacks(expireRunnable)
        handler.postDelayed(expireRunnable, TIMEOUT_MS)
    }

    fun peek(): PendingAction? = synchronized(lock) { activeOrNull() }

    fun take(): PendingAction? {
        val taken = synchronized(lock) {
            val action = activeOrNull()
            pending = null
            pendingState.value = null
            action
        }
        handler.removeCallbacks(expireRunnable)
        return taken
    }

    fun clear() {
        synchronized(lock) {
            pending = null
            pendingState.value = null
        }
        handler.removeCallbacks(expireRunnable)
    }

    fun noteUnclear(): Int = synchronized(lock) {
        unclearCount++
        unclearCount
    }

    private fun activeOrNull(): PendingAction? {
        val current = pending ?: return null
        if (SystemClock.elapsedRealtime() - createdAt > TIMEOUT_MS) {
            pending = null
            pendingState.value = null
            return null
        }
        return current
    }

    private fun normalize(raw: String): String =
        " " + raw.lowercase(Locale.ROOT).replace(NOISE, " ").replace(SPACES, " ").trim() + " "

    fun interpret(raw: String): Answer {
        val text = normalize(raw)
        if (text.trim().split(" ").size > MAX_WORDS) return Answer.UNCLEAR
        if (NO_PHRASES.any { text.contains(" $it ") }) return Answer.NO
        if (YES_PHRASES.any { text.contains(" $it ") }) return Answer.YES
        return Answer.UNCLEAR
    }

    fun isBareAnswer(raw: String): Boolean = interpret(raw) != Answer.UNCLEAR
}
