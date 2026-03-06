package com.straylabs.hound.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

class SafManager(private val context: Context) {

    fun getFileFromUri(uri: Uri): File? {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            if (documentFile?.canRead() == true) {
                persistUriPermissions(uri)
                uriToFile(uri)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val path = if (split.size > 1) split[1] else ""

            when (type) {
                "primary" -> {
                    File("/storage/emulated/0/$path")
                }
                else -> {
                    val externalDirs = context.getExternalFilesDirs(null)
                    externalDirs.firstOrNull()?.let { File(it, path) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun persistUriPermissions(uri: Uri) {
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            val uris = prefs.getStringSet("persisted_uris", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            uris.add(uri.toString())
            prefs.edit().putStringSet("persisted_uris", uris).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getPersistedUri(): Uri? {
        val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val uris = prefs.getStringSet("persisted_uris", emptySet()) ?: emptySet()
        return uris.firstOrNull()?.let { Uri.parse(it) }
    }

    fun hasPersistedUri(): Boolean {
        return getPersistedUri() != null
    }

    fun listFiles(uri: Uri): List<DocumentFile>? {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            documentFile?.listFiles()?.toList()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
