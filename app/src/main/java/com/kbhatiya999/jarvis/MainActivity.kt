package com.kbhatiya999.jarvis

import com.kbhatiya999.jarvis.core.Message
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

private val Accent = Color(0xFF4FC3F7)
private val Background = Color(0xFF0B1220)
private const val AI_STUDIO_KEYS = "https://aistudio.google.com/api-keys"

class MainActivity : ComponentActivity() {
    /** Opened from the Quick Settings tile: start listening once in front. */
    private var talkOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talkOnResume = intent?.getBooleanExtra(EXTRA_TALK, false) == true
        enableEdgeToEdge()
        AndroidPlatform.jarvis(this)
        RelayPollWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Background)) {
                Surface(Modifier.fillMaxSize(), color = Background) {
                    val vm: JarvisViewModel = viewModel()
                    when {
                        vm.jarvis.apiKey.observe().isNullOrBlank() || vm.editingKey -> KeyScreen(vm)
                        vm.screen == "active" -> ActiveScreen(vm)
                        vm.screen == "reminders" -> RemindersScreen(vm)
                        vm.screen == "contacts" -> ContactsScreen(vm)
                        else -> ChatScreen(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TALK, false)) talkOnResume = true
    }

    override fun onStart() {
        super.onStart()
        // Messages from other Jarvises arrive live while the app is open.
        AndroidPlatform.jarvis(this).relay.acquireStream()
    }

    override fun onStop() {
        AndroidPlatform.jarvis(this).relay.releaseStream()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AndroidPlatform.jarvis(this).relay.pollAsync()
        // Active mode can only (re)start while the app is in front, e.g. after a reboot.
        val settings = Settings(this)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (settings.activeEnabled && mic && !ActiveModeService.running) ActiveModeService.start(this)
        if (talkOnResume && mic) ViewModelProvider(this)[JarvisViewModel::class.java].talkNow()
        talkOnResume = false
    }

    companion object {
        const val EXTRA_TALK = "talk"
    }
}

@Composable
private fun KeyScreen(vm: JarvisViewModel) {
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val current = vm.jarvis.apiKey.observe()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("J.A.R.V.I.S.", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(if (current == null) "Systems require a Gemini API key, Sir." else "Replace the Gemini API key.")
        Spacer(Modifier.height(20.dp))
        Text("1. Open Google AI Studio and sign in.")
        Text("2. Tap \"Create API key\" and copy it.")
        Text("3. Paste it below.")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { uri.openUri(AI_STUDIO_KEYS) }) { Text("Open Google AI Studio") }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            placeholder = { Text("AIza...") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (current != null) {
            Text("Current key ends in ...${current.takeLast(4)}", fontSize = 12.sp, color = Color.Gray)
        }
        Text("Stored only on this device, encrypted.", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.saveKey(key) }, enabled = key.isNotBlank()) { Text("Save") }
            if (current != null) TextButton(onClick = { vm.editingKey = false }) { Text("Cancel") }
        }
    }
}

@Composable
private fun ChatScreen(vm: JarvisViewModel) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleVoice()
    }

    val messages = vm.jarvis.conversation.messages.observe()
    val requests = vm.jarvis.contacts.requests.observe()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        // Header
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("J.A.R.V.I.S.", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(vm.status, fontSize = 12.sp, color = if (vm.connected) Accent else Color.Gray)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text(if (requests.isEmpty()) "Settings" else "Settings •") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Active mode") }, onClick = { menu = false; vm.screen = "active" })
                    DropdownMenuItem(text = { Text("Reminders") }, onClick = { menu = false; vm.screen = "reminders" })
                    DropdownMenuItem(
                        text = { Text(if (requests.isEmpty()) "Contacts" else "Contacts (${requests.size} new)") },
                        onClick = { menu = false; vm.screen = "contacts" },
                    )
                    DropdownMenuItem(text = { Text("Change API key") }, onClick = { menu = false; vm.editingKey = true })
                    DropdownMenuItem(text = { Text("Forget conversation") }, onClick = { menu = false; confirmForget = true })
                }
            }
        }

        // Conversation
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item { Text("All systems operational. Tap Talk or type a message.", color = Color.Gray, modifier = Modifier.padding(8.dp)) }
            }
            items(messages) { m -> Bubble(m) }
        }

        // Input
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message Jarvis") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { vm.sendText(input); input = "" }),
            )
            Spacer(Modifier.padding(4.dp))
            if (input.isNotBlank()) {
                Button(onClick = { vm.sendText(input); input = "" }) { Text("Send") }
            } else {
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted || vm.listening) vm.toggleVoice() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    colors = if (vm.listening) ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)) else ButtonDefaults.buttonColors(),
                ) { Text(if (vm.listening) "Stop" else "Talk") }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget conversation?") },
            text = { Text("Jarvis will lose its memory of everything said so far. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.clearMemory() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Bubble(m: Message) {
    if (m.isRelay) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                m.text,
                color = Color(0xFFFFE082),
                fontSize = 13.sp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(Color(0xFF2A2A1E), RoundedCornerShape(10.dp))
                    .padding(10.dp, 6.dp),
            )
        }
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            m.text,
            color = if (m.fromUser) Color.Black else Color.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (m.fromUser) Accent else Color(0xFF1E2A44), RoundedCornerShape(14.dp))
                .padding(12.dp, 8.dp),
        )
    }
}
