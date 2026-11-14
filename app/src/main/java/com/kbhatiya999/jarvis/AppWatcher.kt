package com.kbhatiya999.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings.Secure
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/** Tells active mode whenever a different app comes to the foreground. */
class AppWatcher : AccessibilityService() {
    private var lastPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in overlays()) return // keyboard, status bar, Jarvis: not "opening an app"
        if (pkg == lastPackage) return
        lastPackage = pkg
        if (pkg == homePackage()) return // going home doesn't count, but reopening an app after it does
        ActiveModeService.onAppOpened(pkg)
    }

    override fun onInterrupt() {}

    private fun overlays(): Set<String> {
        val imm = getSystemService(InputMethodManager::class.java)
        val keyboards = imm?.enabledInputMethodList?.map { it.packageName }.orEmpty()
        return keyboards.toSet() + setOf(packageName, "com.android.systemui", "android")
    }

    private fun homePackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, AppWatcher::class.java).flattenToString()
            val enabled = Secure.getString(context.contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
