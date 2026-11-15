package com.kbhatiya999.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Jarvis Talk" in the launcher, so "Hey Google, open Jarvis Talk" wakes Jarvis in the background: Google's own
 * wake word does the listening (no battery for us), nothing shows, and whatever app you're in stays on screen.
 * With active mode on, its service answers and listens (also over the lock screen, if Google opens it there).
 * Otherwise it opens Jarvis and starts listening.
 */
class TalkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ActiveModeService.running) {
            ActiveModeService.talk(this)
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_TALK, true)
            )
        }
        finish()
    }
}
