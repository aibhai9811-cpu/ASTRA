package com.astra.assistant.commands

import android.os.SystemClock
import com.astra.assistant.model.PendingAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

// One shared place for the action waiting for the user's yes or no.
object ConfirmationManager {

    private const val EXPIRY_MS = 45_000L
    private const val MAX_UNCLEAR = 1

    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    private var expiresAt = 0L
    private var unclearCount = 0

    @Volatile
    private var lastResolvedAt = 0L

    @Synchronized
    fun set(action: PendingAction) {
        _pending.value = action
        expiresAt = SystemClock.elapsedRealtime() + EXPIRY_MS
        unclearCount = 0
    }

    @Synchronized
    fun current(): PendingAction? {
        val action = _pending.value ?: return null
        if (SystemClock.elapsedRealtime() > expiresAt) {
            _pending.value = null
            return null
        }
        return action
    }

    // Returns the pending action and clears it, so it can never run twice.
    @Synchronized
    fun take(): PendingAction? {
        val action = current()
        if (action != null) {
            _pending.value = null
            lastResolvedAt = SystemClock.elapsedRealtime()
        }
        return action
    }

    @Synchronized
    fun clear() {
        if (_pending.value != null) lastResolvedAt = SystemClock.elapsedRealtime()
        _pending.value = null
    }

    // Returns true if the user may still try again.
    @Synchronized
    fun noteUnclear(): Boolean {
        unclearCount++
        expiresAt = SystemClock.elapsedRealtime() + EXPIRY_MS
        return unclearCount <= MAX_UNCLEAR
    }

    fun recentlyResolved(windowMs: Long = 6000L): Boolean =
        SystemClock.elapsedRealtime() - lastResolvedAt < windowMs
}

object ConfirmationParser {

    enum class Answer { YES, NO, UNCLEAR }

    private val NO_FIRST = setOf(
        "no", "nope", "nah", "nahi", "nahin", "cancel", "stop", "dont", "don't", "never", "mat",
        "abort", "negative", "नहीं", "नही", "मत", "कैंसल", "रहने", "rehne", "rahne"
    )
    private val YES_FIRST = setOf(
        "yes", "yeah", "yep", "yup", "ya", "yea", "ok", "okay", "sure", "confirm", "confirmed",
        "proceed", "haan", "han", "haa", "haanji", "hanji", "हाँ", "हां", "हा", "ठीक", "theek",
        "thik", "bilkul"
    )
    private val YES_PHRASES = listOf(
        "go ahead", "do it", "send it", "kar do", "kar dijiye", "bhej do", "laga do",
        "कर दो", "भेज दो", "ji haan", "ji han", "जी हाँ", "जी हां"
    )
    private val FILLERS = setOf("astra", "sir", "please", "pls", "thanks", "thank", "you", "now", "ji", "जी", "bhai")

    fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun parse(raw: String): Answer {
        val text = normalize(raw)
        if (text.isEmpty()) return Answer.UNCLEAR
        val tokens = text.split(" ").filter { it.isNotEmpty() }
        if (tokens.size > 5) return Answer.UNCLEAR
        val core = tokens.filter { it !in FILLERS }
        if (core.isEmpty()) return Answer.UNCLEAR
        if (core.any { it in NO_FIRST }) return Answer.NO
        if (core[0] == "do" && core.getOrNull(1) == "not") return Answer.NO
        val joined = core.joinToString(" ")
        if (YES_PHRASES.any { joined.startsWith(it) }) return Answer.YES
        if (core[0] in YES_FIRST) return Answer.YES
        return Answer.UNCLEAR
    }
}

object ChoiceParser {

    private val ORDINALS: Map<String, Int> = mapOf(
        "1" to 0, "one" to 0, "first" to 0, "ek" to 0, "pehla" to 0, "pehle" to 0, "पहला" to 0, "एक" to 0,
        "2" to 1, "two" to 1, "second" to 1, "do" to 1, "dusra" to 1, "doosra" to 1, "दूसरा" to 1, "दो" to 1,
        "3" to 2, "three" to 2, "third" to 2, "teen" to 2, "teesra" to 2, "तीसरा" to 2, "तीन" to 2,
        "4" to 3, "four" to 3, "fourth" to 3, "char" to 3, "chautha" to 3, "चौथा" to 3, "चार" to 3,
        "5" to 4, "five" to 4, "fifth" to 4, "paanch" to 4, "panch" to 4, "पांचवा" to 4, "पांच" to 4
    )

    // Returns the chosen position, or null if the answer was not clear.
    fun parse(raw: String, labels: List<String>): Int? {
        val text = ConfirmationParser.normalize(raw)
        if (text.isEmpty()) return null
        for (token in text.split(" ")) {
            val index = ORDINALS[token]
            if (index != null && index < labels.size) return index
        }
        if (text.length < 3) return null
        val matches = labels.indices.filter { i ->
            val label = ConfirmationParser.normalize(labels[i])
            label.isNotEmpty() && (label.contains(text) || text.contains(label))
        }
        return if (matches.size == 1) matches[0] else null
    }
}
