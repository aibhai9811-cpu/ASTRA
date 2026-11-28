package com.astra.assistant.voice

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

// How the wake word may be heard. The small model does not always spell "astra" the same way.
object WakeVariants {
    val STRICT = setOf(
        "astra", "astro", "aastra", "astraa", "ostra", "austra", "asthra",
        "अस्त्र", "एस्ट्रा", "अस्ट्रा"
    )
    val LOOSE = setOf("extra", "astral", "aster", "estra")
    private val FILLERS = setOf("hey", "ok", "okay", "hi", "hello")

    private fun isWakeToken(token: String): Boolean = token in STRICT || token in LOOSE

    private fun significant(text: String): List<String> =
        text.split(" ").filter { it.isNotBlank() && it != "[unk]" }

    fun isWakeFinal(text: String): Boolean {
        val tokens = significant(text)
        val head = tokens.dropWhile { it in FILLERS }
        if (head.isEmpty()) return false
        val first = head[0]
        if (first in STRICT) return true
        if (tokens.size <= 3 && (first in LOOSE || (first == "a" && head.getOrNull(1) == "star"))) return true
        return false
    }

    fun isWakePartial(text: String): Boolean {
        val head = significant(text).dropWhile { it in FILLERS }
        return head.isNotEmpty() && head[0] in STRICT
    }

    // Removes a leading wake word (and "hey"/"ok" before it). "hello" alone is left untouched.
    fun stripLeading(text: String): String {
        val tokens = text.split(" ").filter { it.isNotBlank() && it != "[unk]" }.toMutableList()
        var start = 0
        if (tokens.size >= 2 && tokens[0] in FILLERS && isWakeToken(tokens[1])) start = 1
        if (start < tokens.size && isWakeToken(tokens[start])) {
            tokens.subList(0, start + 1).clear()
        } else if (start == 0 && tokens.size >= 2 && tokens[0] == "a" && tokens[1] == "star") {
            tokens.subList(0, 2).clear()
        }
        return tokens.joinToString(" ")
    }
}

// Words that mean "stop talking", used while ASTRA is speaking.
object StopPhrases {
    private val SINGLE = setOf(
        "stop", "quiet", "silence", "enough", "cancel", "chup", "bas",
        "बस", "चुप", "रुको", "रुक", "स्टॉप", "शांत"
    )
    private val KARO = setOf("karo", "kara", "carol", "kar", "care", "करो")
    private val JAO = setOf("jao", "jow", "raho", "ho", "hojao")

    fun matches(text: String): Boolean {
        val t = text.split(" ").filter { it.isNotBlank() && it != "[unk]" }
        if (t.isEmpty() || t.size > 5) return false
        if (t.any { it in SINGLE }) return true
        if (t.contains("shut") && t.contains("up")) return true
        if (t.any { it == "bus" || it == "bass" } && t.any { it in KARO }) return true
        if (t.any { it == "chop" || it == "chip" } && t.any { it in JAO }) return true
        if (t.any { it == "ruk" || it == "rook" }) return true
        if (t.contains("band") && (t.contains("awaaz") || t.contains("awaz") || t.contains("bolna"))) return true
        return false
    }
}

// Offline speech recognition with one microphone session and three modes:
// WAKE (waiting for "ASTRA"), COMMAND (capturing a command) and SPEAKING (only listening for "stop").
// In COMMAND mode the pieces of one sentence are collected, and the sentence is delivered only after
// a pause long enough to mean "I am done". All callbacks arrive on the main thread.
class OfflineRecognizer(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onInput: (String) -> Unit,
    private val onCaptureStarted: () -> Unit,
    private val onWake: () -> Unit,
    private val onWindowClosed: () -> Unit,
    private val onBargeIn: () -> Unit
) {
    class Vocabulary(val wake: List<String>, val stop: List<String>, val command: List<String>) {
        fun key(): Int = 31 * (31 * wake.hashCode() + stop.hashCode()) + command.hashCode()

        companion object {
            val EMPTY = Vocabulary(emptyList(), emptyList(), emptyList())
        }
    }

    private enum class Mode { WAKE, COMMAND, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var model: Model? = null
    private var wakeRec: Recognizer? = null
    private var bargeRec: Recognizer? = null
    private var cmdRec: Recognizer? = null
    private var freeRec: Recognizer? = null
    private var loadedPath: String? = null
    private var vocabKey = 0
    private var loading = false
    private var thread: Thread? = null
    private var preferred: AudioDeviceInfo? = null
    private var echoEffect: AcousticEchoCanceler? = null
    private var noiseEffect: NoiseSuppressor? = null
    private var lastPath: String? = null
    private var lastHindi = false
    private var lastVocab: Vocabulary = Vocabulary.EMPTY

    // Used only by the capture thread
    private val ring = ArrayDeque<ShortArray>()
    private var sinceMark = 0
    private var lastPartial = ""
    private var bargeCooldownUntil = 0L
    private var replayPending: List<ShortArray>? = null
    private val assembled = StringBuilder()
    private var lastSpeechAt = 0L
    private var assemblyStartedAt = 0L

    @Volatile var requireWake: () -> Boolean = { true }
    @Volatile private var wantCapture = false
    @Volatile private var running = false
    @Volatile private var speaking = false
    @Volatile private var speakingUntil = 0L
    @Volatile private var windowUntil = 0L

    val isRunning: Boolean
        get() = running

    // ---------------- control (main thread) ----------------

    fun start(path: String, isHindi: Boolean, vocab: Vocabulary, device: AudioDeviceInfo?) {
        wantCapture = true
        preferred = device
        lastPath = path
        lastHindi = isHindi
        lastVocab = vocab
        if (running) {
            if (loadedPath == path && vocabKey == vocab.key()) return
            stopCapture()
            wantCapture = true
        }
        val ready = synchronized(lock) {
            model != null && loadedPath == path && vocabKey == vocab.key() && freeRec != null
        }
        if (ready) {
            main.post { if (wantCapture) beginCapture() }
            return
        }
        val alreadyLoading = synchronized(lock) {
            if (loading) {
                true
            } else {
                loading = true
                false
            }
        }
        if (alreadyLoading) return
        Thread({ prepare(path, vocab) }, "astra-vosk-load").start()
    }

    fun setSpeaking(value: Boolean) {
        if (value) {
            speaking = true
            speakingUntil = 0L
        } else {
            speaking = false
            speakingUntil = SystemClock.elapsedRealtime() + TAIL_MS
        }
    }

    // Lets the next speech through without the wake word, for example while waiting for yes or no.
    fun openCommandWindow(ms: Long) {
        windowUntil = SystemClock.elapsedRealtime() + ms
    }

    fun stopCapture() {
        wantCapture = false
        running = false
        val t = thread
        if (t != null && t.isAlive) {
            try {
                t.join(1500)
            } catch (e: InterruptedException) {
                Log.w(TAG, "interrupted while stopping", e)
            }
        }
        if (t == null || !t.isAlive) thread = null
    }

    fun restartCapture(device: AudioDeviceInfo?) {
        val path = lastPath ?: return
        stopCapture()
        start(path, lastHindi, lastVocab, device)
    }

    fun release() {
        stopCapture()
        synchronized(lock) {
            closeRecognizers()
            closeModel()
        }
    }

    // ---------------- loading ----------------

    private fun prepare(path: String, vocab: Vocabulary) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val existing = model
            val m: Model = if (existing != null && loadedPath == path) {
                existing
            } else {
                synchronized(lock) {
                    closeRecognizers()
                    closeModel()
                }
                Model(path)
            }
            val wake = grammarRecognizer(m, vocab.wake)
            val barge = grammarRecognizer(m, vocab.stop)
            val cmd = grammarRecognizer(m, vocab.command)
            val free = Recognizer(m, SAMPLE_RATE.toFloat())
            synchronized(lock) {
                closeRecognizers()
                model = m
                loadedPath = path
                vocabKey = vocab.key()
                wakeRec = wake
                bargeRec = barge
                cmdRec = cmd
                freeRec = free
                loading = false
            }
            main.post { if (wantCapture) beginCapture() }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load the offline speech model", t)
            synchronized(lock) { loading = false }
            main.post { onFailure("The offline voice engine could not start (${t.javaClass.simpleName}).") }
        }
    }

    private fun grammarRecognizer(m: Model, words: List<String>): Recognizer? {
        if (words.isEmpty()) return null
        return try {
            val json = JSONArray()
            for (w in words) json.put(w)
            json.put("[unk]")
            Recognizer(m, SAMPLE_RATE.toFloat(), json.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Could not build a vocabulary recognizer", t)
            null
        }
    }

    private fun closeRecognizers() {
        for (r in listOf(wakeRec, bargeRec, cmdRec, freeRec)) {
            try {
                r?.close()
            } catch (e: Exception) {
                Log.w(TAG, "recognizer.close failed", e)
            }
        }
        wakeRec = null
        bargeRec = null
        cmdRec = null
        freeRec = null
    }

    private fun closeModel() {
        try {
            model?.close()
        } catch (e: Exception) {
            Log.w(TAG, "model.close failed", e)
        }
        model = null
        loadedPath = null
    }

    // ---------------- capture ----------------

    private fun beginCapture() {
        synchronized(lock) {
            if (running || thread?.isAlive == true) return
            if (freeRec == null) return
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                main.post { onFailure("This phone did not accept the microphone settings.") }
                return
            }
            val record: AudioRecord? = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 16384)
                )
            } catch (e: Exception) {
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                main.post { onFailure("Could not open the microphone.") }
                return
            }
            preferred?.let { record.setPreferredDevice(it) }
            attachEffects(record.audioSessionId)
            try {
                record.startRecording()
            } catch (e: Exception) {
                releaseEffects()
                record.release()
                main.post { onFailure("The microphone could not start.") }
                return
            }
            running = true
            val t = Thread({ loop(record) }, "astra-vosk-capture")
            thread = t
            t.start()
        }
    }

    // Echo cancellation and noise suppression help the stop word work while ASTRA is talking.
    private fun attachEffects(session: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoEffect = AcousticEchoCanceler.create(session)
                echoEffect?.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Echo canceller unavailable", e)
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseEffect = NoiseSuppressor.create(session)
                noiseEffect?.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Noise suppressor unavailable", e)
        }
    }

    private fun releaseEffects() {
        try {
            echoEffect?.release()
        } catch (e: Exception) {
            Log.w(TAG, "echo release failed", e)
        }
        try {
            noiseEffect?.release()
        } catch (e: Exception) {
            Log.w(TAG, "noise release failed", e)
        }
        echoEffect = null
        noiseEffect = null
    }

    private fun currentMode(now: Long): Mode {
        if (speaking || now < speakingUntil) return Mode.SPEAKING
        if (!requireWake()) return Mode.COMMAND
        // Never drop out of command mode while a sentence is still being collected.
        if (assembled.isNotEmpty()) return Mode.COMMAND
        return if (now < windowUntil) Mode.COMMAND else Mode.WAKE
    }

    private fun loop(record: AudioRecord) {
        val buffer = ShortArray(CHUNK)
        var reported = false
        var lastMode = Mode.WAKE
        ring.clear()
        sinceMark = 0
        lastPartial = ""
        replayPending = null
        assembled.setLength(0)
        try {
            while (running) {
                val n = record.read(buffer, 0, CHUNK)
                if (n < 0) {
                    main.post { onFailure("The microphone stopped delivering audio.") }
                    break
                }
                if (n == 0) continue
                if (!reported) {
                    reported = true
                    val label = describe(record.routedDevice)
                    main.post {
                        onCaptureStarted()
                        onInput(label)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val chunk = buffer.copyOf(n)
                val mode = currentMode(now)
                if (mode != lastMode) {
                    onModeChange(lastMode, mode)
                    lastMode = mode
                }
                when (mode) {
                    Mode.SPEAKING -> handleSpeaking(chunk, now)
                    Mode.WAKE -> {
                        remember(chunk)
                        handleWake(chunk, now)
                    }
                    Mode.COMMAND -> {
                        remember(chunk)
                        handleCommand(chunk, now)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Offline capture failed", t)
            main.post { onFailure("The offline voice engine stopped (${t.javaClass.simpleName}).") }
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                Log.w(TAG, "record.stop failed", e)
            }
            releaseEffects()
            record.release()
            running = false
        }
    }

    private fun remember(chunk: ShortArray) {
        ring.addLast(chunk)
        while (ring.size > RING_CHUNKS) ring.removeFirst()
        sinceMark++
    }

    private fun onModeChange(from: Mode, to: Mode) {
        when {
            to == Mode.SPEAKING -> {
                bargeRec?.reset()
                lastPartial = ""
                assembled.setLength(0)
            }
            from == Mode.SPEAKING -> {
                // ASTRA just finished talking. Throw away anything that might be its own voice.
                wakeRec?.reset()
                cmdRec?.reset()
                freeRec?.reset()
                ring.clear()
                sinceMark = 0
                lastPartial = ""
                replayPending = null
                assembled.setLength(0)
            }
            to == Mode.WAKE -> {
                wakeRec?.reset()
                cmdRec?.reset()
                freeRec?.reset()
                windowUntil = 0L
                lastPartial = ""
                assembled.setLength(0)
                if (from == Mode.COMMAND && requireWake()) main.post { onWindowClosed() }
            }
            to == Mode.COMMAND -> {
                cmdRec?.reset()
                freeRec?.reset()
                lastPartial = ""
                assembled.setLength(0)
                val now = SystemClock.elapsedRealtime()
                lastSpeechAt = now
                assemblyStartedAt = now
                val replay = replayPending
                replayPending = null
                if (replay != null) {
                    for (c in replay) handleCommand(c, SystemClock.elapsedRealtime())
                }
            }
        }
    }

    // ---------------- wake mode ----------------

    private fun handleWake(chunk: ShortArray, now: Long) {
        val rec = wakeRec ?: freeRec ?: return
        val end = rec.acceptWaveForm(chunk, chunk.size)
        if (end) {
            val text = clean(field(rec.result, "text"))
            if (WakeVariants.isWakeFinal(text)) triggerWake(now) else sinceMark = 0
            rec.reset()
        } else {
            val partial = clean(field(rec.partialResult, "partial"))
            if (partial.isNotEmpty() && WakeVariants.isWakePartial(partial)) {
                triggerWake(now)
                rec.reset()
            }
        }
    }

    private fun triggerWake(now: Long) {
        val count = minOf(sinceMark + 2, ring.size)
        replayPending = ring.toList().takeLast(count)
        windowUntil = now + FIRST_COMMAND_MS
        sinceMark = 0
        main.post { onWake() }
    }

    // ---------------- command mode ----------------

    private fun handleCommand(chunk: ShortArray, now: Long) {
        val free = freeRec ?: return
        val cmd = cmdRec
        val gEnd = cmd?.acceptWaveForm(chunk, chunk.size) ?: false
        val fEnd = free.acceptWaveForm(chunk, chunk.size)
        if (gEnd || fEnd) {
            val g = if (cmd == null) "" else clean(field(if (gEnd) cmd.result else cmd.finalResult, "text"))
            val f = clean(field(if (fEnd) free.result else free.finalResult, "text"))
            cmd?.reset()
            free.reset()
            lastPartial = ""
            val piece = choose(g, f)
            if (piece.isNotBlank()) {
                if (assembled.isEmpty()) assemblyStartedAt = now else assembled.append(' ')
                assembled.append(piece)
                lastSpeechAt = now
                if (windowUntil != 0L) windowUntil = maxOf(windowUntil, now + WINDOW_EXTEND_MS)
            }
        } else {
            val partial = clean(field(free.partialResult, "partial"))
            if (partial.isNotEmpty() && partial != lastPartial) {
                lastPartial = partial
                lastSpeechAt = now
                if (windowUntil != 0L) windowUntil = maxOf(windowUntil, now + WINDOW_EXTEND_MS)
                val shown = WakeVariants.stripLeading((assembled.toString() + " " + partial).trim())
                main.post { onPartial(shown) }
            }
        }
        flushIfQuiet(now)
    }

    // Delivers the collected sentence once there has been enough quiet to mean "I am done".
    private fun flushIfQuiet(now: Long) {
        if (assembled.isEmpty()) return
        val text = assembled.toString().trim()
        val head = WakeVariants.stripLeading(text)
        val quiet = now - lastSpeechAt
        val tooLong = now - assemblyStartedAt > MAX_SENTENCE_MS
        if (quiet < gapFor(head) && !tooLong) return
        assembled.setLength(0)
        lastPartial = ""
        if (requireWake()) windowUntil = 0L
        if (head.isNotBlank()) main.post { onFinal(head) }
    }

    // How long a pause must last before the sentence counts as finished.
    private fun gapFor(text: String): Long {
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return 900L
        var gap = when {
            words[0] in MESSAGE_VERBS -> 2000L
            words[0] in CALL_VERBS -> 1500L
            words.size <= 3 -> 600L
            else -> 900L
        }
        if (words.last() in CONNECTORS) gap += 1000L
        return gap
    }

    // Trust the command-vocabulary result when it understood every word, otherwise use the open result.
    private fun choose(grammarText: String, freeText: String): String {
        val tokens = grammarText.split(" ").filter { it.isNotBlank() }.toMutableList()
        while (tokens.isNotEmpty() && tokens.first() == UNK) tokens.removeAt(0)
        while (tokens.isNotEmpty() && tokens.last() == UNK) tokens.removeAt(tokens.size - 1)
        return if (tokens.isNotEmpty() && tokens.none { it == UNK }) tokens.joinToString(" ") else freeText
    }

    // ---------------- speaking mode ----------------

    private fun handleSpeaking(chunk: ShortArray, now: Long) {
        val rec = bargeRec ?: return
        val end = rec.acceptWaveForm(chunk, chunk.size)
        val text = clean(field(if (end) rec.result else rec.partialResult, if (end) "text" else "partial"))
        if (end) rec.reset()
        if (now < bargeCooldownUntil) return
        if (text.isNotEmpty() && StopPhrases.matches(text)) {
            rec.reset()
            bargeCooldownUntil = now + 1500L
            main.post { onBargeIn() }
        }
    }

    // ---------------- helpers ----------------

    private fun clean(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private fun field(json: String, key: String): String =
        try {
            JSONObject(json).optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }

    // The real input Android selected, for example the earbuds or the phone microphone.
    private fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "Phone microphone"
        val name = device.productName?.toString().orEmpty()
        val type = device.type
        return when {
            type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth: " + name.ifBlank { "earbuds" }
            Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET ->
                "Bluetooth: " + name.ifBlank { "earbuds" }
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET -> "Wired headset"
            else -> name.ifBlank { "Other microphone" }
        }
    }

    companion object {
        private const val TAG = "ASTRA_OFFLINE"
        private const val UNK = "[unk]"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600
        private const val RING_CHUNKS = 50
        private const val FIRST_COMMAND_MS = 9000L
        private const val WINDOW_EXTEND_MS = 6000L
        private const val MAX_SENTENCE_MS = 20000L
        private const val TAIL_MS = 500L

        private val MESSAGE_VERBS = setOf(
            "send", "text", "message", "msg", "whatsapp", "say", "write", "tell", "remind", "note",
            "remember", "search", "google", "ask", "email"
        )
        private val CALL_VERBS = setOf("call", "dial", "phone", "ring", "set", "wake")
        private val CONNECTORS = setOf(
            "and", "to", "on", "the", "a", "an", "for", "with", "that", "saying", "say", "at", "in", "of",
            "my", "me", "is", "i", "will", "be", "am", "are", "it", "this", "about", "please"
        )
    }
}
        
