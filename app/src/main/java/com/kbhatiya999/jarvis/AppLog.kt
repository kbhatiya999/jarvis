package com.kbhatiya999.jarvis

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.kbhatiya999.jarvis.core.JarvisLog
import com.kbhatiya999.jarvis.core.LogLevel
import com.kbhatiya999.jarvis.core.LogSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android side of [JarvisLog]: every line goes to logcat (tag "Jarvis<Tag>") and to a rolling file in the app's
 * storage (about 1 MB, oldest lines dropped). The level is what the user chose under Settings > Logs, else the one
 * this release was built with ([BuildConfig.LOG_LEVEL]: info for stable releases, debug for dev builds and
 * releases we are unsure about). The file only leaves the phone when the user taps Share.
 */
object AppLog : LogSink {
    private const val PREFS = "jarvis_log"
    private const val KEY_LEVEL = "level"
    const val DEFAULT = "default"

    @Volatile private var app: Context? = null
    private var size = -1L
    private val io = Executors.newSingleThreadExecutor { Thread(it, "jarvis-log").apply { isDaemon = true } }
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    /** Safe to call many times; called first thing in [JarvisApp]. */
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        JarvisLog.sink = this
        JarvisLog.level = effectiveLevel()
        JarvisLog.i("App", "start ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.SDK_INT}, log level ${JarvisLog.level}" +
            " (${if (setting() == DEFAULT) "release default" else "chosen"})")
    }

    /** What the user picked: [DEFAULT] (follow the release) or a level name. */
    fun setting(): String {
        val saved = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_LEVEL, null)
        return if (LogLevel.parse(saved) != null) saved!!.lowercase() else DEFAULT
    }

    fun setSetting(value: String) {
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY_LEVEL, value)?.apply()
        JarvisLog.level = effectiveLevel()
        JarvisLog.i("App", "log level set to ${JarvisLog.level} (${if (value == DEFAULT) "release default" else "chosen"})")
    }

    /** What this release logs unless the user chose otherwise. */
    fun releaseLevel(): LogLevel = JarvisLog.resolveLevel(null, BuildConfig.LOG_LEVEL)

    private fun effectiveLevel() = JarvisLog.resolveLevel(setting(), BuildConfig.LOG_LEVEL)

    /** The phone's language as a BCP-47 code, or "en-IN" when it can't tell. */
    fun languageCode(): String = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en-IN"

    override fun write(level: LogLevel, tag: String, message: String) {
        val priority = when (level) {
            LogLevel.ERROR -> Log.ERROR
            LogLevel.WARN -> Log.WARN
            LogLevel.INFO -> Log.INFO
            LogLevel.DEBUG -> Log.DEBUG
        }
        Log.println(priority, "Jarvis$tag", message)
        val line = JarvisLog.line(stamp.get()!!.format(System.currentTimeMillis()), level, tag, message)
        io.execute { append(line) }
    }

    /**
     * Dev builds (version "…-dev…", from tilt:install) keep the log in the app's external folder, which adb can read
     * (`mise run tilt:phone-logs`), so a test on the phone can be checked from the Mac. Releases keep it private.
     */
    private val dev = BuildConfig.VERSION_NAME.contains("-dev")

    private fun file(): File? = app?.let { ctx ->
        val base = if (dev) ctx.getExternalFilesDir(null) ?: ctx.filesDir else ctx.filesDir
        File(File(base, "logs"), "jarvis.log")
    }

    private fun append(line: String) {
        val f = file() ?: return
        try {
            f.parentFile?.mkdirs()
            if (size < 0) size = f.length()
            f.appendText(line + "\n")
            size += line.length + 1
            if (size > JarvisLog.FILE_MAX) {
                val kept = JarvisLog.trim(f.readText())
                f.writeText(kept)
                size = kept.length.toLong()
            }
        } catch (e: Exception) {
            Log.w("JarvisLog", "Cannot write the log file", e)
        }
    }

    /** Waits for the lines queued so far to reach the file. */
    private fun flush() {
        try { io.submit { }.get(2, TimeUnit.SECONDS) } catch (e: Exception) { }
    }

    /** Size of the log file in bytes. */
    fun bytes(): Long {
        flush()
        return file()?.takeIf { it.exists() }?.length() ?: 0
    }

    fun clear() {
        io.execute {
            file()?.delete()
            size = 0
        }
        JarvisLog.i("App", "log cleared")
    }

    /** A share sheet for the log (with a short header about the app and phone), or null when there is nothing to share. */
    fun shareIntent(context: Context): Intent? {
        flush()
        val log = file()?.takeIf { it.exists() && it.length() > 0 } ?: return null
        val out = File(log.parentFile, "jarvis-log.txt")
        out.writeText(
            "Jarvis ${BuildConfig.VERSION_NAME}\n" +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Phone language ${languageCode()}, log level ${JarvisLog.level}\n\n" + log.readText()
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Jarvis log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share Jarvis log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
