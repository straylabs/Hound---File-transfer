package com.straylabs.hound.client

data class FileItem(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val modified: Long,
    val path: String
)
