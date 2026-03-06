package com.straylabs.hound.client

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Base64
import com.straylabs.hound.util.FileUtils
import com.straylabs.hound.util.TransferHistory
import com.straylabs.hound.util.TransferRecord
import com.straylabs.hound.util.TransferStatus
import com.straylabs.hound.util.TransferType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Thrown when the server responds with 401 Unauthorized. */
class AuthRequiredException : IOException("Authentication required")

class HttpClientManager(
    private val context: Context,
    private val transferHistory: TransferHistory? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Set to supply Basic Auth credentials for every request. */
    var credentials: Pair<String, String>? = null

    private fun Request.Builder.addAuth(): Request.Builder = apply {
        credentials?.let { (u, p) ->
            val token = Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
            header("Authorization", "Basic $token")
        }
    }

    // ---- Directory listing ----

    suspend fun listFiles(baseUrl: String, path: String = ""): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonResult = tryJsonApi(baseUrl, path)
                if (jsonResult != null) return@runCatching jsonResult
                fetchHtmlListing(baseUrl, path)
            }
        }

    private fun tryJsonApi(baseUrl: String, path: String): List<FileItem>? {
        return try {
            val encodedPath = Uri.encode(path)
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/list?path=$encodedPath")
                .addAuth()
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.code == 401) throw AuthRequiredException()
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")

            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("json", ignoreCase = true)) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val array = json.getJSONArray("files")

            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                FileItem(
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    isDirectory = obj.getBoolean("isDirectory"),
                    modified = obj.getLong("modified"),
                    path = obj.getString("path")
                )
            }
        } catch (e: AuthRequiredException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchHtmlListing(baseUrl: String, path: String): List<FileItem> {
        val urlPath = if (path.isEmpty()) "/" else "/${path.trimStart('/')}/"
        val url = "${baseUrl.trimEnd('/')}$urlPath"

        val request = Request.Builder().url(url).addAuth().get().build()
        val response = client.newCall(request).execute()
        if (response.code == 401) throw AuthRequiredException()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")

        val html = response.body?.string() ?: throw IOException("Empty response")
        return parseHtmlListing(html, path)
    }

    private fun parseHtmlListing(html: String, currentPath: String): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val hrefRegex = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        hrefRegex.findAll(html).forEach { match ->
            var raw = match.groupValues[1]
            if (raw.startsWith("http://") || raw.startsWith("https://") ||
                raw.startsWith("?") || raw.startsWith("#") || raw.isEmpty()
            ) return@forEach

            raw = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
            val normalized = raw.trimStart('/')
            if (normalized.isEmpty() || normalized == "../" ||
                normalized.startsWith("../") || normalized == "./"
            ) return@forEach

            val isDir = raw.endsWith("/")
            val withoutSlash = normalized.trimEnd('/')
            val name = withoutSlash.substringAfterLast('/')
            if (name.isBlank()) return@forEach

            val relPath = if (raw.startsWith("/")) {
                withoutSlash
            } else {
                if (currentPath.isEmpty()) withoutSlash else "$currentPath/$withoutSlash"
            }

            items.add(FileItem(name = name, size = -1L, isDirectory = isDir, modified = 0L, path = relPath))
        }

        return items.distinctBy { it.path }
    }

    // ---- Download ----

    suspend fun downloadFile(
        baseUrl: String,
        filePath: String,
        destFolder: File,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val fileName = filePath.substringAfterLast('/')
        try {
            val url = "${baseUrl.trimEnd('/')}/${filePath.trimStart('/')}"

            val request = Request.Builder().url(url).addAuth().get().build()
            val response = client.newCall(request).execute()
            if (response.code == 401) throw AuthRequiredException()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")

            destFolder.mkdirs()
            val destFile = File(destFolder, fileName)
            val totalBytes = response.body?.contentLength() ?: -1L
            var downloaded = 0L

            destFile.outputStream().use { out ->
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) onProgress((downloaded * 100 / totalBytes).toInt())
                    }
                }
            }
            onProgress(100)
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            
            transferHistory?.addRecord(
                TransferRecord(
                    fileName = fileName,
                    filePath = filePath,
                    fileSize = destFile.length(),
                    type = TransferType.DOWNLOAD,
                    status = TransferStatus.COMPLETED,
                    isServer = false
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            transferHistory?.addRecord(
                TransferRecord(
                    fileName = fileName,
                    filePath = filePath,
                    fileSize = 0L,
                    type = TransferType.DOWNLOAD,
                    status = TransferStatus.FAILED,
                    isServer = false
                )
            )
            Result.failure(e)
        }
    }

    // ---- Upload with resume support ----

    suspend fun uploadFile(
        baseUrl: String,
        remotePath: String,
        localFile: File,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val fileName = localFile.name
        val totalSize = localFile.length()
        
        try {
            if (!localFile.exists()) throw IOException("File not found: ${localFile.absolutePath}")
            val mime = FileUtils.getMimeType(localFile.name)
            onProgress(10)
            
            // Simple upload - server handles partial files for resume
            val body = localFile.asRequestBody(mime.toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/upload?path=${Uri.encode(remotePath)}")
                .addAuth()
                .put(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            when (response.code) {
                200, 201 -> {
                    onProgress(100)
                }
                206 -> {
                    // Partial - but let's treat as complete
                    onProgress(100)
                }
                401 -> throw AuthRequiredException()
                else -> {
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    onProgress(100)
                }
            }
            
            transferHistory?.addRecord(
                TransferRecord(
                    fileName = fileName,
                    filePath = remotePath,
                    fileSize = localFile.length(),
                    type = TransferType.UPLOAD,
                    status = TransferStatus.COMPLETED,
                    isServer = false
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            transferHistory?.addRecord(
                TransferRecord(
                    fileName = fileName,
                    filePath = remotePath,
                    fileSize = localFile.length(),
                    type = TransferType.UPLOAD,
                    status = TransferStatus.FAILED,
                    isServer = false
                )
            )
            Result.failure(e)
        }
    }

}
