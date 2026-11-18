package com.kbhatiya999.jarvis.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarvisLogTest {
    private val lines = mutableListOf<String>()

    private fun capture(level: LogLevel) {
        lines.clear()
        JarvisLog.level = level
        JarvisLog.sink = LogSink { l, tag, message -> lines += "${l.letter}/$tag: $message" }
    }

    @AfterTest
    fun reset() {
        JarvisLog.sink = null
        JarvisLog.level = LogLevel.INFO
    }

    @Test
    fun levelsIncludeThoseBefore() {
        capture(LogLevel.WARN)
        JarvisLog.e("T", "e"); JarvisLog.w("T", "w"); JarvisLog.i("T", "i"); JarvisLog.d("T", "d")
        assertEquals(listOf("E/T: e", "W/T: w"), lines)
        capture(LogLevel.DEBUG)
        JarvisLog.e("T", "e"); JarvisLog.w("T", "w"); JarvisLog.i("T", "i"); JarvisLog.d("T", "d")
        assertEquals(4, lines.size)
    }

    @Test
    fun debugLambdaIsOnlyBuiltAtDebug() {
        capture(LogLevel.INFO)
        var built = false
        JarvisLog.d("T") { built = true; "costly" }
        assertFalse(built)
        assertTrue(lines.isEmpty())
        capture(LogLevel.DEBUG)
        JarvisLog.d("T") { built = true; "costly" }
        assertTrue(built)
        assertEquals(listOf("D/T: costly"), lines)
    }

    @Test
    fun spokenTextNeverAppearsBelowDebug() {
        for (level in listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO)) {
            capture(level)
            JarvisLog.said("T", "user", "remind me about the secret plan")
            assertTrue(lines.isEmpty(), "said() leaked at $level")
        }
        capture(LogLevel.DEBUG)
        JarvisLog.said("T", "user", "remind me about the plan")
        assertEquals(listOf("D/T: user said: \"remind me about the plan\""), lines)
    }

    @Test
    fun spokenTextIsCutShort() {
        capture(LogLevel.DEBUG)
        JarvisLog.said("T", "jarvis", "word ".repeat(100))
        val text = lines.single().substringAfter('"').substringBeforeLast('"')
        assertEquals(JarvisLog.SAID_MAX + 1, text.length) // plus the ellipsis
        assertTrue(text.endsWith("…"))
        assertEquals("a b c", JarvisLog.snippet("a\n b\t c"))
    }

    @Test
    fun keysAndEmailsAreMasked() {
        capture(LogLevel.DEBUG)
        val key = "AIzaSyA1234567890abcdefghijklmnopqrstuv"
        JarvisLog.i("T", "failed with $key for karan@example.com")
        JarvisLog.w("T", "request x-goog-api-key: secret-value-123 sent")
        JarvisLog.e("T", "bad", IllegalStateException("apiKey=hunter2 rejected"))
        JarvisLog.w("T", "poll failed for https://ntfy.sh/contact-topic-abc123?x=1")
        assertTrue(lines.none { key in it || "karan@example.com" in it || "secret-value-123" in it || "hunter2" in it || "contact-topic" in it }, lines.toString())
        assertTrue("[key]" in lines[0] && "[email]" in lines[0])
    }

    @Test
    fun errorsShowTheirTypeAndMessage() {
        capture(LogLevel.ERROR)
        JarvisLog.e("T", "no mic", IllegalStateException("denied"))
        assertEquals(listOf("E/T: no mic (IllegalStateException: denied)"), lines)
    }

    @Test
    fun levelComesFromSettingThenReleaseThenInfo() {
        assertEquals(LogLevel.WARN, JarvisLog.resolveLevel("warn", "debug"))
        assertEquals(LogLevel.DEBUG, JarvisLog.resolveLevel("default", "debug"))
        assertEquals(LogLevel.DEBUG, JarvisLog.resolveLevel(null, "DEBUG"))
        assertEquals(LogLevel.INFO, JarvisLog.resolveLevel(null, null))
        assertEquals(LogLevel.INFO, JarvisLog.resolveLevel("loud", "also loud"))
        assertNull(LogLevel.parse("default"))
    }

    @Test
    fun linesAreOneLineEach() {
        assertEquals("10:00:00.000 W/Mic: a | b", JarvisLog.line("10:00:00.000", LogLevel.WARN, "Mic", "a\nb"))
    }

    @Test
    fun trimDropsOldestWholeLines() {
        val text = (1..100).joinToString("") { "line %03d xxxxxxxx\n".format(it) } // 17 chars and a newline each
        assertEquals(text, JarvisLog.trim(text, max = text.length)) // not over: untouched
        val trimmed = JarvisLog.trim(text, max = 1000, keep = 500)
        assertTrue(trimmed.length <= 500 && trimmed.length > 450, "kept ${trimmed.length}")
        assertTrue(trimmed.startsWith("line "), "starts mid-line: ${trimmed.take(20)}")
        assertTrue(trimmed.endsWith("line 100 xxxxxxxx\n"))
        assertEquals(trimmed.lines().filter { it.isNotEmpty() }, trimmed.lines().filter { it.length == 17 }) // every kept line whole
    }

    @Test
    fun trimWithoutNewlineKeepsTheEnd() {
        assertEquals("fghij", JarvisLog.trim("abcdefghij", max = 8, keep = 5))
    }
}
