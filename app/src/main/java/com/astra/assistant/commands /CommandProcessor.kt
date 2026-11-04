package com.astra.assistant.commands

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.astra.assistant.model.PendingAction
import com.astra.assistant.service.AstraAccessibilityService
import com.astra.assistant.util.Calculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

sealed class CommandExecutionResult {
    data class Handled(val message: String, val actionLabel: String? = null, val actionType: String? = null) : CommandExecutionResult()
    data class RequiresConfirmation(val pendingAction: PendingAction) : CommandExecutionResult()
    data class RouteToAi(val query: String) : CommandExecutionResult()
    data class Error(val error: String) : CommandExecutionResult()
}

class CommandProcessor(private val context: Context) {

    private class MessageRequest(val viaWhatsApp: Boolean, val explicit: Boolean, val rest: String)
    private class Choice(val kind: String, val body: String, val options: List<Pair<String, String>>)

    private val audio: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Local commands match only at the START of a sentence. Everything else goes to Gemini.
    fun processCommand(input: String): CommandExecutionResult {
        val raw = input.trim()
        if (raw.isEmpty()) return CommandExecutionResult.Error("No command text detected.")
        val cmd = stripPoliteness(raw.trimEnd('.', '!', '?', ' '))
        val lower = cmd.lowercase(Locale.ROOT)
        if (lower.isEmpty()) return CommandExecutionResult.Error("No command text detected.")

        // 1. Small talk, ASTRA's identity, maths
        if (lower in GREETINGS) {
            return CommandExecutionResult.Handled("Hello, sir. ASTRA is online and ready.", "Greeting", "GREETING")
        }
        if (lower == "how are you" || lower == "kaise ho") {
            return CommandExecutionResult.Handled("All systems nominal, sir. How can I help?", "Status", "STATUS")
        }
        identityReply(lower)?.let {
            return CommandExecutionResult.Handled(it, "About ASTRA", "IDENTITY")
        }
        Calculator.tryEvaluate(cmd)?.let {
            return CommandExecutionResult.Handled(it, "Calculator", "CALCULATE")
        }
        if (lower in STOP_WORDS) {
            return CommandExecutionResult.Handled("Continuous listening paused. Standing by.", "Pause Listening", "STOP_LISTENING")
        }

        // 2. Phone navigation (needs the Accessibility service) and calls in progress
        if (lower in BACK_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", "Back", "NAVIGATE_BACK")
        if (lower in HOME_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_HOME, "Going to the home screen.", "Home", "NAVIGATE_HOME")
        if (lower in RECENT_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps.", "Recents", "NAVIGATE_RECENTS")
        if (lower in NOTIFICATION_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opening notifications.", "Notifications", "NAVIGATE_NOTIFICATIONS")
        if (lower in SCREENSHOT_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Taking a screenshot.", "Screenshot", "SCREENSHOT")
        if (lower in ANSWER_WORDS) return answerCall()
        if (lower in END_CALL_WORDS) return endCall()
        if (lower in ACCESSIBILITY_PHRASES) {
            return if (launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                CommandExecutionResult.Handled("Opening Accessibility settings. Turn on ASTRA there.", "Accessibility", "OPEN_ACCESSIBILITY")
            } else {
                CommandExecutionResult.Error("I could not open Accessibility settings.")
            }
        }

        // 3. Time and date
        if (lower in TIME_QUERIES) {
            val formatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return CommandExecutionResult.Handled("The time is $formatted.", "Time", "GET_TIME")
        }
        if (lower in DATE_QUERIES) {
            val formatted = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            return CommandExecutionResult.Handled("Today is $formatted.", "Date", "GET_DATE")
        }

        // 4. System controls
        mediaCommand(lower)?.let { return it }
        volumeCommand(lower)?.let { return it }
        brightnessCommand(lower)?.let { return it }
        alarmCommand(cmd, lower)?.let { return it }

        // 5. WhatsApp, text messages, calls
        whatsappCommand(cmd, lower)?.let { return it }
        messageCommand(cmd)?.let { return it }
        callCommand(cmd)?.let { return it }

        // 6. Media search, apps, maps, web
        youtubeCommand(cmd)?.let { return it }
        playCommand(cmd)?.let { return it }
        appCommand(cmd)?.let { return it }
        mapsCommand(cmd)?.let { return it }
        searchCommand(cmd)?.let { return it }
        if (lower in CLEAR_WORDS) {
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction("CLEAR_HISTORY", "all", "Wipe the conversation history?", "Wipe Logs")
            )
        }

        return CommandExecutionResult.RouteToAi(raw)
    }

    fun executeConfirmedAction(action: PendingAction): CommandExecutionResult {
        return when (action.actionType) {
            "CALL_PHONE" -> placeCall(action.target, false, action.label)
            "CALL_SPEAKER" -> placeCall(action.target, true, action.label)
            "SEND_SMS" -> {
                val parts = action.target.split("\n", limit = 2)
                val number = parts[0]
                val body = parts.getOrElse(1) { "" }
                if (!hasPermission(Manifest.permission.SEND_SMS)) {
                    CommandExecutionResult.Error("I need the SMS permission to send text messages.")
                } else if (body.isBlank()) {
                    CommandExecutionResult.Error("The message was empty.")
                } else {
                    sendSms(number, body)
                    CommandExecutionResult.Handled("I sent the text message. Android does not tell me if it was delivered.", action.label)
                }
            }
            "SEND_WHATSAPP" -> {
                val parts = action.target.split("\n", limit = 2)
                val number = parts[0]
                val body = parts.getOrElse(1) { "" }
                if (body.isBlank()) {
                    CommandExecutionResult.Error("The message was empty.")
                } else if (!openWhatsAppChatUri(number, body)) {
                    CommandExecutionResult.Error("I could not open WhatsApp. Is it installed?")
                } else {
                    val service = AstraAccessibilityService.instance
                    if (service != null) {
                        service.autoSendWhatsApp()
                        CommandExecutionResult.Handled("WhatsApp is open with your message. I am pressing send. Please check that it went through.", action.label)
                    } else {
                        CommandExecutionResult.Handled("WhatsApp is open with your message. Tap send to finish.", action.label)
                    }
                }
            }
            "WHATSAPP_CALL", "WHATSAPP_VIDEO" -> {
                val video = action.actionType == "WHATSAPP_VIDEO"
                val parts = action.target.split("\n", limit = 2)
                val number = parts[0]
                val name = parts.getOrElse(1) { "the contact" }
                val call = findWhatsAppCall(number, video)
                if (call == null) {
                    if (openWhatsAppChatUri(number, null)) {
                        CommandExecutionResult       // Open any installed app
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
            "CALL_PHONE" -> placeCall(action.target, false, action.label)
            "CALL_SPEAKER" -> placeCall(action.target, true, action.label)
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
            "SEND_WHATSAPP" -> {
                val parts = action.target.split("\n", limit = 2)
                val number = parts[0]
                val body = parts.getOrElse(1) { "" }
                if (body.isBlank()) {
                    CommandExecutionResult.Error("The message was empty.")
                } else if (!openWhatsAppChat(number, body)) {
                    CommandExecutionResult.Error("I could not open WhatsApp. Is it installed?")
                } else {
                    val service = AstraAccessibilityService.instance
                    if (service != null) {
                        service.autoSendWhatsApp()
                        CommandExecutionResult.Handled("Sending your WhatsApp message.", action.label)
                    } else {
                        CommandExecutionResult.Handled("WhatsApp is open with your message. Tap send to finish.", action.label)
                    }
                }
            }
            "CLEAR_HISTORY" -> CommandExecutionResult.Handled("Conversation logs wiped.", "Wipe Logs")
            else -> CommandExecutionResult.Error("Action cancelled.")
        }
    }

    // ---------------- identity ----------------

    private fun identityReply(lower: String, key: String): String? {
        val asksFullName = key in FULL_NAME_QUESTIONS ||
            (lower.contains("astra") && (lower.contains("full form") || lower.contains("full name") ||
                lower.contains("stand for") || lower.contains("stands for")))
        if (asksFullName) {
            return "My full name is ASTRA, which stands for Adaptive System for Thinking, Reasoning and Assistance."
        }
        if (key in CREATOR_QUESTIONS) {
            return "I was created by $CREATOR, who built me as a personal voice assistant."
        }
        if (key in ABOUT_QUESTIONS) {
            return "I am ASTRA, which stands for Adaptive System for Thinking, Reasoning and Assistance. " +
                "I am your personal voice assistant. I can open apps, make calls, send messages, solve maths and research topics."
        }
        if (key in HELP_QUESTIONS) {
            return "I can open any app, call or text your contacts, send WhatsApp messages, go back or home, " +
                "solve maths, search the web and research topics. Say ASTRA first when I am in the background."
        }
        return null
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

    private fun placeCall(number: String, speaker: Boolean, label: String): CommandExecutionResult {
        val uri = Uri.fromParts("tel", number, null)
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_DIAL, uri))
            return CommandExecutionResult.Handled(
                "I opened the dialer. Allow the Phone permission for ASTRA to call in one step.", label
            )
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle()
            if (speaker) {
                extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            telecom.placeCall(uri, extras)
            CommandExecutionResult.Handled(if (speaker) "Calling on speaker." else "Placing the call.", label)
        } catch (e: SecurityException) {
            CommandExecutionResult.Error("Android blocked the call.")
        }
    }

    // ---------------- messages ----------------

    private fun parseMessage(query: String): MessageRequest? {
        if (extractAppName(query, query.lowercase(Locale.ROOT)) != null) return null
        val viaWhatsApp = WHATSAPP_WORD.containsMatchIn(query)
        var cleaned = query.replace(WHATSAPP_PREPOSITION, "")
        cleaned = cleaned.replace(WHATSAPP_LEADING, "message ")
        val match = MESSAGE_START.find(cleaned.trim()) ?: return null
        val rest = match.groupValues[1].trim()
        if (rest.isEmpty()) return null
        return MessageRequest(viaWhatsApp, rest)
    }

    private fun prepareMessage(request: MessageRequest): CommandExecutionResult {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return CommandExecutionResult.Error("I need the Contacts permission to find who to message. Open ASTRA and allow it.")
        }
        if (!request.viaWhatsApp && !hasPermission(Manifest.permission.SEND_SMS)) {
            return CommandExecutionResult.Error("I need the SMS permission to send text messages. Open ASTRA and allow it.")
        }

        var contactName: String
        var number: String
        var body: String

        val split = splitContactFromText(request.rest)
        if (split != null) {
            contactName = split.first
            number = split.second
            body = cleanBody(split.third)
        } else {
            val separator = SMS_SEPARATOR.find(request.rest)
                ?: return CommandExecutionResult.Error("I could not tell who to message. Say, text Rahul, I will be late.")
            val name = request.rest.substring(0, separator.range.first).trim()
            val contact = resolveContact(name)
                ?: return CommandExecutionResult.Error("I could not find $name in your contacts.")
            contactName = contact.first
            number = contact.second
            body = cleanBody(request.rest.substring(separator.range.last + 1))
        }
        if (body.isBlank()) {
            return CommandExecutionResult.Error("What should the message to $contactName say?")
        }
        return if (request.viaWhatsApp) {
            CommandExecutionResult.RequiresConfirmation(
                PendingAction(
                    "SEND_WHATSAPP",
                    number + "\n" + body,
                    "Send \"$body\" to $contactName on WhatsApp?",
                    "WhatsApp $contactName"
                )
            )
        } else {
            CommandExecutionResult.RequiresConfirmation(
                PendingAction(
                    "SEND_SMS",
                    number + "\n" + body,
                    "Send the text \"$body\" to $contactName?",
                    "Text $contactName"
                )
            )
        }
    }

    private fun cleanBody(raw: String): String =
        raw.trim().replace(BODY_LEAD, "").trimStart(',', ':', '-', ' ').trim()

    private fun openWhatsAppChat(number: String, body: String): Boolean {
        val uri = Uri.parse("https://wa.me/${toInternational(number)}?text=" + Uri.encode(body))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next WhatsApp package
            }
        }
        return false
    }

    private fun toInternational(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.substring(2)
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.substring(1)
            else -> digits
        }
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

    // ---------------- contacts ----------------

    private fun looksLikeNumber(text: String): Boolean {
        val cleaned = text.trim()
        return cleaned.length >= 5 && cleaned.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
    }

    // Each entry is Pair(display name, phone number).
    private fun loadContacts(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return list
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return list
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                list.add(Pair(name, number))
            }
        }
        return list
    }

    private fun scoreName(contact: String, wanted: String): Int {
        val c = contact.lowercase(Locale.ROOT).trim()
        val w = wanted.lowercase(Locale.ROOT).trim()
        if (w.isEmpty()) return 0
        val words = c.split(" ")
        return when {
            c == w -> 100
            words.firstOrNull() == w -> 90
            words.contains(w) -> 80
            c.startsWith(w) -> 70
            c.contains(w) -> 50
            else -> 0
        }
    }

    private fun resolveContact(nameOrNumber: String): Pair<String, String>? {
        val cleaned = nameOrNumber.trim()
        if (looksLikeNumber(cleaned)) {
            return Pair(cleaned, cleaned.filter { it.isDigit() || it == '+' })
        }
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (contact in loadContacts()) {
            val score = scoreName(contact.first, cleaned)
            if (score > bestScore) {
                bestScore = score
                best = contact
            }
        }
        return best
    }

    // Finds a contact name at the start of the text. Returns Triple(name, number, rest of the text).
    private fun splitContactFromText(rest: String): Triple<String, String, String>? {
        val lowerRest = rest.lowercase(Locale.ROOT)
        var bestLength = 0
        var best: Triple<String, String, String>? = null
        for (contact in loadContacts()) {
            val full = contact.first.lowercase(Locale.ROOT).trim()
            val first = full.split(" ").firstOrNull().orEmpty()
            for (candidate in listOf(full, first)) {
                if (candidate.isEmpty()) continue
                if (lowerRest == candidate || lowerRest.startsWith("$candidate ")) {
                    if (candidate.length > bestLength) {
                        bestLength = candidate.length
                        best = Triple(contact.first, contact.second, rest.substring(candidate.length).trim())
                    }
                }
            }
        }
        return best
    }

    // ---------------- parsing helpers ----------------

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
        // Change this to the name you want ASTRA to give when asked who made it.
        private const val CREATOR = "Gautam Buddh Kumar Sah"

        private const val ACCESSIBILITY_HINT =
            "Turn on ASTRA in Settings, Accessibility, to use Back, Home, Recents and Screenshot. Say open accessibility settings."

        private val GREETINGS = setOf(
            "hi", "hii", "hello", "hey", "hi astra", "hello astra", "hey astra", "namaste", "namaskar", "नमस्ते"
        )
        private val FULL_NAME_QUESTIONS = setOf(
            "what is your full name", "your full name", "full name", "what's your full name",
            "what is the full form of astra", "full form of astra", "astra full form", "astra ka full form",
            "astra ka full form kya hai", "what does astra stand for", "what does astra mean"
        )
        private val CREATOR_QUESTIONS = setOf(
            "who created you", "who made you", "who built you", "who developed you", "who designed you",
            "who is your creator", "who is your developer", "who is your maker", "who created astra",
            "who made astra", "who built astra", "who developed astra", "tumhe kisne banaya",
            "tumhe kisne banaya hai", "aapko kisne banaya", "kisne banaya"
        )
        private val ABOUT_QUESTIONS = setOf(
            "astra", "what is astra", "who is astra", "what are you", "who are you", "tell me about astra",
            "tell me about yourself", "introduce yourself", "what is your name", "what's your name",
            "your name", "astra kya hai", "tum kaun ho", "aap kaun hain"
        )
        private val HELP_QUESTIONS = setOf(
            "what can you do", "help", "help me", "your abilities", "what are your features", "commands"
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
        private val SCREENSHOT_WORDS = setOf(
            "take a screenshot", "take screenshot", "screenshot", "capture screen", "take a screen shot",
            "screenshot lo", "screenshot le lo"
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
            "what's", "who is", "who was", "explain", "define", "information about", "find information about",
            "meaning of"
        )
        private val RESEARCH_SUFFIXES = listOf(
            "kya hota hai", "kya hota h", "kya hai", "kya h", "kaun hai", "kon hai"
        )
        private val CALL_PREFIXES = listOf(
            "make a phone call to ", "make a call to ", "make call to ", "make a call", "make call",
            "call ", "dial ", "फोन लगाओ "
        )

        private val SPEAKER_PHRASE = Regex(
            "(?i)\\s*(?:,\\s*)?(?:(?:and|then)\\s+)?(?:(?:put|turn|keep|switch|set)\\s+(?:it|the call|call|this call|that)\\s+)?" +
                "(?:on|with|in|to)\\s+(?:the\\s+)?(?:loud\\s*)?speaker(?:\\s*phone)?"
        )
        private val WHATSAPP_WORD = Regex("\\bwhats\\s?app\\b", RegexOption.IGNORE_CASE)
        private val WHATSAPP_PREPOSITION = Regex(
            "\\s*\\b(?:on|via|in|using|through|over|par|pe|se)\\s+whats\\s?app\\b", RegexOption.IGNORE_CASE
        )
        private val WHATSAPP_LEADING = Regex(
            "^\\s*whats\\s?app\\s+(?:message\\s+|msg\\s+)?", RegexOption.IGNORE_CASE
        )
        private val MESSAGE_START = Regex(
            "^(?:please\\s+)?(?:send\\s+|write\\s+|text\\s+|message\\s+|msg\\s+|sms\\s+)" +
                "(?:an?\\s+|the\\s+)?(?:(?:text\\s+|whatsapp\\s+|sms\\s+)?(?:message|massage|msg|text|sms)\\s+)?" +
                "(?:to\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val SMS_SEPARATOR = Regex(
            "\\s+(?:saying|that says|that|say|says|with message)\\s+", RegexOption.IGNORE_CASE
        )
        private val BODY_LEAD = Regex(
            "^(?:to\\s+|ko\\s+|that\\s+|saying\\s+|say\\s+|says\\s+|with message\\s+|ki\\s+|ke liye\\s+)",
            RegexOption.IGNORE_CASE
        )
    }
}
