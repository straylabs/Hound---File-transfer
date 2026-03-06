package com.straylabs.hound.ui.screens

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Full-screen folder browser using java.io.File (requires MANAGE_EXTERNAL_STORAGE on API 30+,
 * or READ_EXTERNAL_STORAGE on API 26-29).
 *
 * Shows all directories (including SD cards, OTG drives, Downloads, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    onFolderSelected: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(storageRoot) }
    val pathHistory = remember { mutableStateListOf<File>() }

    // Pair<File, Int> — directory and its child count, computed once per directory change
    val subDirs = remember(currentDir) {
        currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?.map { dir -> dir to (dir.listFiles()?.size ?: 0) }
            ?: emptyList()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar
            TopAppBar(
                title = {
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (pathHistory.isNotEmpty()) {
                        IconButton(onClick = {
                            currentDir = pathHistory.removeLastOrNull() ?: currentDir
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go up")
                        }
                    } else {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { onFolderSelected(currentDir) }) {
                        Text("Select")
                    }
                }
            )

            // Current folder info banner
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider()

            // Directory list
            Box(modifier = Modifier.weight(1f)) {
                if (subDirs.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.FolderOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No subfolders here",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(subDirs, key = { it.first.absolutePath }) { (dir, childCount) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pathHistory.add(currentDir)
                                        currentDir = dir
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (childCount > 0) "$childCount items" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                        }
                    }
                }
            }

            HorizontalDivider()

            // Select this folder button
            Button(
                onClick = { onFolderSelected(currentDir) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select \"${currentDir.name.ifEmpty { "Root" }}\"")
            }
        }
    }
}
