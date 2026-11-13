package com.astra.assistant.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoskModels {

    data class ModelInfo(
        val id: String,
        val label: String,
        val url: String,
        val folder: String,
        val sizeMb: Int
    )

    data class DownloadState(
        val running: Boolean = false,
        val modelId: String = "",
        val percent: Int = 0,
        val message: String = ""
    )

    val ENGLISH = ModelInfo(
        "en", "English (India)",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
        "vosk-model-small-en-in-0.4", 36
    )
    val HINDI = ModelInfo(
        "hi", "Hindi",
        "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        "vosk-model-small-hi-0.22", 42
    )

    private const val READY_MARKER = ".astra_ready"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = state.asStateFlow()

    fun forLanguage(language: String): ModelInfo =
        if (language == VoiceSettings.LANG_HI) HINDI else ENGLISH

    private fun root(context: Context): File = File(context.applicationContext.filesDir, "vosk")

    fun folder(context: Context, model: ModelInfo): File = File(root(context), model.folder)

    fun isInstalled(context: Context, model: ModelInfo): Boolean =
        File(folder(context, model), READY_MARKER).exists()

    fun delete(context: Context, model: ModelInfo) {
        folder(context, model).deleteRecursively()
        state.value = DownloadState(false, model.id, 0, "${model.label} voice model removed.")
    }

    // Runs in the background, so it keeps going when you leave the Settings tab.
    fun download(context: Context, model: ModelInfo) {
        if (state.value.running) return
        val appContext = context.applicationContext
        state.value = DownloadState(true, model.id, 0, "Starting download...")
        scope.launch {
            val message = try {
                fetchAndUnpack(appContext, model)
                "${model.label} voice model is ready."
            } catch (e: Exception) {
                "Download failed: ${e.message ?: "unknown error"}"
            }
            state.value = DownloadState(false, model.id, 0, message)
        }
    }

    private fun fetchAndUnpack(context: Context, model: ModelInfo) {
        val rootDir = root(context)
        rootDir.mkdirs()
        val zipFile = File(context.cacheDir, model.folder + ".zip")
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(model.url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("the server answered $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 90 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                state.value = DownloadState(true, model.id, percent, "Downloading ${model.label}...")
                            }
                        }
                    }
                }
            }
            state.value = DownloadState(true, model.id, 92, "Unpacking...")
            val target = folder(context, model)
            target.deleteRecursively()
            unzip(zipFile, rootDir)
            if (!target.exists()) throw IOException("the unpacked folder was not found")
            File(target, READY_MARKER).writeText("ok")
        } finally {
            connection?.disconnect()
            zipFile.delete()
        }
    }

    private fun unzip(zip: File, destDir: File) {
        val destPath = destDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(destPath)) throw IOException("the zip file is not safe")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
    }
}
