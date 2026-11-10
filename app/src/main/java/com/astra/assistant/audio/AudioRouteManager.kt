package com.astra.assistant.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.astra.assistant.voice.VoiceSettings

// Asks Android to use Bluetooth earbuds for the microphone. This is a request: Google's speech
// service decides the final input, and Android does not report which device it used.
class AudioRouteManager(context: Context, private val onDevicesChanged: () -> Unit) {

    data class Route(val usingBluetooth: Boolean, val description: String)

    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var routedBluetooth = false
    private var routedName = ""

    private val notifyChanged = Runnable { onDevicesChanged() }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            schedule()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            schedule()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(notifyChanged)
        handler.postDelayed(notifyChanged, 800)
    }

    fun start() {
        if (registered) return
        audio.registerAudioDeviceCallback(callback, handler)
        registered = true
    }

    fun stop() {
        if (registered) {
            audio.unregisterAudioDeviceCallback(callback)
            registered = false
        }
        handler.removeCallbacks(notifyChanged)
        release()
    }

    private fun bluetoothInput(): AudioDeviceInfo? {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun hasBluetoothMic(): Boolean = bluetoothInput() != null

    private fun describeBluetooth(): String = "Bluetooth: " + routedName.ifBlank { "earbuds" }

    fun apply(preference: String): Route {
        if (preference == VoiceSettings.MIC_PHONE) {
            release()
            return Route(false, "Phone microphone")
        }
        val input = bluetoothInput()
        if (input == null) {
            release()
            val text = if (preference == VoiceSettings.MIC_BLUETOOTH) {
                "No Bluetooth microphone connected, using the phone microphone"
            } else {
                "Phone microphone"
            }
            return Route(false, text)
        }
        val mode = audio.mode
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL) {
            return Route(false, "Phone microphone (call in progress)")
        }
        if (routedBluetooth) return Route(true, describeBluetooth())
        return try {
            startBluetoothRoute(input)
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth routing blocked", e)
            release()
            Route(false, "Phone microphone (allow the Nearby devices permission to use earbuds)")
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth routing failed", e)
            release()
            Route(false, "Phone microphone (earbud route failed)")
        }
    }

    @Suppress("DEPRECATION")
    private fun startBluetoothRoute(input: AudioDeviceInfo): Route {
        // Never take over the audio mode while something else (a call, another app) is using it.
        if (audio.mode != AudioManager.MODE_NORMAL) return Route(false, "Phone microphone (audio busy)")
        if (Build.VERSION.SDK_INT >= 31) {
            val target = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return Route(false, "Phone microphone (earbuds offer no microphone)")
            holdingCommunicationMode = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!audio.setCommunicationDevice(target)) {
                release()
                return Route(false, "Phone microphone (Android refused the earbud route)")
            }
            routedName = target.productName?.toString().orEmpty()
        } else {
            if (!audio.isBluetoothScoAvailableOffCall) {
                return Route(false, "Phone microphone (this phone cannot use the earbud microphone)")
            }
            holdingCommunicationMode = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
            routedName = input.productName?.toString().orEmpty()
        }
        routedBluetooth = true
        return Route(true, describeBluetooth())
    }

    @Suppress("DEPRECATION")
    fun release() {
        if (routedBluetooth || holdingCommunicationMode) {
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
            // Only undo the mode if it is still the one ASTRA set. Never touch a call.
            if (holdingCommunicationMode && audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audio.mode = AudioManager.MODE_NORMAL
            }
        }
        routedBluetooth = false
        routedName = ""
        holdingCommunicationMode = false
    }

    companion object {
        private const val TAG = "ASTRA_AUDIO"

        // True only while ASTRA itself holds MODE_IN_COMMUNICATION for the earbud microphone.
        @Volatile
        var holdingCommunicationMode: Boolean = false
    }
}
