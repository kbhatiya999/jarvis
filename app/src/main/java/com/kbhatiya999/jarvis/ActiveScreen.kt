package com.kbhatiya999.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun ActiveScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    val settings = vm.settings
    var enabled by remember { mutableStateOf(settings.activeEnabled && ActiveModeService.running) }
    var watcherOn by remember { mutableStateOf(AppWatcher.isEnabled(context)) }
    var cooldown by remember { mutableStateOf(settings.cooldownMinutes) }
    var quiet by remember { mutableStateOf(settings.quietHours) }

    // Re-check after coming back from system settings.
    LifecycleResumeEffect(Unit) {
        watcherOn = AppWatcher.isEnabled(context)
        enabled = settings.activeEnabled && ActiveModeService.running
        onPauseOrDispose {}
    }

    fun turnOn() {
        settings.activeEnabled = true
        ActiveModeService.start(context)
        enabled = true
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || granted(context, Manifest.permission.RECORD_AUDIO)) turnOn()
    }

    BackHandler { vm.showActiveSettings = false }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.showActiveSettings = false }) { Text("Back") }
            Text("Active mode", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "When you open an app, Jarvis speaks up on its own with reminders: water, junk and oily food, " +
                "staying warm, and not getting hurt. It then listens for 5 seconds; if you say nothing, it stops.",
            color = Color.LightGray,
        )

        HorizontalDivider()
        Text("1. Let Jarvis see which app is open", fontWeight = FontWeight.Bold)
        Text(if (watcherOn) "App watcher is on." else "App watcher is off.", color = if (watcherOn) Color(0xFF81C784) else Color(0xFFE57373))
        if (!watcherOn) {
            Text(
                "Turn on \"Jarvis active mode\" in Accessibility settings. If it's greyed out (apps installed " +
                    "outside the Play Store), open App info, tap ⋮, choose \"Allow restricted settings\", then try again.",
                fontSize = 13.sp, color = Color.Gray,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Accessibility")
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("App info") }
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("2. Active mode", fontWeight = FontWeight.Bold)
                Text("Shows a \"Jarvis active\" notification while on.", fontSize = 13.sp, color = Color.Gray)
            }
            Switch(checked = enabled, onCheckedChange = { on ->
                if (on) {
                    val needed = buildList {
                        if (!granted(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (needed.isEmpty()) turnOn() else permissions.launch(needed.toTypedArray())
                } else {
                    settings.activeEnabled = false
                    ActiveModeService.stop(context)
                    enabled = false
                }
            })
        }
        if (enabled) {
            OutlinedButton(onClick = { ActiveModeService.test(context) }) { Text("Test now") }
        }

        HorizontalDivider()
        Text("Speak up at most every", fontWeight = FontWeight.Bold)
        listOf(5, 15, 30, 60).forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = cooldown == m, onClick = { cooldown = m; settings.cooldownMinutes = m })
                Text("$m minutes")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quiet hours", fontWeight = FontWeight.Bold)
                Text("Silent from 11 pm to 7 am", fontSize = 13.sp, color = Color.Gray)
            }
            Switch(checked = quiet, onCheckedChange = { quiet = it; settings.quietHours = it })
        }
        Text(
            "Also stays silent during calls, in Do Not Disturb or silent mode, and on the lock screen.",
            fontSize = 13.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(24.dp))
    }
}
