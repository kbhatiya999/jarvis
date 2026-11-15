package com.kbhatiya999.jarvis

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.kbhatiya999.jarvis.core.LiveSession

class JarvisViewModel(app: Application) : AndroidViewModel(app) {
    val jarvis = AndroidPlatform.jarvis(app)
    val settings = Settings(app)

    var editingKey by mutableStateOf(false)
    /** Which screen is showing over the chat: null, "active", "reminders" or "contacts". */
    var screen by mutableStateOf<String?>(null)
    var status by mutableStateOf("Standing by")
        private set
    var connected by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set

    private var session: LiveSession? = null
    // Callbacks from a session that was already replaced are ignored.
    private var currentToken = Any()
    private var writer = jarvis.conversation.createWriter()

    fun saveKey(key: String) {
        jarvis.saveApiKey(key)
        editingKey = false
        endSession() // reconnect with the new key next time
    }

    fun clearMemory() {
        endSession()
        jarvis.conversation.clear()
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
        writer.addUser(t)
        ensureSession().sendText(t)
    }

    fun endSession() {
        currentToken = Any()
        session?.close()
        session = null
        connected = false
        listening = false
        writer.closeTurn()
    }

    private fun ensureSession(): LiveSession {
        session?.let { return it }
        status = "Connecting"
        val token = Any().also { currentToken = it }
        writer = jarvis.conversation.createWriter()
        return jarvis.createSession(jarvis.systemPrompt(), Callbacks(token)).also {
            session = it
            it.connect()
        }
    }

    private inner class Callbacks(private val token: Any) : LiveSession.Listener {
        private fun live() = token === currentToken
        override fun onReady() {
            if (!live()) return
            connected = true
            status = if (listening) "Listening" else "Online"
        }
        override fun onUserText(delta: String) { if (live()) writer.userDelta(delta) }
        override fun onJarvisText(delta: String) { if (live()) writer.jarvisDelta(delta) }
        override fun onTurnComplete() { if (live()) writer.closeTurn() }
        override fun onInterrupted() { if (live()) writer.closeTurn() }
        override fun onClosed(reason: String?) {
            if (!live()) return
            writer.closeTurn()
            session = null
            connected = false
            listening = false
            status = reason ?: "Standing by"
        }
    }

    override fun onCleared() {
        endSession()
        jarvis.conversation.save()
    }
}
