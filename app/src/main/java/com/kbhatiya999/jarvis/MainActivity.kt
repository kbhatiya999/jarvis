package com.kbhatiya999.jarvis

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 36f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(version)
        })
    }
}
