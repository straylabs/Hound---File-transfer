package com.straylabs.hound.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.straylabs.hound.R
import com.straylabs.hound.util.FileUtils
import com.straylabs.hound.util.TransferHistory
import com.straylabs.hound.util.TransferRecord
import com.straylabs.hound.util.TransferStatus
import com.straylabs.hound.util.TransferType
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalHttpServer(
    private val context: Context,
    private var rootDirectory: File?,
    port: Int = DEFAULT_PORT,
    private val transferHistory: TransferHistory? = null
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8080
        // DateTimeFormatter is immutable and thread-safe (unlike SimpleDateFormat)
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    // @Volatile ensures visibility across NanoHTTPD's worker threads
    @Volatile var credentials: Pair<String, String>? = null

    fun setRootDirectory(directory: File?) { this.rootDirectory = directory }
    fun getRootDirectory(): File? = rootDirectory

    override fun serve(session: IHTTPSession): Response {
        // Check Basic Auth if credentials are configured
        credentials?.let { (user, pass) ->
            val authHeader = session.headers["authorization"] ?: ""
            val expected = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            if (authHeader != expected) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_HTML,
                    "<html><body><h2>401 Unauthorized</h2></body></html>"
                ).apply {
                    addHeader("WWW-Authenticate", "Basic realm=\"LAN File Server\"")
                }
            }
        }

        val uri = session.uri
        return when {
            uri.startsWith("/api/list") -> serveJsonList(session)
            uri.startsWith("/upload") && (session.method == Method.PUT || session.method == Method.POST) -> handleUpload(session)
            uri.startsWith("/delete") && session.method == Method.DELETE -> handleDelete(session)
            uri == "/.app-icon" -> serveAppIcon()
            else -> serveStaticContent(session)
        }
    }

    // ---- Static content routing ----

    private fun serveStaticContent(session: IHTTPSession): Response {
        val uri = session.uri.trim('/')
        val root = rootDirectory ?: return errorHtml(Response.Status.SERVICE_UNAVAILABLE, "No folder selected")
        val file = if (uri.isEmpty()) root else File(root, uri)
        if (!file.exists()) return errorHtml(Response.Status.NOT_FOUND, "404 – Not Found")
        return when {
            file.isDirectory -> serveDirectory(session, file)
            file.isFile -> serveFile(session, file)
            else -> errorHtml(Response.Status.FORBIDDEN, "403 – Forbidden")
        }
    }

    private fun serveDirectory(session: IHTTPSession, dir: File): Response {
        val html = buildGalleryListing(session, dir)
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
        }
    }

    // ---- File serving with Range support ----

    private fun serveFile(session: IHTTPSession, file: File): Response {
        val mime = FileUtils.getMimeType(file.name)
        val isMedia = FileUtils.isImageFile(file.name) || FileUtils.isVideoFile(file.name) || FileUtils.isAudioFile(file.name)
        val rangeHeader = session.headers["range"]
        
        return try {
            val response = if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                servePartial(file, rangeHeader, mime)
            } else {
                newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length()).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    if (!isMedia) addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                }
            }
            
            // Only track actual downloads (non-media files with Content-Disposition: attachment)
            // Media files served inline are just previews, not downloads
            if (!isMedia) {
                transferHistory?.addRecord(
                    TransferRecord(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        fileSize = file.length(),
                        type = TransferType.DOWNLOAD,
                        status = TransferStatus.COMPLETED,
                        isServer = true
                    )
                )
            }
            
            response
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun servePartial(file: File, rangeHeader: String, mime: String): Response {
        val fileLen = file.length()
        val range = rangeHeader.removePrefix("bytes=")
        val dash = range.indexOf('-')
        val start = if (dash == 0) 0L else range.substring(0, dash).toLongOrNull() ?: 0L
        val end = (if (dash == range.length - 1) fileLen - 1L
                   else range.substring(dash + 1).toLongOrNull() ?: (fileLen - 1L))
            .coerceAtMost(fileLen - 1L)
        val contentLen = end - start + 1L
        return try {
            val fis = FileInputStream(file).also { it.skip(start) }
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLen).apply {
                addHeader("Content-Range", "bytes $start-$end/$fileLen")
                addHeader("Accept-Ranges", "bytes")
                addHeader("Cache-Control", "no-cache")
            }
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // ---- App icon ----

    private fun serveAppIcon(): Response {
        return try {
            val bm = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_foreground)
            val baos = java.io.ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            newFixedLengthResponse(Response.Status.OK, "image/png",
                java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
            ).apply { addHeader("Cache-Control", "public, max-age=86400") }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Icon not found")
        }
    }

    // ---- Gallery HTML ----

    // ---- Recursive search helper ----

    private fun collectAllFilesRecursive(directory: File, query: String): List<File> {
        val results = mutableListOf<File>()
        val queryLower = query.lowercase()
        
        fun traverse(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    traverse(file)
                } else if (file.name.lowercase().contains(queryLower)) {
                    results.add(file)
                }
            }
        }
        
        traverse(directory)
        return results
    }

    private fun buildGalleryListing(session: IHTTPSession, directory: File): String {
        val currentPath = session.uri.trim('/')
        val displayPath = if (currentPath.isEmpty()) "/" else "/$currentPath"
        val searchQuery = session.parameters["search"]?.firstOrNull()?.lowercase() ?: ""

        val allFiles = directory.listFiles() ?: emptyArray()
        
        // Check if this is a recursive search
        val recursiveSearch = session.parameters["recursive"]?.firstOrNull()?.toBoolean() == true

        // Collect all files recursively if searching
        val allDescendants = if (searchQuery.isNotEmpty() && recursiveSearch) {
            collectAllFilesRecursive(directory, searchQuery)
        } else {
            emptyList()
        }

        fun matchesSearch(name: String): Boolean {
            return searchQuery.isEmpty() || name.lowercase().contains(searchQuery)
        }

        // Sort: folders first alphabetically, media newest first
        val folders = if (searchQuery.isEmpty()) {
            allFiles.filter { it.isDirectory && matchesSearch(it.name) }.sortedBy { it.name.lowercase() }
        } else if (recursiveSearch) {
            // In recursive search mode, show matched files from subdirectories
            emptyList()
        } else {
            allFiles.filter { it.isDirectory && matchesSearch(it.name) }.sortedBy { it.name.lowercase() }
        }
        
        val images  = if (recursiveSearch && searchQuery.isNotEmpty()) {
            allDescendants.filter { FileUtils.isImageFile(it.name) }.sortedByDescending { it.lastModified() }
        } else {
            allFiles.filter { it.isFile && FileUtils.isImageFile(it.name) && matchesSearch(it.name) }.sortedByDescending { it.lastModified() }
        }
        val videos  = if (recursiveSearch && searchQuery.isNotEmpty()) {
            allDescendants.filter { FileUtils.isVideoFile(it.name) }.sortedByDescending { it.lastModified() }
        } else {
            allFiles.filter { it.isFile && FileUtils.isVideoFile(it.name) && matchesSearch(it.name) }.sortedByDescending { it.lastModified() }
        }
        val audios  = if (recursiveSearch && searchQuery.isNotEmpty()) {
            allDescendants.filter { FileUtils.isAudioFile(it.name) }.sortedBy { it.name.lowercase() }
        } else {
            allFiles.filter { it.isFile && FileUtils.isAudioFile(it.name) && matchesSearch(it.name) }.sortedBy { it.name.lowercase() }
        }
        val others  = if (recursiveSearch && searchQuery.isNotEmpty()) {
            allDescendants.filter { !FileUtils.isPreviewable(it.name) }.sortedBy { it.name.lowercase() }
        } else {
            allFiles.filter { it.isFile && !FileUtils.isPreviewable(it.name) && matchesSearch(it.name) }.sortedBy { it.name.lowercase() }
        }

        val totalFiltered = folders.size + images.size + videos.size + audios.size + others.size
        val searchActive = searchQuery.isNotEmpty()

        fun href(f: File): String {
            if (searchActive && recursiveSearch) {
                // For recursive search, calculate relative path from current directory
                val relativePath = f.absolutePath.substringAfter(directory.absolutePath).trimStart('/')
                return relativePath
            }
            return if (currentPath.isEmpty()) f.name else "$currentPath/${f.name}"
        }
        fun safeHref(f: File) = "/${href(f).htmlEscape()}"

        val imgPathsJson = images.joinToString(",") { "\"${href(it).jsEscape()}\"" }

        val parentHref = if (currentPath.isNotEmpty()) {
            "/" + currentPath.substringBeforeLast("/", "")
        } else null

        // ---- Breadcrumb ----
        val breadcrumbHtml = buildString {
            append("""<a href="/" class="bc-home">&#8962;</a>""")
            if (currentPath.isNotEmpty()) {
                val parts = currentPath.split("/").filter { it.isNotEmpty() }
                var accum = ""
                parts.forEachIndexed { i, part ->
                    accum += if (accum.isEmpty()) part else "/$part"
                    append("""<span class="bc-sep">&#8250;</span>""")
                    if (i == parts.size - 1) {
                        append("""<span class="bc-cur">${part.htmlEscape()}</span>""")
                    } else {
                        append("""<a href="/${accum.htmlEscape()}" class="bc-item">${part.htmlEscape()}</a>""")
                    }
                }
            } else {
                append("""<span class="bc-sep">&#8250;</span><span class="bc-cur">root</span>""")
            }
        }

        // ---- Sidebar folders ----
        val sidebarFoldersHtml = buildString {
            if (parentHref != null) {
                append("""<a class="fitem up-link" href="${parentHref.htmlEscape()}">&#8593; .. parent folder</a>""")
            }
            if (folders.isEmpty() && parentHref == null) {
                append("""<p class="no-items">No subfolders</p>""")
            } else {
                folders.forEach { f ->
                    append("""<a class="fitem" href="${safeHref(f)}"><svg class="fi-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 7c0-1.1.9-2 2-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>${f.name.htmlEscape()}</a>""")
                }
            }
        }

        // ---- Image section ----
        val imageSection = if (images.isEmpty()) "" else buildString {
            append("""<div class="card"><div class="sec-hdr"><div class="sec-ico"><svg viewBox="0 0 24 24" fill="none" stroke="#00897B" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg></div><span class="sec-title">Images</span><span class="sec-cnt">${images.size} items</span><div class="sp"></div><div class="vtog"><button class="vbtn active" id="gridBtn" onclick="setView('grid')" title="Grid"><svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><rect x="1" y="1" width="5" height="5" rx="1"/><rect x="9" y="1" width="5" height="5" rx="1"/><rect x="1" y="9" width="5" height="5" rx="1"/><rect x="9" y="9" width="5" height="5" rx="1"/></svg></button><button class="vbtn" id="listBtn" onclick="setView('list')" title="List"><svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><rect x="1" y="2" width="14" height="2" rx="1"/><rect x="1" y="7" width="14" height="2" rx="1"/><rect x="1" y="12" width="14" height="2" rx="1"/></svg></button></div></div>""")
            append("""<div class="mgrid" id="imgGrid">""")
            images.forEachIndexed { i, f ->
                val h = safeHref(f); val n = f.name.htmlEscape()
                val js = href(f).jsEscape(); val fn = f.name.jsEscape()
                val hidden = if (i >= 20) """ data-extra="1" style="display:none"""" else ""
                append("""<div class="mc"${hidden} onclick="openImg($i,'${js}','${fn}')"><img src="${h}?t=${f.lastModified()}" alt="${n}" loading="lazy" onerror="this.style.opacity='.25'"><div class="ml">${n}</div><div class="mc-acts"><a class="dlb" href="${h}" download="${n}" onclick="event.stopPropagation()" title="Download"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" width="11" height="11"><path d="M12 5v10M8 15l4 4 4-4M5 19h14"/></svg></a><button class="mc-del" onclick="event.stopPropagation();doDelete('${js}')" title="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="11" height="11"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6M14 11v6M9 6V4h6v2"/></svg></button></div></div>""")
            }
            append("</div>")
            if (images.size > 20) append("""<div class="lm-wrap"><button class="lm-btn" onclick="loadMore()">Load More &nbsp;(${images.size - 20} remaining)</button></div>""")
            append("</div>")
        }

        // ---- Video section ----
        val videoSection = if (videos.isEmpty()) "" else buildString {
            append("""<div class="card"><div class="sec-hdr"><div class="sec-ico"><svg viewBox="0 0 24 24" fill="none" stroke="#00897B" stroke-width="2" stroke-linecap="round"><rect x="2" y="5" width="15" height="14" rx="2"/><path d="M17 9l5-4v14l-5-4"/></svg></div><span class="sec-title">Videos</span><span class="sec-cnt">${videos.size} items</span></div><div class="mgrid">""")
            videos.forEach { f ->
                val h = safeHref(f); val n = f.name.htmlEscape()
                val js = href(f).jsEscape(); val fn = f.name.jsEscape()
                append("""<div class="mc" onclick="openVid('${js}','${fn}')"><div class="vthumb"><div class="play-ico">&#9654;</div><div class="vsz">${FileUtils.formatSize(f.length())}</div></div><div class="ml">${n}</div><div class="mc-acts"><a class="dlb" href="${h}" download="${n}" onclick="event.stopPropagation()" title="Download"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" width="11" height="11"><path d="M12 5v10M8 15l4 4 4-4M5 19h14"/></svg></a><button class="mc-del" onclick="event.stopPropagation();doDelete('${js}')" title="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="11" height="11"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6M14 11v6M9 6V4h6v2"/></svg></button></div></div>""")
            }
            append("</div></div>")
        }

        // ---- Audio section ----
        val audioSection = if (audios.isEmpty()) "" else buildString {
            append("""<div class="card"><div class="sec-hdr"><div class="sec-ico"><svg viewBox="0 0 24 24" fill="none" stroke="#00897B" stroke-width="2" stroke-linecap="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg></div><span class="sec-title">Audio</span><span class="sec-cnt">${audios.size} items</span></div><div class="alist">""")
            audios.forEach { f ->
                val h = safeHref(f); val n = f.name.htmlEscape()
                append("""<div class="aitem"><div class="ainfo"><div class="aico">&#9835;</div><span class="an">${n}</span><span class="asz">${FileUtils.formatSize(f.length())}</span><button class="delbtn" onclick="doDelete('${href(f).jsEscape()}')" title="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="12" height="12"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6M14 11v6M9 6V4h6v2"/></svg></button></div><audio controls preload="none" style="width:100%"><source src="${h}"></audio><a class="dllink" href="${h}" download="${n}">&#8595; Download</a></div>""")
            }
            append("</div></div>")
        }

        // ---- Files section ----
        val fileSection = if (others.isEmpty()) "" else buildString {
            append("""<div class="card"><div class="sec-hdr"><div class="sec-ico"><svg viewBox="0 0 24 24" fill="none" stroke="#00897B" stroke-width="2" stroke-linecap="round"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg></div><span class="sec-title">Files</span><span class="sec-cnt">${others.size} items</span></div><div class="flist">""")
            others.forEach { f ->
                val h = safeHref(f); val n = f.name.htmlEscape(); val js = href(f).jsEscape()
                val mod = DATE_FMT.format(Instant.ofEpochMilli(f.lastModified()))
                append("""<div class="fi"><span class="fic">${fileIcon(f.name)}</span><a href="${h}" class="fn" download="${n}">${n}</a><span class="fm">${FileUtils.formatSize(f.length())} &middot; ${mod}</span><button class="delbtn" onclick="doDelete('${js}')" title="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="13" height="13"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6M14 11v6M9 6V4h6v2"/></svg></button></div>""")
            }
            append("</div></div>")
        }

        val emptyMain = if (images.isEmpty() && videos.isEmpty() && audios.isEmpty() && others.isEmpty()) {
            """<div class="card" style="text-align:center;padding:48px 24px;color:#9E9E9E"><div style="font-size:48px;margin-bottom:12px">&#128193;</div><div style="font-size:15px;font-weight:600;color:#616161;margin-bottom:6px">Empty folder</div><div style="font-size:13px">Upload files using the panel on the left.</div></div>"""
        } else ""

        val host = session.headers["host"] ?: "device:8080"
        val serverUrlRaw = "http://$host/"

        return """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Cache-Control" content="no-cache,no-store,must-revalidate">
<title>Hound &middot; ${displayPath.htmlEscape()}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#F5F5F5;color:#212121;min-height:100vh}
a{text-decoration:none;color:inherit}
/* Navbar */
.navbar{background:#fff;border-bottom:1px solid #E0E0E0;padding:0 20px;height:56px;display:flex;align-items:center;gap:12px;position:sticky;top:0;z-index:30}
.nav-logo{width:32px;height:32px;background:#00897B;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#fff;font-size:18px;flex-shrink:0}
.nav-brand{font-size:17px;font-weight:700;color:#212121}
/* Breadbar */
.breadbar{background:#fff;border-bottom:1px solid #E0E0E0;padding:0 20px;min-height:48px;display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.breadcrumbs{flex:1;display:flex;align-items:center;gap:5px;font-size:13px;min-width:0;padding:8px 0}
.bc-home{color:#757575;font-size:15px}.bc-sep{color:#BDBDBD;font-size:12px}
.bc-item{color:#00897B}.bc-item:hover{text-decoration:underline}
.bc-cur{color:#212121;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sbadge{background:#E8F5E9;color:#2E7D32;font-size:11px;font-weight:700;padding:4px 10px;border-radius:20px;display:flex;align-items:center;gap:5px;white-space:nowrap;flex-shrink:0}
.sdot{width:6px;height:6px;background:#4CAF50;border-radius:50%;animation:blink 2s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.4}}
.search-box{position:relative;flex-shrink:0}
.search-inp{background:#F5F5F5;border:1px solid #E0E0E0;color:#212121;padding:6px 12px 6px 32px;border-radius:8px;font-size:12px;width:160px;transition:.15s}
.search-inp:focus{outline:none;border-color:#00897B;width:200px;background:#fff}
.search-inp::placeholder{color:#9E9E9E}
.search-icon{position:absolute;left:8px;top:50%;transform:translateY(-50%);color:#9E9E9E;font-size:14px;pointer-events:none}
.search-clear{position:absolute;right:6px;top:50%;transform:translateY(-50%);background:none;border:none;color:#9E9E9E;cursor:pointer;font-size:14px;padding:2px 6px;display:none}
.search-clear.show{display:block}
.search-rec{display:flex;align-items:center;gap:4px;font-size:11px;color:#757575;cursor:pointer;white-space:nowrap;flex-shrink:0}
.search-rec input{width:14px;height:14px;accent-color:#00897B}
.search-badge{background:#00897B;color:#fff;font-size:10px;padding:2px 6px;border-radius:10px;margin-left:4px}
.autolbl{display:flex;align-items:center;gap:6px;font-size:12px;color:#757575;cursor:pointer;white-space:nowrap;flex-shrink:0}
.tog{position:relative;width:34px;height:18px;flex-shrink:0}
.tog input{opacity:0;width:0;height:0}
.tog-s{position:absolute;inset:0;background:#BDBDBD;border-radius:9px;cursor:pointer;transition:.2s}
.tog-s:before{content:'';position:absolute;width:14px;height:14px;left:2px;top:2px;background:#fff;border-radius:50%;transition:.2s}
.tog input:checked + .tog-s{background:#00897B}
.tog input:checked + .tog-s:before{transform:translateX(16px)}
.hbtn{background:#F5F5F5;border:1px solid #E0E0E0;color:#424242;padding:6px 12px;border-radius:8px;cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:.15s;flex-shrink:0}
.hbtn:hover{background:#E0E0E0}
/* Layout */
.layout{display:flex;align-items:flex-start;min-height:calc(100vh - 104px)}
.sidebar{width:270px;min-width:270px;background:#fff;border-right:1px solid #E0E0E0;padding:16px;display:flex;flex-direction:column;gap:16px;position:sticky;top:104px;max-height:calc(100vh - 104px);overflow-y:auto}
.content{flex:1;padding:20px;min-width:0}
/* Sidebar */
.s-lbl{font-size:10px;font-weight:700;color:#9E9E9E;letter-spacing:1px;text-transform:uppercase;margin-bottom:8px;display:flex;align-items:center;gap:5px}
.s-lbl svg{width:13px;height:13px;stroke:#9E9E9E}
.flist-s{display:flex;flex-direction:column;gap:1px}
.fitem{display:flex;align-items:center;gap:8px;padding:7px 10px;border-radius:8px;font-size:13px;color:#424242;transition:.15s}
.fitem:hover{background:#E0F2F1;color:#00897B}
.fitem.up-link{color:#9E9E9E;font-size:12px}
.fi-ico{width:17px;height:17px;flex-shrink:0;color:#9E9E9E}
.fitem:hover .fi-ico{color:#00897B}
.no-items{font-size:12px;color:#BDBDBD;text-align:center;padding:6px 0}
/* Upload box */
.upl-box{border:2px dashed #80CBC4;border-radius:12px;padding:14px;background:#F0FDFB;transition:.2s}
.upl-box.drag-over{border-color:#00897B;background:#E0F2F1}
.upl-title{font-size:10px;font-weight:700;color:#00897B;letter-spacing:.8px;text-transform:uppercase;display:flex;align-items:center;gap:5px;margin-bottom:6px}
.upl-sub{font-size:11px;color:#757575;text-align:center;margin-bottom:10px}
.upl-btn{width:100%;background:#00897B;color:#fff;border:none;padding:10px;border-radius:8px;cursor:pointer;font-size:13px;font-weight:600;display:flex;align-items:center;justify-content:center;gap:5px;transition:.15s}
.upl-btn:hover{background:#00796B}
#upst{font-size:11px;color:#757575;margin-top:6px;text-align:center;min-height:15px}
/* Warn box */
.warn-box{background:#FFF3E0;border-radius:8px;padding:10px 12px;display:flex;gap:8px;align-items:flex-start}
.warn-ico{color:#F57C00;font-size:14px;flex-shrink:0;margin-top:1px}
.warn-txt{font-size:11px;color:#E65100;line-height:1.5}
/* Content cards */
.card{background:#fff;border-radius:12px;padding:16px 18px;margin-bottom:14px;border:1px solid #E0E0E0}
.sec-hdr{display:flex;align-items:center;gap:8px;margin-bottom:14px}
.sec-ico{width:28px;height:28px;background:#E0F2F1;border-radius:6px;display:flex;align-items:center;justify-content:center;flex-shrink:0}
.sec-ico svg{width:15px;height:15px}
.sec-title{font-size:12px;font-weight:700;color:#212121;text-transform:uppercase;letter-spacing:.5px}
.sec-cnt{background:#F5F5F5;color:#757575;font-size:11px;padding:2px 8px;border-radius:10px}
.sp{flex:1}
.vtog{display:flex;gap:3px}
.vbtn{background:none;border:1px solid #E0E0E0;color:#9E9E9E;width:26px;height:26px;border-radius:6px;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:.15s;padding:0}
.vbtn.active,.vbtn:hover{background:#E0F2F1;color:#00897B;border-color:#80CBC4}
/* Media grid */
.mgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:8px}
.mc{position:relative;background:#FAFAFA;border-radius:8px;overflow:hidden;cursor:pointer;border:1px solid #EEEEEE;transition:.15s}
.mc:hover{border-color:#80CBC4;transform:translateY(-2px);box-shadow:0 4px 12px rgba(0,137,123,.12)}
.mc img{width:100%;aspect-ratio:1;object-fit:cover;display:block}
.vthumb{width:100%;aspect-ratio:1;background:#1A237E;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:5px}
.play-ico{font-size:28px;color:rgba(255,255,255,.85)}.vsz{font-size:10px;color:rgba(255,255,255,.5)}
.ml{padding:5px 6px;font-size:10px;color:#757575;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mc-acts{position:absolute;top:4px;right:4px;display:flex;gap:3px;opacity:0;transition:.15s}
.mc:hover .mc-acts{opacity:1}
.dlb,.mc-del{background:rgba(0,0,0,.55);color:#fff;border:none;border-radius:50%;width:26px;height:26px;display:flex;align-items:center;justify-content:center;cursor:pointer;transition:.15s;text-decoration:none;flex-shrink:0}
.mc-del:hover{background:rgba(211,47,47,.9)}
.dlb:hover{background:rgba(0,121,107,.9)}
/* List view mode */
.mgrid.list-mode{grid-template-columns:1fr !important;gap:2px}
.mgrid.list-mode .mc{display:flex;flex-direction:row;align-items:center;height:46px;padding:0 10px;gap:10px;border-radius:6px}
.mgrid.list-mode .mc:hover{transform:none}
.mgrid.list-mode .mc img{width:34px;height:34px;aspect-ratio:auto;flex-shrink:0;border-radius:4px}
.mgrid.list-mode .vthumb{width:34px;height:34px;aspect-ratio:auto;flex-shrink:0;border-radius:4px}
.mgrid.list-mode .play-ico{font-size:13px}.mgrid.list-mode .vsz{display:none}
.mgrid.list-mode .ml{flex:1;padding:0;font-size:12px;color:#424242}
.mgrid.list-mode .mc-acts{position:static;opacity:1;gap:4px}
.mgrid.list-mode .dlb,.mgrid.list-mode .mc-del{background:none;color:#BDBDBD;border-radius:4px;width:28px;height:28px}
.mgrid.list-mode .mc-del:hover{color:#D32F2F;background:#FFEBEE}
.mgrid.list-mode .dlb:hover{color:#00897B;background:#E0F2F1}
/* Audio */
.alist{display:flex;flex-direction:column;gap:6px}
.aitem{border:1px solid #E0E0E0;border-radius:8px;padding:12px}
.ainfo{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.aico{width:30px;height:30px;background:#E0F2F1;border-radius:6px;display:flex;align-items:center;justify-content:center;color:#00897B;font-size:15px;flex-shrink:0}
.an{flex:1;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.asz{font-size:11px;color:#9E9E9E}
.dllink{font-size:11px;color:#00897B;margin-top:6px;display:inline-block}
/* File list */
.flist{display:flex;flex-direction:column;gap:5px}
.fi{display:flex;align-items:center;gap:10px;border:1px solid #E0E0E0;border-radius:8px;padding:9px 12px;transition:.15s}
.fi:hover{background:#FAFAFA;border-color:#BDBDBD}
.fic{font-size:15px;min-width:22px;text-align:center}
.fn{flex:1;font-size:13px;color:#212121;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.fn:hover{color:#00897B}
.fm{font-size:11px;color:#9E9E9E;white-space:nowrap}
.delbtn{background:none;border:none;color:#BDBDBD;cursor:pointer;font-size:12px;padding:4px 6px;border-radius:4px;transition:.15s}
.delbtn:hover{color:#F44336;background:#FFEBEE}
/* Load more */
.lm-wrap{text-align:center;margin-top:14px}
.lm-btn{background:#F5F5F5;border:1px solid #E0E0E0;color:#424242;padding:9px 28px;border-radius:24px;cursor:pointer;font-size:13px;transition:.15s}
.lm-btn:hover{background:#E0E0E0}
/* QR FAB */
.qr-fab{position:fixed;bottom:24px;right:24px;width:52px;height:52px;background:#00897B;color:#fff;border:none;border-radius:50%;cursor:pointer;display:flex;align-items:center;justify-content:center;box-shadow:0 4px 16px rgba(0,137,123,.4);transition:.2s;z-index:20}
.qr-fab:hover{background:#00796B;transform:scale(1.06)}
/* Lightbox */
#modal{position:fixed;inset:0;z-index:1000;display:none;flex-direction:column;background:rgba(0,0,0,.9)}
#modal.open{display:flex}
#mbar{display:flex;align-items:center;gap:8px;padding:10px 16px;background:rgba(0,0,0,.5);min-height:48px}
#mtitle{flex:1;font-size:13px;color:#fff;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mbtn{background:none;border:none;color:#fff;cursor:pointer;font-size:18px;padding:6px 10px;border-radius:6px;transition:.15s}
.mbtn:hover{background:rgba(255,255,255,.15)}
#mbody{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;padding:8px;position:relative}
#mbody img{max-width:100%;max-height:100%;object-fit:contain}
#mbody video{max-width:100%;max-height:100%}
#mprev,#mnext{position:absolute;top:50%;transform:translateY(-50%);background:rgba(0,0,0,.45);border:none;color:#fff;font-size:26px;cursor:pointer;padding:10px 13px;border-radius:8px;transition:.15s;z-index:2}
#mprev{left:8px}#mnext{right:8px}
#mprev:hover,#mnext:hover{background:rgba(0,137,123,.55)}
/* URL modal */
#urlModal{position:fixed;inset:0;z-index:1000;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,.6)}
#urlModal.open{display:flex}
.url-card{background:#fff;border-radius:16px;padding:24px;width:90%;max-width:340px;text-align:center}
.url-card h3{font-size:16px;font-weight:700;color:#212121;margin-bottom:4px}
.url-card p{font-size:12px;color:#757575;margin-bottom:16px}
.url-box{background:#F5F5F5;border:1px solid #E0E0E0;border-radius:8px;padding:10px 12px;font-size:12px;color:#00897B;font-family:monospace;word-break:break-all;margin-bottom:14px;text-align:left}
.url-btns{display:flex;gap:8px;justify-content:center}
.copy-btn{background:#00897B;color:#fff;border:none;padding:8px 20px;border-radius:8px;cursor:pointer;font-size:13px;transition:.15s}
.copy-btn:hover{background:#00796B}
.close-btn{background:#F5F5F5;border:1px solid #E0E0E0;color:#424242;padding:8px 20px;border-radius:8px;cursor:pointer;font-size:13px}
@media(max-width:768px){
  .layout{flex-direction:column}
  .sidebar{width:100%;min-width:0;position:static;max-height:none;border-right:none;border-bottom:1px solid #E0E0E0}
  .mgrid{grid-template-columns:repeat(auto-fill,minmax(100px,1fr))}
  .breadbar{padding:8px 12px}
}
</style>
</head>
<body>

<nav class="navbar">
  <div class="nav-logo"><img src="/.app-icon" alt="Hound" width="22" height="22" style="object-fit:contain;display:block"></div>
  <span class="nav-brand">Hound</span>
</nav>

<div class="breadbar">
  <div class="breadcrumbs">$breadcrumbHtml</div>
  <div class="search-box">
    <span class="search-icon">&#128269;</span>
    <input type="text" class="search-inp" id="searchInp" placeholder="Search files..." value="${searchQuery.htmlEscape()}" onkeyup="if(event.key==='Enter')doSearch()">
    <button class="search-clear" id="searchClear" onclick="clearSearch()">&#10005;</button>
  </div>
  <label class="search-rec" title="Search in subfolders">
    <input type="checkbox" id="searchRecursive" ${if (session.parameters["recursive"]?.firstOrNull() == "true") "checked" else ""} onchange="doSearch()">
    <span>Subfolders</span>
  </label>
  <span class="sbadge"><span class="sdot"></span> SERVER RUNNING</span>
  <label class="autolbl">Auto-sync
    <label class="tog"><input type="checkbox" id="autoCb" onchange="setAuto(this.checked)"><span class="tog-s"></span></label>
  </label>
  <button class="hbtn" onclick="hardRefresh()">
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M23 4v6h-6"/><path d="M20.5 15A9 9 0 1120.5 9"/></svg>
    Refresh
  </button>
</div>

<div class="layout">
  <aside class="sidebar">
    <div>
      <div class="s-lbl">
        <svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round"><path d="M3 7c0-1.1.9-2 2-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>
        Folders
      </div>
      <div class="flist-s">$sidebarFoldersHtml</div>
    </div>

    <div class="upl-box" id="dropZone">
      <div class="upl-title">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
        Upload to ${displayPath.htmlEscape()}
      </div>
      <div class="upl-sub">Drag &amp; drop files or browse</div>
      <input type="file" id="finput" multiple style="display:none" onchange="doUpload()">
      <button class="upl-btn" onclick="document.getElementById('finput').click()">+ Choose Files</button>
      <div id="upst"></div>
    </div>

    <div class="warn-box">
      <span class="warn-ico">&#9432;</span>
      <span class="warn-txt">Ensure you are connected to a trusted local network. Devices on the same Wi-Fi will be able to access shared files.</span>
    </div>
  </aside>

  <main class="content">
    ${if (searchActive) """<div style="margin-bottom:16px;padding:10px 14px;background:#E8F5E9;border-radius:8px;font-size:13px;color:#2E7D32;display:flex;align-items:center;gap:8px"><span>&#128269;</span> ${if (recursiveSearch) "Found $totalFiltered results" else "Showing $totalFiltered results"} for "<b>${searchQuery.htmlEscape()}</b>"${if (recursiveSearch) " (searching subfolders)" else ""} <a href="?t=${System.currentTimeMillis()}" style="margin-left:auto;color:#00897B;text-decoration:none;font-size:12px">Clear search</a></div>""" else ""}
    $imageSection
    $videoSection
    $audioSection
    $fileSection
    $emptyMain
  </main>
</div>

<button class="qr-fab" onclick="openURL()" title="Show server URL">
  <svg viewBox="0 0 24 24" fill="currentColor" width="22" height="22"><path d="M3 3h7v7H3zm1 1v5h5V4zm1 1h3v3H5zM14 3h7v7h-7zm1 1v5h5V4zm1 1h3v3h-3zM3 14h7v7H3zm1 1v5h5v-5zm1 1h3v3H5zM13 13h1v2h-1zm2 0h1v1h-1zm1 1h1v1h-1zm1 0h1v1h-1zm-3 1h1v1h-1zm3 0h1v1h-1zm-4 1h1v1h-1zm2 0h1v1h-1zm2 0h1v1h-1zm-4 1h1v1h-1zm1 0h1v1h-1zm2 1h1v1h-1zm1 0h2v1h-2zm-2 1h1v1h-1zm2 0h1v1h-1z"/></svg>
</button>

<div id="modal">
  <div id="mbar">
    <span id="mtitle"></span>
    <a id="mdl" class="mbtn" title="Download">&#8595;</a>
    <button class="mbtn" onclick="closeModal()">&#10005;</button>
  </div>
  <div id="mbody">
    <button id="mprev" onclick="navImg(-1)">&#8249;</button>
    <div id="minner"></div>
    <button id="mnext" onclick="navImg(1)">&#8250;</button>
  </div>
</div>

<div id="urlModal">
  <div class="url-card">
    <h3>Server URL</h3>
    <p>Open on any device connected to the same Wi-Fi</p>
    <div class="url-box" id="srvUrl">${serverUrlRaw.htmlEscape()}</div>
    <div class="url-btns">
      <button class="copy-btn" onclick="copyUrl()">Copy URL</button>
      <button class="close-btn" onclick="closeURL()">Close</button>
    </div>
  </div>
</div>

<script>
var imgs = [$imgPathsJson];
var curI = 0;
var autoTimer = null;

function hardRefresh() {
  location.replace(location.pathname + '?t=' + Date.now());
}
function setAuto(on) {
  clearInterval(autoTimer);
  if (on) autoTimer = setInterval(hardRefresh, 5000);
}

/* Search */
function doSearch() {
  var q = document.getElementById('searchInp').value.trim();
  var rec = document.getElementById('searchRecursive').checked;
  var u = new URL(window.location.href);
  if (q) {
    u.searchParams.set('search', q);
  } else {
    u.searchParams.delete('search');
  }
  u.searchParams.set('recursive', rec ? 'true' : 'false');
  u.searchParams.set('t', Date.now());
  location.replace(u.pathname + '?' + u.searchParams.toString());
}
function clearSearch() {
  document.getElementById('searchInp').value = '';
  document.getElementById('searchClear').classList.remove('show');
  doSearch();
}
(function() {
  var inp = document.getElementById('searchInp');
  var clr = document.getElementById('searchClear');
  if (inp.value) clr.classList.add('show');
  inp.addEventListener('input', function() {
    clr.classList.toggle('show', inp.value.length > 0);
  });
})();

/* View toggle */
function setView(v) {
  var g = document.getElementById('imgGrid');
  if (!g) return;
  if (v === 'list') {
    g.classList.add('list-mode');
    document.getElementById('listBtn').classList.add('active');
    document.getElementById('gridBtn').classList.remove('active');
  } else {
    g.classList.remove('list-mode');
    document.getElementById('gridBtn').classList.add('active');
    document.getElementById('listBtn').classList.remove('active');
  }
}

/* Load more */
function loadMore() {
  document.querySelectorAll('[data-extra]').forEach(function(el) {
    el.style.display = ''; el.removeAttribute('data-extra');
  });
  var wrap = document.querySelector('.lm-wrap');
  if (wrap) wrap.parentNode.removeChild(wrap);
}

/* URL modal */
function openURL() { document.getElementById('urlModal').classList.add('open'); }
function closeURL() { document.getElementById('urlModal').classList.remove('open'); }
function copyUrl() {
  var url = document.getElementById('srvUrl').textContent;
  var btn = document.querySelector('.copy-btn');
  navigator.clipboard.writeText(url).then(function() {
    btn.textContent = 'Copied!';
    setTimeout(function() { btn.textContent = 'Copy URL'; }, 2000);
  }).catch(function() {
    var ta = document.createElement('textarea');
    ta.value = url; document.body.appendChild(ta); ta.select();
    document.execCommand('copy'); document.body.removeChild(ta);
  });
}

/* Lightbox */
function openImg(idx, path, name) {
  curI = idx;
  setModal(name, path, function(inner) {
    var img = document.createElement('img');
    img.src = '/' + path + '?t=' + Date.now(); img.alt = name;
    inner.appendChild(img);
  });
  var show = imgs.length > 1 ? '' : 'none';
  document.getElementById('mprev').style.display = show;
  document.getElementById('mnext').style.display = show;
}
function openVid(path, name) {
  setModal(name, path, function(inner) {
    var v = document.createElement('video');
    v.src = '/' + path; v.controls = true; v.autoplay = true;
    v.style.cssText = 'max-width:100%;max-height:80vh';
    inner.appendChild(v);
  });
  document.getElementById('mprev').style.display = 'none';
  document.getElementById('mnext').style.display = 'none';
}
function setModal(name, path, buildFn) {
  document.getElementById('mtitle').textContent = name;
  var dl = document.getElementById('mdl'); dl.href = '/' + path; dl.download = name;
  var inner = document.getElementById('minner'); inner.innerHTML = '';
  buildFn(inner);
  document.getElementById('modal').classList.add('open');
  document.addEventListener('keydown', onKey);
}
function closeModal() {
  document.getElementById('modal').classList.remove('open');
  document.getElementById('minner').innerHTML = '';
  document.removeEventListener('keydown', onKey);
}
function navImg(dir) {
  if (!imgs.length) return;
  curI = (curI + dir + imgs.length) % imgs.length;
  var p = imgs[curI]; openImg(curI, p, p.split('/').pop());
}
function onKey(e) {
  if (e.key === 'Escape') { closeModal(); closeURL(); }
  else if (e.key === 'ArrowLeft') navImg(-1);
  else if (e.key === 'ArrowRight') navImg(1);
}

/* Upload */
function doUpload() {
  var files = document.getElementById('finput').files;
  if (!files.length) return;
  doUploadFiles(files);
}
function doUploadFiles(files) {
  if (!files.length) return;
  var curPath = '${currentPath.jsEscape()}';
  var st = document.getElementById('upst');
  var done = 0; var total = files.length;
  st.textContent = 'Uploading 0/' + total + '...';
  Array.from(files).forEach(function(f) {
    var path = curPath ? curPath + '/' + f.name : f.name;
    fetch('/upload?path=' + encodeURIComponent(path), {
      method: 'POST', body: f,
      headers: {'Content-Type': f.type || 'application/octet-stream'}
    }).then(function(r) { return r.json(); }).then(function() {
      done++;
      st.textContent = done < total ? 'Uploading ' + done + '/' + total + '...' : 'Done!';
      if (done === total) setTimeout(hardRefresh, 700);
    }).catch(function() { st.textContent = 'Upload error'; });
  });
}

/* Drag & drop */
var dz = document.getElementById('dropZone');
dz.addEventListener('dragover', function(e) { e.preventDefault(); dz.classList.add('drag-over'); });
dz.addEventListener('dragleave', function() { dz.classList.remove('drag-over'); });
dz.addEventListener('drop', function(e) {
  e.preventDefault(); dz.classList.remove('drag-over');
  if (e.dataTransfer && e.dataTransfer.files.length) doUploadFiles(e.dataTransfer.files);
});

/* Delete */
function doDelete(path) {
  var name = path.split('/').pop();
  if (!confirm('Delete "' + name + '"?')) return;
  fetch('/delete?path=' + encodeURIComponent(path), {method: 'DELETE'})
    .then(function(r) { return r.json(); })
    .then(function(d) { if (d.success) hardRefresh(); else alert('Delete failed'); })
    .catch(function() { alert('Delete error'); });
}
</script>
</body></html>"""
    }

    // ---- JSON API ----

    private fun serveJsonList(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: ""
        val root = rootDirectory
            ?: return jsonErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "No folder selected")
        val dir = if (path.isEmpty()) root else File(root, path).canonicalFile
        if (!isWithinRoot(root, dir)) return jsonErrorResponse(Response.Status.FORBIDDEN, "Forbidden")
        if (!dir.exists() || !dir.isDirectory) return jsonErrorResponse(Response.Status.NOT_FOUND, "Directory not found")

        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val json = files.joinToString(",") { f ->
            val rel = if (path.isEmpty()) f.name else "$path/${f.name}"
            """{"name":${f.name.jsonEscape()},"size":${f.length()},"isDirectory":${f.isDirectory},"modified":${f.lastModified()},"path":${rel.jsonEscape()}}"""
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"path":${path.jsonEscape()},"files":[$json]}""").apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    // ---- Upload ----

    private fun handleUpload(session: IHTTPSession): Response {
        val path = session.parms["path"]
            ?: return jsonErrorResponse(Response.Status.BAD_REQUEST, "Missing 'path'")
        val root = rootDirectory ?: return jsonErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "No folder selected")
        val dest = File(root, path).canonicalFile
        if (!isWithinRoot(root, dest)) return jsonErrorResponse(Response.Status.FORBIDDEN, "Path traversal")
        dest.parentFile?.mkdirs()
        
        // Use consistent temp file name based on destination path for resume support
        val tmp = File(dest.parent, ".tmp_${dest.name.hashCode()}")
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val contentRange = session.headers["content-range"]
        
        var startOffset = 0L
        var isResume = false
        
        // Parse Content-Range header (format: bytes start-end/full)
        if (contentRange != null && contentRange.startsWith("bytes=")) {
            val rangePart = contentRange.removePrefix("bytes=")
            val dashIndex = rangePart.indexOf('-')
            if (dashIndex > 0) {
                startOffset = rangePart.substring(0, dashIndex).toLongOrNull() ?: 0L
                isResume = true
            }
        }
        
        // If resuming and partial file exists, check size matches
        if (isResume && tmp.exists() && tmp.length() != startOffset) {
            return jsonErrorResponse(Response.Status.RANGE_NOT_SATISFIABLE, "Range mismatch")
        }
        
        return try {
            val fileLen = tmp.length()
            // Use FileOutputStream with append mode for resume
            val outputStream = java.io.FileOutputStream(tmp, startOffset > 0 && tmp.exists())
            
            outputStream.use { out ->
                val buf = ByteArray(65536); var rem = contentLength
                val inp = session.inputStream
                while (rem != 0L) {
                    val n = if (rem < 0) buf.size else minOf(rem, buf.size.toLong()).toInt()
                    val r = inp.read(buf, 0, n); if (r == -1) break
                    out.write(buf, 0, r); if (rem > 0) rem -= r
                }
            }
            
            // If this is the final chunk, rename to destination
            // Client signals completion by sending full content-length without Content-Range
            // or by sending all bytes
            val totalSize = if (isResume) startOffset + (contentLength.coerceAtLeast(0)) else contentLength.coerceAtLeast(0)
            
            if (!isResume || tmp.length() >= totalSize) {
                tmp.renameTo(dest)
                transferHistory?.addRecord(
                    TransferRecord(
                        fileName = dest.name,
                        filePath = path,
                        fileSize = dest.length(),
                        type = TransferType.UPLOAD,
                        status = TransferStatus.COMPLETED,
                        isServer = true
                    )
                )
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"file":${dest.name.jsonEscape()}}""")
            } else {
                // Partial upload received, signal client can continue
                newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, 
                    "application/json", 
                    """{"partial":true,"received":${tmp.length()},"total":$totalSize}"""
                ).apply {
                    addHeader("Content-Range", "bytes ${tmp.length()}/$totalSize")
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            transferHistory?.addRecord(
                TransferRecord(
                    fileName = dest.name,
                    filePath = path,
                    fileSize = contentLength,
                    type = TransferType.UPLOAD,
                    status = TransferStatus.FAILED,
                    isServer = true
                )
            )
            jsonErrorResponse(Response.Status.INTERNAL_ERROR, "Upload failed: ${e.message}")
        }
    }

    // ---- Delete ----

    private fun handleDelete(session: IHTTPSession): Response {
        val path = session.parms["path"]
            ?: return jsonErrorResponse(Response.Status.BAD_REQUEST, "Missing 'path'")
        val root = rootDirectory ?: return jsonErrorResponse(Response.Status.SERVICE_UNAVAILABLE, "No folder selected")
        val file = File(root, path).canonicalFile
        if (!isWithinRoot(root, file)) return jsonErrorResponse(Response.Status.FORBIDDEN, "Path traversal")
        return if (file.exists() && file.delete())
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        else jsonErrorResponse(Response.Status.NOT_FOUND, "File not found")
    }

    // ---- Helpers ----

    private fun isWithinRoot(root: File, file: File): Boolean {
        val r = root.canonicalPath; val f = file.canonicalPath
        return f == r || f.startsWith(r + File.separator)
    }

    private fun fileIcon(name: String) = when (FileUtils.fileExt(name)) {
        "pdf" -> "📄"; "zip","tar","gz","rar","7z" -> "🗜"; "apk" -> "📦"
        "txt","md" -> "📝"; "doc","docx" -> "📝"; "xls","xlsx","csv" -> "📊"
        else -> "📄"
    }

    private fun errorHtml(status: Response.Status, msg: String) =
        newFixedLengthResponse(status, MIME_HTML, "<html><body><h2>$msg</h2></body></html>")

    private fun jsonErrorResponse(status: Response.Status, msg: String) =
        newFixedLengthResponse(status, "application/json", """{"error":${msg.jsonEscape()}}""")

    private fun String.jsonEscape() = buildString {
        append('"')
        for (c in this@jsonEscape) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    private fun String.htmlEscape() = replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun String.jsEscape() = replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","\\r")
}
