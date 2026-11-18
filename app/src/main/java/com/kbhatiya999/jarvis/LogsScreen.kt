package com.kbhatiya999.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.kbhatiya999.jarvis.core.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    var setting by remember { mutableStateOf(AppLog.setting()) }
    var bytes by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableLongStateOf(0L) }
    var nothingToShare by remember { mutableStateOf(false) }
    BackHandler { vm.screen = null }
    LaunchedEffect(refresh) { bytes = withContext(Dispatchers.IO) { AppLog.bytes() } }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Logs", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Jarvis keeps a short log on this phone (about 1 MB, oldest lines dropped) so a problem can be traced. " +
                "It never holds your API key or your contacts. At Debug it also holds the first few words of what you " +
                "and Jarvis said. Nothing leaves the phone unless you tap Share.",
            color = Color.LightGray,
        )

        Text("Detail", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = setting == AppLog.DEFAULT,
                onClick = { setting = AppLog.DEFAULT; AppLog.setSetting(AppLog.DEFAULT) },
                label = { Text("Default (${AppLog.releaseLevel().label()})") },
            )
            LogLevel.values().forEach { level ->
                FilterChip(
                    selected = setting == level.name.lowercase(),
                    onClick = { setting = level.name.lowercase(); AppLog.setSetting(setting) },
                    label = { Text(level.label()) },
                )
            }
        }
        Text("Default follows what this version of Jarvis was built to log.", color = Color.Gray, fontSize = 12.sp)

        Text("Log file: ${if (bytes < 1024) "$bytes bytes" else "${bytes / 1024} KB"}", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val intent = AppLog.shareIntent(context)
                if (intent == null) nothingToShare = true else { nothingToShare = false; context.startActivity(intent) }
            }) { Text("Share logs") }
            OutlinedButton(onClick = { AppLog.clear(); refresh++ }) { Text("Clear") }
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        if (nothingToShare) Text("Nothing logged yet.", color = Color.Gray)
    }
}

private fun LogLevel.label() = name.lowercase().replaceFirstChar { it.uppercase() }
