package com.astra.assistant.commands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

// Notices when a spoken name matches more than one contact, and understands the answer.
object ContactPicker {

    data class Ambiguity(val needle: String, val options: List<String>)

    private val CALL = Regex("^(?:call|dial|phone|ring)\\s+(.+)$")
    private val MESSAGE = Regex(
        "^(?:send\\s+(?:an?\\s+)?(?:(?:whatsapp|text|sms)\\s+)?(?:message|msg|massage|text)\\s+to|" +
            "text|message|msg|sms|whatsapp(?:\\s+message)?)\\s+(?:to\\s+)?(.+)$"
    )
    private val NAME_STOPS = setOf("saying", "say", "says", "that", "on", "with", "and", "ko", "ki")
    private val ORDINALS = listOf(
        setOf("1", "one", "first", "pehla", "pehle", "ek"),
        setOf("2", "two", "second", "dusra", "do"),
        setOf("3", "three", "third", "teesra", "teen"),
        setOf("4", "four", "fourth", "chautha", "char"),
        setOf("5", "five", "fifth")
    )

    private fun contactNames(context: Context): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val names = LinkedHashSet<String>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            ) ?: return emptyList()
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)?.trim().orEmpty()
                    if (name.isNotEmpty()) names.add(name)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return names.toList()
    }

    // Returns the options when the command names a contact that exists more than once.
    fun find(context: Context, command: String): Ambiguity? {
        val lower = command.lowercase(Locale.ROOT).trim()
            .replace(Regex("[,.!?]"), " ")
            .replace(Regex("\\s+"), " ")
        val rest = (CALL.find(lower) ?: MESSAGE.find(lower))?.groupValues?.get(1) ?: return null
        val tokens = ArrayList<String>()
        for (t in rest.split(" ")) {
            if (t.isEmpty()) continue
            if (tokens.isNotEmpty() && t in NAME_STOPS) break
            tokens.add(t)
            if (tokens.size == 3) break
        }
        if (tokens.isEmpty()) return null
        val names = contactNames(context)
        if (names.isEmpty()) return null

        // A full name that matches exactly is never ambiguous.
        for (n in tokens.size downTo 1) {
            val candidate = tokens.take(n).joinToString(" ")
            if (names.any { it.lowercase(Locale.ROOT) == candidate }) return null
        }
        val first = tokens[0]
        val matches = names.filter { it.lowercase(Locale.ROOT).split(" ").firstOrNull() == first }
        return if (matches.size >= 2) Ambiguity(first, matches.take(5)) else null
    }

    // The contact the user picked by saying the name or the number, or null if it was unclear.
    fun choose(options: List<String>, needle: String, spoken: String): String? {
        val s = spoken.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (s.isEmpty()) return null
        val tokens = s.split(" ")
        for ((index, set) in ORDINALS.withIndex()) {
            if (index < options.size && tokens.any { it in set }) return options[index]
        }
        val scored = options.map { option ->
            val extra = option.lowercase(Locale.ROOT).split(" ").drop(1)
            option to extra.count { it in tokens }
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second == 0) return null
        if (scored.count { it.second == best.second } > 1) return null
        return best.first
    }

    // Puts the chosen full name into the original command in place of the short name.
    fun replaceName(original: String, needle: String, full: String): String {
        val match = Regex("(?i)\\b" + Regex.escape(needle) + "\\b").find(original) ?: return original
        return original.replaceRange(match.range, full)
    }
}
