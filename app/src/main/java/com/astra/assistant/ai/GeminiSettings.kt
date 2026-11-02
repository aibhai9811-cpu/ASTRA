package com.astra.assistant.ai

import android.content.Context

data class GeminiSettings(
    val enabled: Boolean = true,
    val proxyUrl: String = "",
    val hasToken: Boolean = false,
    val historyLimit: Int = 12,
    val style: String = STYLE_SHORT,
    val shareMemory: Boolean = false,
    val redactPrivateInfo: Boolean = true,
    val lastModel: String = "",
    val lastStatus: String = ""
) {
    val isConfigured: Boolean
        get() = proxyUrl.isNotBlank() && hasToken

    companion object {
        const val STYLE_SHORT = "short"
        const val STYLE_BALANCED = "balanced"
        const val STYLE_DETAILED = "detailed"
    }
}

class GeminiSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("astra_gemini", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    fun load(): GeminiSettings = GeminiSettings(
        enabled = prefs.getBoolean("enabled", true),
        proxyUrl = prefs.getString("proxyUrl", "") ?: "",
        hasToken = secure.has(TOKEN_KEY),
        historyLimit = prefs.getInt("historyLimit", 12).coerceIn(2, 30),
        style = prefs.getString("style", GeminiSettings.STYLE_SHORT) ?: GeminiSettings.STYLE_SHORT,
        shareMemory = prefs.getBoolean("shareMemory", false),
        redactPrivateInfo = prefs.getBoolean("redact", true),
        lastModel = prefs.getString("lastModel", "") ?: "",
        lastStatus = prefs.getString("lastStatus", "") ?: ""
    )

    fun save(settings: GeminiSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("proxyUrl", settings.proxyUrl.trim())
            .putInt("historyLimit", settings.historyLimit.coerceIn(2, 30))
            .putString("style", settings.style)
            .putBoolean("shareMemory", settings.shareMemory)
            .putBoolean("redact", settings.redactPrivateInfo)
            .apply()
    }

    fun saveStatus(model: String, status: String) {
        prefs.edit().putString("lastModel", model).putString("lastStatus", status).apply()
    }

    fun saveToken(token: String) {
        secure.put(TOKEN_KEY, token.trim())
    }

    fun clearToken() {
        secure.put(TOKEN_KEY, "")
    }

    internal fun readToken(): String = secure.get(TOKEN_KEY)

    companion object {
        private const val TOKEN_KEY = "proxy_token"
    }
}
