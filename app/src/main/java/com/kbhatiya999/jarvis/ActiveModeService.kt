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
import com.kbhatiya999.jarvis.core.JarvisLog
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
    private val endSession = Runnable { end(reason = "reached the 3-minute limit") }
    private val endAfterGoodbye = Runnable { end(reason = "goodbye") }
    private var sessionStartedAt = 0L
    // Logs Google's audio during a conversation (is Gemini still talking after waking Jarvis?).
    private val otherAudio by lazy { OtherAudioLog(getSystemService(AudioManager::class.java)) }
    // The user spoke this turn / Jarvis answered this turn / the 10 s wait for an answer was already used in this window.
    private var userTurnOpen = false
    private var jarvisSpoke = false
    private var replyWaitUsed = false
    // The last "phrase listener" state written to the log, so only changes are logged.
    private var lastWakeState: String? = null
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            JarvisLog.i(TAG, when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> "screen off"
                Intent.ACTION_USER_PRESENT -> "phone unlocked"
                else -> "screen on, ${if (locked()) "locked" else "unlocked"}"
            })
        }
    }
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
            JarvisLog.w(TAG, "Cannot start active mode", e)
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
        registerReceiver(screen, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        JarvisLog.i(TAG, "active mode started, ${if (locked()) "locked" else "unlocked"}")
        refreshWake()
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

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
        val why = when {
            mode == ActiveMode.WAKE_OFF -> "voice commands are off"
            !ready -> "speech model not downloaded"
            always && wakePaused != null -> "resting: $wakePaused"
            always -> "always on"
            else -> "only during conversations"
        }
        val state = "${if (wake != null) "listening" else "not listening"} ($why)"
        if (state != lastWakeState) {
            lastWakeState = state
            JarvisLog.i(TAG, "phrase listener: $state")
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
        if (session != null) {
            alreadyAwake()
            return
        }
        JarvisLog.i(TAG, "wake attempt (${if (wakeForced) "routine" else "phrase"}): ${if (locked()) "locked" else "unlocked"}, " +
            "audio mode ${getSystemService(AudioManager::class.java).mode}")
        val skip = when {
            VoiceChatService.running -> "voice chat is open"
            (if (wakeForced) phoneCall() else inCall()) -> "in a call"
            jarvis.apiKey.value == null -> "no API key"
            else -> null
        }
        if (skip != null) {
            JarvisLog.i(TAG, "wake skipped: $skip")
            return
        }
        startSession(
            "The user said \"Jarvis wake up\" (the phone may be locked or in another app); you have already " +
                "answered them. Listen and help, briefly. They may ask you to send a message: send it right away " +
                "with send_message unless it's unclear.",
            cue = null,
            countsForCooldown = false,
            source = if (wakeForced) "routine wake" else "wake phrase",
        )
        val t = token
        update("Listening")
        chime(Chime.Kind.WAKE) {
            if (t === token) clips.play(Clips.Slot.WAKE) { if (t === token) main.post(startListening) }
        }
    }

    /**
     * "Jarvis wake up" while it's already up: say so (two beeps, "Already awake, Sir"), so you're never left
     * wondering which it was. Stops whatever it was saying and listens afresh.
     */
    private fun alreadyAwake() {
        val s = session ?: return
        JarvisLog.i(TAG, "wake: already awake")
        s.interrupt()
        main.removeCallbacks(listenTimeout)
        main.removeCallbacks(startListening)
        // Mic off while it answers, so Gemini doesn't hear the clip and reply to it.
        if (micOn) {
            s.stopMic()
            micOn = false
        }
        main.removeCallbacks(endSession)
        main.postDelayed(endSession, MAX_SESSION_MS)
        update("Listening")
        val t = token
        chime(Chime.Kind.ALREADY) {
            if (t === token) clips.play(Clips.Slot.ALREADY_AWAKE) { if (t === token) main.post(startListening) }
        }
    }

    /** Plays [kind] unless chimes are turned off; [done] runs either way. */
    private fun chime(kind: Chime.Kind, done: () -> Unit = {}) {
        if (jarvis.active.chimes) Chime.play(kind, done) else done()
    }

    /**
     * A wake from a Google Home routine arrives while Google is still answering ("OK", a chime) and may still
     * hold the audio. Wait until it's quiet (up to [ROUTINE_QUIET_MAX_MS]) so Jarvis's own reply is heard
     * rather than lost under, or ducked by, Google's.
     */
    private fun wakeAfterGoogle() {
        main.removeCallbacks(routineWait)
        routineWaitStart = System.currentTimeMillis()
        JarvisLog.i(TAG, "routine wake: waiting for Google to finish")
        main.post(routineWait)
    }

    private var routineWaitStart = 0L
    private val routineWait: Runnable = object : Runnable {
        override fun run() {
            val waited = System.currentTimeMillis() - routineWaitStart
            val audio = getSystemService(AudioManager::class.java)
            // Music or a game playing on doesn't count; Google's voice, chimes and notifications do.
            val talking = audio.activePlaybackConfigurations.any {
                it.audioAttributes.usage !in setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
            }
            val busy = audio.mode != AudioManager.MODE_NORMAL || talking
            if (busy && waited < ROUTINE_QUIET_MAX_MS) {
                main.postDelayed(this, ROUTINE_POLL_MS)
                return
            }
            JarvisLog.i(TAG, "routine wake after ${waited} ms" + if (busy) " (still busy: mode ${audio.mode})" else "")
            // You asked out loud: Google's voice session leaving a voice-chat mode set mustn't block it.
            wakeForced = true
            wakePhrase()
            wakeForced = false
        }
    }
    private var wakeForced = false

    /** "Jarvis power down": stop at once, even mid-sentence, acknowledge, and go back to waiting for the phrase. */
    private fun powerDown() {
        when {
            session != null -> {
                session?.interrupt()
                end(sound = false, reason = "power down")
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

    /** A phone call ringing or in progress (not just an app holding a voice-chat audio mode). */
    private fun phoneCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
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
            ACTION_WAKE -> if (intent.getBooleanExtra(EXTRA_ROUTINE, false)) wakeAfterGoogle() else wakePhrase()
        }
        // Not sticky: a restart by the system would come from the background, where the mic is not allowed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(recheckWake)
        main.removeCallbacks(routineWait)
        if (instance === this) try { unregisterReceiver(power); unregisterReceiver(screen) } catch (e: IllegalArgumentException) { }
        wake?.stop()
        wake = null
        clips.stop()
        Chime.stop()
        end(sound = false, reason = "service stopped")
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
        JarvisLog.d(TAG) { "app opened: $pkgOrName" }
        startSession(jarvis.active.appOpenedPrompt(name, now()), ActiveMode.cue(name), source = "app opened")
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
        startSession(Prompts.relay(pending, now()), "(Messages just arrived. Relay them now.)", source = "incoming message")
    }

    /** Talk from the notification or Quick Settings tile, also on the lock screen: Jarvis answers, then listens. */
    private fun talk() {
        if (session != null || jarvis.apiKey.value == null) return
        startSession(
            "The user tapped Talk (the phone may be locked or in another app) because they want to talk to you. " +
                "Converse naturally and briefly.",
            "(The user wants to talk. Answer in two or three words, e.g. \"Yes, Sir?\", then wait for them.)",
            countsForCooldown = false,
            source = "talk",
        )
    }

    private fun now() = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault()).format(Date())

    /** [cue] makes Jarvis speak first; null when it already answered (a clip) and should just listen. */
    private fun startSession(prompt: String, cue: String?, countsForCooldown: Boolean = true, source: String) {
        if (jarvis.apiKey.value == null) return
        JarvisLog.i(TAG, "session start: $source, ${if (locked()) "locked" else "unlocked"}")
        sessionStartedAt = System.currentTimeMillis()
        userTurnOpen = false
        jarvisSpoke = false
        replyWaitUsed = false
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
        otherAudio.start()
        refreshWake() // "only during conversations": start listening for "Jarvis power down"
    }

    private fun allowedNow(pkg: String, ignoreCooldown: Boolean = false, allowLocked: Boolean = false): Boolean {
        fun no(reason: String): Boolean {
            JarvisLog.i(TAG, "not speaking up: $reason")
            return false
        }
        if (!jarvis.active.allowed(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), ignoreCooldown)) return no("cooldown, quiet hours or turned off")
        // Android-only checks: calls, silent mode, Do Not Disturb, lock screen, the phone app itself.
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return no("in a call (audio mode ${audio.mode})")
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return no("phone is on silent")
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return no("Do Not Disturb is on") // Do Not Disturb
        if (!allowLocked && locked()) return no("phone is locked")
        val dialer = try { getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (e: Exception) { null }
        return if (pkg == dialer) no("the phone app is open") else true
    }

    private inner class Callbacks(private val t: Any) : LiveSession.Listener {
        private fun live() = t === token
        override fun onReady() {}

        override fun onUserText(delta: String) {
            if (!live()) return
            writer?.userDelta(delta)
            // They're talking: keep the session, but don't hang forever if Jarvis never answers. This wait is
            // given once per answer window: more speech afterwards (someone else's chat nearby) doesn't keep the
            // session alive, so it still ends when nothing is said to Jarvis.
            userTurnOpen = true
            if (!replyWaitUsed) {
                replyWaitUsed = true
                main.removeCallbacks(listenTimeout)
                main.postDelayed(listenTimeout, REPLY_WAIT_MS)
            }
            update("Listening")
        }

        override fun onJarvisText(delta: String) {
            if (!live()) return
            writer?.jarvisDelta(delta)
            jarvisSpoke = true
            main.removeCallbacks(listenTimeout)
            update("Speaking")
        }

        override fun onTurnComplete() {
            if (!live()) return
            writer?.closeTurn()
            val heardUser = userTurnOpen
            val addressed = jarvisSpoke
            userTurnOpen = false
            jarvisSpoke = false
            val wait = session?.playbackRemainingMs() ?: 0
            if (endAfterTurn) {
                // It said goodbye: end once that has played.
                main.removeCallbacks(listenTimeout)
                main.removeCallbacks(startListening)
                main.postDelayed(endAfterGoodbye, wait + 300)
                return
            }
            if (heardUser && !addressed) {
                // Speech that wasn't for Jarvis (Gemini stayed quiet): no fresh answer window for it. The one that
                // is running ends the session if nobody speaks to Jarvis.
                JarvisLog.i(TAG, "heard speech not meant for Jarvis: answer window not extended")
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
            main.postDelayed(endAfterGoodbye, END_FALLBACK_MS)
        }

        override fun onClosed(reason: String?) {
            if (!live()) return
            if (reason != null) JarvisLog.w(TAG, "session closed: $reason")
            end(reason = if (reason != null) "connection closed" else "closed by Gemini")
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
            end(reason = "nobody spoke to Jarvis in time")
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
        replyWaitUsed = false
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, listenMs)
    }

    /** Ends the conversation; [sound] plays the falling "stopped listening" tone (not on power down / shutdown). */
    private fun end(sound: Boolean = true, reason: String = "ended") {
        if (session != null) JarvisLog.i(TAG, "session end: $reason, after ${(System.currentTimeMillis() - sessionStartedAt) / 1000} s")
        if (sound && session != null && !destroyed) chime(Chime.Kind.SLEEP)
        otherAudio.stop()
        main.removeCallbacks(endAfterGoodbye)
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
        private const val TAG = "Active"
        private const val CHANNEL = "active_mode"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.kbhatiya999.jarvis.STOP"
        private const val ACTION_TEST = "com.kbhatiya999.jarvis.TEST"
        private const val ACTION_TALK = "com.kbhatiya999.jarvis.TALK"
        private const val ACTION_REFRESH = "com.kbhatiya999.jarvis.REFRESH_WAKE"
        private const val ACTION_WAKE = "com.kbhatiya999.jarvis.WAKE"
        private const val EXTRA_ROUTINE = "routine"
        private const val ROUTINE_POLL_MS = 250L
        private const val ROUTINE_QUIET_MAX_MS = 6_000L
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
        fun wake(context: Context, fromRoutine: Boolean = false) {
            context.startService(
                Intent(context, ActiveModeService::class.java).setAction(ACTION_WAKE).putExtra(EXTRA_ROUTINE, fromRoutine)
            )
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
