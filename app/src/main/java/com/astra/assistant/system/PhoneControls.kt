package com.astra.assistant.system

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent

class PhoneControls(private val context: Context) {

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val needsWriteSettings: Boolean = false,
        val needsDndAccess: Boolean = false
    )

    enum class Stream(val id: Int, val label: String) {
        MEDIA(AudioManager.STREAM_MUSIC, "Media"),
        RING(AudioManager.STREAM_RING, "Ringer"),
        ALARM(AudioManager.STREAM_ALARM, "Alarm"),
        CALL(AudioManager.STREAM_VOICE_CALL, "Call"),
        NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "Notification")
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---------------- volume ----------------

    private fun minVolume(stream: Stream): Int =
        if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream.id) else 0

    private fun percentOf(stream: Stream, value: Int): Int {
        val max = audio.getStreamMaxVolume(stream.id)
        return if (max <= 0) 0 else value * 100 / max
    }

    private fun dndOutcome(): Outcome = Outcome(
        false,
        "Android needs Do Not Disturb access to change that volume. I opened the settings screen. Allow ASTRA there.",
        needsDndAccess = true
    )

    fun adjustVolume(stream: Stream, deltaPercent: Int): Outcome {
        return try {
            val max = audio.getStreamMaxVolume(stream.id)
            val current = audio.getStreamVolume(stream.id)
            var step = Math.round(deltaPercent / 100.0 * max).toInt()
            if (step == 0) step = if (deltaPercent >= 0) 1 else -1
            val target = (current + step).coerceIn(minVolume(stream), max)
            audio.setStreamVolume(stream.id, target, AudioManager.FLAG_SHOW_UI)
            Outcome(true, "${stream.label} volume is now ${percentOf(stream, target)} percent.")
        } catch (e: SecurityException) {
            dndOutcome()
        }
    }

    fun setVolumePercent(stream: Stream, percent: Int): Outcome {
        return try {
            val max = audio.getStreamMaxVolume(stream.id)
            val target = Math.round(percent.coerceIn(0, 100) / 100.0 * max).toInt().coerceIn(minVolume(stream), max)
            audio.setStreamVolume(stream.id, target, AudioManager.FLAG_SHOW_UI)
            Outcome(true, "${stream.label} volume set to ${percentOf(stream, target)} percent.")
        } catch (e: SecurityException) {
            dndOutcome()
        }
    }

    fun mute(stream: Stream, mute: Boolean): Outcome {
        return try {
            audio.adjustStreamVolume(
                stream.id,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI
            )
            Outcome(true, if (mute) "${stream.label} muted." else "${stream.label} unmuted.")
        } catch (e: SecurityException) {
            dndOutcome()
        }
    }

    // ---------------- brightness ----------------

    fun writeSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dndAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun writeSettingsOutcome(): Outcome = Outcome(
        false,
        "I need the Modify system settings permission to change brightness. I opened that screen. Allow ASTRA there, then try again.",
        needsWriteSettings = true
    )

    private fun brightnessMax(current: Int): Int = when {
        current > 2047 -> 4095
        current > 255 -> 2047
        else -> 255
    }

    private fun applyBrightness(value: Int, max: Int): Outcome {
        return try {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            Outcome(true, "Brightness set to ${value * 100 / max} percent.")
        } catch (e: SecurityException) {
            writeSettingsOutcome()
        }
    }

    fun adjustBrightness(deltaPercent: Int): Outcome {
        if (!Settings.System.canWrite(context)) return writeSettingsOutcome()
        val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val max = brightnessMax(current)
        val target = (current + deltaPercent * max / 100).coerceIn(1, max)
        return applyBrightness(target, max)
    }

    fun setBrightnessPercent(percent: Int): Outcome {
        if (!Settings.System.canWrite(context)) return writeSettingsOutcome()
        val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val max = brightnessMax(current)
        val target = (percent.coerceIn(0, 100) * max / 100).coerceIn(1, max)
        return applyBrightness(target, max)
    }

    // ---------------- flashlight ----------------

    fun setTorch(on: Boolean?): Outcome {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Outcome(false, "This phone has no flashlight I can reach.")
            val target = on ?: !torchOn
            manager.setTorchMode(id, target)
            torchOn = target
            Outcome(true, if (target) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Outcome(false, "I could not control the flashlight right now.")
        }
    }

    // ---------------- media ----------------

    fun isMusicActive(): Boolean = audio.isMusicActive

    fun mediaKey(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    fun playFromSearchIntent(query: String, packageName: String?): Intent {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) intent.setPackage(packageName)
        return intent
    }

    fun youtubeSearchIntent(query: String): Intent =
        Intent(Intent.ACTION_SEARCH)
            .setPackage("com.google.android.youtube")
            .putExtra("query", query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------- settings screens (fallback when Accessibility is off) ----------------

    fun settingsIntentFor(id: String): Intent {
        val action = when (id) {
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "data" ->
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                else Settings.ACTION_DATA_ROAMING_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "dnd" -> Settings.ACTION_SOUND_SETTINGS
            "rotate" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        @Volatile
        private var torchOn = false
    }
}
