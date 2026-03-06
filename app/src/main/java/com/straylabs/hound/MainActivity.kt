package com.straylabs.hound

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.straylabs.hound.server.LocalHttpServer
import com.straylabs.hound.server.ServerForegroundService
import com.straylabs.hound.ui.screens.ClientScreen
import com.straylabs.hound.ui.screens.FolderPickerDialog
import com.straylabs.hound.ui.screens.ServerScreen
import com.straylabs.hound.ui.theme.LANFileServerTheme
import com.straylabs.hound.util.NotificationHelper
import com.straylabs.hound.util.TransferHistory
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

private const val KEY_SERVER_FOLDER = "server_folder_path"
private const val KEY_CLIENT_USERNAME = "client_username"
private const val KEY_CLIENT_PASSWORD = "client_password"
private const val NOTIFICATION_PERMISSION_CODE = 100

class MainActivity : ComponentActivity() {

    private var httpServer: LocalHttpServer? = null
    private lateinit var transferHistory: TransferHistory
    private lateinit var notificationHelper: NotificationHelper

    private val prefs by lazy { getSharedPreferences("hound_prefs", MODE_PRIVATE) }

    private val hasFullStorageAccess = mutableStateOf(false)
    private val serverFolder = mutableStateOf<File?>(null)
    private val downloadFolder = mutableStateOf<File?>(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    )
    private val showServerFolderPicker = mutableStateOf(false)
    private val showDownloadFolderPicker = mutableStateOf(false)
    private val showPermissionRationale = mutableStateOf(false)

    private val serverAuthEnabled = mutableStateOf(false)
    private val serverAuthUsername = mutableStateOf("")
    private val serverAuthPassword = mutableStateOf("")

    private val clientCredentials = mutableStateOf<Pair<String, String>?>(null)

    private fun loadSavedCredentials() {
        val savedUser = prefs.getString(KEY_CLIENT_USERNAME, null)
        val savedPass = prefs.getString(KEY_CLIENT_PASSWORD, null)
        if (savedUser != null && savedPass != null) {
            clientCredentials.value = savedUser to savedPass
        }
    }

    private fun saveClientCredentials(creds: Pair<String, String>?) {
        if (creds != null) {
            prefs.edit()
                .putString(KEY_CLIENT_USERNAME, creds.first)
                .putString(KEY_CLIENT_PASSWORD, creds.second)
                .apply()
        } else {
            prefs.edit()
                .remove(KEY_CLIENT_USERNAME)
                .remove(KEY_CLIENT_PASSWORD)
                .apply()
        }
    }

    private var pendingPickerAfterPermission = false

    private val requestLegacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            hasFullStorageAccess.value = granted
            if (granted && pendingPickerAfterPermission) {
                showServerFolderPicker.value = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transferHistory = TransferHistory(this)
        transferHistory.startNewSession()
        notificationHelper = NotificationHelper(this)
        httpServer = LocalHttpServer(this, null, transferHistory = transferHistory)

        // Load saved credentials
        loadSavedCredentials()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
            }
        }

        // Load saved folder
        val savedPath = prefs.getString(KEY_SERVER_FOLDER, null)
        if (savedPath != null) {
            val file = File(savedPath)
            if (file.exists() && file.isDirectory) {
                serverFolder.value = file
                httpServer?.setRootDirectory(file)
            }
        }

        setContent {
            LANFileServerTheme {
                val serverDir by serverFolder
                val downloadDir by downloadFolder
                val showServerPicker by showServerFolderPicker
                val showDownloadPicker by showDownloadFolderPicker
                val showRationale by showPermissionRationale
                val authEnabled by serverAuthEnabled
                val authUsername by serverAuthUsername
                val authPassword by serverAuthPassword
                val savedClientCredentials by clientCredentials

                var serverRunning by remember { mutableStateOf(false) }
                var serverPort by remember { mutableStateOf(LocalHttpServer.DEFAULT_PORT) }
                var serverIp by remember { mutableStateOf<String?>(null) }
                var selectedTab by remember { mutableStateOf(0) }

                LaunchedEffect(Unit) { serverIp = getLocalIpAddress() }

                if (showRationale) {
                    AllFilesPermissionDialog(
                        onConfirm = {
                            showPermissionRationale.value = false
                            openAllFilesSettings()
                        },
                        onDismiss = { showPermissionRationale.value = false }
                    )
                }

                if (showServerPicker) {
                    FolderPickerDialog(
                        onFolderSelected = { dir ->
                            showServerFolderPicker.value = false
                            serverFolder.value = dir
                            httpServer?.setRootDirectory(dir)
                            prefs.edit().putString(KEY_SERVER_FOLDER, dir.absolutePath).apply()
                        },
                        onDismiss = { showServerFolderPicker.value = false }
                    )
                }

                if (showDownloadPicker) {
                    FolderPickerDialog(
                        onFolderSelected = { dir ->
                            showDownloadFolderPicker.value = false
                            downloadFolder.value = dir
                        },
                        onDismiss = { showDownloadFolderPicker.value = false }
                    )
                }

                if (!showServerPicker && !showDownloadPicker) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            AppTabRow(
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it }
                            )

                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = if (selectedTab == 0) Modifier.fillMaxSize()
                                    else Modifier.requiredSize(0.dp)
                                ) {
                                    ServerScreen(
                                        serverRunning = serverRunning,
                                        selectedPath = serverDir?.absolutePath,
                                        serverPort = serverPort,
                                        serverIp = serverIp,
                                        authEnabled = authEnabled,
                                        authUsername = authUsername,
                                        authPassword = authPassword,
                                        onStartServer = { port ->
                                            httpServer?.start()
                                            serverRunning = true
                                            ServerForegroundService.start(
                                                this@MainActivity, port, serverDir?.absolutePath
                                            )
                                            serverIp?.let { ip ->
                                                notificationHelper.showServerRunningNotification(ip, port)
                                            }
                                        },
                                        onStopServer = {
                                            httpServer?.stop()
                                            serverRunning = false
                                            ServerForegroundService.stop(this@MainActivity)
                                            notificationHelper.showServerStoppedNotification()
                                            transferHistory.clearCurrentSession()
                                        },
                                        onSelectFolder = { requestFolderPicker(isServer = true) },
                                        onPathChanged = { },
                                        onPortChanged = { port ->
                                            serverPort = port
                                            if (serverRunning) {
                                                httpServer?.stop()
                                                httpServer = LocalHttpServer(
                                                    this@MainActivity, serverDir, port, transferHistory
                                                )
                                                httpServer?.start()
                                            }
                                        },
                                        onAuthChanged = { enabled, user, pass ->
                                            serverAuthEnabled.value = enabled
                                            serverAuthUsername.value = user
                                            serverAuthPassword.value = pass
                                            httpServer?.credentials =
                                                if (enabled && user.isNotBlank() && pass.isNotBlank())
                                                    Pair(user, pass)
                                                else null
                                        },
                                        transferHistory = transferHistory
                                    )
                                }
                                Box(
                                    modifier = if (selectedTab == 1) Modifier.fillMaxSize()
                                    else Modifier.requiredSize(0.dp)
                                ) {
                                    ClientScreen(
                                        downloadFolder = downloadDir,
                                        savedCredentials = savedClientCredentials,
                                        onCredentialsSaved = { 
                                            clientCredentials.value = it
                                            saveClientCredentials(it)
                                        },
                                        onSelectDownloadFolder = {
                                            requestFolderPicker(isServer = false)
                                        },
                                        transferHistory = transferHistory,
                                        notificationHelper = notificationHelper
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasFullStorageAccess.value = checkAllFilesAccess()
    }

    private fun requestFolderPicker(isServer: Boolean) {
        if (checkAllFilesAccess()) {
            if (isServer) showServerFolderPicker.value = true
            else showDownloadFolderPicker.value = true
        } else {
            pendingPickerAfterPermission = isServer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                showPermissionRationale.value = true
            } else {
                requestLegacyStorage.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    private fun checkAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openAllFilesSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (e: ActivityNotFoundException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
    }
}

// ── Tab Row ───────────────────────────────────────────────────────────────────

@Composable
private fun AppTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp)
        ) {
            listOf("Server", "Client").forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selectedTab == index) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selectedTab == index)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}


// ── Permission Dialog ─────────────────────────────────────────────────────────

@Composable
private fun AllFilesPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
        title = { Text("Storage Access Required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "To browse and share any folder on your device, this app needs " +
                            "\"All Files Access\" permission.",
                    textAlign = TextAlign.Center
                )
                Text(
                    "Tap \"Open Settings\", then enable \"Allow access to manage all files\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Open Settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
