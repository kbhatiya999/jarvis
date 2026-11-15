package com.kbhatiya999.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(
    val role: String,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val id: Long = 0,
) {
    val fromUser get() = role == USER
    val isRelay get() = role == SENT || role == RECEIVED

    companion object {
        const val USER = "user"
        const val JARVIS = "jarvis"
        /** A message this Jarvis sent to someone else's Jarvis. */
        const val SENT = "sent"
        /** A message that arrived from someone else's Jarvis. */
        const val RECEIVED = "received"
    }
}

/** The whole conversation, saved on the device so Jarvis remembers it across sessions. */
class ChatHistory(context: Context) {
    private val file = File(context.filesDir, "conversation.json")

    fun load(): List<Message> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Message(o.getString("role"), o.getString("text"), o.optLong("time"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(messages: List<Message>) {
        val arr = JSONArray()
        messages.filter { it.text.isNotBlank() }.forEach {
            arr.put(JSONObject().put("role", it.role).put("text", it.text).put("time", it.time))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }

    companion object {
        /** Most recent part of the conversation, as text for the system prompt. */
        fun memoryText(messages: List<Message>, maxChars: Int = 16_000): String {
            val lines = ArrayDeque<String>()
            var size = 0
            for (m in messages.asReversed()) {
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
    }
}
