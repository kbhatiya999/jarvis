package com.kbhatiya999.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.kbhatiya999.jarvis.core.JarvisLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one mic recording in the app (16 kHz mono 16-bit PCM), shared by everyone who needs it: a voice session
 * and the "Jarvis ..." phrase listener hear the same audio at the same time. Recording runs while anyone listens.
 *
 * Two modes, to save battery: while only the phrase listener listens (idle), it records plainly in 320 ms reads
 * (the CPU wakes ~3 times a second). While a session listens (Jarvis may be speaking), it switches to echo
 * cancellation and noise suppression in 64 ms reads, so Jarvis doesn't hear itself and replies stay quick.
 */
object MicHub {
    const val RATE = 16000
    private const val SESSION_CHUNK = 2048 // 1024 samples, 64 ms
    private const val IDLE_CHUNK = 10240 // 5120 samples, 320 ms

    private class Listener(val onChunk: (ByteArray) -> Unit, val echoCancel: Boolean)

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var thread: Thread? = null

    /** [echoCancel]: this listener needs Jarvis's own voice taken out (a conversation). */
    @Synchronized
    fun add(listener: (ByteArray) -> Unit, echoCancel: Boolean) {
        listeners.add(Listener(listener, echoCancel))
        if (thread == null) thread = Thread { run() }.apply { name = "jarvis-mic"; start() }
    }

    @Synchronized
    fun remove(listener: (ByteArray) -> Unit) {
        listeners.removeAll { it.onChunk === listener }
    }

    @Synchronized
    private fun stillNeeded(): Boolean {
        if (listeners.isNotEmpty()) return true
        thread = null
        return false
    }

    private fun run() {
        var rec: AudioRecord? = null
        var echoCancel = false
        var buf = ByteArray(0)
        try {
            while (stillNeeded()) {
                val want = listeners.any { it.echoCancel }
                if (rec == null || want != echoCancel) {
                    rec?.let { close(it) }
                    rec = open(want)
                    if (rec == null) {
                        synchronized(this) { thread = null; listeners.clear() }
                        return
                    }
                    echoCancel = want
                    buf = ByteArray(if (want) SESSION_CHUNK else IDLE_CHUNK)
                }
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    for (l in listeners) l.onChunk(chunk)
                }
            }
        } finally {
            rec?.let { close(it) }
        }
    }

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before anything listens
    private fun open(echoCancel: Boolean): AudioRecord? {
        val chunk = if (echoCancel) SESSION_CHUNK else IDLE_CHUNK
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                // Idle: tuned for speech recognition, no call processing.
                .setAudioSource(if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, chunk * 2))
                // Background listening must not block other apps' recordings (voice notes, video, calls).
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setPrivacySensitive(false) }
                .build()
        } catch (e: Exception) {
            JarvisLog.w("Mic", "Cannot open the mic", e)
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        if (echoCancel) {
            // Keep Jarvis's own voice from the speaker out of the mic.
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        }
        return try {
            rec.startRecording()
            rec
        } catch (e: IllegalStateException) {
            rec.release()
            null
        }
    }

    private fun close(rec: AudioRecord) {
        try { rec.stop() } catch (e: IllegalStateException) { }
        rec.release()
    }

    /** Loudness (RMS of 16-bit little-endian PCM, of 32767). */
    fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val n = pcm.size / 2
        return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
    }
}
