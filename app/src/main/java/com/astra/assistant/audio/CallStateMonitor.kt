package com.astra.assistant.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// Watches the phone's audio mode. A ringing or active phone call has priority over ASTRA.
// This needs no extra permission. A call is MODE_RINGTONE or MODE_IN_CALL. Another app's
// internet call shows as MODE_IN_COMMUNICATION, which we only trust when ASTRA did not set it.
class CallStateMonitor(context: Context, private val onChange: (Boolean) -> Unit) {

    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var inCall = false
    private var started = false
    private var lastCallSeenAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            evaluate()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        handler.post(poll)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(poll)
        inCall = false
    }

    private fun isCallMode(): Boolean {
        val mode = audio.mode
        return mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            (mode == AudioManager.MODE_IN_COMMUNICATION && !AudioRouteManager.holdingCommunicationMode)
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        if (isCallMode()) {
            lastCallSeenAt = now
            if (!inCall) {
                inCall = true
                onChange(true)
            }
        } else if (inCall && now - lastCallSeenAt >= RESUME_DELAY_MS) {
            inCall = false
            onChange(false)
        }
    }

    companion object {
        private const val POLL_MS = 500L
        private const val RESUME_DELAY_MS = 1500L
    }
}
