package com.kbhatiya999.jarvis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One Gemini Live API session over a WebSocket: streams mic audio (16 kHz PCM) up,
 * plays Jarvis's voice (24 kHz PCM) back, and reports transcripts of both sides.
 * Port of the Python AudioLoop (audio only, no camera/screen).
 */
class LiveSession(
    private val apiKey: String,
    private val systemPrompt: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onUserText(delta: String)
        fun onJarvisText(delta: String)
        fun onTurnComplete()
        fun onInterrupted()
        fun onClosed(reason: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    private val pending = mutableListOf<String>()
    private val player = Player()
    @Volatile private var recording = false
    private var micThread: Thread? = null

    fun connect() {
        val request = Request.Builder()
            .url(URL)
            .header("x-goog-api-key", apiKey)
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(if (code == 1000) null else "Closed ($code) ${reason.ifBlank { "" }}".trim())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName
                finish("Connection failed: $detail")
            }
        })
    }

    fun sendText(text: String) {
        val msg = JSONObject().put(
            "clientContent", JSONObject()
                .put("turns", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("turnComplete", true)
        ).toString()
        synchronized(pending) { if (ready) ws?.send(msg) else pending.add(msg) }
    }

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling this
    fun startMic() {
        if (recording) return
        recording = true
        micThread = Thread {
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
                while (recording && !closed) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0 && ready) {
                        val data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                        ws?.send(
                            JSONObject().put(
                                "realtimeInput", JSONObject().put(
                                    "audio", JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=$IN_RATE")
                                )
                            ).toString()
                        )
                    }
                }
            } finally {
                try { rec.stop() } catch (e: IllegalStateException) { }
                rec.release()
            }
        }.apply { name = "jarvis-mic"; start() }
    }

    fun stopMic() {
        recording = false
        if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
    }

    fun close() {
        recording = false
        ws?.close(1000, "bye")
        finish(null)
    }

    private fun setupMessage(): JSONObject {
        val generation = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))
                )
            )
        return JSONObject().put(
            "setup", JSONObject()
                .put("model", MODEL)
                .put("generationConfig", generation)
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "contextWindowCompression", JSONObject()
                        .put("triggerTokens", 104857)
                        .put("slidingWindow", JSONObject().put("targetTokens", 52428))
                )
        )
    }

    private fun handle(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }

        if (msg.has("setupComplete")) {
            synchronized(pending) {
                ready = true
                pending.forEach { ws?.send(it) }
                pending.clear()
            }
            main.post { listener.onReady() }
            return
        }

        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let {
                    player.play(Base64.decode(it, Base64.DEFAULT))
                }
            }
        }
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            main.post { listener.onUserText(it) }
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            main.post { listener.onJarvisText(it) }
        }
        if (content.optBoolean("interrupted")) {
            // The user spoke over Jarvis: drop audio that is queued but not yet played.
            player.flush()
            main.post { listener.onInterrupted() }
        }
        if (content.optBoolean("turnComplete")) main.post { listener.onTurnComplete() }
    }

    private fun finish(reason: String?) {
        if (closed) return
        closed = true
        ready = false
        recording = false
        player.release()
        client.dispatcher.executorService.shutdown()
        main.post { listener.onClosed(reason) }
    }

    /** Streams PCM chunks to the speaker on its own thread. */
    private class Player {
        private val queue = LinkedBlockingQueue<ByteArray>()
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
            if (running) queue.offer(pcm)
        }

        @Synchronized
        fun flush() {
            if (!running) return
            queue.clear()
            track.pause()
            track.flush()
            track.play()
        }

        @Synchronized
        fun release() {
            running = false
            queue.clear()
        }
    }

    companion object {
        const val MODEL = "models/gemini-3.8-live"
        const val VOICE = "Charon"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
        private const val CHUNK = 2048 // 1024 samples of 16-bit audio, like the Python script
    }
}
