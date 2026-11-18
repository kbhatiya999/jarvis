package com.kbhatiya999.jarvis

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kbhatiya999.jarvis.core.JarvisLog

/** One line of the self-check: [ok] true = tick, false = cross, null = just information. */
data class Check(val title: String, val ok: Boolean?, val detail: String, val fixLabel: String? = null, val fix: (() -> Unit)? = null)

/**
 * Tests each link the "Hey Google" wake depends on, so a user can see which one is broken and how to fix it.
 * The results go to the shared log (tag "Check") so a shared log shows them too.
 */
object SelfCheck {
    private const val GEMINI = "com.google.android.apps.bard"
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val HOME = "com.google.android.apps.chromecast.app"
    private const val CHANNEL = "self_check"
    const val TEST_ID = 9001

    fun run(context: Context): List<Check> {
        val checks = listOf(
            micCheck(context), serviceCheck(), accessCheck(context), batteryCheck(context), assistantCheck(context),
            googleCheck(context), homeCheck(context),
        )
        JarvisLog.i("Check", checks.joinToString("; ") { "${it.title}=${when (it.ok) { true -> "ok"; false -> "FAIL"; null -> "info" }}" })
        return checks
    }

    private fun micCheck(c: Context): Check {
        val ok = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return Check("Microphone permission", ok, if (ok) "Allowed." else "Jarvis can't hear you without it.", if (ok) null else "Open app settings") {
            c.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
        }
    }

    private fun serviceCheck(): Check {
        val ok = ActiveModeService.running
        return Check("Active mode is running", ok, if (ok) "Jarvis is on and holding the microphone service." else
            "Turn Active mode on (Settings › Active mode). If you did and it stops, your phone is closing it: see the battery line.")
    }

    private fun accessCheck(c: Context): Check {
        val on = RoutineListener.hasAccess(c)
        val live = RoutineListener.connected
        val detail = when {
            !on -> "Off. The Hey Google routine can't wake Jarvis without it."
            !live -> "On, but the system has disconnected it. Switch it off and on again; some phones do this after a while."
            else -> "On and connected."
        }
        return Check("Notification access", on && live, detail, if (on && live) null else "Open notification access") { openNotificationAccess(c) }
    }

    private fun batteryCheck(c: Context): Check {
        val ok = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        return Check("Battery optimisation off", ok,
            if (ok) "Jarvis is not restricted." else "The phone may close Jarvis or its notification access in the background. Set Jarvis to \"Unrestricted\" or \"Don't optimise\".",
            if (ok) null else "Open battery settings") {
            try { c.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e: Exception) { c.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))) }
        }
    }

    private fun assistantCheck(c: Context): Check {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Check("Jarvis as the phone's assistant", null, "Needs Android 12 or newer. Use the Hey Google routine or \"All the time\" listening.")
        }
        val ok = JarvisAssistant.isDefault(c)
        return Check("Jarvis as the phone's assistant", ok,
            if (ok) "Holding the power button or swiping from a bottom corner wakes Jarvis, with no Google needed, also on the lock screen."
            else "Optional but the most reliable wake: choose Jarvis as the Digital assistant app. Then the power-button hold or corner swipe wakes it without Google.",
            if (ok) null else "Choose Jarvis") {
            try { c.startActivity(JarvisAssistant.settingsIntent()) }
            catch (e: Exception) { c.startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
        }
    }

    private fun googleCheck(c: Context): Check {
        val gemini = installed(c, GEMINI)
        val google = installed(c, GOOGLE_APP)
        val detail = when {
            gemini -> "Gemini is installed. In Gemini, turn on the Google Home connection (and lock-screen access) for the routine to run."
            google -> "Google Assistant (Google app) is installed."
            else -> "Neither Gemini nor Google Assistant was found: \"Hey Google\" can't start the wake. Use \"All the time\" listening instead."
        }
        JarvisLog.i("Check", "assistant apps: gemini=$gemini google=$google")
        return Check("Gemini or Assistant", gemini || google, detail)
    }

    private fun homeCheck(c: Context): Check {
        val ok = installed(c, HOME)
        return Check("Google Home app", ok, if (ok) "Installed." else "The wake routine is made in Google Home, which isn't installed.")
    }

    private fun installed(c: Context, pkg: String) = try {
        c.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    /** Posts a "Jarvis wake up" notification from Jarvis itself; [RoutineListener] must see it (and wakes nothing). */
    fun sendTest(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            JarvisLog.w("Check", "test notification: notifications are off for Jarvis")
            return false
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Self-check", NotificationManager.IMPORTANCE_LOW))
        nm.notify(TEST_ID, NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis wake up")
            .setContentText("Self-check test: nothing is woken")
            .setAutoCancel(true)
            .build())
        JarvisLog.i("Check", "test notification sent")
        return true
    }

    fun cleanUpTest(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(TEST_ID)
}
