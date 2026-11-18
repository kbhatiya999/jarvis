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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kbhatiya999.jarvis.core.Languages

/** Settings › Languages: what Jarvis listens for (all of them) and speaks (the first). Default English (India), Hindi. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagesScreen(vm: JarvisViewModel) {
    val languages = vm.jarvis.languages
    var codes by remember { mutableStateOf(languages.codes.value) }
    DisposableEffect(Unit) {
        val sub = languages.codes.watch { codes = it }
        onDispose { sub.cancel() }
    }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { vm.screen = null }

    fun add(code: String) {
        error = languages.add(code)
        if (error == null) typed = ""
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Languages", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Jarvis listens for all of these, so you can mix them (Hinglish works). It speaks the first one and " +
                "answers in whichever of them you just spoke. Notes and messages stay in the language you used.",
            color = Color.LightGray,
        )

        codes.forEachIndexed { i, code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Languages.label(code))
                    if (i == 0) Text("Jarvis speaks this by default", color = Color.Gray, fontSize = 12.sp)
                }
                if (i > 0) TextButton(onClick = { languages.moveUp(code) }) { Text("Move up") }
                if (codes.size > 1) TextButton(onClick = { languages.remove(code) }) { Text("Remove") }
            }
        }

        if (codes.size < Languages.MAX) {
            Text("Add a language", fontWeight = FontWeight.Bold)
            val phone = Languages.normalize(vm.jarvis.languageCode)
            val offer = (listOfNotNull(phone) + Languages.SUGGESTED).distinct().filter { it !in codes }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offer.forEach { code -> AssistChip(onClick = { add(code) }, label = { Text(Languages.label(code)) }) }
            }
            Text("Not listed? Type its code, like or-IN or pa-IN:", color = Color.Gray, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it; error = null }, singleLine = true,
                    placeholder = { Text("xx-XX") }, modifier = Modifier.weight(1f),
                )
                Button(enabled = typed.isNotBlank(), onClick = { add(typed) }) { Text("Add") }
            }
        } else {
            Text("At most ${Languages.MAX} languages: Gemini hears better with fewer. Remove one to add another.", color = Color.Gray)
        }
        error?.let { Text(it, color = Color(0xFFFFB74D)) }

        if (codes != Languages.DEFAULT) {
            TextButton(onClick = { languages.resetToDefaults() }) { Text("Back to English (India) and Hindi") }
        }
        Text("Changes apply from the next conversation.", color = Color.Gray, fontSize = 12.sp)
    }
}
