package com.straylabs.hound.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.straylabs.hound.client.AuthRequiredException
import com.straylabs.hound.client.FileItem
import com.straylabs.hound.client.HttpClientManager
import com.straylabs.hound.util.FileUtils
import com.straylabs.hound.util.NotificationHelper
import com.straylabs.hound.util.TransferHistory
import com.straylabs.hound.util.TransferType
import com.straylabs.hound.util.TransferStatus
import com.straylabs.hound.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ClientScreen(
    downloadFolder: File?,
    savedCredentials: Pair<String, String>? = null,
    onCredentialsSaved: (Pair<String, String>?) -> Unit = {},
    onSelectDownloadFolder: () -> Unit,
    transferHistory: TransferHistory? = null,
    notificationHelper: NotificationHelper? = null
) {
    val context = LocalContext.current
    val httpClient = remember { HttpClientManager(context, transferHistory) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var urlInput by remember { mutableStateOf("http://") }
    var connectedUrl by remember { mutableStateOf<String?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0) }
    var uploadingFile by remember { mutableStateOf<String?>(null) }
    val pathHistory = remember { mutableStateListOf<String>() }
    var previewItem by remember { mutableStateOf<FileItem?>(null) }

    var showAuthDialog by remember { mutableStateOf(false) }
    var authUsername by remember { mutableStateOf(savedCredentials?.first ?: "") }
    var authPassword by remember { mutableStateOf(savedCredentials?.second ?: "") }
    var authPasswordVisible by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(savedCredentials) {
        httpClient.credentials = savedCredentials
    }

    fun loadFiles(baseUrl: String, path: String) {
        scope.launch {
            isLoading = true
            error = null
            httpClient.listFiles(baseUrl, path)
                .onSuccess { files = it }
                .onFailure { e ->
                    if (e is AuthRequiredException) showAuthDialog = true
                    else error = e.message ?: "Unknown error"
                }
            isLoading = false
        }
    }

    fun connect() {
        val url = urlInput.trimEnd('/')
        if (url.isBlank()) return
        connectedUrl = url
        currentPath = ""
        pathHistory.clear()
        loadFiles(url, "")
    }

    fun navigateTo(item: FileItem) {
        val url = connectedUrl ?: return
        pathHistory.add(currentPath)
        currentPath = item.path
        loadFiles(url, item.path)
    }

    fun navigateBack() {
        val url = connectedUrl ?: return
        val prev = pathHistory.removeLastOrNull() ?: return
        currentPath = prev
        loadFiles(url, prev)
    }

    fun downloadFile(item: FileItem) {
        val url = connectedUrl ?: return
        val folder = downloadFolder ?: run {
            scope.launch { snackbarHostState.showSnackbar("Select a download folder first") }
            return
        }
        downloadingFile = item.name
        downloadProgress = 0
        notificationHelper?.showDownloadNotification(item.name, 0)
        scope.launch {
            httpClient.downloadFile(url, item.path, folder) { progress ->
                downloadProgress = progress
                notificationHelper?.showDownloadNotification(item.name, progress)
            }.onSuccess {
                downloadingFile = null
                notificationHelper?.cancelDownloadNotification()
                snackbarHostState.showSnackbar("Saved: ${item.name}")
            }.onFailure { e ->
                downloadingFile = null
                notificationHelper?.cancelDownloadNotification()
                snackbarHostState.showSnackbar("Download failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            val url = scanned.trimEnd('/')
            urlInput = url
            connectedUrl = url
            currentPath = ""
            pathHistory.clear()
            loadFiles(url, "")
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val url = connectedUrl ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var successCount = 0
            var failCount = 0
            uris.forEachIndexed { index, uri ->
                // Resolve real display name via ContentResolver (works for all URI types)
                val fileName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "file_${System.currentTimeMillis()}"
                }
                uploadingFile = if (uris.size == 1) fileName
                               else "$fileName  (${index + 1}/${uris.size})"
                notificationHelper?.showUploadNotification(fileName, 0)
                val tmp = File(context.cacheDir, fileName)
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw java.io.IOException("Cannot open: $fileName")
                    }
                    val uploadPath = if (currentPath.isBlank()) fileName
                                     else "$currentPath/$fileName"
                    httpClient.uploadFile(url, uploadPath, tmp) { progress ->
                        notificationHelper?.showUploadNotification(fileName, progress)
                    }.onSuccess { 
                        successCount++
                        notificationHelper?.cancelUploadNotification()
                    }.onFailure { 
                        failCount++
                        notificationHelper?.cancelUploadNotification()
                    }
                } catch (_: Exception) {
                    failCount++
                    notificationHelper?.cancelUploadNotification()
                } finally {
                    tmp.delete()
                }
            }
            uploadingFile = null
            val msg = when {
                failCount == 0 -> if (uris.size == 1) "Uploaded successfully"
                                  else "Uploaded $successCount files"
                successCount == 0 -> "Upload failed"
                else -> "Uploaded $successCount / ${uris.size} (${failCount} failed)"
            }
            snackbarHostState.showSnackbar(msg)
            loadFiles(url, currentPath)
        }
    }

    // Auth dialog
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            title = { Text("Authentication Required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This server requires a username and password.")
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { authUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { authPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (authPasswordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { authPasswordVisible = !authPasswordVisible }) {
                                Icon(
                                    if (authPasswordVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAuthDialog = false
                        val creds = authUsername to authPassword
                        httpClient.credentials = creds
                        onCredentialsSaved(creds)
                        connectedUrl?.let { loadFiles(it, currentPath) }
                    },
                    enabled = authUsername.isNotBlank() && authPassword.isNotBlank()
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showAuthDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showHistory) {
        ClientHistoryDialog(
            transferHistory = transferHistory,
            onDismiss = { showHistory = false }
        )
    }

    BackHandler(enabled = previewItem != null) { previewItem = null }

    previewItem?.let { item ->
        val url = connectedUrl ?: return@let
        val imageItems = files.filter { isImageFile(it.name) }
        MediaPreview(
            item = item,
            baseUrl = url,
            imageItems = imageItems,
            credentials = httpClient.credentials,
            onDismiss = { previewItem = null },
            onDownload = { downloadFile(item) }
        )
        return@ClientScreen
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (connectedUrl != null && !isLoading) {
                FloatingActionButton(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Teal700,
                    contentColor = Color.White
                ) {
                    if (uploadingFile != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Filled.CloudUpload, contentDescription = "Upload file")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // ── Connect card ──────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CONNECT TO SERVER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // URL input + QR button row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = { Text("http://192.168.x.x:8080") },
                            singleLine = true,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(1f),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        scanLauncher.launch(
                                            ScanOptions().apply {
                                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                                setPrompt("Scan server QR code")
                                                setBeepEnabled(true)
                                                setOrientationLocked(true)
                                            }
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.QrCodeScanner,
                                        contentDescription = "Scan QR code",
                                        tint = Teal700
                                    )
                                }
                            }
                        )
                    }

                    // Connection status + Connect button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Connection status badge
                        val isConnected = connectedUrl != null && error == null && !isLoading
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isConnected) ConnectedBg else DisconnectedBg
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isConnected) "● Connected" else "⊙ Disconnected",
                                color = if (isConnected) ConnectedFg else DisconnectedFg,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Connect button
                        Button(
                            onClick = ::connect,
                            enabled = !isLoading && urlInput.isNotBlank() && urlInput != "http://",
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Teal700,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = if (connectedUrl != null) "Reconnect" else "Connect",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // ── Download folder row ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDownloadFolder() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = if (downloadFolder != null) Teal700 else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (downloadFolder != null)
                        "Save to: ${downloadFolder.absolutePath.substringAfterLast('/')}"
                    else "Tap to select download folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadFolder != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            // History button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHistory = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Transfer History",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // ── Breadcrumb row (only when connected) ──────────────────────────
            if (connectedUrl != null) {
                BreadcrumbRow(
                    currentPath = currentPath,
                    onNavigateToSegment = { segmentPath ->
                        val url = connectedUrl ?: return@BreadcrumbRow
                        // pop history until we reach the target
                        while (pathHistory.isNotEmpty() && currentPath != segmentPath) {
                            val prev = pathHistory.removeLastOrNull() ?: break
                            if (prev == segmentPath || prev.isEmpty()) {
                                currentPath = prev
                                loadFiles(url, prev)
                                break
                            }
                        }
                    },
                    onRefresh = { connectedUrl?.let { loadFiles(it, currentPath) } },
                    isLoading = isLoading
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }

            // ── File section label ────────────────────────────────────────────
            if (connectedUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REMOTE FILES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Content area ──────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            color = Teal700,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    error != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Ghost skeleton items
                            repeat(4) { SkeletonFileItem() }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Error message
                            Text(
                                text = error ?: "Connection failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // Retry button
                            OutlinedButton(
                                onClick = {
                                    error = null
                                    connectedUrl?.let { loadFiles(it, currentPath) }
                                },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry Connection", fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    connectedUrl == null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Ghost skeleton items
                            repeat(5) { SkeletonFileItem() }

                            Spacer(modifier = Modifier.height(32.dp))
                            Icon(
                                Icons.Filled.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Enter a server URL and tap Connect",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    files.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Text(
                                "Empty directory",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {
                        LazyColumn {
                            items(files, key = { it.path }) { item ->
                                FileListItem(
                                    item = item,
                                    canDownload = downloadFolder != null && !item.isDirectory,
                                    isDownloading = downloadingFile == item.name,
                                    downloadProgress = if (downloadingFile == item.name) downloadProgress else 0,
                                    onItemClick = {
                                        when {
                                            item.isDirectory -> navigateTo(item)
                                            isPreviewable(item.name) -> previewItem = item
                                        }
                                    },
                                    onDownload = { downloadFile(item) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Download progress bar ─────────────────────────────────────────
            if (downloadingFile != null) {
                HorizontalDivider()
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Teal700
                )
                Text(
                    text = "Downloading: $downloadingFile ($downloadProgress%)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Breadcrumb Row ────────────────────────────────────────────────────────────

@Composable
private fun BreadcrumbRow(
    currentPath: String,
    onNavigateToSegment: (String) -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            val segments = if (currentPath.isBlank()) emptyList()
            else currentPath.split("/").filter { it.isNotEmpty() }

            // Root chip
            item {
                BreadcrumbChip(
                    label = "root",
                    isLast = segments.isEmpty(),
                    onClick = { onNavigateToSegment("") }
                )
            }

            segments.forEachIndexed { index, segment ->
                item {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                item {
                    val segPath = segments.take(index + 1).joinToString("/")
                    BreadcrumbChip(
                        label = segment,
                        isLast = index == segments.lastIndex,
                        onClick = { onNavigateToSegment(segPath) }
                    )
                }
            }
        }

        if (!isLoading) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(label: String, isLast: Boolean, onClick: () -> Unit) {
    val bg = if (isLast) Teal700.copy(alpha = 0.15f) else Color.Transparent
    val fg = if (isLast) Teal700 else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = fg,
            maxLines = 1
        )
    }
}

// ── File List Item ────────────────────────────────────────────────────────────

@Composable
private fun FileListItem(
    item: FileItem,
    canDownload: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    onItemClick: () -> Unit,
    onDownload: () -> Unit
) {
    val isClickable = item.isDirectory || isPreviewable(item.name) || canDownload
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isClickable) { onItemClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Colored file type icon
        FileTypeIcon(name = item.name, isDirectory = item.isDirectory)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.isDirectory) {
                Text(
                    text = FileUtils.formatSize(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            item.isDirectory -> {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            isDownloading -> {
                CircularProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Teal700
                )
            }
            isPreviewable(item.name) -> {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = "Preview",
                        tint = Teal700,
                        modifier = Modifier.size(20.dp)
                    )
                    if (canDownload) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            canDownload -> {
                IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.FileDownload,
                        contentDescription = "Download",
                        tint = Teal700,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    )
}

// ── File Type Icon (colored rounded-square) ───────────────────────────────────

@Composable
fun FileTypeIcon(name: String, isDirectory: Boolean) {
    val ext = FileUtils.fileExt(name)
    val (bg, fg, icon) = when {
        isDirectory -> Triple(FolderIconBg, FolderIconFg, Icons.Filled.Folder)
        FileUtils.isImageFile(name) -> Triple(ImageIconBg, ImageIconFg, Icons.Filled.Image)
        FileUtils.isVideoFile(name) -> Triple(VideoIconBg, VideoIconFg, Icons.Filled.PlayCircle)
        FileUtils.isAudioFile(name) -> Triple(AudioIconBg, AudioIconFg, Icons.Filled.MusicNote)
        ext == "pdf" -> Triple(PdfIconBg, PdfIconFg, Icons.Filled.PictureAsPdf)
        ext in setOf("doc", "docx", "odt") -> Triple(DocIconBg, DocIconFg, Icons.Filled.Article)
        ext in setOf("xls", "xlsx", "csv") -> Triple(DocIconBg, DocIconFg, Icons.Filled.TableChart)
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Triple(ZipIconBg, ZipIconFg, Icons.Filled.Archive)
        else -> Triple(GenericIconBg, GenericIconFg, Icons.Filled.InsertDriveFile)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ── Skeleton placeholder item ─────────────────────────────────────────────────

@Composable
private fun SkeletonFileItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            )
        }
    }
}

// ── History Dialog ─────────────────────────────────────────────────────────────

@Composable
private fun ClientHistoryDialog(
    transferHistory: TransferHistory?,
    onDismiss: () -> Unit
) {
    val sessions = transferHistory?.getSessions() ?: emptyList()
    val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())

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
                                text = "Session: ${dateFormat.format(java.util.Date(session.startTime))}",
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
