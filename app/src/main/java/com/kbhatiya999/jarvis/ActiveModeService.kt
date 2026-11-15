package com.kbhatiya999.jarvis

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
 * Jarvis speaks up on its own, then listens for 5 seconds; silence ends the session.
 */
class ActiveModeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var session: LiveSession? = null
    private var token = Any()
    private var writer = Conversation.Writer()
    private var micOn = false
    private var focus: AudioFocusRequest? = null
    private val endSession = Runnable { end() }
    private var streaming = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        Conversation.init(this)
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
        Relay.acquireStream(this)
        streaming = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.activeEnabled = false
                stopSelf()
            }
            ACTION_TEST -> appOpened("Jarvis test", force = true)
        }
        // Not sticky: a restart by the system would come from the background, where the mic is not allowed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        end()
        if (streaming) Relay.releaseStream()
        streaming = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun appOpened(pkgOrName: String, force: Boolean = false) {
        if (session != null) return
        if (!force && !allowedNow(pkgOrName)) return
        if (ApiKeyStore(this).get() == null) return
        val name = appLabel(pkgOrName)
        val pending = Inbox.takePending(this)
        startSession(Prompts.proactive(name, now(), pending), "(The user has just opened $name. Speak up now.)")
    }

    /** A message from another Jarvis arrived: say it now if that's appropriate, else it waits for the next app open. */
    private fun messageArrived() {
        val s = session
        if (s != null) {
            val pending = Inbox.takePending(this)
            if (pending.isNotEmpty()) s.sendText("(New message just arrived: ${pending.joinToString(" | ")}. Relay it to the user now.)")
            return
        }
        if (!allowedNow("", ignoreCooldown = true) || ApiKeyStore(this).get() == null) return
        val pending = Inbox.takePending(this)
        if (pending.isEmpty()) return
        startSession(Prompts.relay(pending, now()), "(Messages just arrived. Relay them now.)")
    }

    private fun now() = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault()).format(Date())

    private fun startSession(prompt: String, cue: String) {
        val key = ApiKeyStore(this).get() ?: return
        settings.lastPromptAt = System.currentTimeMillis()
        val t = Any().also { token = it }
        writer = Conversation.Writer()
        micOn = false
        requestFocus()
        update("Speaking")
        val tools = { n: String, a: org.json.JSONObject, r: (org.json.JSONObject) -> Unit -> JarvisTools.call(this, n, a, r) }
        session = LiveSession(key, Conversation.systemPrompt(prompt), Callbacks(t), tools).also {
            it.connect()
            // Cue for Jarvis to start talking. Not stored in memory.
            it.sendText(cue)
        }
        main.postDelayed(endSession, MAX_SESSION_MS)
    }

    private fun allowedNow(pkg: String, ignoreCooldown: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!ignoreCooldown && now - settings.lastPromptAt < settings.cooldownSeconds * 1000L) return false
        if (settings.quietHours) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h >= 23 || h < 7) return false
        }
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return false
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false // Do Not Disturb
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val dialer = try { getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (e: Exception) { null }
        return pkg != dialer
    }

    private inner class Callbacks(private val t: Any) : LiveSession.Listener {
        private fun live() = t === token
        override fun onReady() {}

        override fun onUserText(delta: String) {
            if (!live()) return
            writer.userDelta(delta)
            // They're talking: keep the session, but don't hang forever if Jarvis never answers.
            main.removeCallbacks(listenTimeout)
            main.postDelayed(listenTimeout, REPLY_WAIT_MS)
            update("Listening")
        }

        override fun onJarvisText(delta: String) {
            if (!live()) return
            writer.jarvisDelta(delta)
            main.removeCallbacks(listenTimeout)
            update("Speaking")
        }

        override fun onTurnComplete() {
            if (!live()) return
            writer.closeTurn()
            // Once Jarvis has finished speaking, open the mic and give the user 5 seconds.
            val wait = session?.playbackRemainingMs() ?: 0
            main.removeCallbacks(startListening)
            main.postDelayed(startListening, wait + 300)
        }

        override fun onInterrupted() {
            if (live()) writer.closeTurn()
        }

        override fun onClosed(reason: String?) {
            if (!live()) return
            if (reason != null) Log.w(TAG, "Session closed: $reason")
            end()
        }
    }

    private val listenTimeout = Runnable { end() }

    private val startListening = Runnable {
        val s = session ?: return@Runnable
        if (!micOn) {
            s.startMic()
            micOn = true
        }
        update("Listening")
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, LISTEN_MS)
    }

    private fun end() {
        main.removeCallbacks(endSession)
        main.removeCallbacks(listenTimeout)
        main.removeCallbacks(startListening)
        token = Any()
        session?.close()
        session = null
        micOn = false
        writer.closeTurn()
        abandonFocus()
        if (instance === this) update("Watching for app launches")
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis active")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "JarvisActive"
        private const val CHANNEL = "active_mode"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.kbhatiya999.jarvis.STOP"
        private const val ACTION_TEST = "com.kbhatiya999.jarvis.TEST"
        private const val LISTEN_MS = 5_000L
        private const val REPLY_WAIT_MS = 10_000L
        private const val MAX_SESSION_MS = 3 * 60_000L

        @Volatile
        private var instance: ActiveModeService? = null

        val running get() = instance != null

        /** Called by AppWatcher on the main thread. */
        fun onAppOpened(pkg: String) {
            instance?.appOpened(pkg)
        }

        /** Called by Inbox on the main thread. */
        fun onMessageArrived() {
            instance?.messageArrived()
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ActiveModeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActiveModeService::class.java))
        }

        fun test(context: Context) {
            context.startService(Intent(context, ActiveModeService::class.java).setAction(ACTION_TEST))
        }
    }
}
