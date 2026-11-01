package com.astra.assistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object ResearchService {

    private val PREFIXES = listOf(
        "what is meant by", "meaning of", "research on", "research about", "research",
        "analysis of", "analysis on", "analysis", "analyze", "analyse", "tell me about",
        "what is", "what are", "what's", "who is", "who was", "explain", "define",
        "find information about", "information about", "search for"
    )
    private val SUFFIXES = listOf(
        "kya hota hai", "kya hota h", "kya hai", "kya h", "kaun hai", "kon hai", "kaun tha",
        "ke bare me batao", "ke baare mein batao", "ke bare mein batao", "explain karo",
        "samjhao", "bataiye", "batao"
    )

    suspend fun lookup(rawQuery: String): String = withContext(Dispatchers.IO) {
        val topic = cleanQuery(rawQuery)
        if (topic.isBlank()) {
            return@withContext "What would you like me to research, sir?"
        }
        try {
            val encoded = URLEncoder.encode(topic, "UTF-8")
            val search = JSONObject(
                httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=4&format=json")
            )
            val hits = search.getJSONObject("query").getJSONArray("search")
            for (i in 0 until hits.length()) {
                val title = hits.getJSONObject(i).getString("title")
                val pageName = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
                val summary = JSONObject(httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/$pageName"))
                if (summary.optString("type") == "disambiguation") continue
                val extract = summary.optString("extract", "")
                if (extract.isBlank()) continue
                return@withContext shorten(extract) + " Source: Wikipedia, $title."
            }
            "I could not find reliable information on $topic. Say search $topic to open a web search."
        } catch (e: Exception) {
            "I could not reach the research source. Please check your internet connection."
        }
    }

    private fun cleanQuery(raw: String): String {
        var q = raw.trim().trimEnd('?', '.', '!', ' ')
        var lower = q.lowercase(Locale.ROOT)
        for (p in PREFIXES) {
            if (lower.startsWith("$p ")) {
                q = q.substring(p.length).trim()
                break
            }
        }
        lower = q.lowercase(Locale.ROOT)
        for (s in SUFFIXES) {
            if (lower.endsWith(" $s")) {
                q = q.substring(0, q.length - s.length).trim()
                break
            }
        }
        return q.removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
    }

    private fun shorten(text: String): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > 420) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
            if (sb.length >= 280) break
        }
        return sb.toString()
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "ASTRA-Assistant/1.0 (Android)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
