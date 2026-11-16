package com.kbhatiya999.jarvis

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What Jarvis says on "Jarvis wake up" / "Jarvis power down": the lines it records in its own voice, and,
 * for those who want them, audio files of your own (which take priority). Your files can be exported as a
 * zip and imported again.
 */
@Composable
fun VoiceScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    val clips = remember { Clips.get(context) }
    var version by remember { mutableIntStateOf(0) } // bump to re-read files
    var message by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var addingTo by remember { mutableStateOf<Clips.Slot?>(null) }

    fun record(force: Boolean) {
        recording = true
        message = "Recording Jarvis's voice…"
        clips.recordMissing(force) { error ->
            recording = false
            message = error ?: "Recorded."
            version++
        }
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val slot = addingTo ?: return@rememberLauncherForActivityResult
        val errors = uris.mapNotNull { clips.add(slot, it) }
        message = errors.firstOrNull() ?: if (uris.isNotEmpty()) "Added ${uris.size}." else null
        version++
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) message = clips.export(uri) ?: "Exported."
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (added, error) = clips.import(uri)
            message = error ?: "Imported $added clip${if (added == 1) "" else "s"}."
            version++
        }
    }

    BackHandler { vm.screen = "active" }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = "active" }) { Text("Back") }
            Text("Jarvis's replies", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Jarvis answers these at once, without the internet. It records each line once in its own voice; until " +
                "then Android's voice reads them. Add your own audio files if you like: they're used instead.",
            color = Color.LightGray,
        )
        message?.let { Text(it, fontSize = 13.sp, color = Color(0xFF81C784)) }

        Clips.Slot.entries.forEach { slot ->
            // Re-read when files change.
            val mine = remember(version) { clips.custom(slot) }
            var text by remember(version) { mutableStateOf(clips.lines(slot).joinToString("\n")) }
            val recordedCount = remember(version) { clips.lines(slot).count { clips.recorded(it) } }

            HorizontalDivider()
            Text(slot.label, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Lines, one per row (one picked at random)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val changed = text.lines().map { it.trim() }.filter { it.isNotEmpty() } != clips.lines(slot)
                Button(enabled = changed && !recording, onClick = {
                    clips.setLines(slot, text.lines())
                    record(force = false)
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    clips.setLines(slot, Clips.DEFAULTS.getValue(slot))
                    version++
                    record(force = false)
                }) { Text("Defaults") }
                OutlinedButton(onClick = { clips.play(slot) }) { Text("Play") }
            }
            Text(
                "Recorded in Jarvis's voice: $recordedCount of ${clips.lines(slot).size}" +
                    if (mine.isNotEmpty()) " (not used while you have your own files)" else "",
                fontSize = 13.sp, color = Color.Gray,
            )

            Text("Your audio files", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (mine.isEmpty()) Text("None.", fontSize = 13.sp, color = Color.Gray)
            mine.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { clips.playFile(file) }) { Text("Play") }
                    TextButton(onClick = { clips.remove(file); version++ }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { addingTo = slot; pickAudio.launch(arrayOf("audio/*")) }) { Text("Add audio file") }
        }

        HorizontalDivider()
        Text("Recording", fontWeight = FontWeight.Bold)
        Text(
            "Uses one Gemini session. If it fails (offline, or the free-tier limit), try again in a minute.",
            fontSize = 13.sp, color = Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !recording, onClick = { record(force = false) }) { Text("Record missing") }
            OutlinedButton(enabled = !recording, onClick = { record(force = true) }) { Text("Re-record all") }
        }

        HorizontalDivider()
        Text("Backup", fontWeight = FontWeight.Bold)
        Text("Your audio files and lines, as a zip you can keep or move to another phone.", fontSize = 13.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportZip.launch("jarvis-replies.zip") }) { Text("Export zip") }
            OutlinedButton(onClick = { importZip.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import zip") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
