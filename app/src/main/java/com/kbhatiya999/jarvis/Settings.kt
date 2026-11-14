package com.kbhatiya999.jarvis

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var activeEnabled: Boolean
        get() = prefs.getBoolean("active_enabled", false)
        set(v) = prefs.edit().putBoolean("active_enabled", v).apply()

    /** Minimum gap between two proactive prompts. */
    var cooldownMinutes: Int
        get() = prefs.getInt("cooldown_minutes", 30)
        set(v) = prefs.edit().putInt("cooldown_minutes", v).apply()

    /** No proactive prompts from 23:00 to 07:00. */
    var quietHours: Boolean
        get() = prefs.getBoolean("quiet_hours", true)
        set(v) = prefs.edit().putBoolean("quiet_hours", v).apply()

    var lastPromptAt: Long
        get() = prefs.getLong("last_prompt_at", 0)
        set(v) = prefs.edit().putLong("last_prompt_at", v).apply()
}
