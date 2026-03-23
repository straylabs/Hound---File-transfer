package com.straylabs.hound.ui.screens

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val chatHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

data class ChatSession(val label: String, val url: String, val isLocal: Boolean = false)

data class ChatMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val ts: Long,
    val type: String = "chat"
)

@Composable
fun ChatScreen(
    sessions: List<ChatSession> = emptyList(),
    savedDisplayName: String = "",
    onDisplayNameSaved: (String) -> Unit = {}
) {
    val defaultName = savedDisplayName.ifBlank { Build.MODEL }
    var selectedUrl by remember { mutableStateOf(sessions.firstOrNull()?.url ?: "") }
    var displayName by remember { mutableStateOf(defaultName) }
    var manualUrl by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var messageText by remember { mutableStateOf("") }
    var lastTs by remember { mutableStateOf(0L) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Keep selectedUrl in sync when sessions change (e.g. server starts while on Chat tab)
    LaunchedEffect(sessions) {
        if (!isConnected) {
            val first = sessions.firstOrNull()?.url
            if (first != null) selectedUrl = first
        }
    }

    // Polling loop
    LaunchedEffect(isConnected, selectedUrl) {
        if (!isConnected) return@LaunchedEffect
        lastTs = 0L
        messages.clear()
        while (true) {
            try {
                val url = selectedUrl.trim().let { if (it.startsWith("http")) it else "http://$it" }
                val fetched = withContext(Dispatchers.IO) { fetchMessages(url, lastTs) }
                if (fetched.isNotEmpty()) {
                    messages.addAll(fetched)
                    lastTs = fetched.last().ts
                    listState.animateScrollToItem(messages.size - 1)
                }
                connectionError = null
            } catch (e: Exception) {
                connectionError = "Connection error: ${e.message}"
            }
            delay(1500)
        }
    }

    fun postSystemMessage(url: String, text: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sendSystemMessageHttp(url, text) }
            } catch (_: Exception) {}
        }
    }

    // Send leave when composable is disposed (covers app close and tab switch away while connected).
    // IMPORTANT: capture isConnected/selectedUrl/displayName as local vals so onDispose closes
    // over the snapshot at effect-run time — NOT the live Compose state. Without this, reading
    // `isConnected` inside onDispose would return the *new* value (already flipped to true on
    // join), causing a spurious leave message to fire every time the user connects.
    DisposableEffect(isConnected, selectedUrl, displayName) {
        val wasConnected = isConnected
        val capturedUrl = selectedUrl.trim().let { if (it.startsWith("http")) it else "http://$it" }
        val capturedName = displayName.trim()
        onDispose {
            if (wasConnected) {
                Thread {
                    try { sendSystemMessageHttp(capturedUrl, "$capturedName left the chat") } catch (_: Exception) {}
                }.start()
            }
        }
    }

    fun sendMessage() {
        val text = messageText.trim()
        val name = displayName.trim()
        if (text.isEmpty() || name.isEmpty() || !isConnected) return
        messageText = ""
        scope.launch {
            try {
                val url = selectedUrl.trim().let { if (it.startsWith("http")) it else "http://$it" }
                withContext(Dispatchers.IO) { sendMessageHttp(url, name, text) }
            } catch (e: Exception) {
                connectionError = "Send failed: ${e.message}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isConnected) {
            ConnectionSetupCard(
                sessions = sessions,
                selectedUrl = selectedUrl,
                manualUrl = manualUrl,
                displayName = displayName,
                onSessionSelected = { selectedUrl = it },
                onManualUrlChange = { manualUrl = it; selectedUrl = it },
                onDisplayNameChange = { displayName = it },
                onConnect = {
                    val url = selectedUrl.trim().let { if (it.startsWith("http")) it else "http://$it" }
                    val name = displayName.trim()
                    if (url.isNotBlank() && name.isNotBlank()) {
                        onDisplayNameSaved(name)
                        connectionError = null
                        isConnected = true
                        postSystemMessage(url, "$name joined the chat")
                    }
                }
            )
        } else {
            // Header
            Surface(shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LAN Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = selectedUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        isConnected = false
                        messages.clear()
                        connectionError = null
                    }) {
                        Text("Disconnect")
                    }
                }
            }

            if (connectionError != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = connectionError!!,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.type == "system") {
                        SystemEventRow(text = msg.text)
                    } else {
                        ChatBubble(message = msg, isMine = msg.sender == displayName.trim())
                    }
                }
            }

            // Input bar
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Message…") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            sendMessage()
                            keyboardController?.hide()
                        }),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            sendMessage()
                            keyboardController?.hide()
                        },
                        enabled = messageText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionSetupCard(
    sessions: List<ChatSession>,
    selectedUrl: String,
    manualUrl: String,
    displayName: String,
    onSessionSelected: (String) -> Unit,
    onManualUrlChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    var showManual by remember { mutableStateOf(sessions.isEmpty()) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "LAN Chat",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                if (sessions.isEmpty()) {
                    // No active sessions — show manual URL input
                    Text(
                        "No active sessions detected. Start the server or connect as client first, or enter a server URL manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = onManualUrlChange,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.1.x:8080") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        )
                    )
                } else {
                    // Show detected sessions as selectable cards
                    Text(
                        "Select a session to join:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sessions.forEach { session ->
                            SessionCard(
                                session = session,
                                isSelected = selectedUrl == session.url,
                                onClick = {
                                    onSessionSelected(session.url)
                                    showManual = false
                                }
                            )
                        }
                    }

                    // Manual URL toggle
                    TextButton(
                        onClick = { showManual = !showManual },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            if (showManual) "Hide manual entry" else "Enter URL manually",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (showManual) {
                        OutlinedTextField(
                            value = manualUrl,
                            onValueChange = onManualUrlChange,
                            label = { Text("Server URL") },
                            placeholder = { Text("http://192.168.1.x:8080") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next
                            )
                        )
                    }
                }

                // Display name
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("Your Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConnect() })
                )

                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedUrl.isNotBlank() && displayName.isNotBlank()
                ) {
                    Text("Join Chat")
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ChatSession,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (session.isLocal) Icons.Filled.Dns else Icons.Filled.Computer,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = session.url,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        if (isSelected) {
            Text(
                "✓",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun SystemEventRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(message: ChatMessage, isMine: Boolean) {
    val bubbleColor = if (isMine) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            if (!isMine) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = if (isMine) 16.dp else 4.dp,
                            topEnd = if (isMine) 4.dp else 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd = 16.dp
                        )
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(message.text))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun fetchMessages(serverUrl: String, since: Long): List<ChatMessage> {
    val url = "${serverUrl.trimEnd('/')}/chat/messages?since=$since"
    val request = Request.Builder().url(url).get().build()
    chatHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val arr = json.getJSONArray("messages")
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ChatMessage(
                id = obj.getLong("id"),
                sender = obj.getString("sender"),
                text = obj.getString("text"),
                ts = obj.getLong("ts"),
                type = obj.optString("type", "chat")
            )
        }
    }
}

private fun sendMessageHttp(serverUrl: String, sender: String, text: String) {
    val url = "${serverUrl.trimEnd('/')}/chat/send"
    val body = FormBody.Builder().add("sender", sender).add("text", text).build()
    val request = Request.Builder().url(url).post(body).build()
    chatHttpClient.newCall(request).execute().use { /* consume response */ }
}

private fun sendSystemMessageHttp(serverUrl: String, text: String) {
    val url = "${serverUrl.trimEnd('/')}/chat/send"
    val body = FormBody.Builder().add("sender", "").add("text", text).add("type", "system").build()
    val request = Request.Builder().url(url).post(body).build()
    chatHttpClient.newCall(request).execute().use { /* consume response */ }
}
