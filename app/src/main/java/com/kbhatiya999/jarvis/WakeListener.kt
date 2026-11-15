package com.kbhatiya999.jarvis

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Hears "Jarvis wake up", "Jarvis power down" and "Jarvis" on the phone itself (Vosk, offline), from the
 * shared mic. Only the phrases are recognised; nothing leaves the phone. To save battery it decodes only
 * while there's sound. Callbacks run on its own thread.
 */
class WakeListener(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onPowerDown: () -> Unit,
    /** "Jarvis" said on its own or before anything else (used to cut Jarvis off). */
    private val onName: () -> Unit,
) {
    private val chunks = LinkedBlockingQueue<ByteArray>(200)
    private val mic: (ByteArray) -> Unit = { chunks.offer(it) }
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { run() }.apply { name = "jarvis-wake"; start() }
    }

    fun stop() {
        running = false
        MicHub.remove(mic)
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val model = try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(Models.dir(context).absolutePath)
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot load the speech model", e)
            running = false
            return
        }
        val recognizer = Recognizer(model, MicHub.RATE.toFloat(), GRAMMAR)
        MicHub.add(mic)
        val preRoll = ArrayDeque<ByteArray>()
        var lastSound = 0L
        var decoding = false
        var fired = mutableSetOf<String>() // what this utterance already triggered
        try {
            while (running) {
                val chunk = try { chunks.poll(500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
                val now = System.currentTimeMillis()
                if (MicHub.rms(chunk) >= SOUND_RMS) lastSound = now
                if (now - lastSound > TAIL_MS) {
                    if (decoding) {
                        // Quiet again: finish this utterance and idle.
                        check(JSONObject(recognizer.finalResult).optString("text"), fired)
                        recognizer.reset()
                        fired = mutableSetOf()
                        decoding = false
                    }
                    preRoll.addLast(chunk)
                    if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    continue
                }
                if (!decoding) {
                    decoding = true
                    while (preRoll.isNotEmpty()) preRoll.removeFirst().let { recognizer.acceptWaveForm(it, it.size) }
                }
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    check(JSONObject(recognizer.result).optString("text"), fired)
                    fired = mutableSetOf()
                } else if (check(JSONObject(recognizer.partialResult).optString("partial"), fired)) {
                    // A command fired: start fresh so it can't fire twice.
                    recognizer.reset()
                    fired = mutableSetOf()
                }
            }
        } finally {
            MicHub.remove(mic)
            recognizer.close()
            model.close()
        }
    }

    /** Fires what [text] contains (once per utterance). True when a command (not just the name) fired. */
    private fun check(text: String, fired: MutableSet<String>): Boolean {
        val t = " ${text.replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")} "
        if (" jarvis " !in t) return false
        if ("name" !in fired) { fired += "name"; onName() }
        return when {
            " jarvis power down " in t -> { onPowerDown(); true }
            " jarvis wake up " in t -> { onWake(); true }
            else -> false
        }
    }

    /** The offline speech model (Vosk small English, about 40 MB), downloaded once when the feature is turned on. */
    object Models {
        private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        fun dir(context: Context) = File(context.filesDir, "vosk-en")
        fun ready(context: Context) = File(dir(context), ".complete").exists()

        /** Blocking; call off the main thread. [progress] gets 0..1. Returns an error or null. */
        fun download(context: Context, progress: (Float) -> Unit): String? {
            val dir = dir(context)
            val tmp = File(context.filesDir, "vosk-en.tmp")
            return try {
                tmp.deleteRecursively()
                tmp.mkdirs()
                val response = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(URL).build()).execute()
                response.use { r ->
                    if (!r.isSuccessful) return "Download failed (${r.code})."
                    val body = r.body ?: return "Download failed (empty)."
                    val total = body.contentLength().takeIf { it > 0 } ?: 41_205_931L
                    val counting = object : java.io.FilterInputStream(body.byteStream()) {
                        var read = 0L
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also { if (it > 0) { read += it; progress((read.toFloat() / total).coerceAtMost(1f)) } }
                    }
                    ZipInputStream(counting).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            // Drop the top folder ("vosk-model-small-en-us-0.15/...").
                            val rel = e.name.substringAfter('/', "")
                            if (rel.isEmpty() || rel.contains("..")) continue
                            val out = File(tmp, rel)
                            if (e.isDirectory) out.mkdirs()
                            else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                        }
                    }
                }
                File(tmp, ".complete").writeText("vosk-model-small-en-us-0.15")
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) return "Could not save the speech model."
                null
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed", e)
                "Download failed: ${e.message ?: e}"
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    private companion object {
        const val TAG = "JarvisWake"
        // Only these can be recognised; everything else comes out as [unk].
        const val GRAMMAR = """["jarvis wake up", "jarvis power down", "jarvis", "[unk]"]"""
        const val SOUND_RMS = 250.0 // quiet room after noise suppression is well below this
        const val TAIL_MS = 1_200L // keep decoding this long after the last sound
        const val PRE_ROLL_CHUNKS = 6 // ~0.4 s before the sound, so the start of "Jarvis" isn't lost
    }
}
