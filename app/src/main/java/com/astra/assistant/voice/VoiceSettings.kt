package com.astra.assistant.voice

import android.content.Context

object VoiceSettings {
    const val MIC_AUTO = "auto"
    const val MIC_BLUETOOTH = "bluetooth"
    const val MIC_PHONE = "phone"
    const val LANG_AUTO = "auto"
    const val LANG_EN = "en"
    const val LANG_HI = "hi"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("astra_voice", Context.MODE_PRIVATE)

    // The user's microphone switch. It stays ON through calls and system restarts.
    fun listeningOn(context: Context): Boolean = prefs(context).getBoolean("listening_on", false)

    fun setListeningOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("listening_on", on).apply()
    }

    // true = keep listening after each command. false = listen for one command and then switch OFF.
    fun continuous(context: Context): Boolean = prefs(context).getBoolean("continuous", true)

    fun setContinuous(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("continuous", value).apply()
    }

    fun micPreference(context: Context): String = prefs(context).getString("mic_pref", MIC_AUTO) ?: MIC_AUTO

    fun setMicPreference(context: Context, value: String) {
        prefs(context).edit().putString("mic_pref", value).apply()
    }

    fun language(context: Context): String = prefs(context).getString("language", LANG_AUTO) ?: LANG_AUTO

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString("language", value).apply()
    }

    fun spokenResponses(context: Context): Boolean = prefs(context).getBoolean("spoken", true)

    fun setSpokenResponses(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("spoken", value).apply()
    }

    fun onDevice(context: Context): Boolean = prefs(context).getBoolean("on_device", false)

    fun setOnDevice(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("on_device", value).apply()
    }

    // The offline engine has no restarts and no beeps. It needs a downloaded voice model.
    fun useOffline(context: Context): Boolean = prefs(context).getBoolean("use_offline", true)

    fun setUseOffline(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("use_offline", value).apply()
    }
}
