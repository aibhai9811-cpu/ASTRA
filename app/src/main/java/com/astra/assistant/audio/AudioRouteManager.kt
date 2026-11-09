package com.astra.assistant.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

// Sends the microphone input to Bluetooth earbuds when they are connected.
class AudioRouteManager(context: Context, private val onRouteChanged: () -> Unit) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var routedToBluetooth = false
    private var registered = false

    private val debounce = Runnable {
        val before = routedToBluetooth
        applyRoute()
        if (routedToBluetooth != before) onRouteChanged()
    }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scheduleReapply()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scheduleReapply()
        }
    }

    private fun scheduleReapply() {
        handler.removeCallbacks(debounce)
        handler.postDelayed(debounce, 800)
    }

    fun start() {
        if (!registered) {
            audio.registerAudioDeviceCallback(callback, handler)
            registered = true
        }
        applyRoute()
    }

    fun stop() {
        if (registered) {
            audio.unregisterAudioDeviceCallback(callback)
            registered = false
        }
        handler.removeCallbacks(debounce)
        release()
    }

    private fun bluetoothInput(): AudioDeviceInfo? {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    // Returns a short sentence describing which microphone is in use.
    fun applyRoute(): String {
        val wantBluetooth = preferBluetooth(appContext)
        val input = if (wantBluetooth) bluetoothInput() else null
        if (input == null) {
            release()
            return if (wantBluetooth) {
                "No Bluetooth microphone is connected, so I am using the phone microphone."
            } else {
                "I am using the phone microphone."
            }
        }
        if (routedToBluetooth) return "I am using the earbud microphone."
        return try {
            routeToBluetooth()
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth routing blocked", e)
            "Android blocked the earbud microphone. Allow the Nearby devices permission for ASTRA."
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth routing failed", e)
            "I could not switch to the earbud microphone."
        }
    }

    @Suppress("DEPRECATION")
    private fun routeToBluetooth(): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val target = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return "Your earbuds do not offer a microphone route, so I am using the phone microphone."
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = audio.setCommunicationDevice(target)
            routedToBluetooth = ok
            if (!ok) audio.mode = AudioManager.MODE_NORMAL
            return if (ok) "I am using the earbud microphone." else "Android would not switch to the earbud microphone."
        }
        if (audio.isBluetoothScoAvailableOffCall) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
            routedToBluetooth = true
            return "I am using the earbud microphone."
        }
        return "This phone cannot use the earbud microphone."
    }

    @Suppress("DEPRECATION")
    private fun release() {
        if (!routedToBluetooth) return
        routedToBluetooth = false
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth release failed", e)
        }
        audio.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        private const val TAG = "ASTRA_AUDIO"
        private const val PREFS = "astra_audio"
        private const val KEY = "prefer_bt_mic"

        fun preferBluetooth(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

        fun setPreferBluetooth(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        }
    }
}
