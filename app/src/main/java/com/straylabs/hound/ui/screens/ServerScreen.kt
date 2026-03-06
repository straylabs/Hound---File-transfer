package com.straylabs.hound.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.straylabs.hound.ui.theme.*
import com.straylabs.hound.util.TransferHistory
import com.straylabs.hound.util.TransferSession
import com.straylabs.hound.util.TransferType
import com.straylabs.hound.util.TransferStatus
import com.straylabs.hound.util.FileUtils
import android.graphics.Color as AndroidColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("UNUSED_PARAMETER")
@Composable
fun ServerScreen(
    serverRunning: Boolean,
    selectedPath: String?,
    serverPort: Int,
    serverIp: String?,
    authEnabled: Boolean = false,
    authUsername: String = "",
    authPassword: String = "",
    onStartServer: (Int) -> Unit,
    onStopServer: () -> Unit,
    onSelectFolder: () -> Unit,
    onPathChanged: (String?) -> Unit,
    onPortChanged: (Int) -> Unit,
    onAuthChanged: (enabled: Boolean, username: String, password: String) -> Unit = { _, _, _ -> },
    transferHistory: TransferHistory? = null
) {
    val context = LocalContext.current
    var portText by remember(serverPort) { mutableStateOf(serverPort.toString()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showStartConfirm by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var showFolderError by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // ── Confirmation dialogs ──────────────────────────────────────────────────
    if (showStartConfirm) {
        AlertDialog(
            onDismissRequest = { showStartConfirm = false },
            icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
            title = { Text("Start Server?") },
            text = {
                Text(
                    "Anyone on the same Wi-Fi will be able to browse your shared folder." +
                    if (selectedPath != null) "\n\nSharing: ${selectedPath.substringAfterLast('/')}" else ""
                )
            },
            confirmButton = {
                Button(
                    onClick = { showStartConfirm = false; onStartServer(serverPort) },
                    colors = ButtonDefaults.buttonColors(containerColor = StartGreen)
                ) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showStartConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            icon = { Icon(Icons.Filled.WifiOff, contentDescription = null) },
            title = { Text("Stop Server?") },
            text = { Text("Active transfers will be interrupted and the server will go offline.") },
            confirmButton = {
                Button(
                    onClick = { showStopConfirm = false; onStopServer() },
                    colors = ButtonDefaults.buttonColors(containerColor = StopRed)
                ) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            }
        )
    }

    val serverUrl = if (serverIp != null) "http://$serverIp:$serverPort" else null
    val qrBitmap = remember(serverUrl) { serverUrl?.let { generateQrBitmap(it, 512) } }

    val isDark = isSystemInDarkTheme()
    val cardBg by animateColorAsState(
        targetValue = if (serverRunning) {
            if (isDark) RunningCardDark else RunningCardLight
        } else {
            if (isDark) StoppedCardDark else StoppedCardLight
        },
        label = "serverCardBg"
    )
    val statusColor = if (serverRunning) RunningBadgeFg else StoppedBadgeFg

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Main settings card ────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Section label
                Text(
                    text = "SERVER SETTINGS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = statusColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                // Server on/off toggle row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (serverRunning) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (serverRunning) "Server On" else "Server Off",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor
                            )
                        )
                    }
                    Switch(
                        checked = serverRunning,
                        onCheckedChange = { on ->
                            if (on) {
                                if (selectedPath == null) showFolderError = true
                                else showStartConfirm = true
                            } else {
                                showStopConfirm = true
                            }
                        },
                        enabled = true,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = RunningBadgeFg,
                            uncheckedTrackColor = StoppedBadgeFg.copy(alpha = 0.35f),
                            uncheckedThumbColor = StoppedBadgeFg,
                            uncheckedBorderColor = StoppedBadgeFg.copy(alpha = 0.5f),
                        )
                    )
                }

                // Running state: URL + QR
                if (serverRunning && serverUrl != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    )
                    // URL row with copy button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = serverUrl,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Teal700,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Server URL", serverUrl))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy URL",
                                tint = Teal700,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (qrBitmap != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR code for server URL",
                            modifier = Modifier
                                .size(200.dp)
                                .align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Text(
                            text = "Scan to connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )

                // Port field
                OutlinedTextField(
                    value = portText,
                    onValueChange = { text ->
                        portText = text.filter { it.isDigit() }
                        text.toIntOrNull()?.let { onPortChanged(it) }
                    },
                    label = { Text("Port") },
                    placeholder = { Text("8080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !serverRunning,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Auth toggle + fields
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (authEnabled) Teal700 else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Password Protection",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                    Switch(
                        checked = authEnabled,
                        onCheckedChange = { onAuthChanged(it, authUsername, authPassword) },
                        enabled = !serverRunning,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = Teal700,
                        )
                    )
                }

                if (authEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { onAuthChanged(authEnabled, it, authPassword) },
                        label = { Text("Username") },
                        placeholder = { Text("Optional") },
                        singleLine = true,
                        enabled = !serverRunning,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { onAuthChanged(authEnabled, authUsername, it) },
                        label = { Text("Password") },
                        placeholder = { Text("Optional") },
                        singleLine = true,
                        enabled = !serverRunning,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (authEnabled && (authUsername.isBlank() || authPassword.isBlank())) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Enter both username and password to enable protection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Folder selection button
                OutlinedButton(
                    onClick = {
                        showFolderError = false
                        onSelectFolder()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !serverRunning,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (showFolderError && selectedPath == null)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = if (showFolderError && selectedPath == null)
                        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    else ButtonDefaults.outlinedButtonBorder,
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedPath != null)
                            selectedPath.substringAfterLast('/')
                        else "Select Share Folder",
                        maxLines = 1,
                    )
                }

                if (showFolderError && selectedPath == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Please select a folder to share before starting the server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // History button
                OutlinedButton(
                    onClick = { showHistory = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Transfer History")
                }
            }
        }

        // ── Empty state (stopped + no folder selected) ────────────────────────
        if (!serverRunning && selectedPath == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Text(
                    "Server stopped",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    "Select a folder to start sharing files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── LAN-only warning card ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(WarnBackground)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = WarnOrange,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 1.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "LAN Network Only",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = WarnTextLight
                )
                Text(
                    "Anyone on the same Wi-Fi can access your files. " +
                            "Avoid use on public networks. Enable password protection for security.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnTextLight.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }

    // ── History Dialog ─────────────────────────────────────────────────────────────
    if (showHistory) {
        HistoryDialog(
            transferHistory = transferHistory,
            onDismiss = { showHistory = false }
        )
    }
}

// ── QR generator ─────────────────────────────────────────────────────────────

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (bits[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    bmp
}.getOrNull()

// ── History Dialog ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryDialog(
    transferHistory: TransferHistory?,
    onDismiss: () -> Unit
) {
    val sessions = transferHistory?.getSessions() ?: emptyList()
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer History") },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    "No transfer history yet.\nUploads and downloads will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sessions.forEach { session ->
                        Column {
                            Text(
                                text = "Session: ${dateFormat.format(Date(session.startTime))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (session.records.isEmpty()) {
                                Text(
                                    "No transfers",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                session.records.forEach { record ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (record.type == TransferType.UPLOAD)
                                                    Icons.Filled.Upload
                                                else Icons.Filled.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (record.status == TransferStatus.COMPLETED)
                                                    Teal700
                                                else MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = record.fileName,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = FileUtils.formatSize(record.fileSize),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (sessions.isNotEmpty()) {
                TextButton(
                    onClick = {
                        transferHistory?.clearAllHistory()
                        onDismiss()
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
