package com.kbhatiya999.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Settings › Self-check: a tick or a cross for each link the "Hey Google" wake needs, with how to fix it. */
@Composable
fun CheckScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    var refresh by remember { mutableLongStateOf(0L) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    BackHandler { vm.screen = null }

    // Re-check when coming back from a settings page.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val checks = remember(refresh) { SelfCheck.run(context) }

    LaunchedEffect(testing) {
        if (!testing) return@LaunchedEffect
        val before = RoutineListener.testSeenAt
        testResult = if (!SelfCheck.sendTest(context)) "Notifications are off for Jarvis: allow them in app settings first."
        else {
            var seen = false
            repeat(20) { if (!seen) { delay(250); seen = RoutineListener.testSeenAt > before } }
            SelfCheck.cleanUpTest(context)
            if (seen) "Jarvis saw the test notification: the notification link works."
            else "Jarvis did not see it. Notification access is off or disconnected, or the phone is blocking it."
        }
        com.kbhatiya999.jarvis.core.JarvisLog.i("Check", "test notification result: $testResult")
        testing = false
        refresh++
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Self-check", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "\"Hey Google\" can only wake Jarvis if every step below works. Fix any cross, then come back: the list updates by itself.",
            color = Color.LightGray,
        )
        checks.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${when (c.ok) { true -> "✓"; false -> "✗"; null -> "•" }}  ${c.title}",
                    fontWeight = FontWeight.Bold,
                    color = when (c.ok) { true -> Color(0xFF81C784); false -> Color(0xFFE57373); null -> Color.LightGray },
                )
                Text(c.detail, color = Color.LightGray, fontSize = 14.sp)
                if (c.fix != null && c.fixLabel != null) OutlinedButton(onClick = c.fix) { Text(c.fixLabel) }
            }
        }
        Text("Test notification", fontWeight = FontWeight.Bold)
        Text("Sends a \"Jarvis wake up\" notification from Jarvis itself and checks that Jarvis sees it. Nothing wakes.", color = Color.LightGray, fontSize = 14.sp)
        Button(onClick = { testResult = null; testing = true }, enabled = !testing) { Text(if (testing) "Testing…" else "Run test") }
        testResult?.let { Text(it, color = Color.LightGray) }
        Text("The results are also written to the log (Settings › Logs), so Share logs includes them.", color = Color.Gray, fontSize = 12.sp)
    }
}
