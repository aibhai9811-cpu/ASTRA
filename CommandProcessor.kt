package com.astra.assistant.commands

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.astra.assistant.model.PendingAction
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

        // 0. Continuous Listening Stop Command
        if (lower == "stop listening" || lower == "ruk jao" || lower == "so jao") {
            return CommandExecutionResult.Handled("Continuous listening paused. Standing by.", "Pause Listening", "STOP_LISTENING")
        }

        // 1. Back Navigation
        if (lower == "back" || lower == "go back" || lower == "peeche jao") {
            if (context is Activity) {
                context.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            }
            return CommandExecutionResult.Handled("Going back.", "Back Navigation", "NAVIGATE_BACK")
        }

        // 2. Settings
        if (lower.contains("setting") || lower.contains("सेटिंग")) {
            val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            return CommandExecutionResult.Handled("Opening Android device settings.", "Settings", "OPEN_SETTINGS")
        }

        // 3. YouTube
        if (lower.contains("youtube") || lower.contains("यूट्यूब")) {
            val queryParam = query.replace("(?i)^(open|launch|search|on)?\s*(youtube|यूट्यूब)\s*".toRegex(), "").trim()
            val intent = if (queryParam.isNotEmpty()) {
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", queryParam)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                } ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            }
            context.startActivity(intent)
            return CommandExecutionResult.Handled("Opening YouTube.", "YouTube", "OPEN_YOUTUBE")
        }

        // 4. Current Time
        if (lower.contains("time") || lower.contains("समय") || lower.contains("बजे")) {
            val formatted = SimpleDateFormat("h:mm a, EEEE, MMM d", Locale.getDefault()).format(Date())
            return CommandExecutionResult.Handled("The current time is $formatted, sir.", "Time Query", "GET_TIME")
        }

        // 5. Camera
        if (lower.contains("camera") || lower.contains("कैमरा") || lower.contains("फोटो")) {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            return CommandExecutionResult.Handled("Opening camera viewfinder.", "Camera", "OPEN_CAMERA")
        }

        // 6. Alarms & Clock
        if (lower.contains("alarm") || lower.contains("अलार्म") || lower.contains("clock")) {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            return CommandExecutionResult.Handled("Opening Android clock & alarms manager.", "Clock & Alarms", "OPEN_CLOCK")
        }

        // 7. Media & Music
        if (lower.contains("play music") || lower.contains("spotify") || lower.contains("gaana")) {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            return CommandExecutionResult.Handled("Starting media playback.", "Play Media", "PLAY_MEDIA")
        }

        // 8. Maps & Navigation
        if (lower.contains("maps") || lower.contains("navigate") || lower.contains("रास्ता")) {
            val dest = query.replace("(?i)^(open|launch|navigate|directions|to)?\s*(maps?)?\s*(to)?\s*".toRegex(), "").trim()
            val uri = if (dest.isNotEmpty()) Uri.parse("geo:0,0?q=" + Uri.encode(dest)) else Uri.parse("geo:0,0")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return CommandExecutionResult.Handled("Launching Google Maps.", "Maps", "OPEN_MAPS")
        }

        // 9. Consequential: Phone call
        if (lower.startsWith("call ") || lower.startsWith("dial ") || lower.contains("फोन लगाओ")) {
            val target = query.replace("(?i)^(call|dial|फोन लगाओ)\s+".toRegex(), "").trim()
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction("CALL_PHONE", target, "Confirm placing call to '$target'?", "Call $target")
            )
        }

        // 10. Consequential: Clear history
        if (lower.contains("clear history") || lower.contains("wipe data")) {
            return CommandExecutionResult.RequiresConfirmation(
                PendingAction("CLEAR_HISTORY", "all", "Confirm permanent wipe of all conversation history?", "Wipe Logs")
            )
        }

        // 11. Route to AI Backend
        return CommandExecutionResult.RouteToAi(query)
    }

    fun executeConfirmedAction(action: PendingAction): CommandExecutionResult {
        return when (action.actionType) {
            "CALL_PHONE" -> {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(action.target)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                CommandExecutionResult.Handled("Dialer opened for '${action.target}'.", "Dial Phone")
            }
            "CLEAR_HISTORY" -> CommandExecutionResult.Handled("Conversation logs wiped.", "Wipe Logs")
            else -> CommandExecutionResult.Error("Action cancelled.")
        }
    }
}
