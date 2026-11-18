package com.kbhatiya999.jarvis.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Carries encrypted messages between Jarvises through ntfy.sh, a free public pub/sub relay
 * (no account). Each Jarvis has a random inbox topic; the relay only ever sees ciphertext.
 * It keeps messages for about 12 hours. Messages arrive by a live stream while an app holds
 * one open ([acquireStream]), or by [poll] (on app open, and in the background).
 */
class Relay internal constructor(private val jarvis: Jarvis) {
    private val http = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    /** Encrypts [text] for [contact] and posts it. Returns an error message, or null. */
    suspend fun send(contact: Contact, text: String): String? = try {
        val to = Address.parse(contact.address) ?: error("bad address")
        val payload = JsonObject(mapOf(
            "name" to JsonPrimitive(jarvis.contacts.myName.value.ifBlank { "A friend" }),
            "text" to JsonPrimitive(text.take(MAX_TEXT)),
            "t" to JsonPrimitive(jarvis.platform.nowMillis()),
        ))
        val body = jarvis.identity.encryptFor(to, payload.toString()).toString()
        val resp = http.post("$SERVER/${to.topic}") {
            contentType(ContentType.Text.Plain)
            setBody(body)
        }
        if (resp.status.isSuccess()) null else "relay returned HTTP ${resp.status.value}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        JarvisLog.w("Relay", "send failed", e)
        e.message ?: e.toString()
    }

    /** Fetches anything waiting and delivers it. Returns false if the relay couldn't be reached. */
    suspend fun poll(): Boolean = try {
        val resp = http.get("$SERVER/${jarvis.identity.topic}/json?poll=1&since=${jarvis.inbox.since()}")
        if (!resp.status.isSuccess()) {
            false
        } else {
            val lines = resp.bodyAsText().lines()
            withContext(Dispatchers.Main) { lines.forEach { handleLine(it) } }
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        JarvisLog.w("Relay", "poll failed", e)
        false
    }

    /** Polls without blocking the caller. */
    fun pollAsync() {
        jarvis.scope.launch { poll() }
    }

    // Live stream, shared by whoever needs it (the open app, active mode). Main thread only.
    private var streamUsers = 0
    private var streamJob: Job? = null

    fun acquireStream() {
        streamUsers++
        if (streamJob?.isActive == true) return
        streamJob = jarvis.scope.launch { streamLoop() }
    }

    fun releaseStream() {
        streamUsers = maxOf(0, streamUsers - 1)
        if (streamUsers == 0) {
            streamJob?.cancel()
            streamJob = null
        }
    }

    private suspend fun streamLoop() {
        var backoff = 2_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                http.prepareGet("$SERVER/${jarvis.identity.topic}/json?since=${jarvis.inbox.since()}") {
                    timeout {
                        requestTimeoutMillis = Long.MAX_VALUE
                        socketTimeoutMillis = 90_000 // ntfy sends keepalives every 45 s
                    }
                }.execute { resp ->
                    if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
                    backoff = 2_000L
                    val channel = resp.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        handleLine(line)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JarvisLog.w("Relay", "stream dropped", e)
            }
            delay(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun handleLine(line: String) {
        if (line.isBlank()) return
        val event = parseJson(line) as? JsonObject ?: return
        if (event.str("event") != "message") return
        jarvis.inbox.deliver(event.str("id"), event.str("message"))
    }

    companion object {
        const val SERVER = "https://ntfy.sh"
        const val MAX_TEXT = 2000
    }
}

/** Incoming messages: decrypt, check the sender, remember, notify, and hand to Jarvis to say. Main thread only. */
class Inbox internal constructor(private val jarvis: Jarvis) {
    private val store = jarvis.platform.store("jarvis_inbox")

    /** Relay position to resume from. */
    fun since(): String = store.get("last_id") ?: "12h"

    fun deliver(id: String, body: String) {
        if (id.isEmpty() || !markSeen(id)) return
        val envelope = parseJson(body) as? JsonObject ?: return
        val (from, plain) = jarvis.identity.decrypt(envelope) ?: return
        val payload = parseJson(plain) as? JsonObject ?: return
        val text = payload.str("text").trim().take(Relay.MAX_TEXT)
        val age = jarvis.platform.nowMillis() - payload.long("t")
        if (text.isEmpty() || age > MAX_AGE_MS || age < -MAX_AGE_MS) return
        val claimedName = payload.str("name").trim().take(40).ifEmpty { "Someone" }

        val contact = jarvis.contacts.byAddress(from.raw)
        if (contact == null) {
            jarvis.contacts.addRequest(ContactRequest(claimedName, from.raw, text, jarvis.platform.nowMillis()))
            jarvis.platform.notify("$claimedName's Jarvis wants to connect", "Open Jarvis › Contacts to accept or ignore.")
            return
        }
        if (jarvis.bridge.isBridge(contact.address)) {
            JarvisLog.i("Bridge", "message from Gemini (${text.length} characters)")
            JarvisLog.said("Bridge", "gemini", text)
            receive(contact.name, text, fromBridge = true)
        } else {
            receive(contact.name, text)
        }
    }

    /** A message from a known contact (or a request you just accepted). */
    fun receive(name: String, text: String, fromBridge: Boolean = false) {
        jarvis.conversation.addReceived(name, text, if (fromBridge) "the Gemini app" else "$name's Jarvis")
        // Gemini (through the bridge) isn't someone's Jarvis and can't receive a reply.
        val line = if (fromBridge) "$name, the Gemini app (reports back, can't be replied to): $text" else "$name's Jarvis: $text"
        store.set("pending", stringListJson((stringList(store.get("pending")) + line).takeLast(10)))
        jarvis.platform.notify(if (fromBridge) "From Gemini" else "Message from $name's Jarvis", text)
        jarvis.onMessageArrived?.invoke()
    }

    /** Messages Jarvis hasn't said out loud yet; taking them marks them as said. */
    fun takePending(): List<String> {
        val list = stringList(store.get("pending"))
        store.set("pending", null)
        return list
    }

    /** False if this relay message was already handled. */
    private fun markSeen(id: String): Boolean {
        val seen = stringList(store.get("seen"))
        if (id in seen) return false
        store.set("seen", stringListJson((seen + id).takeLast(200)))
        store.set("last_id", id)
        return true
    }

    private companion object {
        const val MAX_AGE_MS = 2 * 24 * 3600 * 1000L
    }
}
