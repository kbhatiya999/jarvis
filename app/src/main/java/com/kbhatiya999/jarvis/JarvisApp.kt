package com.kbhatiya999.jarvis

import android.app.Application

/** Starts the log before anything else, so services, listeners and the UI all write to the same one. */
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
