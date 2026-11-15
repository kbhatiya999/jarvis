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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kbhatiya999.jarvis.core.ActiveMode

private val LISTEN_PRESETS = listOf(3, 5, 8, 12, 20)

private val INTERRUPT_OPTIONS = listOf(
    Triple(ActiveMode.INTERRUPT_NAME, "When I say \"Jarvis\"", "Coughs and the TV can't cut it off"),
    Triple(ActiveMode.INTERRUPT_ANY, "Any time I talk", "Any sound of talking stops it"),
    Triple(ActiveMode.INTERRUPT_NEVER, "Never", "Jarvis always finishes; the mic waits until it's done"),
)

private val COOLDOWN_PRESETS = listOf(
    0 to "Every app open (no cooldown)",
    30 to "30 seconds",
    300 to "5 minutes",
    900 to "15 minutes",
    1800 to "30 minutes",
    3600 to "1 hour",
)

private fun formatSeconds(s: Int) = when {
    s % 3600 == 0 -> "${s / 3600} h"
    s % 60 == 0 -> "${s / 60} min"
    else -> "$s sec"
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun ActiveScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    val settings = vm.settings
    var enabled by remember { mutableStateOf(settings.activeEnabled && ActiveModeService.running) }
    var watcherOn by remember { mutableStateOf(AppWatcher.isEnabled(context)) }
    var cooldown by remember { mutableStateOf(settings.cooldownSeconds) }
    var custom by remember { mutableStateOf(cooldown !in COOLDOWN_PRESETS.map { it.first }) }
    var customText by remember { mutableStateOf(if (custom) cooldown.toString() else "") }
    var quiet by remember { mutableStateOf(settings.quietHours) }
    var listen by remember { mutableStateOf(settings.listenSeconds) }
    var locked by remember { mutableStateOf(settings.speakWhenLocked) }
    var interruptMode by remember { mutableStateOf(settings.interruptMode) }
    var restAtNight by remember { mutableStateOf(vm.jarvis.active.wakePauseAtNight) }
    var minBattery by remember { mutableStateOf(vm.jarvis.active.wakeMinBattery) }

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

    BackHandler { vm.screen = null }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Active mode", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "When you open an app, Jarvis speaks up on its own with your reminders (Settings › Reminders) and " +
                "relays messages from other Jarvises. It then listens for your answer; if you say nothing, it stops.",
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
        Text("How often Jarvis may speak up", fontWeight = FontWeight.Bold)
        COOLDOWN_PRESETS.forEach { (secs, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = !custom && cooldown == secs,
                    onClick = { custom = false; cooldown = secs; settings.cooldownSeconds = secs },
                )
                Text(label)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = custom, onClick = {
                custom = true
                customText.toIntOrNull()?.let { cooldown = it; settings.cooldownSeconds = it }
            })
            Text("Custom")
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = customText,
                onValueChange = { v ->
                    customText = v.filter { it.isDigit() }.take(6)
                    custom = true
                    customText.toIntOrNull()?.let { cooldown = it; settings.cooldownSeconds = it }
                },
                placeholder = { Text("e.g. 90") },
                suffix = { Text("sec") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
        }
        Text(
            if (cooldown == 0) "Speaks on every app open (once the previous one has finished)."
            else "At most once every ${formatSeconds(cooldown)}.",
            fontSize = 13.sp, color = Color.Gray,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quiet hours", fontWeight = FontWeight.Bold)
                Text("Silent from 11 pm to 7 am", fontSize = 13.sp, color = Color.Gray)
            }
            Switch(checked = quiet, onCheckedChange = { quiet = it; settings.quietHours = it })
        }
        Text("Also stays silent during calls and in Do Not Disturb or silent mode.", fontSize = 13.sp, color = Color.Gray)

        HorizontalDivider()
        Text("Answering Jarvis", fontWeight = FontWeight.Bold)
        Text(
            "After Jarvis has finished speaking, it listens for your answer: say a reply to a message, or anything else. " +
                "No need to touch the phone.",
            fontSize = 13.sp, color = Color.Gray,
        )
        Text("Listen for")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LISTEN_PRESETS.forEach { secs ->
                FilterChip(
                    selected = listen == secs,
                    onClick = { listen = secs; settings.listenSeconds = secs },
                    label = { Text("$secs s") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On the lock screen", fontWeight = FontWeight.Bold)
                Text("Say messages from other Jarvises, and hear your reply, while the phone is locked", fontSize = 13.sp, color = Color.Gray)
            }
            Switch(checked = locked, onCheckedChange = { locked = it; settings.speakWhenLocked = it })
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice commands", fontWeight = FontWeight.Bold)
                Text(
                    "Say \"Jarvis wake up\" to talk (also on the lock screen) and \"Jarvis power down\" to stop it " +
                        "at once. Heard on the phone itself, offline; needs active mode on. Downloads a 40 MB speech " +
                        "model once. Android shows the mic dot while it listens.",
                    fontSize = 13.sp, color = Color.Gray,
                )
            }
            Switch(checked = vm.wakeOn, enabled = vm.wakeDownload == null, onCheckedChange = { vm.setWake(it) })
        }
        vm.wakeDownload?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            Text("Downloading the speech model… ${(p * 100).toInt()}%", fontSize = 13.sp, color = Color.Gray)
        }
        vm.wakeError?.let { Text(it, fontSize = 13.sp, color = Color(0xFFE57373)) }
        if (vm.wakeOn) {
            OutlinedButton(onClick = { vm.screen = "voice" }) { Text("Jarvis's replies") }
            Text("Save battery", fontWeight = FontWeight.Bold)
            Text("Voice commands rest at these times, and always listen while charging. Battery Saver also rests them.", fontSize = 13.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rest at night (11 pm–7 am)", Modifier.weight(1f))
                Switch(checked = restAtNight, onCheckedChange = {
                    restAtNight = it
                    vm.jarvis.active.wakePauseAtNight = it
                    ActiveModeService.refreshWake(context)
                })
            }
            Text("Rest when battery is below")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 15, 20, 30, 50).forEach { pct ->
                    FilterChip(
                        selected = minBattery == pct,
                        onClick = {
                            minBattery = pct
                            vm.jarvis.active.wakeMinBattery = pct
                            ActiveModeService.refreshWake(context)
                        },
                        label = { Text(if (pct == 0) "Never" else "$pct%") },
                    )
                }
            }
        }

        Text("Cutting Jarvis off", fontWeight = FontWeight.Bold)
        INTERRUPT_OPTIONS.forEach { (mode, label, hint) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = interruptMode == mode, onClick = { interruptMode = mode; settings.interruptMode = mode })
                Column {
                    Text(label)
                    Text(hint, fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
        if (interruptMode == ActiveMode.INTERRUPT_NAME && !vm.wakeOn) {
            Text("Needs voice commands on; until then Jarvis always finishes.", fontSize = 13.sp, color = Color(0xFFFFB74D))
        }
        Text("\"Jarvis power down\" always stops it.", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
    }
}
