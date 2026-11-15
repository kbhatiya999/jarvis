package com.kbhatiya999.jarvis.core

/**
 * Active mode rules shared by both phones: on/off, cooldown, quiet hours and when Jarvis last spoke up.
 * The trigger differs (Android: Accessibility sees the app open; iPhone: a Shortcuts automation runs the
 * check-in), and each platform adds its own "bad moment" checks (calls, silent mode, ...) before [allowed].
 */
class ActiveMode internal constructor(private val jarvis: Jarvis) {
    private val store = jarvis.platform.store("jarvis_active")

    /** Android: the background service is on. iPhone: check-ins from Shortcuts are allowed. */
    var enabled: Boolean
        get() = store.get(ENABLED) == "true"
        set(v) = store.set(ENABLED, v.toString())

    /** Minimum gap between two times Jarvis speaks up, in seconds. 0 = every app open. */
    var cooldownSeconds: Int
        get() = store.get(COOLDOWN)?.toIntOrNull() ?: DEFAULT_COOLDOWN
        set(v) = store.set(COOLDOWN, maxOf(0, v).toString())

    /** Silent from 23:00 to 07:00. */
    var quietHours: Boolean
        get() = store.get(QUIET) != "false"
        set(v) = store.set(QUIET, v.toString())

    /** Seconds Jarvis listens for an answer once it has finished speaking (Android). */
    var listenSeconds: Int
        get() = store.get(LISTEN)?.toIntOrNull() ?: DEFAULT_LISTEN
        set(v) = store.set(LISTEN, v.coerceIn(1, 60).toString())

    /** Messages from other Jarvises are said aloud (and answered) on the lock screen too (Android). */
    var speakWhenLocked: Boolean
        get() = store.get(LOCKED) != "false"
        set(v) = store.set(LOCKED, v.toString())

    /** Talking over Jarvis cuts it off. Off: Jarvis always finishes reading, the mic waits until it's done. */
    var interruptible: Boolean
        get() = store.get(INTERRUPT) == "true"
        set(v) = store.set(INTERRUPT, v.toString())

    var lastPromptAt: Long
        get() = store.get(LAST)?.toLongOrNull() ?: 0
        set(v) = store.set(LAST, v.toString())

    /** True once settings exist here (Android uses this to copy its older settings over once). */
    val hasSettings: Boolean get() = store.get(COOLDOWN) != null

    fun isQuietHour(hour: Int) = quietHours && (hour >= 23 || hour < 7)

    /** The shared rules, given the local hour (0-23). */
    fun allowed(hour: Int, ignoreCooldown: Boolean = false): Boolean {
        if (jarvis.apiKey.value == null) return false
        if (isQuietHour(hour)) return false
        return ignoreCooldown || jarvis.platform.nowMillis() - lastPromptAt >= cooldownSeconds * 1000L
    }

    fun markSpoke() {
        lastPromptAt = jarvis.platform.nowMillis()
    }

    /**
     * The session instructions for "the user just opened [appName]", including messages waiting to be said
     * (they count as said once taken). [canListen] is false on iPhone, where Jarvis can't hear a reply.
     */
    fun appOpenedPrompt(appName: String, time: String, canListen: Boolean = true): String =
        Prompts.proactive(appName, time, jarvis.inbox.takePending(), canListen)

    companion object {
        /** Sent right after connecting so Jarvis starts talking. Not stored in memory. */
        fun cue(appName: String) = "(The user has just opened $appName. Speak up now.)"

        const val DEFAULT_COOLDOWN = 30 * 60
        const val DEFAULT_LISTEN = 5
        private const val LISTEN = "listen_seconds"
        private const val LOCKED = "speak_when_locked"
        private const val INTERRUPT = "interruptible"
        private const val ENABLED = "enabled"
        private const val COOLDOWN = "cooldown_seconds"
        private const val QUIET = "quiet_hours"
        private const val LAST = "last_prompt_at"
    }
}
