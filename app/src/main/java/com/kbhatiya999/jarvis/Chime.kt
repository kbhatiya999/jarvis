package com.kbhatiya999.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short tones that tell you Jarvis's state without words: rising when it wakes, two even beeps when it was
 * already awake, falling (and softer) when it stops listening. Made in code, so nothing to record or ship.
 */
object Chime {
    enum class Kind(val notes: List<Double>, val noteMs: Int, val volume: Float) {
        WAKE(listOf(523.25, 783.99, 1046.5), 110, 0.55f), // C5 G5 C6: "powering up"
        ALREADY(listOf(880.0, 880.0), 90, 0.5f), // A5 A5: "I'm here"
        SLEEP(listOf(783.99, 523.25), 130, 0.3f), // G5 C5: "going quiet"
    }

    private const val RATE = 24000
    private const val GAP_MS = 35
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null

    /** Plays [kind]; [done] runs on the main thread when it has finished. */
    fun play(kind: Kind, done: () -> Unit = {}) {
        stop()
        val pcm = tones(kind)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        } catch (e: Exception) {
            done()
            return
        }
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        val ms = pcm.size * 1000L / RATE
        main.postDelayed({
            if (track === t) stop()
            done()
        }, ms + 60)
    }

    fun stop() {
        track?.let { try { it.stop() } catch (e: IllegalStateException) { }; it.release() }
        track = null
    }

    private fun tones(kind: Kind): ShortArray {
        val noteLen = RATE * kind.noteMs / 1000
        val gapLen = RATE * GAP_MS / 1000
        val out = ShortArray(kind.notes.size * (noteLen + gapLen))
        var pos = 0
        for (freq in kind.notes) {
            val fade = min(noteLen / 4, RATE * 12 / 1000) // soft edges, no clicks
            for (i in 0 until noteLen) {
                val env = when {
                    i < fade -> i.toFloat() / fade
                    i > noteLen - fade * 3 -> (noteLen - i).toFloat() / (fade * 3)
                    else -> 1f
                }
                // A touch of the octave makes it bell-like rather than a test tone.
                val x = 2 * PI * freq * i / RATE
                val v = (sin(x) * 0.8 + sin(2 * x) * 0.2) * env * kind.volume
                out[pos + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            pos += noteLen + gapLen
        }
        return out
    }
}
