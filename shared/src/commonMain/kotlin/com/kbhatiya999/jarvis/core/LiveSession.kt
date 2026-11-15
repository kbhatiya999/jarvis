package com.kbhatiya999.jarvis.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One Gemini Live API session over a WebSocket: streams mic audio (16 kHz PCM) up, plays
 * Jarvis's voice (24 kHz PCM) back, reports transcripts of both sides and runs tool calls.
 * Create with [Jarvis.createSession]. Listener callbacks arrive on the main thread.
 */
/** Mic loudness (RMS, of 32767) that counts as speech; above room noise after echo cancellation. */
private const val VOICE_RMS = 700.0

class LiveSession internal constructor(
    private val jarvis: Jarvis,
    private val apiKey: String,
    private val systemPrompt: String,
    private val listener: Listener,
    private val speakOnly: Boolean = false,
    /** False: while Jarvis's voice plays (and briefly after), the mic sends silence, so nothing cuts it off. */
    private val interruptible: Boolean = true,
    /** Instead of the device's mic and speaker (e.g. recording Jarvis's voice to a file). */
    audioOverride: AudioIO? = null,
) {
    interface Listener {
        fun onReady()
        fun onUserText(delta: String)
        fun onJarvisText(delta: String)
        fun onTurnComplete()
        fun onInterrupted()
        fun onClosed(reason: String?)

        /** The user wants to stop ("stop", "that's enough", ...): Jarvis says a short goodbye; end once it has played. */
        fun onEndRequested()
    }

    private val audio = audioOverride ?: jarvis.platform.createAudio(speakOnly)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = mutableListOf<String>() // sent once setup completes
    private var ready = false
    private var closed = false
    private var recording = false
    private var job: Job? = null
    // Mic audio from before setup completes (e.g. the user starts talking right after "Yes, Sir?"), sent once ready.
    private val early = Channel<ByteArray>(EARLY_CHUNKS, BufferOverflow.DROP_OLDEST)
    // After interrupt(): the rest of the reply Jarvis was giving is dropped, not played.
    private var dropReply = false
    // Gemini is in the middle of sending a reply (audio still arriving).
    private var replying = false

    fun connect() {
        job = jarvis.scope.launch {
            val client = HttpClient { install(WebSockets) { pingIntervalMillis = 20_000 } }
            var reason: String? = null
            try {
                client.webSocket(urlString = URL, request = { header("x-goog-api-key", apiKey) }) {
                    val sender = launch { for (m in queue) send(Frame.Text(m)) }
                    send(Frame.Text(setupMessage().toString()))
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> handle(frame.readText())
                            is Frame.Binary -> handle(frame.readBytes().decodeToString())
                            else -> {}
                        }
                    }
                    val close = closeReason.await()
                    if (close != null && close.code != 1000.toShort()) reason = closeReason(close.code.toInt(), close.message)
                    sender.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = "Connection failed: ${e.message ?: e.toString()}"
            } finally {
                client.close()
                finish(reason)
            }
        }
    }

    fun sendText(text: String) {
        send(JsonObject(mapOf("clientContent" to JsonObject(mapOf(
            "turns" to JsonArray(listOf(JsonObject(mapOf(
                "role" to JsonPrimitive("user"),
                "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(text))))),
            )))),
            "turnComplete" to JsonPrimitive(true),
        )))).toString())
    }

    fun startMic() {
        if (recording || closed) return
        recording = true
        audio.startCapture { chunk ->
            // Audio thread: the channel is thread-safe. Chunks before setup completes are dropped.
            val held = holdMic()
            if (!held && isVoice(chunk)) lastVoiceAt = jarvis.platform.nowMillis()
            val pcm = if (held) ByteArray(chunk.size) else chunk
            if (ready) queue.trySend(audioMessage(pcm)) else early.trySend(pcm)
        }
    }

    /**
     * Cut Jarvis off now (the user said its name over it): stop its voice, drop the rest of that reply and
     * let the mic through, so Gemini hears what the user says next. Main thread.
     */
    fun interrupt() {
        if (closed) return
        dropReply = replying
        quietUntil = 0
        audio.flush()
    }

    @kotlin.concurrent.Volatile private var quietUntil = 0L

    /**
     * When the mic last picked up something loud enough to be speech (platform clock, ms; 0 = never).
     * Reacts at once, unlike the transcript, which lags speech by up to a second.
     */
    @kotlin.concurrent.Volatile var lastVoiceAt = 0L
        private set

    /** Loudness (RMS of 16-bit little-endian PCM) above which a chunk counts as someone talking. */
    private fun isVoice(pcm: ByteArray): Boolean {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            n++
            i += 2
        }
        return n > 0 && kotlin.math.sqrt(sum / n) >= VOICE_RMS
    }

    /** Mic thread: true while Jarvis is speaking (plus 300 ms of echo) and it mustn't be interrupted. */
    private fun holdMic(): Boolean {
        if (interruptible) return false
        val now = jarvis.platform.nowMillis()
        if (audio.remainingMs() > 0) quietUntil = now + 300
        return now < quietUntil
    }

    fun stopMic() {
        if (!recording) return
        recording = false
        audio.stopCapture()
        if (ready) queue.trySend("""{"realtimeInput":{"audioStreamEnd":true}}""")
    }

    /** How much of Jarvis's reply is still to be heard, in milliseconds. */
    fun playbackRemainingMs(): Long = audio.remainingMs()

    fun close() {
        job?.cancel()
        finish(null)
    }

    private fun send(message: String) {
        if (closed) return
        if (ready) queue.trySend(message) else pending.add(message)
    }

    private fun drainEarly() {
        while (true) {
            val pcm = early.tryReceive().getOrNull() ?: break
            queue.trySend(audioMessage(pcm))
        }
    }

    private fun audioMessage(pcm: ByteArray) =
        """{"realtimeInput":{"audio":{"data":"${B64.std(pcm)}","mimeType":"audio/pcm;rate=16000"}}}"""

    private fun setupMessage(): JsonObject {
        val setup = mutableMapOf(
            "model" to JsonPrimitive(MODEL),
            "generationConfig" to JsonObject(mapOf(
                "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
                "speechConfig" to JsonObject(mapOf("voiceConfig" to JsonObject(mapOf(
                    "prebuiltVoiceConfig" to JsonObject(mapOf("voiceName" to JsonPrimitive(VOICE))),
                )))),
            )),
            "systemInstruction" to JsonObject(mapOf("parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(systemPrompt))))))),
            "tools" to jarvis.tools.declarations,
            "inputAudioTranscription" to JsonObject(emptyMap()),
            "outputAudioTranscription" to JsonObject(emptyMap()),
            "contextWindowCompression" to JsonObject(mapOf(
                "triggerTokens" to JsonPrimitive(104857),
                "slidingWindow" to JsonObject(mapOf("targetTokens" to JsonPrimitive(52428))),
            )),
        )
        return JsonObject(mapOf("setup" to JsonObject(setup)))
    }

    // Runs on the main thread (the session coroutine runs on Jarvis.scope).
    private fun handle(raw: String) {
        val msg = parseJson(raw) as? JsonObject ?: return

        if (msg.containsKey("setupComplete")) {
            ready = true
            drainEarly()
            pending.forEach { queue.trySend(it) }
            pending.clear()
            listener.onReady()
            return
        }

        msg.obj("toolCall")?.arr("functionCalls")?.let { calls ->
            calls.forEach { c ->
                val call = c as? JsonObject ?: return@forEach
                val id = call.str("id")
                val name = call.str("name")
                if (name == Tools.END_CONVERSATION) {
                    sendToolResponse(id, name, JsonObject(mapOf("result" to JsonPrimitive("Ending after your goodbye."))))
                    listener.onEndRequested()
                    return@forEach
                }
                jarvis.tools.call(name, call.obj("args") ?: JsonObject(emptyMap())) { result -> sendToolResponse(id, name, result) }
            }
            return
        }

        val content = msg.obj("serverContent") ?: return
        content.obj("modelTurn")?.let { replying = true }
        content.obj("modelTurn")?.arr("parts")?.forEach { p ->
            if (dropReply) return@forEach
            (p as? JsonObject)?.obj("inlineData")?.str("data")?.takeIf { it.isNotEmpty() }?.let { audio.play(B64.unstd(it)) }
        }
        content.obj("inputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }?.let { listener.onUserText(it) }
        content.obj("outputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }?.let { listener.onJarvisText(it) }
        if (content.bool("interrupted")) {
            // The user spoke over Jarvis: drop audio that is queued but not yet played.
            audio.flush()
            dropReply = false
            replying = false
            listener.onInterrupted()
        }
        if (content.bool("turnComplete")) {
            dropReply = false
            replying = false
            listener.onTurnComplete()
        }
    }

    private fun sendToolResponse(id: String, name: String, response: JsonObject) {
        val fr = mutableMapOf("name" to JsonPrimitive(name), "response" to response)
        if (id.isNotEmpty()) fr["id"] = JsonPrimitive(id)
        send(JsonObject(mapOf("toolResponse" to JsonObject(mapOf("functionResponses" to JsonArray(listOf(JsonObject(fr))))))).toString())
    }

    private fun closeReason(code: Int, message: String) = when {
        // Free tier: too many sessions or tokens in a short time. It passes after a minute or so.
        code == 1011 && "exhausted" in message -> "Gemini's free-tier limit was reached. Try again in a minute."
        else -> "Closed ($code) $message".trim()
    }

    private fun finish(reason: String?) {
        if (closed) return
        closed = true
        ready = false
        recording = false
        queue.close()
        early.close()
        audio.shutdown()
        listener.onClosed(reason)
    }

    companion object {
        const val MODEL = "models/gemini-3.8-live"
        const val VOICE = "Charon"
        private const val EARLY_CHUNKS = 160 // about 10 s of 64 ms chunks
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
