package com.kbhatiya999.jarvis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything Jarvis knows and does, shared by the Android and iOS apps. One instance per app
 * process: call [setUp] once at startup with the platform, then use [shared]. Main thread only.
 */
class Jarvis private constructor(val platform: Platform) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val conversation = Conversation(platform)
    val reminders = Reminders(platform.store("jarvis_reminders"))
    val contacts = Contacts(platform.store("jarvis_contacts"))
    val identity: Identity by lazy { Identity.load(platform) }
    val inbox = Inbox(this)
    val relay = Relay(this)
    val tools = Tools(this)

    /** The Gemini API key, kept by the platform as a secret. */
    val apiKey = Watchable(platform.secrets.get("api_key")?.takeIf { it.isNotBlank() })

    /** Called after a message from a contact arrives (e.g. active mode says it out loud). */
    var onMessageArrived: (() -> Unit)? = null

    fun saveApiKey(key: String) {
        val k = key.trim()
        platform.secrets.set("api_key", k)
        apiKey.value = k.ifBlank { null }
    }

    /** Persona, the user's reminders and contacts, memory, plus optional instructions for this session. */
    fun systemPrompt(extra: String? = null): String = buildString {
        append(Prompts.PERSONA)
        append("\n\n").append(Prompts.context(reminders.items.value, contacts.contacts.value, contacts.myName.value))
        if (extra != null) append("\n\n").append(extra)
        val memory = conversation.memoryText()
        if (memory.isNotEmpty()) append("\n\n").append(Prompts.MEMORY_INTRO).append('\n').append(memory)
    }

    /** A new voice session with the current API key; call connect() on it. */
    fun createSession(systemPrompt: String, listener: LiveSession.Listener) =
        LiveSession(this, apiKey.value ?: "", systemPrompt, listener)

    /** Accepts a contact request: adds the contact and delivers its message. */
    fun acceptRequest(request: ContactRequest) {
        if (contacts.add(request.name, request.address) == null) inbox.receive(request.name, request.text)
    }

    companion object {
        private var instance: Jarvis? = null

        fun setUp(platform: Platform): Jarvis = instance ?: Jarvis(platform).also { instance = it }

        val shared: Jarvis get() = instance ?: error("Jarvis.setUp(platform) first")
    }
}
