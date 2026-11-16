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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

private val Ok = Color(0xFF81C784)
private val Missing = Color(0xFFE57373)
private val Warn = Color(0xFFFFB74D)
private val Hint = Color.Gray

private val LISTEN_PRESETS = listOf(3, 5, 8, 12, 20)
private val COOLDOWN_PRESETS = listOf(0 to "Every open", 30 to "30 s", 300 to "5 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 h")
private val BATTERY_PRESETS = listOf(0, 15, 20, 30, 50)

/** The words to type in Google Home: the automation's name, the "Hey Google" phrase and the notification text. */
private const val ROUTINE_PHRASE = "Jarvis wake up"

/**
 * Google Home's automation editor, in its order and with its labels. Steps with [RoutineStep.copy] get a Copy
 * button for the text to type there.
 */
private data class RoutineStep(val text: String, val copy: String? = null)

private val ROUTINE_STEPS = listOf(
    RoutineStep("Open the Google Home app and tap + › Automation."),
    RoutineStep("Name: type \"$ROUTINE_PHRASE\" (only you see it).", ROUTINE_PHRASE),
    RoutineStep("Starter › Hey Google › type the phrase \"$ROUTINE_PHRASE\".", ROUTINE_PHRASE),
    RoutineStep(
        "Actions › Notify › message \"$ROUTINE_PHRASE\". It must contain \"Jarvis\" and \"wake up\"; that's what Jarvis looks for.",
        ROUTINE_PHRASE,
    ),
    RoutineStep("Save. Then lock the phone and say \"Hey Google, $ROUTINE_PHRASE\"."),
)

private val WAKE_OPTIONS = listOf(
    Triple(ActiveMode.WAKE_CONVERSATION, "Only while talking (recommended)", "\"Jarvis power down\" and \"Jarvis…\" to cut it off. Almost no battery"),
    Triple(ActiveMode.WAKE_ALWAYS, "All the time", "Also \"Jarvis wake up\" without Google. About 1% of a CPU core; mic dot always on"),
    Triple(ActiveMode.WAKE_OFF, "Never", "Say \"that's enough\" to end a conversation"),
)

private val INTERRUPT_OPTIONS = listOf(
    Triple(ActiveMode.INTERRUPT_NAME, "When I say \"Jarvis\"", "Coughs and the TV can't cut it off"),
    Triple(ActiveMode.INTERRUPT_ANY, "Any time I talk", "Any sound of talking stops it"),
    Triple(ActiveMode.INTERRUPT_NEVER, "Never", "Jarvis always finishes; the mic waits until it's done"),
)

private fun formatSeconds(s: Int) = when {
    s == 0 -> "every app open"
    s % 3600 == 0 -> "${s / 3600} h"
    s % 60 == 0 -> "${s / 60} min"
    else -> "$s s"
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppInfo(context: Context) = context.startActivity(
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
)

private fun openNotificationAccess(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, RoutineListener.component(context).flattenToString())
    } else {
        Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    try { context.startActivity(intent) } catch (e: Exception) {
        context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

/**
 * Active mode settings: a setup checklist on top, then one card per topic (waking Jarvis, speaking up,
 * answering). Cards fold away; each shows a one-line summary of its settings when folded.
 */
@Composable
fun ActiveScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    val settings = vm.settings
    val active = vm.jarvis.active
    var enabled by remember { mutableStateOf(settings.activeEnabled && ActiveModeService.running) }
    var watcherOn by remember { mutableStateOf(AppWatcher.isEnabled(context)) }
    var routineOn by remember { mutableStateOf(RoutineListener.hasAccess(context)) }
    var cooldown by remember { mutableStateOf(settings.cooldownSeconds) }
    var custom by remember { mutableStateOf(cooldown !in COOLDOWN_PRESETS.map { it.first }) }
    var customText by remember { mutableStateOf(if (custom) cooldown.toString() else "") }
    var quiet by remember { mutableStateOf(settings.quietHours) }
    var listen by remember { mutableStateOf(settings.listenSeconds) }
    var locked by remember { mutableStateOf(settings.speakWhenLocked) }
    var interruptMode by remember { mutableStateOf(settings.interruptMode) }
    var restAtNight by remember { mutableStateOf(active.wakePauseAtNight) }
    var minBattery by remember { mutableStateOf(active.wakeMinBattery) }
    var chimes by remember { mutableStateOf(active.chimes) }

    // Re-check after coming back from system settings.
    LifecycleResumeEffect(Unit) {
        watcherOn = AppWatcher.isEnabled(context)
        routineOn = RoutineListener.hasAccess(context)
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

    fun setEnabled(on: Boolean) {
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
    }

    val listensForName = vm.wakeMode != ActiveMode.WAKE_OFF
    val modelMissing = listensForName && !vm.wakeModelReady

    BackHandler { vm.screen = null }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Active mode", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { setEnabled(it) })
        }
        Text(
            "Jarvis speaks up when you open an app (your reminders, messages from other Jarvises), wakes when you " +
                "ask, and listens for your answer, also on the lock screen.",
            color = Color.LightGray,
        )

        // --- Setup checklist: only what's still needed shows a button. ---
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Setup", fontWeight = FontWeight.Bold)
                Check(enabled, "Active mode", if (enabled) "On: \"Jarvis active\" notification shows" else "Off") {
                    Button(onClick = { setEnabled(true) }) { Text("Turn on") }
                }
                Check(routineOn, "Notification access", "For \"Hey Google, Jarvis wake up\" on the lock screen") {
                    Button(onClick = { openNotificationAccess(context) }) { Text("Allow") }
                }
                Check(watcherOn, "App watcher", "So Jarvis can speak up when you open an app") {
                    Button(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Allow") }
                }
                if (listensForName) {
                    Check(!modelMissing, "Speech model", "For \"Jarvis power down\" and cutting Jarvis off (40 MB, once)") {
                        if (vm.wakeDownload == null) Button(onClick = { vm.downloadModel() }) { Text("Download") }
                    }
                }
                vm.wakeDownload?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading the speech model… ${(p * 100).toInt()}%", fontSize = 13.sp, color = Hint)
                }
                vm.wakeError?.let { Text(it, fontSize = 13.sp, color = Missing) }
                if (!routineOn || !watcherOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Greyed out? Android blocks these for apps from outside the Play Store: App info › ⋮ › " +
                                "Allow restricted settings, then try again.",
                            fontSize = 13.sp, color = Hint, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { openAppInfo(context) }) { Text("App info") }
                    }
                }
                if (enabled) OutlinedButton(onClick = { ActiveModeService.test(context) }) { Text("Test now") }
            }
        }

        // --- Waking Jarvis ---
        Section(
            "Waking Jarvis",
            summary = "\"Hey Google, Jarvis wake up\"" + if (vm.wakeMode == ActiveMode.WAKE_ALWAYS) " or \"Jarvis wake up\"" else "",
            startOpen = !routineOn,
        ) {
            Text("\"Hey Google, Jarvis wake up\" (recommended)", fontWeight = FontWeight.Bold)
            Text(
                "Works on the lock screen and costs no battery: Google does the listening and runs a Google Home " +
                    "routine, which sends your phone a notification; Jarvis hides it and answers.",
                fontSize = 13.sp, color = Hint,
            )
            Text(
                if (routineOn) "Notification access is on." else "Needs Notification access (Setup, above).",
                fontSize = 13.sp, color = if (routineOn) Ok else Warn,
            )
            var stepsOpen by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { stepsOpen = !stepsOpen }) {
                Text(if (stepsOpen) "Hide routine steps" else "How to make the routine in Google Home")
            }
            AnimatedVisibility(stepsOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ROUTINE_STEPS.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${step.text}", Modifier.weight(1f), fontSize = 13.sp, color = Color.LightGray)
                            step.copy?.let { words -> TextButton(onClick = { copyText(context, words) }) { Text("Copy") } }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Other ways", fontWeight = FontWeight.Bold)
            Text(
                "• Talk on the \"Jarvis active\" notification, or the \"Talk to Jarvis\" Quick Settings tile (both work locked).\n" +
                    "• \"Hey Google, open Jarvis Talk\" (Google asks you to unlock first).",
                fontSize = 13.sp, color = Hint,
            )

            HorizontalDivider()
            Text("Phone listens for \"Jarvis\"", fontWeight = FontWeight.Bold)
            Text("Offline, on the phone; nothing leaves it.", fontSize = 13.sp, color = Hint)
            Choices(WAKE_OPTIONS, vm.wakeMode, enabled = vm.wakeDownload == null) { vm.chooseWakeMode(it) }
            if (vm.wakeMode == ActiveMode.WAKE_ALWAYS && vm.wakeModelReady) {
                Text("Save battery", fontWeight = FontWeight.Bold)
                Text(
                    "At these times it listens only while talking; while charging it always listens. Battery Saver does the same.",
                    fontSize = 13.sp, color = Hint,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rest at night (11 pm–7 am)", Modifier.weight(1f))
                    Switch(checked = restAtNight, onCheckedChange = {
                        restAtNight = it
                        active.wakePauseAtNight = it
                        ActiveModeService.refreshWake(context)
                    })
                }
                Text("Rest when battery is below")
                ChipRow(BATTERY_PRESETS, minBattery, { if (it == 0) "Never" else "$it%" }) {
                    minBattery = it
                    active.wakeMinBattery = it
                    ActiveModeService.refreshWake(context)
                }
            }
            Setting(
                "Wake and sleep sounds",
                "A rising chime when Jarvis wakes, two beeps if it was already awake, a soft falling one when it stops listening",
                chimes,
            ) { chimes = it; active.chimes = it }
            OutlinedButton(onClick = { vm.screen = "voice" }) { Text("Jarvis's replies…") }
        }

        // --- Speaking up on its own ---
        Section(
            "Speaking up",
            summary = "At most ${if (cooldown == 0) "on every app open" else "once every ${formatSeconds(cooldown)}"}" +
                if (quiet) ", quiet at night" else "",
        ) {
            Text("How often, when you open an app", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COOLDOWN_PRESETS.forEach { (secs, label) ->
                    FilterChip(
                        selected = !custom && cooldown == secs,
                        onClick = { custom = false; cooldown = secs; settings.cooldownSeconds = secs },
                        label = { Text(label) },
                    )
                }
                FilterChip(selected = custom, onClick = { custom = true }, label = { Text("Custom") })
            }
            if (custom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { v ->
                        customText = v.filter { it.isDigit() }.take(6)
                        customText.toIntOrNull()?.let { cooldown = it; settings.cooldownSeconds = it }
                    },
                    placeholder = { Text("e.g. 90") },
                    suffix = { Text("sec") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp),
                )
            }
            Setting("Quiet hours", "Silent from 11 pm to 7 am", quiet) { quiet = it; settings.quietHours = it }
            Text("Always silent during calls, in Do Not Disturb and in silent mode.", fontSize = 13.sp, color = Hint)
        }

        // --- Answering ---
        Section(
            "Answering & cutting off",
            summary = "Listens $listen s" + (if (locked) ", also locked" else "") + ", cut off: " +
                INTERRUPT_OPTIONS.first { it.first == interruptMode }.second.replaceFirstChar { it.lowercase() },
        ) {
            Text("Time to start answering", fontWeight = FontWeight.Bold)
            Text(
                "After Jarvis finishes, it waits this long for you to start talking; once you do, it listens until you stop.",
                fontSize = 13.sp, color = Hint,
            )
            ChipRow(LISTEN_PRESETS, listen, { "$it s" }) { listen = it; settings.listenSeconds = it }
            Setting("On the lock screen", "Say messages from other Jarvises, and hear your reply, while locked", locked) {
                locked = it; settings.speakWhenLocked = it
            }

            HorizontalDivider()
            Text("Cutting Jarvis off", fontWeight = FontWeight.Bold)
            Choices(INTERRUPT_OPTIONS, interruptMode) { interruptMode = it; settings.interruptMode = it }
            if (interruptMode == ActiveMode.INTERRUPT_NAME && (!listensForName || !vm.wakeModelReady)) {
                Text("Needs the phone to listen for \"Jarvis\" (Waking Jarvis); until then Jarvis always finishes.", fontSize = 13.sp, color = Warn)
            }
            Text(
                "\"That's enough\" or \"stop\" always end it." +
                    if (listensForName) " \"Jarvis power down\" stops it at once, even mid-sentence." else "",
                fontSize = 13.sp, color = Hint,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A setup item: green tick when done, else what it's for and a button to fix it. */
@Composable
private fun Check(done: Boolean, title: String, hint: String, fix: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "!", color = if (done) Ok else Missing, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, fontSize = 13.sp, color = Hint)
        }
        if (!done) fix()
    }
}

/** A card that folds away, showing [summary] when folded. */
@Composable
private fun Section(title: String, summary: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (!open) Text(summary, fontSize = 13.sp, color = Hint)
            }
            Text(if (open) "▲" else "▼", color = Hint)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun Setting(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(hint, fontSize = 13.sp, color = Hint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choices(options: List<Triple<String, String, String>>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    options.forEach { (value, label, hint) ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == value, enabled = enabled, onClick = { onSelect(value) })
            Column {
                Text(label)
                Text(hint, fontSize = 13.sp, color = Hint)
            }
        }
    }
}

@Composable
private fun ChipRow(values: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v -> FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(label(v)) }) }
    }
}

/** Puts [text] on the clipboard (Android 13+ shows its own confirmation; older versions get a toast). */
private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(text, text))
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(context, "Copied \"$text\"", android.widget.Toast.LENGTH_SHORT).show()
    }
}
