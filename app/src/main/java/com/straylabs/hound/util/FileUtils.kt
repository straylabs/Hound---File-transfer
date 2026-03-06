package com.straylabs.hound.util

object FileUtils {

    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts")
    val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "wma")

    fun fileExt(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImageFile(name: String): Boolean = fileExt(name) in IMAGE_EXTS
    fun isVideoFile(name: String): Boolean = fileExt(name) in VIDEO_EXTS
    fun isAudioFile(name: String): Boolean = fileExt(name) in AUDIO_EXTS
    fun isPreviewable(name: String): Boolean = isImageFile(name) || isVideoFile(name) || isAudioFile(name)

    fun getMimeType(name: String): String = when (fileExt(name)) {
        "txt"         -> "text/plain"
        "html", "htm" -> "text/html"
        "css"         -> "text/css"
        "js"          -> "application/javascript"
        "json"        -> "application/json"
        "xml"         -> "application/xml"
        "pdf"         -> "application/pdf"
        "zip"         -> "application/zip"
        "tar"         -> "application/x-tar"
        "gz"          -> "application/gzip"
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "gif"         -> "image/gif"
        "webp"        -> "image/webp"
        "svg"         -> "image/svg+xml"
        "heic", "heif"-> "image/heic"
        "mp3"         -> "audio/mpeg"
        "wav"         -> "audio/wav"
        "ogg"         -> "audio/ogg"
        "flac"        -> "audio/flac"
        "m4a", "aac"  -> "audio/aac"
        "opus"        -> "audio/opus"
        "mp4", "m4v"  -> "video/mp4"
        "webm"        -> "video/webm"
        "avi"         -> "video/x-msvideo"
        "mkv"         -> "video/x-matroska"
        "mov"         -> "video/quicktime"
        "3gp"         -> "video/3gpp"
        "ts"          -> "video/mp2t"
        "apk"         -> "application/vnd.android.package-archive"
        else          -> "application/octet-stream"
    }

    /** Format a file size in bytes to a human-readable string. Returns "" for unknown sizes (<0). */
    fun formatSize(size: Long): String = when {
        size < 0             -> ""
        size < 1_024         -> "$size B"
        size < 1_048_576     -> "${size / 1_024} KB"
        size < 1_073_741_824 -> "${size / 1_048_576} MB"
        else                 -> "${size / 1_073_741_824} GB"
    }
}
