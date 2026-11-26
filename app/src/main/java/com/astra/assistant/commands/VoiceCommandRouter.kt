package com.astra.assistant.commands

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.astra.assistant.service.AstraAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

// Turns what the microphone heard into one or more commands for CommandProcessor.
// Simple device actions stay local. Anything else goes back as RouteToAi.
class VoiceCommandRouter(private val context: Context) {

    private sealed class Step {
        data class Command(val text: String) : Step()
        data class Alarm(val hour: Int, val minute: Int) : Step()
    }

    suspend fun route(processor: CommandProcessor, raw: String): CommandExecutionResult {
        val prepared = prepare(raw)
        if (prepared.isBlank()) return processor.processCommand(raw)
        val steps = splitClauses(prepared).map { interpret(it) }

        if (steps.size == 1) {
            val only = steps[0]
            return when (only) {
                is Step.Alarm -> executeAlarm(only)
                is Step.Command -> {
                    val result = processor.processCommand(only.text)
                    if (result is CommandExecutionResult.RouteToAi) CommandExecutionResult.RouteToAi(prepared) else result
                }
            }
        }

        val messages = ArrayList<String>()
        val errors = ArrayList<String>()
        for ((index, step) in steps.withIndex()) {
            if (index > 0) delay(900)
            val result: CommandExecutionResult = when (step) {
                is Step.Command -> processor.processCommand(step.text)
                is Step.Alarm -> executeAlarm(step)
            }
            when (result) {
                is CommandExecutionResult.Handled -> {
                    if (result.actionType in SPECIAL) return result
                    messages.add(result.message)
                }
                is CommandExecutionResult.RequiresConfirmation -> return result
                is CommandExecutionResult.RouteToAi -> return CommandExecutionResult.RouteToAi(prepared)
                is CommandExecutionResult.Error -> errors.add(result.error)
            }
        }
        return if (messages.isNotEmpty()) {
            CommandExecutionResult.Handled(messages.joinToString(" "), "Multiple actions", "MULTI")
        } else {
            CommandExecutionResult.Error(errors.firstOrNull() ?: "I could not do that.")
        }
    }

    // ---------------- cleaning ----------------

    private fun prepare(raw: String): String {
        var t = raw.lowercase(Locale.ROOT).trim()
        t = t.replace(Regex("[,.!?;]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = numberWords(t)
        t = t.replace(Regex("(\\d)\\s*a\\s?m\\b"), "\$1 am")
        t = t.replace(Regex("(\\d)\\s*p\\s?m\\b"), "\$1 pm")
        t = t.replace("o'clock", "")
        t = t.replace(Regex("\\btop (?:the )?(song|songs|music|track|playing|playback)\\b"), "stop \$1")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun numberWords(text: String): String {
        val tokens = text.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val ten = TENS[t]
            val unit = UNITS[t]
            if (ten != null) {
                var value = ten
                val next = if (i + 1 < tokens.size) UNITS[tokens[i + 1]] else null
                if (next != null && next in 1..9) {
                    value += next
                    i++
                }
                out.add(value.toString())
            } else if (unit != null) {
                var value = unit
                if (i + 1 < tokens.size && tokens[i + 1] == "hundred") {
                    value *= 100
                    i++
                }
                out.add(value.toString())
            } else if (t == "hundred") {
                out.add("100")
            } else {
                out.add(t)
            }
            i++
        }
        return out.joinToString(" ")
    }

    private fun splitClauses(text: String): List<String> {
        if (NO_SPLIT.containsMatchIn(text) || text.contains(" saying ")) return listOf(text)
        val parts = text.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(text)
        return if (parts.all { startsLikeCommand(it) }) parts else listOf(text)
    }

    private fun startsLikeCommand(piece: String): Boolean {
        val first = stripFillers(piece).split(" ").firstOrNull().orEmpty()
        return first in COMMAND_STARTS
    }

    private fun stripFillers(clause: String): String {
        var c = clause.trim()
        for (round in 0 until 2) {
            c = LEAD_SIMPLE.replace(c, "")
            c = LEAD_POLITE.replace(c, "")
            c = TAIL_FILLER.replace(c, "")
            c = c.trim()
        }
        return c
    }

    // ---------------- interpretation ----------------

    private fun interpret(clause: String): Step {
        var c = stripFillers(clause)
        c = fixAppNames(c)

        val alarm = ALARM_A.find(c) ?: ALARM_B.find(c)
        if (alarm != null) {
            val hour = alarm.groupValues[1].toIntOrNull()
            if (hour != null) {
                val minute = alarm.groupValues[2].toIntOrNull() ?: 0
                val meridian = alarm.groupValues[3]
                var h = hour
                if (meridian == "pm" && h < 12) h += 12
                if (meridian == "am" && h == 12) h = 0
                if (h in 0..23 && minute in 0..59) return Step.Alarm(h, minute)
            }
        }
        return Step.Command(rewrite(c))
    }

    private fun rewrite(clause: String): String {
        var c = clause
        when {
            YT_WATCH.matches(c) -> return "open youtube"
            YT_SEARCH.matches(c) -> {
                val query = YT_SEARCH.find(c)?.groupValues?.get(1).orEmpty()
                return if (query.isBlank()) "open youtube" else "youtube $query"
            }
            PLAY_MUSIC.matches(c) -> return "play music"
            STOP_MUSIC.matches(c) -> return "stop music"
            PAUSE_MUSIC.matches(c) -> return "pause music"
            NEXT_SONG.matches(c) -> return "next song"
            PREV_SONG.matches(c) -> return "previous song"
        }
        if ((c.contains("volume") || c.contains("brightness")) && !c.contains(Regex("\\d"))) {
            if (LITTLE.containsMatchIn(c)) {
                c = LITTLE.replace(c, "").replace(Regex("\\s+"), " ").trim() + " by 8"
            } else if (LOT.containsMatchIn(c)) {
                c = LOT.replace(c, "").replace(Regex("\\s+"), " ").trim() + " by 30"
            }
        }
        return c
    }

    // ---------------- app names ----------------

    private fun knownApps(): List<String> = (CommandVocabulary.appLabels + COMMON_APPS).distinct()

    // Repairs recognition slips such as "open you to" or "oh you too" when the target is a known app.
    private fun fixAppNames(clause: String): String {
        val tokens = clause.split(" ").filter { it.isNotEmpty() }
        if (tokens.size < 2) return clause
        if (tokens[0] !in OPEN_VERBS) return clause
        val rest = tokens.drop(1)
        val labels = knownApps()
        for (n in minOf(3, rest.size) downTo 1) {
            val candidate = rest.take(n).joinToString("")
            val match = bestApp(candidate, labels)
            if (match != null) {
                return (listOf("open", match) + rest.drop(n)).joinToString(" ")
            }
        }
        return clause
    }

    private fun bestApp(candidate: String, labels: List<String>): String? {
        if (candidate.length < 3) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (label in labels) {
            val key = label.replace(" ", "")
            if (key.length < 3) continue
            if (candidate == key) return label
            val d = levenshtein(candidate, key)
            val close = candidate.length >= 4 && d <= maxOf(1, key.length / 3)
            val prefix = candidate.length >= 4 &&
                key.startsWith(candidate.take(4)) &&
                key.length - candidate.length in 0..4
            if ((close || prefix) && d < bestDistance) {
                best = label
                bestDistance = d
            }
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    // ---------------- alarm ----------------

    private fun executeAlarm(step: Step.Alarm): CommandExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, step.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, step.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "ASTRA alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val hour12 = when {
            step.hour == 0 -> 12
            step.hour > 12 -> step.hour - 12
            else -> step.hour
        }
        val suffix = if (step.hour < 12) "AM" else "PM"
        val label = "$hour12:${step.minute.toString().padStart(2, '0')} $suffix"
        return try {
            val service = AstraAccessibilityService.instance
            if (service != null) service.startActivity(intent) else context.startActivity(intent)
            CommandExecutionResult.Handled("Asked the clock app to set an alarm for $label.", "Alarm", "SET_ALARM")
        } catch (e: ActivityNotFoundException) {
            CommandExecutionResult.Error("I could not find a clock app to set the alarm.")
        } catch (e: Exception) {
            CommandExecutionResult.Error("Android blocked the alarm request.")
        }
    }

    companion object {
        private val SPECIAL = setOf("STOP_LISTENING", "HUSH", "USE_BT_MIC", "USE_PHONE_MIC")

        private val UNITS = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
        )

        private val SPLIT = Regex("\\s+(?:and then|and also|after that|and|then|also)\\s+")
        private val NO_SPLIT = Regex("^(?:text|message|send|whatsapp|call|dial|say|search|google|remember|note)\\b")

        private val COMMAND_STARTS = setOf(
            "open", "launch", "start", "stop", "pause", "play", "resume", "next", "previous", "skip", "turn",
            "set", "increase", "decrease", "mute", "unmute", "volume", "brightness", "go", "take", "close",
            "lower", "raise", "flashlight", "bluetooth", "wifi", "call", "text", "send", "search", "watch",
            "listen", "wake", "oh", "kholo", "what"
        )
        private val OPEN_VERBS = setOf("open", "launch", "start", "opened", "opening", "run", "kholo", "oh")

        private val LEAD_SIMPLE = Regex("^(?:(?:hey|ok|okay|please|just|now|so)\\s+)+")
        private val LEAD_POLITE = Regex(
            "^(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?|i\\s+(?:want|wanna|need|would like|'d like)\\s+to\\s+|" +
                "i\\s+want\\s+you\\s+to\\s+|let'?s\\s+)"
        )
        private val TAIL_FILLER = Regex("\\s+(?:please|now|for me)$")

        private val YT_WATCH = Regex(
            "^(?:watch|see|view)(?:\\s+(?:something|some videos?|videos?|a video|a movie|movies?))?\\s+on youtube$"
        )
        private val YT_SEARCH = Regex("^(?:watch|search|find|play)\\s+(.+?)\\s+on youtube$")
        private val PLAY_MUSIC = Regex(
            "^(?:play|put on|start|listen to|play me)\\s+(?:some\\s+|a\\s+|the\\s+)?(?:music|songs?|gaana|gana|track)$"
        )
        private val STOP_MUSIC = Regex(
            "^(?:stop|end|halt|turn off)\\s+(?:the\\s+|this\\s+|that\\s+)?(?:song|songs|music|track|playing|playback|audio)$"
        )
        private val PAUSE_MUSIC = Regex(
            "^pause\\s+(?:the\\s+|this\\s+|that\\s+)?(?:song|songs|music|track|playing|playback|audio)$"
        )
        private val NEXT_SONG = Regex(
            "^(?:play\\s+)?(?:the\\s+)?next(?:\\s+(?:song|track))?$|^skip(?:\\s+(?:this|the))?(?:\\s+(?:song|track))?$"
        )
        private val PREV_SONG = Regex("^(?:play\\s+)?(?:the\\s+)?previous(?:\\s+(?:song|track))?$")

        private val LITTLE = Regex("\\b(?:a little|a bit|slightly|little|bit)\\b")
        private val LOT = Regex("\\b(?:a lot|much|way)\\b")

        private val ALARM_A = Regex(
            "(?:set|create|make|add|start|put)\\s+(?:an?\\s+|the\\s+)?alarm\\s+(?:for|at|on)\\s+(\\d{1,2})" +
                "(?:(?::|\\s)(\\d{2}))?\\s*(am|pm)?"
        )
        private val ALARM_B = Regex(
            "wake\\s+me\\s+(?:up\\s+)?(?:at|by)\\s+(\\d{1,2})(?:(?::|\\s)(\\d{2}))?\\s*(am|pm)?"
        )

        private val COMMON_APPS = listOf(
            "youtube", "whatsapp", "instagram", "chrome", "facebook", "telegram", "spotify", "gmail",
            "maps", "camera", "settings", "calculator", "clock", "gallery", "photos", "messages",
            "contacts", "phone", "snapchat", "twitter"
        )
    }
}
