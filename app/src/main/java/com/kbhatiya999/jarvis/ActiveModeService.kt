package com.kbhatiya999.jarvis

import com.kbhatiya999.jarvis.core.Conversation
import com.kbhatiya999.jarvis.core.Jarvis
import com.kbhatiya999.jarvis.core.LiveSession
import com.kbhatiya999.jarvis.core.ActiveMode
import com.kbhatiya999.jarvis.core.Prompts
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Active mode. Runs as a microphone foreground service (started while the app is open, which is
 * what lets it use the mic later from the background). When AppWatcher reports an app opening,
 * Jarvis speaks up on its own, then listens for your answer (8 seconds by default); silence ends the session. With voice commands on,
 * it also listens (offline) for "Jarvis wake up", "Jarvis power down" and "Jarvis" to cut it off.
 */
class ActiveModeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var jarvis: Jarvis
    private var session: LiveSession? = null
    private var token = Any()
    private var writer: Conversation.Writer? = null
    private var micOn = false
    private var focus: AudioFocusRequest? = null
    private val endSession = Runnable { end() }
    private var streaming = false
    private var listenMs = ActiveMode.DEFAULT_LISTEN * 1000L
    private var wake: WakeListener? = null
    private lateinit var clips: Clips
    // "That's enough": end once Jarvis's goodbye has played instead of listening again.
    private var endAfterTurn = false
    // Why voice commands are resting to save battery (night, low battery, Battery Saver), or null.
    private var wakePaused: String? = null
    private val power = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshWake()
    }
    private val recheckWake: Runnable = Runnable { refreshWake() }
    private var destroyed = false
    private var clipsChecked = false
    private var shownText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        jarvis = AndroidPlatform.jarvis(this)
        createChannel()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Watching for app launches"), type)
        } catch (e: Exception) {
            // e.g. mic permission missing, or started from the background.
            Log.w(TAG, "Cannot start active mode", e)
            settings.activeEnabled = false
            stopSelf()
            return
        }
        instance = this
        clips = Clips.get(this)
        jarvis.onMessageArrived = { messageArrived() }
        jarvis.relay.acquireStream()
        streaming = true
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(power, filter)
        refreshWake()
    }

    /** Starts or stops listening for "Jarvis ..." to match the setting (and the model being there). */
    private fun refreshWake() {
        if (instance !== this || destroyed) return
        main.removeCallbacks(recheckWake)
        val mode = jarvis.active.wakeMode
        val ready = mode != ActiveMode.WAKE_OFF && WakeListener.Models.ready(this)
        val always = ready && mode == ActiveMode.WAKE_ALWAYS
        wakePaused = if (always) pauseReason() else null
        // Night starts and ends on the clock: look again in a while.
        if (always) main.postDelayed(recheckWake, WAKE_RECHECK_MS)
        // Listening all the time, or (also while "always" rests to save battery) only during a conversation, so
        // "Jarvis power down" and "Jarvis" to cut it off still work. startSession() and end() look again.
        val want = ready && ((always && wakePaused == null) || session != null)
        // Jarvis's own voice for the instant replies, recorded once (no-op when they exist).
        if (ready && !clipsChecked && jarvis.apiKey.value != null) {
            clipsChecked = true
            clips.recordMissing()
        }
        if (want && wake == null) {
            wake = WakeListener(
                this,
                onWake = { main.post { wakePhrase() } },
                onPowerDown = { main.post { powerDown() } },
                onName = { main.post { nameSaid() } },
            ).also { it.start() }
        } else if (!want && wake != null) {
            wake?.stop()
            wake = null
        }
        if (session == null) update(idleText())
    }

    private fun idleText() = when {
        wake != null -> "Say \"Jarvis wake up\""
        wakePaused != null -> "Resting ($wakePaused): say \"Hey Google, open Jarvis Talk\""
        else -> "Say \"Hey Google, open Jarvis Talk\" to talk"
    }

    private fun pauseReason(): String? {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level < 0 || scale <= 0) -1 else level * 100 / scale
        val charging = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val saver = getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
        return jarvis.active.wakePauseReason(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), percent, charging, saver)
    }

    /** "Jarvis wake up": Jarvis answers at once (a clip, no network), then listens. Also on the lock screen. */
    private fun wakePhrase() {
        if (session != null || VoiceChatService.running || inCall() || jarvis.apiKey.value == null) return
        startSession(
            "The user said \"Jarvis wake up\" (the phone may be locked or in another app); you have already " +
                "answered them. Listen and help, briefly. They may ask you to send a message: send it right away " +
                "with send_message unless it's unclear.",
            cue = null,
            countsForCooldown = false,
        )
        val t = token
        update("Listening")
        clips.play(Clips.Slot.WAKE) { if (t === token) main.post(startListening) }
    }

    /** "Jarvis power down": stop at once, even mid-sentence, acknowledge, and go back to waiting for the phrase. */
    private fun powerDown() {
        when {
            session != null -> {
                session?.interrupt()
                end()
            }
            VoiceChatService.running -> VoiceChatService.onStopRequested?.invoke()
            else -> return // nothing to stop
        }
        clips.play(Clips.Slot.POWER_DOWN)
    }

    /** "Jarvis" over Jarvis's voice: with that interrupt setting, cut it off and listen. */
    private fun nameSaid() {
        val s = session ?: return
        if (jarvis.active.interruptMode != ActiveMode.INTERRUPT_NAME) return
        if (s.playbackRemainingMs() <= 0) return // not speaking: the mic already reaches Gemini
        s.interrupt()
        main.removeCallbacks(startListening)
        main.post(startListening)
    }

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.activeEnabled = false
                stopSelf()
            }
            ACTION_TEST -> appOpened("Jarvis test", force = true)
            ACTION_TALK -> talk()
            ACTION_REFRESH -> refreshWake()
            ACTION_WAKE -> wakePhrase()
        }
        // Not sticky: a restart by the system would come from the background, where the mic is not allowed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(recheckWake)
        if (instance === this) try { unregisterReceiver(power) } catch (e: IllegalArgumentException) { }
        wake?.stop()
        wake = null
        clips.stop()
        end()
        if (streaming) jarvis.relay.releaseStream()
        streaming = false
        if (instance === this) {
            instance = null
            jarvis.onMessageArrived = null
        }
        super.onDestroy()
    }

    private fun appOpened(pkgOrName: String, force: Boolean = false) {
        if (session != null) return
        if (!force && !allowedNow(pkgOrName)) return
        if (jarvis.apiKey.value == null) return
        val name = appLabel(pkgOrName)
        startSession(jarvis.active.appOpenedPrompt(name, now()), ActiveMode.cue(name))
    }

    /** A message from another Jarvis arrived: say it now if that's appropriate, else it waits for the next app open. */
    private fun messageArrived() {
        val s = session
        if (s != null) {
            val pending = jarvis.inbox.takePending()
            if (pending.isNotEmpty()) {
                s.sendText("(New message just arrived: ${pending.joinToString(" | ")}. Relay it to the user now and ask if they'd like to reply.)")
            }
            return
        }
        // On the lock screen too, unless turned off (the phone is often locked when a message comes in).
        if (!allowedNow("", ignoreCooldown = true, allowLocked = jarvis.active.speakWhenLocked)) return
        val pending = jarvis.inbox.takePending()
        if (pending.isEmpty()) return
        startSession(Prompts.relay(pending, now()), "(Messages just arrived. Relay them now.)")
    }

    /** Talk from the notification or Quick Settings tile, also on the lock screen: Jarvis answers, then listens. */
    private fun talk() {
        if (session != null || jarvis.apiKey.value == null) return
        startSession(
            "The user tapped Talk (the phone may be locked or in another app) because they want to talk to you. " +
                "Converse naturally and briefly.",
            "(The user wants to talk. Answer in two or three words, e.g. \"Yes, Sir?\", then wait for them.)",
            countsForCooldown = false,
        )
    }

    private fun now() = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault()).format(Date())

    /** [cue] makes Jarvis speak first; null when it already answered (a clip) and should just listen. */
    private fun startSession(prompt: String, cue: String?, countsForCooldown: Boolean = true) {
        if (jarvis.apiKey.value == null) return
        if (countsForCooldown) jarvis.active.markSpoke()
        listenMs = jarvis.active.listenSeconds * 1000L
        val t = Any().also { token = it }
        writer = jarvis.conversation.createWriter()
        micOn = false
        endAfterTurn = false
        requestFocus()
        update("Speaking")
        session = jarvis.createSession(jarvis.systemPrompt(prompt), Callbacks(t), interruptible = jarvis.active.interruptible).also {
            it.connect()
            // Cue for Jarvis to start talking. Not stored in memory.
            if (cue != null) it.sendText(cue)
        }
        main.postDelayed(endSession, MAX_SESSION_MS)
        refreshWake() // "only during conversations": start listening for "Jarvis power down"
    }

    private fun allowedNow(pkg: String, ignoreCooldown: Boolean = false, allowLocked: Boolean = false): Boolean {
        if (!jarvis.active.allowed(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), ignoreCooldown)) return false
        // Android-only checks: calls, silent mode, Do Not Disturb, lock screen, the phone app itself.
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return false
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false // Do Not Disturb
        if (!allowLocked && getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val dialer = try { getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (e: Exception) { null }
        return pkg != dialer
    }

    private inner class Callbacks(private val t: Any) : LiveSession.Listener {
        private fun live() = t === token
        override fun onReady() {}

        override fun onUserText(delta: String) {
            if (!live()) return
            writer?.userDelta(delta)
            // They're talking: keep the session, but don't hang forever if Jarvis never answers.
            main.removeCallbacks(listenTimeout)
            main.postDelayed(listenTimeout, REPLY_WAIT_MS)
            update("Listening")
        }

        override fun onJarvisText(delta: String) {
            if (!live()) return
            writer?.jarvisDelta(delta)
            main.removeCallbacks(listenTimeout)
            update("Speaking")
        }

        override fun onTurnComplete() {
            if (!live()) return
            writer?.closeTurn()
            val wait = session?.playbackRemainingMs() ?: 0
            if (endAfterTurn) {
                // It said goodbye: end once that has played.
                main.removeCallbacks(listenTimeout)
                main.removeCallbacks(startListening)
                main.postDelayed(endSession, wait + 300)
                return
            }
            // Once Jarvis has finished speaking, open the mic and give the user time to answer.
            main.removeCallbacks(startListening)
            main.postDelayed(startListening, wait + 300)
        }

        override fun onInterrupted() {
            if (live()) writer?.closeTurn()
        }

        override fun onEndRequested() {
            if (!live()) return
            endAfterTurn = true
            // In case the goodbye never comes.
            main.removeCallbacks(endSession)
            main.postDelayed(endSession, END_FALLBACK_MS)
        }

        override fun onClosed(reason: String?) {
            if (!live()) return
            if (reason != null) Log.w(TAG, "Session closed: $reason")
            end()
        }
    }

    /**
     * The answer window ran out. If the mic heard speech in the last moment, they've started talking (the
     * transcript just hasn't arrived yet), so keep listening, up to [MAX_VOICE_WAIT_MS] on loudness alone.
     */
    private val listenTimeout: Runnable = Runnable {
        val s = session
        val now = System.currentTimeMillis()
        if (s != null && now - s.lastVoiceAt < VOICE_RECENT_MS && now - listenStartedAt < listenMs + MAX_VOICE_WAIT_MS) {
            main.postDelayed(listenTimeout, VOICE_RECHECK_MS)
        } else {
            end()
        }
    }
    private var listenStartedAt = 0L

    private val startListening: Runnable = Runnable {
        val s = session ?: return@Runnable
        // Only once Jarvis has finished reading out loud: the listening time starts after its last word.
        val left = s.playbackRemainingMs()
        if (left > 0) {
            main.postDelayed(startListening, left + 300)
            return@Runnable
        }
        if (!micOn) {
            s.startMic()
            micOn = true
        }
        update("Listening")
        listenStartedAt = System.currentTimeMillis()
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, listenMs)
    }

    private fun end() {
        main.removeCallbacks(endSession)
        main.removeCallbacks(listenTimeout)
        main.removeCallbacks(startListening)
        token = Any()
        session?.close()
        session = null
        micOn = false
        writer?.closeTurn()
        endAfterTurn = false
        abandonFocus()
        if (instance === this) refreshWake()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        getSystemService(AudioManager::class.java).requestAudioFocus(req)
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Active mode", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ActiveModeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val talk = PendingIntent.getService(
            this, 2, Intent(this, ActiveModeService::class.java).setAction(ACTION_TALK), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis active")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Talk works from the lock screen
            .addAction(0, "Talk", talk)
            .addAction(0, "Turn off", stop)
            .build()
    }

    private fun update(text: String) {
        if (text == shownText) return // battery updates call this often
        shownText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "JarvisActive"
        private const val CHANNEL = "active_mode"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.kbhatiya999.jarvis.STOP"
        private const val ACTION_TEST = "com.kbhatiya999.jarvis.TEST"
        private const val ACTION_TALK = "com.kbhatiya999.jarvis.TALK"
        private const val ACTION_REFRESH = "com.kbhatiya999.jarvis.REFRESH_WAKE"
        private const val ACTION_WAKE = "com.kbhatiya999.jarvis.WAKE"
        private const val END_FALLBACK_MS = 8_000L
        private const val WAKE_RECHECK_MS = 5 * 60_000L

        private const val REPLY_WAIT_MS = 10_000L
        private const val VOICE_RECENT_MS = 1_500L
        private const val VOICE_RECHECK_MS = 500L
        private const val MAX_VOICE_WAIT_MS = 10_000L
        private const val MAX_SESSION_MS = 3 * 60_000L

        @Volatile
        private var instance: ActiveModeService? = null

        val running get() = instance != null

        /** Called by AppWatcher on the main thread. */
        fun onAppOpened(pkg: String) {
            instance?.appOpened(pkg)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ActiveModeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActiveModeService::class.java))
        }

        /** Only while the service runs (it holds the mic): Jarvis greets you and listens. */
        fun talk(context: Context) {
            context.startService(Intent(context, ActiveModeService::class.java).setAction(ACTION_TALK))
        }

        /** Like saying "Jarvis wake up": an instant reply, then it listens (e.g. from a Google Home routine). */
        fun wake(context: Context) {
            context.startService(Intent(context, ActiveModeService::class.java).setAction(ACTION_WAKE))
        }

        /** The wake phrase setting changed (or its model arrived). */
        fun refreshWake(context: Context) {
            if (running) context.startService(Intent(context, ActiveModeService::class.java).setAction(ACTION_REFRESH))
        }

        fun test(context: Context) {
            context.startService(Intent(context, ActiveModeService::class.java).setAction(ACTION_TEST))
        }
    }
}
