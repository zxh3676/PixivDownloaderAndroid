package com.pixiv.downloader.model

data class ImageInfo(
    val url: String,
    val filename: String,
    val page: Int,
    val isFallback: Boolean = false
)

data class PixivWork(
    val id: String,
    val title: String,
    val authorName: String,
    val authorId: String,
    val imageUrls: List<ImageInfo> = emptyList()
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data class Completed(val illustCount: Int, val imageCount: Int) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: String,
    val message: String
)

enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    DARK("深色"),
    LIGHT("浅色")
}
