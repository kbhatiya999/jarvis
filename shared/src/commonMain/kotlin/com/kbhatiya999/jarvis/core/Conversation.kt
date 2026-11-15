package com.kbhatiya999.jarvis.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * The single conversation (Jarvis's memory), saved to conversation.json and fed back into
 * every session. The chat screen and active mode both write here. Main thread only.
 */
class Conversation internal constructor(private val platform: Platform) {
    val messages = Watchable<List<Message>>(emptyList())
    private var nextId = 1L

    init {
        val loaded = try {
            platform.readFile(FILE)?.let { text ->
                parseJson(text)!!.jsonArray.map { e ->
                    val o = e as JsonObject
                    Message(o.str("role"), o.str("text"), o.long("time"), nextId++)
                }
            }
        } catch (e: Exception) {
            null
        }
        messages.value = loaded ?: emptyList()
    }

    fun clear() {
        messages.value = emptyList()
        platform.deleteFile(FILE)
    }

    fun addSent(name: String, text: String) = add(Message.SENT, "To $name's Jarvis: $text")
    fun addReceived(name: String, text: String) = add(Message.RECEIVED, "From $name's Jarvis: $text")

    internal fun add(role: String, text: String): Long {
        val m = Message(role, text, platform.nowMillis(), nextId++)
        messages.value = messages.value + m
        save()
        return m.id
    }

    internal fun append(id: Long, delta: String) {
        messages.value = messages.value.map { if (it.id == id) it.copy(text = it.text + delta) else it }
    }

    internal fun dropBlank() {
        if (messages.value.any { it.text.isBlank() }) messages.value = messages.value.filter { it.text.isNotBlank() }
    }

    fun save() {
        val arr = JsonArray(messages.value.filter { it.text.isNotBlank() }.map {
            JsonObject(mapOf("role" to JsonPrimitive(it.role), "text" to JsonPrimitive(it.text), "time" to JsonPrimitive(it.time)))
        })
        platform.writeFile(FILE, arr.toString())
    }

    /** Most recent part of the conversation, as text for the system prompt. */
    fun memoryText(maxChars: Int = 16_000): String {
        val lines = ArrayDeque<String>()
        var size = 0
        for (m in messages.value.asReversed()) {
            if (m.text.isBlank()) continue
            val label = when (m.role) {
                Message.USER -> "User: "
                Message.SENT -> "[You sent a message] "
                Message.RECEIVED -> "[Message received] "
                else -> "Jarvis: "
            }
            val line = label + m.text.trim()
            if (size + line.length > maxChars) break
            lines.addFirst(line)
            size += line.length + 1
        }
        return lines.joinToString("\n")
    }

    fun createWriter() = Writer()

    /** Turns one session's streaming transcripts into messages. */
    inner class Writer {
        private var userId = -1L
        private var jarvisId = -1L

        fun addUser(text: String) {
            closeTurn()
            add(Message.USER, text)
        }

        fun userDelta(delta: String) {
            if (userId < 0) userId = add(Message.USER, "")
            append(userId, delta)
        }

        fun jarvisDelta(delta: String) {
            if (jarvisId < 0) jarvisId = add(Message.JARVIS, "")
            append(jarvisId, delta)
        }

        fun closeTurn() {
            if (userId < 0 && jarvisId < 0) return
            userId = -1
            jarvisId = -1
            dropBlank()
            save()
        }
    }

    private companion object {
        const val FILE = "conversation.json"
    }
}
