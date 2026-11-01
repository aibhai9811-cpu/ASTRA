package com.astra.assistant.commands

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.astra.assistant.model.PendingAction
import com.astra.assistant.service.AstraAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CommandExecutionResult {
    data class Handled(val message: String, val actionLabel: String? = null, val actionType: String? = null) : CommandExecutionResult()
    data class RequiresConfirmation(val pendingAction: PendingAction) : CommandExecutionResult()
    data class RouteToAi(val query: String) : CommandExecutionResult()
    data class Error(val error: String) : CommandExecutionResult()
}

class CommandProcessor(private val context: Context) {

    fun processCommand(input: String): CommandExecutionResult {
        val query = input.trim()
        if (query.isEmpty()) {
            return CommandExecutionResult.Error("No command text detected.")
        }
        val lower = query.lowercase(Locale.ROOT)
        val key = lower.trimEnd('.', '!', '?', ' ')

        // Greetings
        if (key in GREETINGS) {
            return CommandExecutionResult.Handled("Hello, sir. ASTRA is online and ready.", "Greeting", "GREETING")
        }
        if (key == "how are you" || key == "kaise ho") {
            return CommandExecutionResult.Handled("All systems nominal, sir. How can I help?", "Status", "STATUS")
        }

        // Stop listening
        if (key in STOP_WORDS) {
            return CommandExecutionResult.Handled("Continuous listening paused. Standing by.", "Pause Listening", "STOP_LISTENING")
        }

        // Phone-wide navigation (needs the Accessibility service)
        if (key in BACK_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", "Back", "NAVIGATE_BACK")
        if (key in HOME_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_HOME, "Going to the home screen.", "Home", "NAVIGATE_HOME")
        if (key in RECENT_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps.", "Recents", "NAVIGATE_RECENTS")
        if (key in NOTIFICATION_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opening notifications.", "Notifications", "NAVIGATE_NOTIFICATIONS")

        // Incoming call control
        if (key in ANSWER_WORDS) return answerCall()
        if (key in END_CALL_WORDS) return endCall()

        // Accessibility settings
        if (lower.contains("accessibility")) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return CommandExecutionResult.Handled(
                "Opening Accessibility settings. Turn on ASTRA there.", "Accessibility", "OPEN_ACCESSIBILITY"
            )
        }

        // Time
        if (key == "time" || lower.contains("what time") || lower.contains("current time") ||
            lower.contains("the time") || lower.contains("time is it") ||
            lower.contains("समय") || lower.contains("बजे")
        ) {
            val formatted = SimpleDateFormat("h:mm a, EEEE, MMM d", Locale.getDefault()).format(Date())
            return CommandExecutionResult.Handled("The current time is $formatted, sir.", "Time Query", "GET_TIME")
        }

        // Research and information questions
        if (RESEARCH_PREFIXES.any { lower == it || lower.startsWith("$it ") }) {
            return CommandExecutionResult.RouteToAi(query)
        }

        // Settings
        if (lower.contains("setting") || lower.contains("सेटिंग")) {
            launch(Intent(Settings.ACTION_SETTINGS))
            return CommandExecutionResult.Handled("Opening Android device settings.", "Settings", "OPEN_SETTINGS")
        }

        // YouTube
        if (lower.contains("youtube") || lower.contains("यूट्यूब")) {
            val queryParam = query.replace("(?i)^(open|launch|search|on)?\\s*(youtube|यूट्यूब)\\s*".toRegex(), "").trim()
            val intent = if (queryParam.isNotEmpty()) {
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", queryParam)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            }
            launch(intent)
            return CommandExecutionResult.Handled("Opening YouTube.", "YouTube", "OPEN_YOUTUBE")
        }

        // Camera
        if (lower.contains("camera") || lower.contains("कैमरा") || lower.contains("फोटो")) {
            launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            return CommandExecutionResult.Handled("Opening camera viewfinder.", "Camera", "OPEN_CAMERA")
        }

        // Alarms and clock
        if (lower.contains("alarm") || lower.contains("अलार्म") || lower.contains("clock")) {
            launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            return CommandExecutionResult.Handled("Opening Android clock and alarms.", "Clock & Alarms", "OPEN_CLOCK")
        }

        // Music
        if (lower.contains("play music") || lower.contains("spotify") || lower.contains("gaana")) {
            launch(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH))
            return CommandExecutionResult.Handled("Starting media playback.", "Play Media", "PLAY_MEDIA")
        }

        // Maps
        if (lower.contains("maps") || lower.contains("navigate") || lower.contains("रास्ता")) {
            val dest = query.replace("(?i)^(open|launch|navigate|directions|to)?\\s*(maps?)?\\s*(to)?\\s*".toRegex(), "").trim()
            val uri = if (dest.isNotEmpty()) Uri.parse("geo:0,0?q=" + Uri.encode(dest)) else Uri.parse("geo:0,0")
            launch(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
            return CommandExecutionResult.Handled("Launching Google Maps.", "Maps", "OPEN_MAPS")
        }

        // Text message
        val sms = parseSms(query)
        if (sms != null) {
            val (name, body) = sms
            if (!hasPermission(Manifest.permission.SEND_SMS)) {
                return CommandExecutionResult.Error("I need the SMS permission to send messages. Open ASTRA and allow it.")
            }
            val contact = resolveContact(name)
                ?: return CommandExecutionResult.Error("I could not find $name in your contacts.")
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction(
                    "SEND_SMS",
                    contact.second + "\n" + body,
                    "Send \"$body\" to ${contact.first}?",
                    "Text ${contact.first}"
                )
            )
        }

        // Make a call
        val callTarget = extractCallTarget(query, lower)
        if (callTarget != null) {
            if (callTarget.isEmpty()) {
                return CommandExecutionResult.Error("Who should I call?")
            }
            val contact = resolveContact(callTarget)
                ?: return CommandExecutionResult.Error("I could not find $callTarget in your contacts.")
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction("CALL_PHONE", contact.second, "Call ${contact.first}?", "Call ${contact.first}")
            )
        }

        // Clear history
        if (lower.contains("clear history") || lower.contains("wipe data")) {
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction("CLEAR_HISTORY", "all", "Confirm permanent wipe of all conversation history?", "Wipe Logs")
            )
        }

        // Web search
        val searchTerm = extractSearchTerm(query, lower)
        if (searchTerm != null && searchTerm.isNotEmpty()) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(searchTerm))))
            return CommandExecutionResult.Handled("Searching the web for $searchTerm.", "Web Search", "WEB_SEARCH")
        }

        // Open any installed app
        val appName = extractAppName(query, lower)
        if (appName != null && appName.isNotEmpty()) {
            val found = findApp(appName)
            return if (found != null) {
                launch(found.second)
                CommandExecutionResult.Handled("Opening ${found.first}.", found.first, "OPEN_APP")
            } else {
                CommandExecutionResult.Error("I could not find an app called $appName.")
            }
        }

        return CommandExecutionResult.RouteToAi(query)
    }

    fun executeConfirmedAction(action: PendingAction): CommandExecutionResult {
        return when (action.actionType) {
            "CALL_PHONE" -> {
                val uri = Uri.fromParts("tel", action.target, null)
                val intent = if (hasPermission(Manifest.permission.CALL_PHONE)) {
                    Intent(Intent.ACTION_CALL, uri)
                } else {
                    Intent(Intent.ACTION_DIAL, uri)
                }
                launch(intent)
                CommandExecutionResult.Handled("Placing the call.", action.label)
            }
            "SEND_SMS" -> {
                val parts = action.target.split("\n", limit = 2)
                val number = parts[0]
                val body = parts.getOrElse(1) { "" }
                if (!hasPermission(Manifest.permission.SEND_SMS)) {
                    CommandExecutionResult.Error("I need the SMS permission to send messages.")
                } else if (body.isBlank()) {
                    CommandExecutionResult.Error("The message was empty.")
                } else {
                    sendSms(number, body)
                    CommandExecutionResult.Handled("Message sent.", action.label)
                }
            }
            "CLEAR_HISTORY" -> CommandExecutionResult.Handled("Conversation logs wiped.", "Wipe Logs")
            else -> CommandExecutionResult.Error("Action cancelled.")
        }
    }

    // ---------------- helpers ----------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val service = AstraAccessibilityService.instance
        if (service != null) {
            service.startActivity(intent)
        } else {
            context.startActivity(intent)
        }
    }

    private fun navigate(action: Int, message: String, label: String, type: String): CommandExecutionResult {
        if (!AstraAccessibilityService.isEnabled()) {
            return CommandExecutionResult.Error(ACCESSIBILITY_HINT)
        }
        return if (AstraAccessibilityService.perform(action)) {
            CommandExecutionResult.Handled(message, label, type)
        } else {
            CommandExecutionResult.Error("Android did not accept that action.")
        }
    }

    private fun answerCall(): CommandExecutionResult {
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return CommandExecutionResult.Error("I need the Phone permission to answer calls. Open ASTRA and allow it.")
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            CommandExecutionResult.Handled("Answering the call.", "Answer Call", "ANSWER_CALL")
        } catch (e: SecurityException) {
            CommandExecutionResult.Error("Android blocked me from answering the call.")
        }
    }

    private fun endCall(): CommandExecutionResult {
        if (Build.VERSION.SDK_INT < 28) {
            return CommandExecutionResult.Error("Ending calls needs Android 9 or newer.")
        }
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return CommandExecutionResult.Error("I need the Phone permission to end calls. Open ASTRA and allow it.")
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.endCall()
            CommandExecutionResult.Handled("Call ended.", "End Call", "END_CALL")
        } catch (e: SecurityException) {
            CommandExecutionResult.Error("Android blocked me from ending the call.")
        }
    }

    // Returns Pair(display name, phone number) or null.
    private fun resolveContact(nameOrNumber: String): Pair<String, String>? {
        val cleaned = nameOrNumber.trim()
        if (cleaned.length >= 5 && cleaned.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
            return Pair(cleaned, cleaned.filter { it.isDigit() || it == '+' })
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$cleaned%"),
            null
        ) ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                val number = it.getString(1) ?: return null
                return Pair(it.getString(0) ?: cleaned, number)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun sendSms(number: String, body: String) {
        val manager = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = manager.divideMessage(body)
        manager.sendMultipartTextMessage(number, null, parts, null, null)
    }

    private fun parseSms(query: String): Pair<String, String>? {
        val match = SMS_START.find(query.trim()) ?: return null
        val rest = match.groupValues[1].trim()
        val separator = SMS_SEPARATOR.find(rest)
        if (separator != null) {
            val name = rest.substring(0, separator.range.first).trim()
            val body = rest.substring(separator.range.last + 1).trim()
            return if (name.isEmpty() || body.isEmpty()) null else Pair(name, body)
        }
        val parts = rest.split(" ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return null
        return Pair(parts[0], parts[1].trim())
    }

    // Returns the person to call, "" if none was said, or null if this is not a call command.
    private fun extractCallTarget(query: String, lower: String): String? {
        for (p in CALL_PREFIXES) {
            if (lower == p.trim()) return ""
            if (lower.startsWith(p)) return query.substring(p.length).trim()
        }
        return null
    }

    private fun extractSearchTerm(query: String, lower: String): String? {
        for (p in listOf("search for ", "search ", "google ")) {
            if (lower.startsWith(p)) return query.substring(p.length).trim()
        }
        return null
    }

    private fun extractAppName(query: String, lower: String): String? {
        for (p in listOf("open ", "launch ", "start ", "run ", "kholo ")) {
            if (lower.startsWith(p)) {
                return query.substring(p.length).trim().removeSuffix(" app").trim()
            }
        }
        for (s in listOf(" kholo", " khol do", " खोलो", " खोल दो", " open karo", " chalu karo")) {
            if (lower.endsWith(s)) return query.substring(0, query.length - s.length).trim()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findApp(name: String): Pair<String, Intent>? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val target = name.lowercase(Locale.ROOT).trim()
        var bestLabel: String? = null
        var bestPackage: String? = null
        var bestScore = 0
        for (info in pm.queryIntentActivities(main, 0)) {
            val label = info.loadLabel(pm).toString()
            val l = label.lowercase(Locale.ROOT)
            val score = when {
                l == target -> 100
                l.startsWith(target) -> 80
                l.contains(target) -> 60
                l.length >= 3 && target.contains(l) -> 40
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
                bestPackage = info.activityInfo.packageName
            }
        }
        val pkg = bestPackage ?: return null
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return null
        return Pair(bestLabel ?: name, intent)
    }

    companion object {
        private const val ACCESSIBILITY_HINT =
            "Turn on ASTRA in Settings, Accessibility, to use Back, Home and Recents. Say open accessibility settings."

        private val GREETINGS = setOf(
            "hi", "hii", "hello", "hey", "hi astra", "hello astra", "hey astra", "namaste", "namaskar", "नमस्ते"
        )
        private val STOP_WORDS = setOf("stop listening", "ruk jao", "so jao")
        private val BACK_WORDS = setOf("back", "go back", "press back", "previous screen", "peeche jao", "पीछे जाओ")
        private val HOME_WORDS = setOf(
            "home", "go home", "go to home", "home screen", "go to home screen", "press home", "ghar jao", "होम", "होम स्क्रीन"
        )
        private val RECENT_WORDS = setOf(
            "recent apps", "recents", "recent", "show recent apps", "open recent apps", "open recents",
            "app switcher", "multitasking", "रीसेंट ऐप्स"
        )
        private val NOTIFICATION_WORDS = setOf(
            "notifications", "open notifications", "show notifications", "notification panel"
        )
        private val ANSWER_WORDS = setOf(
            "answer", "answer call", "answer the call", "pick up", "pick up call", "pick up the call",
            "pickup call", "receive call", "receive the call", "accept call", "phone uthao", "call uthao",
            "फोन उठाओ", "कॉल उठाओ"
        )
        private val END_CALL_WORDS = setOf(
            "end call", "end the call", "cut call", "hang up", "reject call", "decline call", "call kaat do", "फोन काटो"
        )
        private val RESEARCH_PREFIXES = listOf(
            "research", "analyze", "analyse", "analysis", "tell me about", "what is", "what are",
            "what's", "who is", "who was", "explain", "define", "information about", "find information about"
        )
        private val CALL_PREFIXES = listOf(
            "make a call to ", "make call to ", "make a call", "make call", "call ", "dial ", "phone ", "फोन लगाओ "
        )
        private val SMS_START = Regex(
            "^(?:send\\s+(?:a\\s+)?(?:text\\s+)?(?:message|sms|text)|text\\s+message|sms|message|text)\\s+(?:to\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val SMS_SEPARATOR = Regex(
            "\\s+(?:saying|that says|that|say|says|with message)\\s+",
            RegexOption.IGNORE_CASE
        )
    }
}
