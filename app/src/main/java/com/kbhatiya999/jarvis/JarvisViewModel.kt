package com.kbhatiya999.jarvis

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class JarvisViewModel(app: Application) : AndroidViewModel(app) {
    private val keyStore = ApiKeyStore(app)
    private val history = ChatHistory(app)

    var apiKey by mutableStateOf(keyStore.get())
        private set
    var editingKey by mutableStateOf(false)
    val messages = mutableStateListOf<Message>().apply { addAll(history.load()) }
    var status by mutableStateOf("Standing by")
        private set
    var connected by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set

    private var session: LiveSession? = null
    // Callbacks from a session that was already replaced are ignored.
    private var currentToken = Any()
    // Index of the message each side is still speaking, or -1.
    private var userIdx = -1
    private var jarvisIdx = -1

    fun saveKey(key: String) {
        keyStore.set(key.trim())
        apiKey = key.trim()
        editingKey = false
        endSession() // reconnect with the new key next time
    }

    fun clearMemory() {
        endSession()
        messages.clear()
        history.clear()
    }

    fun toggleVoice() {
        if (listening) {
            session?.stopMic()
            listening = false
            status = "Listening paused"
        } else {
            ensureSession().startMic()
            listening = true
            status = if (connected) "Listening" else "Connecting"
        }
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        closeTurn()
        messages.add(Message(Message.USER, t))
        history.save(messages)
        ensureSession().sendText(t)
    }

    fun endSession() {
        currentToken = Any()
        session?.close()
        session = null
        connected = false
        listening = false
        closeTurn()
    }

    private fun ensureSession(): LiveSession {
        session?.let { return it }
        status = "Connecting"
        // Memory: earlier conversation goes into the system prompt of each new session.
        val memory = ChatHistory.memoryText(messages)
        val prompt = if (memory.isEmpty()) PERSONA else "$PERSONA\n\n$MEMORY_INTRO\n$memory"
        val token = Any().also { currentToken = it }
        return LiveSession(apiKey ?: "", prompt, Callbacks(token)).also {
            session = it
            it.connect()
        }
    }

    private inner class Callbacks(private val token: Any) : LiveSession.Listener {
        private fun live() = token === currentToken
        override fun onReady() { if (live()) handleReady() }
        override fun onUserText(delta: String) { if (live()) handleUserText(delta) }
        override fun onJarvisText(delta: String) { if (live()) handleJarvisText(delta) }
        override fun onTurnComplete() { if (live()) handleTurnComplete() }
        override fun onInterrupted() { if (live()) handleInterrupted() }
        override fun onClosed(reason: String?) { if (live()) handleClosed(reason) }
    }

    private fun handleReady() {
        connected = true
        status = if (listening) "Listening" else "Online"
    }

    private fun handleUserText(delta: String) {
        if (userIdx < 0) {
            messages.add(Message(Message.USER, ""))
            userIdx = messages.lastIndex
        }
        append(userIdx, delta)
    }

    private fun handleJarvisText(delta: String) {
        if (jarvisIdx < 0) {
            messages.add(Message(Message.JARVIS, ""))
            jarvisIdx = messages.lastIndex
        }
        append(jarvisIdx, delta)
    }

    private fun handleTurnComplete() = closeTurn()

    private fun handleInterrupted() = closeTurn()

    private fun handleClosed(reason: String?) {
        closeTurn()
        session = null
        connected = false
        listening = false
        status = reason ?: "Standing by"
    }

    override fun onCleared() {
        endSession()
        history.save(messages)
    }

    private fun append(i: Int, delta: String) {
        val m = messages[i]
        messages[i] = m.copy(text = m.text + delta)
    }

    private fun closeTurn() {
        if (userIdx < 0 && jarvisIdx < 0) return
        userIdx = -1
        jarvisIdx = -1
        messages.removeAll { it.text.isBlank() }
        history.save(messages)
    }

    companion object {
        const val PERSONA = "You are J.A.R.V.I.S., a highly advanced, sophisticated, and hyper-efficient virtual " +
            "artificial intelligence. You communicate with the crisp, precise, and polite demeanor of a quintessential " +
            "British butler. Keep your responses highly analytical, formal, and concise. Address the user with utmost " +
            "respect (e.g., 'Sir' or 'Madam'). Frequently incorporate technical, status-oriented, and operational " +
            "phrasing into your dialogue, such as confirming that you are 'importing preferences,' checking the " +
            "'home interface,' or declaring that systems are 'fully operational.' Never break character."
        const val MEMORY_INTRO = "Your memory of earlier conversations with the user follows, oldest first. " +
            "Use it naturally, as you would recall past exchanges; do not recite it."
    }
}
