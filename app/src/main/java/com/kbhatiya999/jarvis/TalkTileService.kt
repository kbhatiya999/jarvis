package com.kbhatiya999.jarvis

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Talk to Jarvis". With active mode on, its service already holds the mic, so
 * Jarvis answers right away, even on the lock screen. Otherwise it opens Jarvis (after unlocking)
 * and starts listening.
 */
class TalkTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = if (ActiveModeService.running) "Works when locked" else "Opens Jarvis"
            updateTile()
        }
    }

    override fun onClick() {
        if (ActiveModeService.running) {
            ActiveModeService.talk(this)
            return
        }
        if (isLocked) unlockAndRun { openAndTalk() } else openAndTalk()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAndTalk() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TALK, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
