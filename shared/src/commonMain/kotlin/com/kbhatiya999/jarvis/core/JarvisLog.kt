package com.kbhatiya999.jarvis.core

/** How much the app logs. A level includes every level before it: INFO shows ERROR, WARN and INFO. */
enum class LogLevel(val letter: Char) {
    ERROR('E'), WARN('W'), INFO('I'), DEBUG('D');

    companion object {
        /** "info", "INFO" ... or null (also for "default" and anything unknown). */
        fun parse(text: String?): LogLevel? = values().firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}

/** Where log lines go (Android: logcat and a rolling file). Called on any thread, one line at a time. */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String)
}

/**
 * The one log for the whole app, shared code and platform code alike. Lines below [level] are dropped before
 * they are even built (use the lambda form of [d] for anything costly). Nothing here leaves the phone; the user
 * shares the log file by hand.
 *
 * Privacy: never log the API key or contact details. What the user said or Jarvis replied goes through [said],
 * which writes only at DEBUG and only the first [SAID_MAX] characters. [redact] is a last line of defence.
 */
object JarvisLog {
    const val SAID_MAX = 80

    /** The rolling log file keeps about this much (characters, which is bytes for the mostly-ASCII lines). */
    const val FILE_MAX = 1_000_000

    @kotlin.concurrent.Volatile var level: LogLevel = LogLevel.INFO
    @kotlin.concurrent.Volatile var sink: LogSink? = null

    fun enabled(level: LogLevel) = level.ordinal <= this.level.ordinal

    fun e(tag: String, message: String, error: Throwable? = null) = write(LogLevel.ERROR, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = write(LogLevel.WARN, tag, message, error)
    fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message, null)
    fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null)

    /** DEBUG with a message that is only built when DEBUG is on. */
    inline fun d(tag: String, message: () -> String) {
        if (enabled(LogLevel.DEBUG)) d(tag, message())
    }

    /** Something the user or Jarvis said: DEBUG only, cut short. [who] is "user" or "jarvis". */
    fun said(tag: String, who: String, text: String) {
        if (enabled(LogLevel.DEBUG)) d(tag, "$who said: \"${snippet(text)}\"")
    }

    private fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (!enabled(level)) return
        val full = if (error == null) message else "$message (${error::class.simpleName}: ${error.message})"
        sink?.write(level, tag, redact(full))
    }

    /** The level to use: what the user chose, else the release's default, else INFO. */
    fun resolveLevel(setting: String?, releaseDefault: String?): LogLevel =
        LogLevel.parse(setting) ?: LogLevel.parse(releaseDefault) ?: LogLevel.INFO

    /** First [max] characters on one line. */
    fun snippet(text: String, max: Int = SAID_MAX): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }

    /** Masks anything that looks like an API key, an email address or a web address (relay topics are contact addresses). */
    fun redact(text: String): String = text
        .replace(Regex("https?://\\S+"), "[url]")
        .replace(Regex("AIza[0-9A-Za-z_\\-]{20,}"), "[key]")
        .replace(Regex("(?i)(api[_-]?key|x-goog-api-key)([=:\\s\"']+)[^\\s\"',;&]+"), "$1$2[key]")
        .replace(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), "[email]")

    /** One line of the log file. [timestamp] is already formatted by the platform. Newlines in [message] become " | ". */
    fun line(timestamp: String, level: LogLevel, tag: String, message: String): String =
        "$timestamp ${level.letter}/$tag: ${message.replace("\r", "").replace("\n", " | ")}"

    /**
     * Keeps the log file bounded: when [text] is longer than [max], drops the oldest whole lines so that about
     * [keep] (default three quarters of [max]) remain. Trimming below [max] each time avoids rewriting the file on
     * every line.
     */
    fun trim(text: String, max: Int = FILE_MAX, keep: Int = max / 4 * 3): String {
        if (text.length <= max) return text
        val cut = text.length - keep
        val newline = text.indexOf('\n', cut)
        return if (newline < 0) text.takeLast(keep) else text.substring(newline + 1)
    }
}
