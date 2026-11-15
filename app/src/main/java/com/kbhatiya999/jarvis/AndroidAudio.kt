package com.kbhatiya999.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.kbhatiya999.jarvis.core.AudioIO
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Mic (via [MicHub]: 16 kHz, echo-cancelled) and speaker (24 kHz) for one voice session. */
class AndroidAudio : AudioIO {
    private val player = Player()
    private var listener: ((ByteArray) -> Unit)? = null

    @Synchronized
    override fun startCapture(onChunk: (ByteArray) -> Unit) {
        if (listener != null) return
        listener = onChunk.also { MicHub.add(it, echoCancel = true) }
    }

    @Synchronized
    override fun stopCapture() {
        listener?.let { MicHub.remove(it) }
        listener = null
    }

    override fun play(pcm: ByteArray) = player.play(pcm)
    override fun flush() = player.flush()
    override fun remainingMs(): Long = player.remainingMs()

    override fun shutdown() {
        stopCapture()
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
        const val OUT_RATE = 24000
    }
}
