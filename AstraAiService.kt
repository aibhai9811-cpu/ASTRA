package com.astra.assistant.network

import android.util.Log
import com.astra.assistant.model.ChatMessage
import com.astra.assistant.model.MessageSource
import com.astra.assistant.model.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AiResponseResult(
    val replyText: String,
    val actionType: String? = null,
    val actionTarget: String? = null,
    val actionLabel: String? = null,
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val isNetworkError: Boolean = false,
    val sourceType: MessageSource = MessageSource.ONLINE_AI
)

class AstraAiService {
    suspend fun queryAiBackend(
        endpointUrl: String,
        userQuery: String,
        history: List<ChatMessage>,
        memoryContext: String,
        assistantName: String,
        language: String
    ): AiResponseResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.doOutput = true

            val requestJson = JSONObject().apply {
                put("prompt", userQuery)
                put("message", userQuery)
                put("memoryContext", memoryContext)
                put("userSettings", JSONObject().apply {
                    put("assistantName", assistantName)
                    put("language", language)
                })
            }

            OutputStreamWriter(connection.outputStream).use { it.write(requestJson.toString()) }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseString = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val responseJson = JSONObject(responseString)
                val reply = responseJson.optString("replyText", responseJson.optString("text", "Directive acknowledged."))
                val actionType = responseJson.optString("actionType", "NONE")
                val actionTarget = responseJson.optString("actionTarget", "")
                val actionLabel = responseJson.optString("actionLabel", "")
                val reqConfirm = responseJson.optBoolean("requiresConfirmation", false)

                AiResponseResult(
                    replyText = reply,
                    actionType = if (actionType == "NONE") null else actionType,
                    actionTarget = actionTarget.ifBlank { null },
                    actionLabel = actionLabel.ifBlank { null },
                    requiresConfirmation = reqConfirm,
                    sourceType = MessageSource.ONLINE_AI
                )
            } else {
                AiResponseResult(
                    replyText = "AI Backend returned status $statusCode. Local phone directives remain ready.",
                    isNetworkError = true,
                    sourceType = MessageSource.LOCAL_ROUTER
                )
            }
        } catch (e: Exception) {
            AiResponseResult(
                replyText = "Network connection unavailable. Operating in local mode.",
                isNetworkError = true,
                sourceType = MessageSource.LOCAL_ROUTER
            )
        } finally {
            connection?.disconnect()
        }
    }
}
