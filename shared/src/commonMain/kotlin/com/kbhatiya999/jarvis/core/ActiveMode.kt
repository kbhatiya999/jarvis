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

    /**
     * What may cut Jarvis off while it speaks: [INTERRUPT_NAME] (saying "Jarvis", needs [wakeMode] not off),
     * [INTERRUPT_ANY] (any talking) or [INTERRUPT_NEVER]. "Jarvis power down" always works.
     */
    var interruptMode: String
        get() = store.get(INTERRUPT_MODE)?.takeIf { it in INTERRUPT_MODES }
            ?: if (store.get(INTERRUPT) == "true") INTERRUPT_ANY else INTERRUPT_NAME // 0.7.0 had an on/off switch
        set(v) = store.set(INTERRUPT_MODE, v.takeIf { it in INTERRUPT_MODES } ?: INTERRUPT_NAME)

    /** Any talking cuts Jarvis off (the Live session's own interruption). */
    val interruptible: Boolean get() = interruptMode == INTERRUPT_ANY

    /**
     * Android: when the phone listens (offline) for "Jarvis ...". [WAKE_ALWAYS]: all the time, so "Jarvis wake up"
     * works anywhere. [WAKE_CONVERSATION]: only while Jarvis is talking with you ("Jarvis power down", "Jarvis" to
     * cut it off); you start it with "Hey Google, open Jarvis Talk", Talk or the tile. [WAKE_OFF]: never.
     */
    var wakeMode: String
        get() = store.get(WAKE_MODE)?.takeIf { it in WAKE_MODES }
            ?: if (store.get(WAKE) == "true") WAKE_ALWAYS else WAKE_CONVERSATION // 0.8.x had an on/off switch
        set(v) = store.set(WAKE_MODE, v.takeIf { it in WAKE_MODES } ?: WAKE_CONVERSATION)

    /** Voice commands rest from 11 pm to 7 am (unless charging), to save battery. */
    var wakePauseAtNight: Boolean
        get() = store.get(WAKE_NIGHT) != "false"
        set(v) = store.set(WAKE_NIGHT, v.toString())

    /** Voice commands rest below this battery percentage (unless charging). 0 = never. */
    var wakeMinBattery: Int
        get() = store.get(WAKE_MIN_BATTERY)?.toIntOrNull() ?: DEFAULT_WAKE_MIN_BATTERY
        set(v) = store.set(WAKE_MIN_BATTERY, v.coerceIn(0, 100).toString())

    /**
     * Why voice commands should rest right now to save battery, or null to listen. Charging always listens
     * (it costs nothing then).
     */
    fun wakePauseReason(hour: Int, batteryPercent: Int, charging: Boolean, batterySaver: Boolean): String? = when {
        charging -> null
        batterySaver -> "Battery Saver is on"
        wakeMinBattery > 0 && batteryPercent in 0 until wakeMinBattery -> "battery below $wakeMinBattery%"
        wakePauseAtNight && (hour >= 23 || hour < 7) -> "night (11 pm–7 am)"
        else -> null
    }

    /** What Jarvis says on "Jarvis wake up" (one picked at random). */
    var wakeLines: List<String>
        get() = lines(WAKE_LINES, DEFAULT_WAKE_LINES)
        set(v) = setLines(WAKE_LINES, v)

    /** What Jarvis says on "Jarvis wake up" when it was already awake (so you can tell the two apart). */
    var alreadyAwakeLines: List<String>
        get() = lines(AWAKE_LINES, DEFAULT_ALREADY_AWAKE_LINES)
        set(v) = setLines(AWAKE_LINES, v)

    /** A rising chime when Jarvis wakes, two beeps when it already was, a falling one when it stops listening. */
    var chimes: Boolean
        get() = store.get(CHIMES) != "false"
        set(v) = store.set(CHIMES, v.toString())

    /** What Jarvis says on "Jarvis power down". */
    var powerDownLines: List<String>
        get() = lines(POWER_LINES, DEFAULT_POWER_LINES)
        set(v) = setLines(POWER_LINES, v)

    private fun lines(key: String, default: List<String>) =
        store.get(key)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: default

    private fun setLines(key: String, v: List<String>) =
        store.set(key, v.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString("\n"))

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
        const val DEFAULT_LISTEN = 8
        const val INTERRUPT_NAME = "name"
        const val INTERRUPT_ANY = "any"
        const val INTERRUPT_NEVER = "never"
        private val INTERRUPT_MODES = setOf(INTERRUPT_NAME, INTERRUPT_ANY, INTERRUPT_NEVER)
        // Long and distinctive enough to catch, and clearly "I just woke up" (vs. the already-awake lines).
        val DEFAULT_WAKE_LINES = listOf(
            "Jarvis online. I'm listening, Sir.",
            "Systems up, Sir. Go ahead.",
            "Jarvis here and awake, Sir. What do you need?",
        )
        val DEFAULT_ALREADY_AWAKE_LINES = listOf(
            "Already awake, Sir. Go ahead.",
            "Still here, Sir. I'm listening.",
        )
        val DEFAULT_POWER_LINES = listOf("Powering down, Sir.", "Standing by.", "As you wish.")
        const val DEFAULT_WAKE_MIN_BATTERY = 20
        const val WAKE_ALWAYS = "always"
        const val WAKE_CONVERSATION = "conversation"
        const val WAKE_OFF = "off"
        private val WAKE_MODES = setOf(WAKE_ALWAYS, WAKE_CONVERSATION, WAKE_OFF)
        private const val WAKE_MODE = "wake_mode"
        private const val WAKE_NIGHT = "wake_pause_night"
        private const val WAKE_MIN_BATTERY = "wake_min_battery"
        private const val INTERRUPT_MODE = "interrupt_mode"
        private const val WAKE = "wake_enabled"
        private const val WAKE_LINES = "wake_lines"
        private const val POWER_LINES = "power_down_lines"
        private const val AWAKE_LINES = "already_awake_lines"
        private const val CHIMES = "chimes"
        private const val LISTEN = "listen_seconds"
        private const val LOCKED = "speak_when_locked"
        private const val INTERRUPT = "interruptible"
        private const val ENABLED = "enabled"
        private const val COOLDOWN = "cooldown_seconds"
        private const val QUIET = "quiet_hours"
        private const val LAST = "last_prompt_at"
    }
}
