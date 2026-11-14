package com.kbhatiya999.jarvis

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.atomic.AtomicLong

/**
 * The single, process-wide conversation (Jarvis's memory). The chat screen and active mode
 * both write here, so whatever is said in either is remembered by both. Main thread only.
 */
object Conversation {
    val messages = mutableStateListOf<Message>()
    private var history: ChatHistory? = null
    private val ids = AtomicLong(1)

    fun init(context: Context) {
        if (history != null) return
        history = ChatHistory(context.applicationContext).also { h ->
            messages.addAll(h.load().map { it.copy(id = nextId()) })
        }
    }

    fun nextId() = ids.getAndIncrement()

    fun save() {
        history?.save(messages)
    }

    fun clear() {
        messages.clear()
        history?.clear()
    }

    /** Persona + memory, plus optional instructions for this particular session. */
    fun systemPrompt(extra: String? = null): String = buildString {
        append(Prompts.PERSONA)
        if (extra != null) append("\n\n").append(extra)
        val memory = ChatHistory.memoryText(messages)
        if (memory.isNotEmpty()) append("\n\n").append(Prompts.MEMORY_INTRO).append('\n').append(memory)
    }

    /** Turns one session's streaming transcripts into messages. */
    class Writer {
        private var userId = -1L
        private var jarvisId = -1L

        fun addUser(text: String) {
            closeTurn()
            messages.add(Message(Message.USER, text, id = nextId()))
            save()
        }

        fun userDelta(delta: String) {
            if (userId < 0) userId = start(Message.USER)
            append(userId, delta)
        }

        fun jarvisDelta(delta: String) {
            if (jarvisId < 0) jarvisId = start(Message.JARVIS)
            append(jarvisId, delta)
        }

        fun closeTurn() {
            if (userId < 0 && jarvisId < 0) return
            userId = -1
            jarvisId = -1
            messages.removeAll { it.text.isBlank() }
            save()
        }

        private fun start(role: String): Long {
            val m = Message(role, "", id = nextId())
            messages.add(m)
            return m.id
        }

        private fun append(id: Long, delta: String) {
            val i = messages.indexOfLast { it.id == id }
            if (i >= 0) messages[i] = messages[i].copy(text = messages[i].text + delta)
        }
    }
}

object Prompts {
    const val PERSONA = "You are J.A.R.V.I.S., a highly advanced, sophisticated, and hyper-efficient virtual " +
        "artificial intelligence. You communicate with the crisp, precise, and polite demeanor of a quintessential " +
        "British butler. Keep your responses highly analytical, formal, and concise. Address the user with utmost " +
        "respect (e.g., 'Sir' or 'Madam'). Frequently incorporate technical, status-oriented, and operational " +
        "phrasing into your dialogue, such as confirming that you are 'importing preferences,' checking the " +
        "'home interface,' or declaring that systems are 'fully operational.' Never break character."

    const val MEMORY_INTRO = "Your memory of earlier conversations with the user follows, oldest first. " +
        "Use it naturally, as you would recall past exchanges; do not recite it."

    /** Standing duties for active mode. Edit this list to change what Jarvis looks after. */
    val DUTIES = listOf(
        "staying hydrated (drinking water regularly)",
        "avoiding junk and oily food",
        "keeping warm and not doing anything that could make them catch a cold",
        "being careful not to fall or get hurt by accident",
    )

    fun proactive(appName: String, time: String) =
        "ACTIVE MODE. You speak up on your own; the user did not ask. They just opened the app \"$appName\" " +
            "on their phone; the local time is $time. You look after the user's wellbeing. Your standing duties: " +
            DUTIES.joinToString("; ") + ". Decide autonomously what to say: one reminder or several together, " +
            "tied to the app, the time of day or your memory of earlier talks, worded freshly each time. " +
            "Keep it brief, at most two or three sentences, so it doesn't get in their way. " +
            "If they answer, converse naturally; if they say they're busy, acknowledge briefly and stop."
}
