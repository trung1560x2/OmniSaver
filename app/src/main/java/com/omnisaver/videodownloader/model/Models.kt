package com.omnisaver.videodownloader.model

enum class SocialPlatform(
    val displayName: String,
    val folderName: String,
    val badgeColorHex: String,
    val referer: String,
    val origin: String
) {
    FACEBOOK("Facebook", "Facebook", "#1877F2", "https://www.facebook.com/", "https://www.facebook.com"),
    X_TWITTER("X / Twitter", "X", "#1DA1F2", "https://www.x.com/", "https://www.x.com"),
    INSTAGRAM("Instagram", "Instagram", "#E1306C", "https://www.instagram.com/", "https://www.instagram.com"),
    TIKTOK("TikTok", "TikTok", "#00B4DB", "https://www.tiktok.com/", "https://www.tiktok.com"),
    THREADS("Threads", "Threads", "#333333", "https://www.threads.net/", "https://www.threads.net"),
    UNKNOWN("Web / Khác", "Other", "#607D8B", "https://www.google.com/", "https://www.google.com")
}

enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE
}

enum class UrlSource {
    INTENT,
    CLIPBOARD,
    MANUAL
}

data class MediaOption(
    val url: String,
    val qualityLabel: String,
    val extension: String,
    val mediaType: MediaType,
    val sizeText: String = "",
    val isHighestQuality: Boolean = false,
    val isNoWatermark: Boolean = true,
    val customHeaders: Map<String, String> = emptyMap()
)

data class ExtractedMediaInfo(
    val originalUrl: String,
    val cleanUrl: String,
    val platform: SocialPlatform,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val durationSeconds: Long = 0L,
    val options: List<MediaOption>
)

data class DownloadedFileItem(
    val filePath: String,
    val fileName: String,
    val platform: SocialPlatform,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val lastModified: Long
)

sealed class DownloadProgressState {
    object Idle : DownloadProgressState()
    data class Running(
        val downloadId: Long,
        val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : DownloadProgressState()
    data class Completed(
        val downloadId: Long,
        val filePath: String,
        val fileName: String,
        val mediaInfo: ExtractedMediaInfo,
        val option: MediaOption
    ) : DownloadProgressState()
    data class Failed(
        val downloadId: Long,
        val reason: String
    ) : DownloadProgressState()
}
