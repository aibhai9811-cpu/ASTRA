package com.astra.assistant.commands

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.astra.assistant.system.PhoneControls
import com.astra.assistant.util.Calculator
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

    private class MessageRequest(val viaWhatsApp: Boolean, val rest: String)

    private class ToggleTarget(
        val id: String,
        val spoken: String,
        val keywords: List<String>,
        val labels: List<String>
    )

    private val phone = PhoneControls(context)

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

        // Questions about ASTRA itself
        identityReply(lower, key)?.let {
            return CommandExecutionResult.Handled(it, "About ASTRA", "IDENTITY")
        }

        // Maths
        Calculator.tryEvaluate(query)?.let {
            return CommandExecutionResult.Handled(it, "Calculator", "CALCULATE")
        }

        // Polite phrasing: "can you open YouTube" becomes "open YouTube"
        for (p in listOf("can you please ", "could you please ", "would you please ", "can you ", "could you ", "would you ", "please ", "kya aap ", "zara ")) {
            if (lower.startsWith(p) && query.length > p.length + 2) {
                return processCommand(query.substring(p.length))
            }
        }

        // Questions go to the AI brain, never to phone controls
        val asksTime = key == "time" || key == "what is the time" || key == "what's the time" ||
            key == "whats the time" || key == "tell me the time" || lower.contains("what time") ||
            lower.contains("time is it") || lower.contains("current time") || lower.contains("kitne baje") ||
            lower.contains("time kya")
        val questionStarts = listOf(
            "what ", "what's ", "whats ", "why ", "how ", "who ", "whom ", "when ", "where ", "which ",
            "explain", "describe", "define", "tell me ", "teach me", "is ", "are ", "does ", "do you ", "did ",
            "kya ", "kaise ", "kyun ", "kyu ", "kaun ", "kab ", "kahan ", "kitna ", "kitne "
        )
        if (!asksTime && questionStarts.any { lower.startsWith(it) }) {
            return CommandExecutionResult.RouteToAi(query)
        }

        // Stop listening
        if (key in STOP_WORDS) {
            return CommandExecutionResult.Handled("Continuous listening paused. Standing by.", "Pause Listening", "STOP_LISTENING")
        }

        // Stop ASTRA's own speech
        if (key in HUSH_WORDS) {
            return CommandExecutionResult.Handled("Okay.", "Quiet", "HUSH")
        }

        // Microphone choice
        if (key in USE_BT_MIC_WORDS) {
            return CommandExecutionResult.Handled(
                "Okay, I will use the earbud microphone when one is connected.", "Earbud mic", "USE_BT_MIC"
            )
        }
        if (key in USE_PHONE_MIC_WORDS) {
            return CommandExecutionResult.Handled("Okay, I will use the phone microphone.", "Phone mic", "USE_PHONE_MIC")
        }

        // Music playback
        mediaCommand(query, lower, key)?.let { return it }

        // Volume
        volumeCommand(lower, key)?.let { return it }

        // Brightness
        if (lower.contains("brightness") || lower.contains("brightnes") || lower.contains("roshni") ||
            lower.contains("ब्राइटनेस")
        ) {
            return brightnessCommand(lower)
        }

        // Flight mode, Bluetooth, Wi-Fi, mobile data, flashlight and similar switches
        toggleCommand(lower)?.let { return it }

        // Phone-wide navigation (needs the Accessibility service)
        if (key in BACK_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", "Back", "NAVIGATE_BACK")
        if (key in HOME_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_HOME, "Going to the home screen.", "Home", "NAVIGATE_HOME")
        if (key in RECENT_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps.", "Recents", "NAVIGATE_RECENTS")
        if (key in NOTIFICATION_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opening notifications.", "Notifications", "NAVIGATE_NOTIFICATIONS")
        if (key in SCREENSHOT_WORDS) return navigate(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Taking a screenshot.", "Screenshot", "SCREENSHOT")

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

        // Open-ended questions and follow-ups go to the AI brain
        val aiStarts = listOf(
            "how ", "why ", "which ", "describe ", "compare ", "summarize ", "summarise ", "translate ",
            "teach me ", "suggest ", "recommend ", "difference between ", "can you explain", "could you explain",
            "help me understand", "give me an example", "give an example", "in hindi", "in english", "in hinglish",
            "explain it", "explain this", "when did ", "when was ", "samjhao", "isko explain"
        )
        if (aiStarts.any { lower.startsWith(it) }) {
            return CommandExecutionResult.RouteToAi(query)
        }

        // Research and information questions
        if (RESEARCH_PREFIXES.any { lower == it || lower.startsWith("$it ") } ||
            RESEARCH_SUFFIXES.any { key.endsWith(" $it") }
        ) {
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

        // Maps
        if (lower.contains("maps") || lower.contains("navigate") || lower.contains("रास्ता")) {
            val dest = query.replace("(?i)^(open|launch|navigate|directions|to)?\\s*(maps?)?\\s*(to)?\\s*".toRegex(), "").trim()
            val uri = if (dest.isNotEmpty()) Uri.parse("geo:0,0?q=" + Uri.encode(dest)) else Uri.parse("geo:0,0")
            launch(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
            return CommandExecutionResult.Handled("Launching Google Maps.", "Maps", "OPEN_MAPS")
        }

        // Text message or WhatsApp message
        val messageRequest = parseMessage(query)
        if (messageRequest != null) {
            return prepareMessage(messageRequest)
        }

        // Make a call (optionally on speaker)
        val wantsSpeaker = SPEAKER_PHRASE.containsMatchIn(query)
        val callQuery = query.replace(SPEAKER_PHRASE, "").trim().trimEnd(',', '.', ' ')
        val callTarget = extractCallTarget(callQuery, callQuery.lowercase(Locale.ROOT))
        if (callTarget != null) {
            if (callTarget.isEmpty()) {
                return CommandExecutionResult.Error("Who should I call?")
            }
            if (!looksLikeNumber(callTarget) && !hasPermission(Manifest.permission.READ_CONTACTS)) {
                return CommandExecutionResult.Error("I need the Contacts permission to find $callTarget. Open ASTRA and allow it.")
            }
            val contact = resolveContact(callTarget)
                ?: return CommandExecutionResult.Error("I could not find $callTarget in your contacts.")
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction(
                    if (wantsSpeaker) "CALL_SPEAKER" else "CALL_PHONE",
                    contact.second,
                    if (wantsSpeaker) "Call ${contact.first} on speaker?" else "Call ${contact.first}?",
                    "Call ${contact.first}"
                )
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
                "control music, volume and brightness, switch flight mode and Bluetooth, solve maths, search the web " +
                "and answer questions. Say ASTRA first when I am in the background."
        }
        return null
    }

    // ---------------- music ----------------

    private fun mediaCommand(query: String, lower: String, key: String): CommandExecutionResult? {
        val k = key.removeSuffix(" please").trim()
        if (k in MEDIA_NEXT) return sendMedia(KeyEvent.KEYCODE_MEDIA_NEXT, "Sent the next-song command to your music app.", "NEXT")
        if (k in MEDIA_PREVIOUS) return sendMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Sent the previous-song command to your music app.", "PREVIOUS")
        if (k in MEDIA_PAUSE) return sendMedia(KeyEvent.KEYCODE_MEDIA_PAUSE, "Sent pause to your music app.", "PAUSE")
        if (k in MEDIA_STOP) return sendMedia(KeyEvent.KEYCODE_MEDIA_STOP, "Sent stop to your music app.", "STOP")
        if (k in MEDIA_PLAY) {
            phone.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            val note = if (phone.isMusicActive()) "Music is already playing." else
                "Sent play to your music app. If nothing starts, tell me a song name."
            return CommandExecutionResult.Handled(note, "Play", "PLAY")
        }
        val openAndPlay = OPEN_AND_PLAY.find(query)
        if (openAndPlay != null) {
            return playQuery(openAndPlay.groupValues[2], openAndPlay.groupValues[1], openAndPlay.groupValues[2])
        }
        if (lower.startsWith("play ")) {
            val original = query.substring(5).trim()
            val playOn = PLAY_ON.find(query)
            if (playOn != null) {
                return playQuery(playOn.groupValues[1], playOn.groupValues[2], original)
            }
            return playQuery(original, null, original)
        }
        return null
    }

    private fun sendMedia(keyCode: Int, message: String, type: String): CommandExecutionResult {
        phone.mediaKey(keyCode)
        return CommandExecutionResult.Handled(message, "Music", type)
    }

    private fun playQuery(rawQuery: String, appHint: String?, original: String): CommandExecutionResult {
        var query = rawQuery
        var app: Pair<String, Intent>? = null
        if (!appHint.isNullOrBlank()) {
            app = findApp(appHint.trim())
            if (app == null) query = original
        }
        val cleaned = query
            .replace(Regex("(?i)\\b(the\\s+)?(song|songs|gana|gaana|music|track)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val finalQuery = if (cleaned.isBlank()) query.trim() else cleaned
        if (finalQuery.isBlank()) {
            phone.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return CommandExecutionResult.Handled("Sent play to your music app.", "Play", "PLAY")
        }
        val pkg = app?.second?.component?.packageName
        if (pkg == "com.google.android.youtube") {
            launch(phone.youtubeSearchIntent(finalQuery))
            return CommandExecutionResult.Handled(
                "Searching YouTube for $finalQuery. YouTube needs a tap on a result to start playing.",
                "YouTube",
                "YOUTUBE_SEARCH"
            )
        }
        return try {
            launch(phone.playFromSearchIntent(finalQuery, pkg))
            CommandExecutionResult.Handled(
                "Asking ${app?.first ?: "your music app"} to play $finalQuery.", "Play", "PLAY_QUERY"
            )
        } catch (e: ActivityNotFoundException) {
            CommandExecutionResult.Error("I could not find a music app that can play $finalQuery.")
        }
    }

    // ---------------- volume and brightness ----------------

    private fun wordIndex(lower: String, word: String): Int {
        val match = Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(word) + "(?![\\p{L}\\p{M}])").find(lower)
        return match?.range?.first ?: -1
    }

    // +1 for up words, -1 for down words, whichever comes first.
    private fun direction(lower: String): Int {
        var bestIndex = Int.MAX_VALUE
        var dir = 0
        for (w in UP_WORDS) {
            val i = wordIndex(lower, w)
            if (i >= 0 && i < bestIndex) {
                bestIndex = i
                dir = 1
            }
        }
        for (w in DOWN_WORDS) {
            val i = wordIndex(lower, w)
            if (i >= 0 && i < bestIndex) {
                bestIndex = i
                dir = -1
            }
        }
        return dir
    }

    private fun extractPercent(lower: String): Int? =
        NUMBER.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)

    private fun volumeStream(lower: String): PhoneControls.Stream = when {
        lower.contains("ring") -> PhoneControls.Stream.RING
        lower.contains("alarm") -> PhoneControls.Stream.ALARM
        lower.contains("call") -> PhoneControls.Stream.CALL
        lower.contains("notification") -> PhoneControls.Stream.NOTIFICATION
        else -> PhoneControls.Stream.MEDIA
    }

    private fun volumeCommand(lower: String, key: String): CommandExecutionResult? {
        val isUnmute = lower.contains("unmute")
        val isMute = !isUnmute && Regex("\\bmute\\b").containsMatchIn(lower)
        val mentionsVolume = lower.contains("volume") || lower.contains("awaz") || lower.contains("आवाज")
        val loudWords = key in setOf("louder", "softer", "quieter", "speak louder", "speak softer")
        if (!mentionsVolume && !isMute && !isUnmute && !loudWords) return null

        val stream = volumeStream(lower)
        val outcome = when {
            isUnmute -> phone.mute(stream, false)
            isMute -> phone.mute(stream, true)
            else -> {
                val dir = direction(lower)
                val by = BY_NUMBER.find(lower)?.groupValues?.get(1)?.toIntOrNull()
                val percent = extractPercent(lower)
                when {
                    by != null && dir != 0 -> phone.adjustVolume(stream, dir * by)
                    percent != null -> phone.setVolumePercent(stream, percent)
                    lower.contains("max") || lower.contains("full") -> phone.setVolumePercent(stream, 100)
                    lower.contains("min") || lower.contains("zero") -> phone.setVolumePercent(stream, 0)
                    dir != 0 -> phone.adjustVolume(stream, dir * 15)
                    else -> return CommandExecutionResult.Error(
                        "Say volume up, volume down, or set volume to 50 percent."
                    )
                }
            }
        }
        return outcomeToResult(outcome, "Volume")
    }

    private fun brightnessCommand(lower: String): CommandExecutionResult {
        val dir = direction(lower)
        val by = BY_NUMBER.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val percent = extractPercent(lower)
        val outcome = when {
            by != null && dir != 0 -> phone.adjustBrightness(dir * by)
            percent != null -> phone.setBrightnessPercent(percent)
            lower.contains("max") || lower.contains("full") -> phone.setBrightnessPercent(100)
            lower.contains("min") || lower.contains("lowest") -> phone.setBrightnessPercent(5)
            dir != 0 -> phone.adjustBrightness(dir * 20)
            else -> return CommandExecutionResult.Error(
                "Say increase brightness, decrease brightness, or set brightness to 50 percent."
            )
        }
        return outcomeToResult(outcome, "Brightness")
    }

    private fun outcomeToResult(outcome: PhoneControls.Outcome, label: String): CommandExecutionResult {
        if (outcome.needsWriteSettings) {
            launch(phone.writeSettingsIntent())
            return CommandExecutionResult.Error(outcome.message)
        }
        if (outcome.needsDndAccess) {
            launch(phone.dndAccessIntent())
            return CommandExecutionResult.Error(outcome.message)
        }
        return if (outcome.ok) {
            CommandExecutionResult.Handled(outcome.message, label, "PHONE_CONTROL")
        } else {
            CommandExecutionResult.Error(outcome.message)
        }
    }

    // ---------------- switches (flight mode, Bluetooth and so on) ----------------

    private fun toggleCommand(lower: String): CommandExecutionResult? {
        val target = TARGETS.firstOrNull { t -> t.keywords.any { lower.contains(it) } } ?: return null
        val wantsOff = OFF_REGEX.containsMatchIn(lower)
        val wantsOn = ON_REGEX.containsMatchIn(lower)
        val wantsToggle = lower.contains("toggle")
        if (!wantsOff && !wantsOn && !wantsToggle) return null
        val desired: Boolean? = when {
            wantsOff -> false
            wantsOn -> true
            else -> null
        }

        if (target.id == "torch") {
            return outcomeToResult(phone.setTorch(desired), "Flashlight")
        }

        val service = AstraAccessibilityService.instance
        if (service == null) {
            launch(phone.settingsIntentFor(target.id))
            return CommandExecutionResult.Handled(
                "Accessibility is off, so I opened the ${target.spoken} settings. Please tap the switch yourself. " +
                    "Turn on ASTRA in Accessibility to let me do it.",
                target.spoken,
                "TOGGLE"
            )
        }
        service.toggleQuickSetting(target.labels, desired, target.spoken)
        val verb = when (desired) {
            true -> "turning on"
            false -> "turning off"
            null -> "toggling"
        }
        return CommandExecutionResult.Handled(
            "Okay, $verb ${target.spoken} in Quick Settings.", target.spoken, "TOGGLE"
        )
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
        private val HUSH_WORDS = setOf(
            "stop", "quiet", "quite", "be quiet", "shut up", "silence", "stop talking", "stop speaking",
            "enough", "chup", "chup ho jao", "chup raho", "bas", "bas karo", "बस", "चुप", "चुप हो जाओ"
        )
        private val USE_BT_MIC_WORDS = setOf(
            "use earbud mic", "use earbuds mic", "use earbud microphone", "use bluetooth mic",
            "use bluetooth microphone", "use headset mic", "earbud mic on"
        )
        private val USE_PHONE_MIC_WORDS = setOf(
            "use phone mic", "use phone microphone", "use mobile mic", "phone mic", "use the phone mic"
        )

        private val MEDIA_NEXT = setOf(
            "next", "next song", "next track", "skip", "skip song", "skip this song", "play next",
            "play next song", "play the next song", "agla gana", "next gana", "agla song"
        )
        private val MEDIA_PREVIOUS = setOf(
            "previous", "previous song", "previous track", "play previous", "play previous song",
            "play the previous song", "pichla gana", "previous gana", "go to previous song"
        )
        private val MEDIA_PAUSE = setOf(
            "pause", "pause music", "pause the music", "pause song", "pause the song", "music pause",
            "music pause karo", "pause karo", "gana pause karo"
        )
        private val MEDIA_STOP = setOf(
            "stop music", "stop the music", "stop song", "stop the song", "stop playing", "music stop",
            "music band karo", "gana band karo", "stop playback"
        )
        private val MEDIA_PLAY = setOf(
            "play", "resume", "resume music", "resume the music", "play music", "play the music",
            "play song", "play a song", "continue", "continue music", "continue playing", "music chalao",
            "gana chalao", "music play karo", "gana play karo"
        )
        private val OPEN_AND_PLAY = Regex(
            "^(?:open|launch|start)\\s+(.+?)\\s+and\\s+play\\s+(.+)$", RegexOption.IGNORE_CASE
        )
        private val PLAY_ON = Regex(
            "^play\\s+(.+?)\\s+(?:on|in|using|with|par)\\s+(.+)$", RegexOption.IGNORE_CASE
        )

        private val UP_WORDS = listOf(
            "increase", "raise", "up", "higher", "louder", "more", "badhao", "badha", "badhaao",
            "zyada", "jyada", "बढ़ाओ", "बढ़ा", "ज्यादा", "तेज"
        )
        private val DOWN_WORDS = listOf(
            "decrease", "reduce", "lower", "down", "softer", "quieter", "less", "dim", "ghatao",
            "ghata", "kam", "कम", "घटाओ", "घटा", "धीमा"
        )
        private val NUMBER = Regex("(\\d{1,3})")
        private val BY_NUMBER = Regex("\\bby\\s+(\\d{1,3})")

        private val ON_REGEX = Regex(
            "\\b(on|enable|enabled|activate|start|chalu|shuru)\\b", RegexOption.IGNORE_CASE
        )
        private val OFF_REGEX = Regex(
            "\\b(off|disable|disabled|deactivate|band|stop)\\b", RegexOption.IGNORE_CASE
        )

        private val TARGETS = listOf(
            ToggleTarget(
                "airplane", "airplane mode",
                listOf("airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane", "flight"),
                listOf("airplane mode", "flight mode", "aeroplane mode")
            ),
            ToggleTarget(
                "bluetooth", "Bluetooth",
                listOf("bluetooth", "blue tooth"),
                listOf("bluetooth")
            ),
            ToggleTarget(
                "wifi", "Wi-Fi",
                listOf("wi-fi", "wifi", "wi fi"),
                listOf("wi-fi", "wifi", "internet")
            ),
            ToggleTarget(
                "hotspot", "hotspot",
                listOf("hotspot", "hot spot", "tethering"),
                listOf("hotspot", "mobile hotspot")
            ),
            ToggleTarget(
                "data", "mobile data",
                listOf("mobile data", "cellular data", "internet data", "data"),
                listOf("mobile data", "cellular data", "internet")
            ),
            ToggleTarget(
                "location", "location",
                listOf("location", "gps"),
                listOf("location", "gps")
            ),
            ToggleTarget(
                "dnd", "Do Not Disturb",
                listOf("do not disturb", "don't disturb", "dnd"),
                listOf("do not disturb", "dnd")
            ),
            ToggleTarget(
                "rotate", "auto rotate",
                listOf("auto rotate", "auto-rotate", "rotation", "screen rotate"),
                listOf("auto-rotate", "auto rotate", "rotation")
            ),
            ToggleTarget(
                "battery_saver", "battery saver",
                listOf("battery saver", "power saving", "power saver", "battery saving"),
                listOf("battery saver", "power saving", "power saver")
            ),
            ToggleTarget(
                "torch", "flashlight",
                listOf("flashlight", "flash light", "torch"),
                listOf("flashlight", "torch")
            )
        )

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
