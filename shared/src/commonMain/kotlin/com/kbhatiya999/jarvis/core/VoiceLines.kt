package com.kbhatiya999.jarvis.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Records Jarvis saying fixed lines ("Powering down, Sir.") in its own Gemini voice, so the app can play
 * them instantly and offline later. All lines go through one Live session (the free tier limits how many
 * sessions may start per minute). Main thread.
 */
class VoiceLines internal constructor(private val jarvis: Jarvis) {
    /** The voice the clips are recorded in; a clip made with another voice should be recorded again. */
    val voice: String get() = LiveSession.VOICE

    /**
     * Says each of [lines] and hands its audio (24 kHz mono 16-bit PCM) to [onLine]. [onDone] gets null when
     * all were recorded, else why it stopped (no key, quota, network, ...).
     */
    fun record(lines: List<String>, onLine: (line: String, pcm: ByteArray) -> Unit, onDone: (error: String?) -> Unit) {
        if (lines.isEmpty()) return onDone(null)
        if (jarvis.apiKey.value == null) return onDone("No Gemini API key yet.")
        Recorder(lines, onLine, onDone).start()
    }

    private inner class Recorder(
        private val lines: List<String>,
        private val onLine: (String, ByteArray) -> Unit,
        private val onDone: (String?) -> Unit,
    ) : LiveSession.Listener {
        private val sink = MemoryAudio()
        private var index = 0
        private var finished = false
        private var timeout: Job? = null
        private val session = LiveSession(jarvis, jarvis.apiKey.value ?: "", PROMPT, this, audioOverride = sink)

        fun start() {
            session.connect()
            say()
            timeout = jarvis.scope.launch {
                delay(PER_LINE_MS * lines.size + 10_000)
                done("Timed out recording Jarvis's voice.")
            }
        }

        private fun say() {
            sink.take()
            session.sendText("Say exactly: \"${lines[index]}\"")
        }

        private fun done(error: String?) {
            if (finished) return
            finished = true
            timeout?.cancel()
            session.close()
            onDone(error)
        }

        override fun onTurnComplete() {
            if (finished) return
            val pcm = sink.take()
            if (pcm.isEmpty()) return done("Gemini sent no audio for \"${lines[index]}\".")
            onLine(lines[index], pcm)
            index++
            if (index < lines.size) say() else done(null)
        }

        override fun onClosed(reason: String?) = done(reason ?: "Connection closed early.")
        override fun onReady() {}
        override fun onUserText(delta: String) {}
        override fun onJarvisText(delta: String) {}
        override fun onInterrupted() {}
        override fun onEndRequested() {}
    }

    /** Collects what would have been played. */
    private class MemoryAudio : AudioIO {
        private var chunks = mutableListOf<ByteArray>()
        override fun startCapture(onChunk: (ByteArray) -> Unit) {}
        override fun stopCapture() {}
        override fun play(pcm: ByteArray) { chunks.add(pcm) }
        override fun flush() { chunks = mutableListOf() }
        override fun remainingMs() = 0L
        override fun shutdown() {}

        fun take(): ByteArray {
            val out = ByteArray(chunks.sumOf { it.size })
            var at = 0
            for (c in chunks) { c.copyInto(out, at); at += c.size }
            chunks = mutableListOf()
            return out
        }
    }

    private companion object {
        const val PER_LINE_MS = 15_000L
        const val PROMPT = "You are the voice of J.A.R.V.I.S., a calm, crisp, polite British butler AI. You are " +
            "recording short voice lines. Each time you are given a line, say exactly that line, word for word, " +
            "in character, with nothing before or after it. Never call any function."
    }
}
