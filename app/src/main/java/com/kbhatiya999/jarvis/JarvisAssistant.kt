package com.kbhatiya999.jarvis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.kbhatiya999.jarvis.core.JarvisLog

/**
 * Jarvis as the phone's assistant app (Settings > Apps > Default apps > Digital assistant app). Then the power button
 * long-press or the corner swipe opens Jarvis, without Google, also on the lock screen. It shows nothing of its own:
 * it wakes Jarvis exactly like the phrase would and goes away.
 */
class JarvisAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        JarvisLog.i("Assistant", "Jarvis is the phone's assistant app")
    }
}

class JarvisAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisAssistantSession(this)
}

private class JarvisAssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        JarvisLog.i("Assistant", "invoked (flags $showFlags), active mode ${if (ActiveModeService.running) "on" else "off"}")
        if (ActiveModeService.running) {
            ActiveModeService.wake(context)
        } else {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_TALK, true)
            )
        }
        hide()
    }
}

object JarvisAssistant {
    fun component(context: Context) = ComponentName(context, JarvisAssistantService::class.java)

    /** True when the user picked Jarvis as the digital assistant app. */
    fun isDefault(context: Context): Boolean {
        val me = component(context).flattenToString()
        return listOf("voice_interaction_service", "assistant").any {
            try { AndroidSettings.Secure.getString(context.contentResolver, it) == me } catch (e: Exception) { false }
        }
    }

    fun settingsIntent() = Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)
}
