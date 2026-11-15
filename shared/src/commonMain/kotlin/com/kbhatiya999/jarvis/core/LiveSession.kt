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
class LiveSession internal constructor(
    private val jarvis: Jarvis,
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

    private val audio = jarvis.platform.createAudio()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = mutableListOf<String>() // sent once setup completes
    private var ready = false
    private var closed = false
    private var recording = false
    private var job: Job? = null

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
                    if (close != null && close.code != 1000.toShort()) reason = "Closed (${close.code}) ${close.message}".trim()
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
            if (ready) queue.trySend(audioMessage(chunk))
        }
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
                jarvis.tools.call(name, call.obj("args") ?: JsonObject(emptyMap())) { result -> sendToolResponse(id, name, result) }
            }
            return
        }

        val content = msg.obj("serverContent") ?: return
        content.obj("modelTurn")?.arr("parts")?.forEach { p ->
            (p as? JsonObject)?.obj("inlineData")?.str("data")?.takeIf { it.isNotEmpty() }?.let { audio.play(B64.unstd(it)) }
        }
        content.obj("inputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }?.let { listener.onUserText(it) }
        content.obj("outputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }?.let { listener.onJarvisText(it) }
        if (content.bool("interrupted")) {
            // The user spoke over Jarvis: drop audio that is queued but not yet played.
            audio.flush()
            listener.onInterrupted()
        }
        if (content.bool("turnComplete")) listener.onTurnComplete()
    }

    private fun sendToolResponse(id: String, name: String, response: JsonObject) {
        val fr = mutableMapOf("name" to JsonPrimitive(name), "response" to response)
        if (id.isNotEmpty()) fr["id"] = JsonPrimitive(id)
        send(JsonObject(mapOf("toolResponse" to JsonObject(mapOf("functionResponses" to JsonArray(listOf(JsonObject(fr))))))).toString())
    }

    private fun finish(reason: String?) {
        if (closed) return
        closed = true
        ready = false
        recording = false
        queue.close()
        audio.shutdown()
        listener.onClosed(reason)
    }

    companion object {
        const val MODEL = "models/gemini-3.8-live"
        const val VOICE = "Charon"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
