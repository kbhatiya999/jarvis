package com.kbhatiya999.jarvis

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.kbhatiya999.jarvis.core.JarvisLog
import java.util.concurrent.Executor

/**
 * For one Jarvis conversation: logs what else the phone's audio is doing, to see whether Google (Gemini) is still
 * in its own conversation after waking Jarvis and answering too ("Hey Google keeps responding"). Only logs, changes
 * nothing. A line is written when the picture changes: the audio mode (7 while Gemini talks) and what is playing.
 *
 * Android doesn't say which app plays what. Jarvis's own Gemini voice is MEDIA; its clips and chimes are ASSISTANT,
 * as is Google's voice, so an ASSISTANT line right after a wake may be Jarvis's own clip.
 */
class OtherAudioLog(private val audio: AudioManager) {
    private val main = Handler(Looper.getMainLooper())
    private var last: String? = null
    private var started = false

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = record(configs)
    }
    private val modeListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { record(audio.activePlaybackConfigurations) }
    } else null

    fun start() {
        if (started) return
        started = true
        last = null
        audio.registerAudioPlaybackCallback(playback, main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnModeChangedListener(Executor { main.post(it) }, modeListener as AudioManager.OnModeChangedListener)
        }
        record(audio.activePlaybackConfigurations)
    }

    fun stop() {
        if (!started) return
        started = false
        try { audio.unregisterAudioPlaybackCallback(playback) } catch (e: Exception) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audio.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener) } catch (e: Exception) { }
        }
    }

    private fun record(configs: List<AudioPlaybackConfiguration>) {
        if (!started) return
        val playing = configs.map { usage(it.audioAttributes.usage) }.sorted()
        val now = "audio mode ${audio.mode}${if (audio.mode == MODE_ASSISTANT) " (assistant conversation)" else ""}, " +
            "playing ${if (playing.isEmpty()) "nothing" else playing.joinToString(", ")}"
        if (now == last) return
        last = now
        JarvisLog.i("Audio", "during conversation: $now")
    }

    private fun usage(u: Int) = when (u) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "SOUNDS"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "CALL"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        else -> "usage $u"
    }

    companion object {
        /** AudioManager's hidden MODE_ASSISTANT_CONVERSATION; what Gemini sets while it talks. */
        private const val MODE_ASSISTANT = 7
    }
}
