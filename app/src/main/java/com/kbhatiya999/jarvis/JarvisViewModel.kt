package com.kbhatiya999.jarvis

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.kbhatiya999.jarvis.core.ActiveMode
import com.kbhatiya999.jarvis.core.LiveSession

class JarvisViewModel(app: Application) : AndroidViewModel(app) {
    val jarvis = AndroidPlatform.jarvis(app)
    val settings = Settings(app)

    var editingKey by mutableStateOf(false)
    /** Which screen is showing over the chat: null, "active", "reminders", "contacts", "logs" or "check". */
    var screen by mutableStateOf<String?>(null)
    var status by mutableStateOf("Standing by")
        private set
    var connected by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set

    /** Downloading the offline speech model for "Jarvis wake up": 0..1, or null when not downloading. */
    var wakeDownload by mutableStateOf<Float?>(null)
        private set
    var wakeError by mutableStateOf<String?>(null)
        private set
    /** When the phone listens for "Jarvis ..." (ActiveMode.WAKE_*). */
    var wakeMode by mutableStateOf(jarvis.active.wakeMode)
        private set
    /** The offline speech model is on the phone (needed for any mode but off). */
    var wakeModelReady by mutableStateOf(WakeListener.Models.ready(app))
        private set

    /** Sets when Jarvis listens for its name, downloading the speech model first if that mode needs it. */
    fun chooseWakeMode(mode: String) {
        val app = getApplication<Application>()
        wakeError = null
        jarvis.active.wakeMode = mode
        wakeMode = mode
        ActiveModeService.refreshWake(app)
        if (mode != ActiveMode.WAKE_OFF && !WakeListener.Models.ready(app)) downloadModel()
    }

    fun downloadModel() {
        val app = getApplication<Application>()
        if (wakeDownload != null) return
        wakeError = null
        wakeDownload = 0f
        Thread {
            var last = 0f
            val error = WakeListener.Models.download(app) { p ->
                if (p - last >= 0.01f || p >= 1f) { last = p; main.post { wakeDownload = p } }
            }
            main.post {
                wakeDownload = null
                wakeError = error
                wakeModelReady = WakeListener.Models.ready(app)
                ActiveModeService.refreshWake(app)
            }
        }.apply { name = "jarvis-model-download"; start() }
    }

    private var session: LiveSession? = null
    // Callbacks from a session that was already replaced are ignored.
    private var currentToken = Any()
    private var writer = jarvis.conversation.createWriter()
    private val main = Handler(Looper.getMainLooper())
    // "That's enough": end the chat once Jarvis's goodbye has played.
    private var endAfterTurn = false

    init {
        VoiceChatService.onStopRequested = { endSession() }
    }

    /** The mic is on: keep it working when the screen locks (a microphone foreground service). */
    private fun keepAlive(on: Boolean) {
        if (on) VoiceChatService.start(getApplication()) else VoiceChatService.stop(getApplication())
    }

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
            keepAlive(false)
            status = "Listening paused"
        } else {
            ensureSession().startMic()
            listening = true
            keepAlive(true)
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
        if (OneJarvis.chat === session) OneJarvis.chat = null
        session = null
        connected = false
        if (listening) keepAlive(false)
        listening = false
        writer.closeTurn()
    }

    /** Opened from the Quick Settings tile: start listening if not already. */
    fun talkNow() {
        if (!listening) toggleVoice()
    }

    private fun ensureSession(): LiveSession {
        session?.let { return it }
        // One Jarvis at a time: the chat takes over from active mode's conversation, if one is going.
        ActiveModeService.endForChat()
        status = "Connecting"
        endAfterTurn = false
        val token = Any().also { currentToken = it }
        writer = jarvis.conversation.createWriter()
        return jarvis.createSession(jarvis.systemPrompt(), Callbacks(token)).also {
            session = it
            OneJarvis.chat = it
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
        override fun onTurnComplete() {
            if (!live()) return
            writer.closeTurn()
            if (endAfterTurn) {
                val wait = session?.playbackRemainingMs() ?: 0
                main.postDelayed({ if (live()) { endSession(); status = "Standing by" } }, wait + 300)
            }
        }
        override fun onEndRequested() { if (live()) endAfterTurn = true }
        override fun onInterrupted() { if (live()) writer.closeTurn() }
        override fun onClosed(reason: String?) {
            if (!live()) return
            writer.closeTurn()
            if (OneJarvis.chat === session) OneJarvis.chat = null
            session = null
            connected = false
            if (listening) keepAlive(false)
            listening = false
            status = reason ?: "Standing by"
        }
    }

    override fun onCleared() {
        VoiceChatService.onStopRequested = null
        endSession()
        jarvis.conversation.save()
    }
}
