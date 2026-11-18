package com.kbhatiya999.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.kbhatiya999.jarvis.core.GeminiBridge

/**
 * Settings › Connect Gemini: the link to paste into the Gemini app (Connectors › + Custom), so Gemini can call
 * tell_jarvis and Jarvis says the result. See core/GeminiBridge and bridge/ in the repo.
 */
@Composable
fun GeminiScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    val bridge = vm.jarvis.bridge
    var version by remember { mutableIntStateOf(0) } // bumps to re-read the bridge after a change
    var custom by remember { mutableStateOf(bridge.customUrl) }
    var confirmNew by remember { mutableStateOf(false) }
    BackHandler { vm.screen = null }
    val connected = remember(version) { bridge.connected }
    val base = remember(version) { bridge.baseUrl }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Connect Gemini", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Let the Gemini app talk through Jarvis. Gemini does the work (with your connectors), then calls " +
                "\"tell_jarvis\" and Jarvis says the result out loud, as a message from \"Gemini\". Active mode " +
                "must be on to hear it.",
            color = Color.LightGray,
        )

        if (base.isEmpty()) {
            Text("This build has no bridge address. Enter one below (yours, or a test tunnel).", color = Color(0xFFFFB74D))
        } else {
            Text("1. Copy your link", fontWeight = FontWeight.Bold)
            Text(
                "Anyone with this link can make your Jarvis speak (always as \"Gemini\"). Keep it to yourself; " +
                    "\"New link\" turns the old one off.",
                color = Color.Gray, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    bridge.link()?.let { copyText(context, it, sensitive = true) }
                    version++
                }) { Text(if (connected) "Copy link" else "Make and copy link") }
                if (connected) OutlinedButton(onClick = { confirmNew = true }) { Text("New link") }
            }
            if (confirmNew) {
                Text("The old link stops working at once; you'll paste the new one into Gemini.", color = Color(0xFFFFB74D))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        bridge.newLink()?.let { copyText(context, it, sensitive = true) }
                        confirmNew = false
                        version++
                    }) { Text("Make new link and copy") }
                    TextButton(onClick = { confirmNew = false }) { Text("Cancel") }
                }
            }

            Text("2. Add it in Gemini", fontWeight = FontWeight.Bold)
            Text(
                "Gemini (app or gemini.google.com) › Settings › Connectors › + Custom › paste the link as MCP Server " +
                    "URL › leave Client ID and secret empty › Next.",
                color = Color.LightGray,
            )

            Text("3. Tell Gemini when to use it (optional)", fontWeight = FontWeight.Bold)
            Text("Save this in a Gem, or Gemini's saved info, so it reports back on its own:", color = Color.LightGray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\"${GeminiBridge.GEM_INSTRUCTION}\"", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { copyText(context, GeminiBridge.GEM_INSTRUCTION) }) { Text("Copy") }
            }
            Text("Try it: ask Gemini \"Tell my Jarvis hello\".", color = Color.LightGray)

            if (connected) {
                TextButton(onClick = { bridge.disconnect(); version++ }) { Text("Disconnect Gemini") }
            }
        }

        Text("Bridge address", fontWeight = FontWeight.Bold)
        Text(
            "Empty uses the one this version came with${if (bridge.defaultUrl.isNotEmpty()) " (${bridge.defaultUrl})" else ""}. " +
                "Set your own bridge, or a test tunnel from tilt:bridge-tunnel. Changing it needs a new link.",
            color = Color.Gray, fontSize = 12.sp,
        )
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            singleLine = true,
            placeholder = { Text("https://…") },
            modifier = Modifier.fillMaxWidth(),
        )
        val valid = custom.isBlank() || custom.trim().startsWith("https://")
        if (!valid) Text("It must start with https://", color = Color(0xFFFFB74D), fontSize = 12.sp)
        if (custom.trim().trimEnd('/') != bridge.customUrl) {
            Button(enabled = valid, onClick = {
                bridge.customUrl = custom
                if (bridge.connected) bridge.disconnect() // the old link points at the old bridge
                version++
            }) { Text("Save address") }
        }
    }
}
