package com.kbhatiya999.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps a voice chat going when the screen locks or you switch apps. Android only lets an app use the
 * mic from the background while it runs a microphone foreground service, so this runs (with a
 * "Talking to Jarvis" notification) for as long as the chat's mic is on. The session itself lives in
 * [JarvisViewModel]; this service only holds the permission and offers Stop.
 */
class VoiceChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Voice chat", NotificationManager.IMPORTANCE_LOW))
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            running = true
        } catch (e: Exception) {
            // Only possible from the background; the chat then simply stops hearing you when locked.
            Log.w("JarvisVoice", "Cannot keep the mic in the background", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceChatService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Talking to Jarvis")
            .setContentText("Jarvis keeps listening while the phone is locked.")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Stop is reachable from the lock screen
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "voice_chat"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.kbhatiya999.jarvis.VOICE_STOP"

        /** A voice chat in the app has the mic on. */
        @Volatile var running = false
            private set

        /** Set by the chat: Stop on the notification ends the voice chat. */
        var onStopRequested: (() -> Unit)? = null

        /** Call while the app is in front (Android refuses a mic service started from the background). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceChatService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceChatService::class.java))
        }
    }
}
