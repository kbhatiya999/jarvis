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
        Reminders.init(context)
        Contacts.init(context)
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

    fun addSent(name: String, text: String) = addNote(Message.SENT, "To $name's Jarvis: $text")

    fun addReceived(name: String, text: String) = addNote(Message.RECEIVED, "From $name's Jarvis: $text")

    private fun addNote(role: String, text: String) {
        messages.add(Message(role, text, id = nextId()))
        save()
    }

    /** Persona, the user's reminders and contacts, memory, plus optional instructions for this session. */
    fun systemPrompt(extra: String? = null): String = buildString {
        append(Prompts.PERSONA)
        append("\n\n").append(Prompts.context())
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

    /** What Jarvis knows about the user's setup: their reminders, contacts and how to use the tools. */
    fun context(): String = buildString {
        if (Contacts.myName.isNotBlank()) append("The user's name is ${Contacts.myName}. ")
        append("The user's standing reminders, which you look after: ")
        append(if (Reminders.items.isEmpty()) "none yet" else Reminders.items.joinToString("; "))
        append(". When the user asks you to remind them of something regularly, or to stop reminding them, ")
        append("use add_reminder or remove_reminder.\n")
        append("Other people's Jarvises you can message: ")
        append(if (Contacts.contacts.isEmpty()) "none yet (they can be added under Settings › Contacts)" else Contacts.contacts.joinToString(", ") { it.name })
        append(". When the user asks you to tell someone, or someone's Jarvis, something, call send_message with the ")
        append("contact's name and the message written as you would say it to that person (in your own voice, ")
        append("mentioning it comes from the user), then confirm briefly. Messages to and from other Jarvises appear ")
        append("in your memory marked [You sent a message] and [Message received].")
    }

    fun proactive(appName: String, time: String, pending: List<String>) = buildString {
        append("ACTIVE MODE. You speak up on your own; the user did not ask. They just opened the app \"$appName\" ")
        append("on their phone; the local time is $time. You look after the user's wellbeing using their standing ")
        append("reminders listed above. ")
        if (pending.isNotEmpty()) {
            append("First, relay these messages that arrived from other Jarvises, naming who sent each: ")
            append(pending.joinToString(" | ")).append(". Then ")
        }
        append("Decide autonomously what to say: one reminder or several together, tied to the app, the time of ")
        append("day or your memory of earlier talks, worded freshly each time. If there are no reminders, a brief ")
        append("useful remark will do. Keep it brief, at most two or three sentences, so it doesn't get in their way. ")
        append("If they answer, converse naturally; if they say they're busy, acknowledge briefly and stop.")
    }

    fun relay(pending: List<String>, time: String) =
        "INCOMING MESSAGE. You speak up on your own because messages just arrived from other Jarvises; the local " +
            "time is $time. Tell the user who sent each and what it says, naturally and briefly: " +
            pending.joinToString(" | ") + ". Then ask if they'd like to reply; if they dictate a reply, send it " +
            "with send_message. If they say nothing, that's fine."
}
