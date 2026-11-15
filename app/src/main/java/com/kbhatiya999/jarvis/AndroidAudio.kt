package com.kbhatiya999.jarvis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.kbhatiya999.jarvis.core.AudioIO
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Mic (16 kHz, echo-cancelled) and speaker (24 kHz) for one voice session. */
class AndroidAudio : AudioIO {
    private val player = Player()
    @Volatile private var recording = false

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before a session uses the mic
    override fun startCapture(onChunk: (ByteArray) -> Unit) {
        if (recording) return
        recording = true
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                recording = false
                return@Thread
            }
            // Keep Jarvis's own voice from the speaker out of the mic.
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            val buf = ByteArray(CHUNK)
            try {
                rec.startRecording()
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onChunk(buf.copyOf(n))
                }
            } finally {
                try { rec.stop() } catch (e: IllegalStateException) { }
                rec.release()
            }
        }.apply { name = "jarvis-mic"; start() }
    }

    override fun stopCapture() {
        recording = false
    }

    override fun play(pcm: ByteArray) = player.play(pcm)
    override fun flush() = player.flush()
    override fun remainingMs(): Long = player.remainingMs()

    override fun release() {
        recording = false
        player.release()
    }

    /** Streams PCM chunks to the speaker on its own thread. */
    private class Player {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val enqueuedFrames = AtomicLong(0)
        @Volatile private var running = true
        private val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }

        private val thread = Thread {
            while (running) {
                val chunk = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
                if (chunk != null && running) track.write(chunk, 0, chunk.size)
            }
            // Released here, on the only thread that writes, so a write can't hit a dead track.
            try { track.stop() } catch (e: IllegalStateException) { }
            track.release()
        }.apply { name = "jarvis-speaker"; start() }

        fun play(pcm: ByteArray) {
            if (!running) return
            enqueuedFrames.addAndGet(pcm.size / 2L)
            queue.offer(pcm)
        }

        @Synchronized
        fun remainingMs(): Long {
            if (!running) return 0
            val played = track.playbackHeadPosition.toLong() and 0xffffffffL
            return maxOf(0L, (enqueuedFrames.get() - played) * 1000 / OUT_RATE)
        }

        @Synchronized
        fun flush() {
            if (!running) return
            queue.clear()
            track.pause()
            track.flush()
            enqueuedFrames.set(0)
            track.play()
        }

        @Synchronized
        fun release() {
            running = false
            queue.clear()
        }
    }

    private companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        const val CHUNK = 2048 // 1024 samples of 16-bit audio
    }
}
