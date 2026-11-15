package com.kbhatiya999.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one mic recording in the app (16 kHz mono 16-bit PCM, echo-cancelled), shared by everyone who needs it:
 * a voice session and the "Jarvis ..." phrase listener hear the same audio at the same time. Recording runs
 * while anyone listens.
 */
object MicHub {
    const val RATE = 16000
    private const val CHUNK = 2048 // 1024 samples, 64 ms

    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private var thread: Thread? = null

    @Synchronized
    fun add(listener: (ByteArray) -> Unit) {
        listeners.add(listener)
        if (thread == null) thread = Thread { run() }.apply { name = "jarvis-mic"; start() }
    }

    @Synchronized
    fun remove(listener: (ByteArray) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun stillNeeded(): Boolean {
        if (listeners.isNotEmpty()) return true
        thread = null
        return false
    }

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before anything listens
    private fun run() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK * 4))
                // Background listening must not block other apps' recordings (voice notes, video, calls).
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setPrivacySensitive(false) }
                .build()
        } catch (e: Exception) {
            Log.w("JarvisMic", "Cannot open the mic", e)
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            synchronized(this) { thread = null; listeners.clear() }
            return
        }
        // Keep Jarvis's own voice from the speaker out of the mic.
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        val buf = ByteArray(CHUNK)
        try {
            rec.startRecording()
            while (stillNeeded()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    for (l in listeners) l(chunk)
                }
            }
        } finally {
            try { rec.stop() } catch (e: IllegalStateException) { }
            rec.release()
        }
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
