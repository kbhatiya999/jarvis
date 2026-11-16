package com.kbhatiya999.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.util.Log
import com.kbhatiya999.jarvis.core.ActiveMode
import com.kbhatiya999.jarvis.core.Jarvis
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Short replies Jarvis plays instantly, without Gemini: on "Jarvis wake up" and "Jarvis power down".
 * Order of preference for each slot:
 *  1. clips the user added (any audio file), one at random;
 *  2. clips Jarvis recorded of itself in its Gemini voice (made once, kept per voice and line);
 *  3. Android's built-in voice reading one of the lines, until those recordings exist.
 * Main thread.
 */
class Clips(context: Context) {
    enum class Slot(val folder: String, val label: String) {
        WAKE("wake", "When you say \"Jarvis wake up\""),
        ALREADY_AWAKE("already_awake", "When you say \"Jarvis wake up\" and it's already awake"),
        POWER_DOWN("power_down", "When you say \"Jarvis power down\""),
    }

    private val app = context.applicationContext
    private val jarvis: Jarvis = AndroidPlatform.jarvis(app)
    private val main = Handler(Looper.getMainLooper())
    private val root = File(app.filesDir, "clips")
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun lines(slot: Slot): List<String> = when (slot) {
        Slot.WAKE -> jarvis.active.wakeLines
        Slot.ALREADY_AWAKE -> jarvis.active.alreadyAwakeLines
        Slot.POWER_DOWN -> jarvis.active.powerDownLines
    }

    fun setLines(slot: Slot, lines: List<String>) = when (slot) {
        Slot.WAKE -> jarvis.active.wakeLines = lines
        Slot.ALREADY_AWAKE -> jarvis.active.alreadyAwakeLines = lines
        Slot.POWER_DOWN -> jarvis.active.powerDownLines = lines
    }

    // --- Jarvis's own recordings ---

    private fun recordedFile(line: String) =
        File(root, "recorded/${jarvis.voiceLines.voice}_${Integer.toHexString(line.trim().hashCode())}.wav")

    fun recorded(line: String) = recordedFile(line).exists()

    /** Lines (of every slot) not yet recorded in the current voice. */
    fun missing(): List<String> = Slot.entries.flatMap { lines(it) }.distinct().filter { !recorded(it) }

    /**
     * Has Gemini say the lines that aren't recorded yet and keeps them. One session for all of them.
     * [done] gets null on success, else why not.
     */
    fun recordMissing(force: Boolean = false, done: (String?) -> Unit = {}) {
        if (recording) return done("Already recording.")
        val todo = if (force) Slot.entries.flatMap { lines(it) }.distinct() else missing()
        if (todo.isEmpty()) return done(null)
        recording = true
        jarvis.voiceLines.record(todo, onLine = { line, pcm -> writeWav(recordedFile(line), pcm) }) { error ->
            recording = false
            if (error != null) Log.w(TAG, "Recording Jarvis's lines: $error")
            done(error)
        }
    }

    // --- The user's own clips ---

    private fun customDir(slot: Slot) = File(root, "custom/${slot.folder}")

    fun custom(slot: Slot): List<File> =
        customDir(slot).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()

    /** Copies an audio file the user picked into [slot]. Returns an error or null. */
    fun add(slot: Slot, uri: Uri): String? = try {
        val name = displayName(uri) ?: "clip_${System.currentTimeMillis()}"
        val dir = customDir(slot).apply { mkdirs() }
        val target = unique(dir, safeName(name))
        app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return "Cannot read that file."
        if (duration(target) == null) {
            target.delete()
            "That file doesn't play as audio."
        } else null
    } catch (e: Exception) {
        "Could not add it: ${e.message ?: e}"
    }

    fun remove(file: File) {
        if (file.canonicalPath.startsWith(File(root, "custom").canonicalPath)) file.delete()
    }

    /** Writes all of the user's clips (folders wake/ and power_down/) and the lines into a zip. */
    fun export(uri: Uri): String? = try {
        app.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                for (slot in Slot.entries) {
                    for (f in custom(slot)) {
                        zip.putNextEntry(ZipEntry("${slot.folder}/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("${slot.folder}/lines.txt"))
                    zip.write(lines(slot).joinToString("\n").toByteArray())
                    zip.closeEntry()
                }
            }
        } ?: "Cannot write there."
        null
    } catch (e: Exception) {
        "Export failed: ${e.message ?: e}"
    }

    /** Adds clips (and lines) from a zip made by [export]. Returns how many clips were added, or an error. */
    fun import(uri: Uri): Pair<Int, String?> = try {
        var added = 0
        app.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val slot = Slot.entries.firstOrNull { e.name.startsWith("${it.folder}/") } ?: continue
                    val name = e.name.substringAfterLast('/')
                    if (name.isEmpty() || name.startsWith(".")) continue
                    if (name == "lines.txt") {
                        setLines(slot, zip.readBytes().decodeToString().lines())
                        continue
                    }
                    val target = unique(customDir(slot).apply { mkdirs() }, safeName(name))
                    target.outputStream().use { zip.copyTo(it) }
                    if (duration(target) == null) target.delete() else added++
                }
            }
        } ?: return 0 to "Cannot read that file."
        added to null
    } catch (e: Exception) {
        0 to "Import failed: ${e.message ?: e}"
    }

    // --- Playing ---

    /** Plays one reply for [slot]; [done] runs (main thread) when it has finished. */
    fun play(slot: Slot, done: () -> Unit = {}) {
        val mine = custom(slot)
        val lines = lines(slot)
        val file = mine.randomOrNull() ?: lines.shuffled().firstOrNull { recorded(it) }?.let { recordedFile(it) }
        if (file != null) playFile(file, done) else speak(lines.random(), done)
    }

    fun playFile(file: File, done: () -> Unit = {}) {
        stop()
        val p = MediaPlayer()
        player = p
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                if (player === p) player = null
                p.release()
                done()
            }
        }
        try {
            p.setAudioAttributes(attributes())
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { finish() }
            p.setOnErrorListener { _, _, _ -> finish(); true }
            p.prepare()
            p.start()
            // Never let a broken file block what comes next.
            main.postDelayed({ finish() }, (p.duration.coerceIn(500, MAX_CLIP_MS)) + 500L)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play ${file.name}", e)
            finish()
        }
    }

    /** Android's built-in voice, used until Jarvis's own recordings exist. */
    private fun speak(text: String, done: () -> Unit) {
        val engine = tts
        if (engine != null && ttsReady) {
            val id = "jarvis_${System.nanoTime()}"
            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) main.post(done) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) main.post(done) }
            })
            engine.setAudioAttributes(attributes())
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            return
        }
        if (engine != null) { done(); return } // still starting or broken: skip rather than wait
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            main.post { if (ttsReady) speak(text, done) else done() }
        }
    }

    fun stop() {
        player?.let { try { it.stop() } catch (e: IllegalStateException) { }; it.release() }
        player = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun duration(file: File): Int? = try {
        MediaPlayer().run {
            try { setDataSource(file.absolutePath); prepare(); duration.takeIf { it > 0 } } finally { release() }
        }
    } catch (e: Exception) {
        null
    }

    private fun displayName(uri: Uri): String? =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "clip" }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($i)${name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}")
            i++
        }
        return f
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            fun le32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.write("RIFF".toByteArray()); le32(36 + pcm.size); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(RATE); le32(RATE * 2); le16(2); le16(16)
            f.write("data".toByteArray()); le32(pcm.size); f.write(pcm)
        }
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "JarvisClips"
        private const val RATE = 24000 // Gemini's voice
        private const val MAX_CLIP_MS = 15_000
        private var recording = false

        @Volatile private var instance: Clips? = null
        fun get(context: Context): Clips = instance ?: synchronized(this) { instance ?: Clips(context).also { instance = it } }

        val DEFAULTS = mapOf(
            Slot.WAKE to ActiveMode.DEFAULT_WAKE_LINES,
            Slot.ALREADY_AWAKE to ActiveMode.DEFAULT_ALREADY_AWAKE_LINES,
            Slot.POWER_DOWN to ActiveMode.DEFAULT_POWER_LINES,
        )
    }
}
