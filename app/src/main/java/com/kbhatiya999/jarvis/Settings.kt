package com.kbhatiya999.jarvis

import android.content.Context
import com.kbhatiya999.jarvis.core.ActiveMode

/** Active mode settings, kept by the shared [ActiveMode] (the same rules as on iPhone). */
class Settings(context: Context) {
    private val active: ActiveMode = AndroidPlatform.jarvis(context).active

    init {
        // Up to 0.6.0 these lived in SharedPreferences "jarvis_settings": copy them over once.
        if (!active.hasSettings) {
            val old = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            active.enabled = old.getBoolean("active_enabled", false)
            active.quietHours = old.getBoolean("quiet_hours", true)
            active.lastPromptAt = old.getLong("last_prompt_at", 0)
            active.cooldownSeconds = old.getInt("cooldown_seconds", old.getInt("cooldown_minutes", 30) * 60)
        }
    }

    var activeEnabled by active::enabled
    var cooldownSeconds by active::cooldownSeconds
    var quietHours by active::quietHours
    var listenSeconds by active::listenSeconds
    var speakWhenLocked by active::speakWhenLocked
    var interruptible by active::interruptible
}
