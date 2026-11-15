package com.kbhatiya999.jarvis

import com.kbhatiya999.jarvis.core.Contact
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun ContactsScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    val book = vm.jarvis.contacts
    val me = remember { vm.jarvis.identity }
    val contacts = book.contacts.observe()
    val requests = book.requests.observe()
    var myName by remember { mutableStateOf(book.myName.value) }
    var newName by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<Contact?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        // So incoming messages can show up when Jarvis can't say them.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler { vm.screen = null }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = null }) { Text("Back") }
            Text("Contacts", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Add a friend's Jarvis, then ask yours: \"tell Priya's Jarvis I'll be late\". Their Jarvis passes it on. " +
                "Messages are end-to-end encrypted and carried by the free ntfy.sh relay, which keeps them up to 12 hours.",
            color = Color.LightGray,
        )

        HorizontalDivider()
        Text("Your Jarvis", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = myName,
            onValueChange = { myName = it.take(40); book.updateMyName(myName) },
            label = { Text("Your name (what friends' Jarvises call you)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SelectionContainer {
            Text(
                me.address,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF141C2E), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, book.shareText(me.address))
                context.startActivity(Intent.createChooser(send, "Share your Jarvis address"))
            }) { Text("Share") }
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Jarvis address", book.shareText(me.address)))
            }) { Text("Copy") }
        }
        Text("Send this to a friend who uses Jarvis; they add it under Contacts. Add theirs here too.", fontSize = 13.sp, color = Color.Gray)

        if (requests.isNotEmpty()) {
            HorizontalDivider()
            Text("Requests", fontWeight = FontWeight.Bold)
            requests.forEach { r ->
                Column(Modifier.fillMaxWidth().background(Color(0xFF2A2A1E), RoundedCornerShape(10.dp)).padding(12.dp)) {
                    Text("${r.name}'s Jarvis (not in your contacts) says:", fontSize = 13.sp, color = Color(0xFFFFE082))
                    Text(r.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            vm.jarvis.acceptRequest(r)
                        }) { Text("Accept") }
                        TextButton(onClick = { book.dismissRequest(r) }) { Text("Ignore") }
                    }
                }
            }
            Text("The name is what they chose; rename them after accepting if needed.", fontSize = 13.sp, color = Color.Gray)
        }

        HorizontalDivider()
        Text("Add a contact", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = newAddress,
            onValueChange = { v ->
                newAddress = v
                error = null
                if (newName.isBlank()) book.extractName(v)?.let { newName = it }
            },
            label = { Text("Their Jarvis address") },
            placeholder = { Text("Paste what they shared") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it.take(40); error = null },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Color(0xFFE57373), fontSize = 13.sp) }
        Button(
            onClick = {
                error = if (book.extractAddress(newAddress) == me.address) "That's your own address." else book.add(newName, newAddress)
                if (error == null) { newName = ""; newAddress = "" }
            },
            enabled = newAddress.isNotBlank(),
        ) { Text("Add") }

        HorizontalDivider()
        Text("Contacts", fontWeight = FontWeight.Bold)
        if (contacts.isEmpty()) Text("None yet.", color = Color.Gray)
        contacts.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, Modifier.weight(1f))
                TextButton(onClick = { confirmRemove = c }) { Text("Remove", color = Color(0xFFE57373)) }
            }
        }
        confirmRemove?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remove ${c.name}?", Modifier.weight(1f))
                TextButton(onClick = { book.remove(c); confirmRemove = null }) { Text("Remove") }
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
