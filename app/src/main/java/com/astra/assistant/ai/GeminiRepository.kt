package com.astra.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

sealed class GeminiResult {
    enum class Kind { NETWORK, TIMEOUT, AUTH, RATE_LIMIT, SERVER, EMPTY, INVALID, CONFIG }

    data class Success(
        val reply: String,
        val suggestedCommand: String?,
        val actionLabel: String?,
        val model: String?
    ) : GeminiResult()

    data class Failure(val kind: Kind, val message: String) : GeminiResult()
}

data class HealthResult(
    val reachable: Boolean,
    val model: String,
    val geminiKeyPresent: Boolean,
    val message: String
)

class GeminiRepository {

    suspend fun chat(baseUrl: String, token: String, payload: JSONObject): GeminiResult =
        withContext(Dispatchers.IO) {
            val body = payload.toString()
            var attempt = 0
            var last: GeminiResult = GeminiResult.Failure(GeminiResult.Kind.NETWORK, NETWORK_MESSAGE)
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                val result = singleCall(baseUrl, token, body)
                if (result is GeminiResult.Success) return@withContext result
                last = result
                val failure = result as GeminiResult.Failure
                val retryable = failure.kind == GeminiResult.Kind.NETWORK ||
                    
                    failure.kind == GeminiResult.Kind.SERVER
                if (!retryable || attempt >= MAX_ATTEMPTS) return@withContext failure
                delay(700L * attempt)
            }
            last
        }

    suspend fun health(baseUrl: String, token: String): HealthResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(baseUrl.trimEnd('/') + "/health").openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val text = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code in 200..299) {
                val json = JSONObject(text)
                HealthResult(true, json.optString("model", ""), json.optBoolean("geminiConfigured", false), "Server reachable.")
            } else if (code == 401) {
                HealthResult(false, "", false, "The server rejected the access token.")
            } else if (code == 429) {
                HealthResult(false, "", false, "The server is rate limiting requests. Wait a minute.")
            } else {
                HealthResult(false, "", false, "The server answered with error $code.")
            }
        } catch (e: SocketTimeoutException) {
            HealthResult(false, "", false, "The server took too long to answer.")
        } catch (e: IOException) {
            HealthResult(false, "", false, "Could not reach the server. Check your internet and the address.")
        } catch (e: Exception) {
            HealthResult(false, "", false, "The server sent a reply I could not read.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun singleCall(baseUrl: String, token: String, body: String): GeminiResult {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL(baseUrl.trimEnd('/') + "/chat").openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code in 200..299) parseSuccess(text) else mapError(code, text)
        } catch (e: SocketTimeoutException) {
            GeminiResult.Failure(GeminiResult.Kind.TIMEOUT, "The AI took too long to answer. Please try again.")
        } catch (e: UnknownHostException) {
            GeminiResult.Failure(GeminiResult.Kind.NETWORK, NETWORK_MESSAGE)
        } catch (e: IOException) {
            GeminiResult.Failure(GeminiResult.Kind.NETWORK, NETWORK_MESSAGE)
        } catch (e: Exception) {
            GeminiResult.Failure(GeminiResult.Kind.INVALID, "Something went wrong talking to the AI server.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseSuccess(text: String): GeminiResult {
        return try {
            val json = JSONObject(text)
            val reply = json.optString("replyText", "").trim()
            if (reply.isEmpty()) {
                return GeminiResult.Failure(GeminiResult.Kind.EMPTY, "Gemini sent back an empty answer. Please try again.")
            }
            GeminiResult.Success(
                reply = reply,
                suggestedCommand = json.optString("suggestedCommand", "").trim().ifBlank { null },
                actionLabel = json.optString("actionLabel", "").trim().ifBlank { null },
                model = json.optString("model", "").trim().ifBlank { null }
            )
        } catch (e: Exception) {
            GeminiResult.Failure(GeminiResult.Kind.INVALID, "The AI server sent a reply I could not read.")
        }
    }

    private fun mapError(code: Int, text: String): GeminiResult.Failure {
        val error = try {
            JSONObject(text).optString("error", "")
        } catch (e: Exception) {
            ""
        }
        return when {
            code == 401 || error == "unauthorized" ->
                GeminiResult.Failure(GeminiResult.Kind.AUTH, "The AI server rejected my access token. Check it in Settings.")
            error == "gemini_key_rejected" ->
                GeminiResult.Failure(GeminiResult.Kind.CONFIG, "The Gemini key on your server was rejected. Check it in Cloudflare.")
            error == "model_not_found" ->
                GeminiResult.Failure(GeminiResult.Kind.CONFIG, "The Gemini model name on your server was not found. Update GEMINI_MODEL.")
            error == "server_not_configured" ->
                GeminiResult.Failure(GeminiResult.Kind.CONFIG, "Your AI server is missing its Gemini key or settings.")
            code == 429 || error == "rate_limited" || error == "gemini_rate_limited" ->
                GeminiResult.Failure(GeminiResult.Kind.RATE_LIMIT, "Too many AI requests right now. Please wait a minute.")
            code == 413 || code == 400 ->
                GeminiResult.Failure(GeminiResult.Kind.INVALID, "That request was too large or invalid.")
            code == 504 || error == "upstream_timeout" ->
                GeminiResult.Failure(GeminiResult.Kind.TIMEOUT, "The AI took too long to answer. Please try again.")
            code >= 500 ->
                GeminiResult.Failure(GeminiResult.Kind.SERVER, "Gemini is busy right now. Please try again.")
            else ->
                GeminiResult.Failure(GeminiResult.Kind.INVALID, "The AI server answered with error $code.")
        }
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader().use { it.readText() }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val NETWORK_MESSAGE =
            "I can't reach the AI right now. Check your internet. Local commands still work."
    }
}
