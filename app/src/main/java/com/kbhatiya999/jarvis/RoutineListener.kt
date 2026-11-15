package com.kbhatiya999.jarvis

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Wakes Jarvis from a Google Home routine, so "Hey Google, Jarvis wake up" works on the lock screen (Gemini won't
 * open apps while locked, but it runs routines). The routine's action sends a notification "Jarvis wake up" to the
 * phone; this sees it, removes it and wakes Jarvis like the spoken phrase. It only looks at notifications from
 * Google's apps, and only acts on that one; nothing is stored or sent anywhere.
 */
class RoutineListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .joinToString(" ") { extras.getCharSequence(it)?.toString().orEmpty() }
            .lowercase()
        if (!isTrigger(text)) return
        if (sbn.packageName !in GOOGLE_APPS) {
            Log.i(TAG, "Ignoring a \"Jarvis wake up\" notification from ${sbn.packageName}")
            return
        }
        Log.i(TAG, "Routine trigger from ${sbn.packageName}")
        try { cancelNotification(sbn.key) } catch (e: Exception) { }
        val now = System.currentTimeMillis()
        if (now - lastWake < DEDUPE_MS) return // the same routine can post twice (phone + watch, updates)
        lastWake = now
        if (ActiveModeService.running) ActiveModeService.wake(this)
        else Log.i(TAG, "Active mode is off: nothing to wake")
    }

    companion object {
        private const val TAG = "JarvisRoutine"
        private const val DEDUPE_MS = 5_000L
        private var lastWake = 0L

        /** Google Home, the Google app (Assistant) and Gemini: where routine notifications come from. */
        private val GOOGLE_APPS = setOf(
            "com.google.android.apps.chromecast.app",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard",
        )

        /** "Jarvis wake up", "Wake up, Jarvis", "jarvis: wake up"... */
        fun isTrigger(text: String): Boolean {
            val t = text.lowercase()
            return "jarvis" in t && Regex("\\bwake\\s*up\\b").containsMatchIn(t)
        }

        fun hasAccess(context: Context) =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, RoutineListener::class.java)
    }
}
