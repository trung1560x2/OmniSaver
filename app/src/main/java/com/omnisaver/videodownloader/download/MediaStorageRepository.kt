package com.omnisaver.videodownloader.download

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.FileProvider
import com.omnisaver.videodownloader.model.DownloadedFileItem
import com.omnisaver.videodownloader.model.MediaType
import com.omnisaver.videodownloader.model.SocialPlatform
import java.io.File
import java.util.Locale

class MediaStorageRepository(private val context: Context) {

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac", "ogg")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }

    fun getRootDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OmniSaver"
        ).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun listDownloadedFiles(
        platformFilter: SocialPlatform? = null,
        typeFilter: MediaType? = null
    ): List<DownloadedFileItem> {
        val rootDir = getRootDirectory()
        if (!rootDir.exists()) return emptyList()

        return rootDir.walkTopDown()
            .filter { it.isFile && it.length() > 0L }
            .mapNotNull { file ->
                val ext = file.extension.lowercase(Locale.ROOT)
                val mediaType = resolveMediaType(ext) ?: return@mapNotNull null
                val platform = resolvePlatform(file)

                DownloadedFileItem(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    platform = platform,
                    mediaType = mediaType,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            }
            .filter { item ->
                (platformFilter == null || item.platform == platformFilter) &&
                    (typeFilter == null || item.mediaType == typeFilter)
            }
            .sortedByDescending { it.lastModified }
            .toList()
    }

    fun deleteFile(item: DownloadedFileItem): Boolean {
        val file = File(item.filePath)
        val deleted = if (file.exists()) file.delete() else false
        MediaScannerConnection.scanFile(context, arrayOf(item.filePath), null, null)
        return deleted
    }

    fun shareFile(activityContext: Context, item: DownloadedFileItem) {
        val file = File(item.filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            activityContext,
            "${activityContext.packageName}.fileprovider",
            file
        )

        val mimeType = when (item.mediaType) {
            MediaType.VIDEO -> "video/*"
            MediaType.AUDIO -> "audio/*"
            MediaType.IMAGE -> "image/*"
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(shareIntent, "Chia sẻ tệp").apply {
            if (activityContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        activityContext.startActivity(chooserIntent)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
        }
    }

    private fun resolveMediaType(extension: String): MediaType? {
        return when (extension) {
            in VIDEO_EXTENSIONS -> MediaType.VIDEO
            in AUDIO_EXTENSIONS -> MediaType.AUDIO
            in IMAGE_EXTENSIONS -> MediaType.IMAGE
            else -> null
        }
    }

    private fun resolvePlatform(file: File): SocialPlatform {
        val parentFolderName = file.parentFile?.name.orEmpty()
        SocialPlatform.values().firstOrNull { platform ->
            platform.folderName.equals(parentFolderName, ignoreCase = true)
        }?.let { return it }

        SocialPlatform.values().firstOrNull { platform ->
            file.name.startsWith("${platform.folderName}_", ignoreCase = true)
        }?.let { return it }

        return SocialPlatform.UNKNOWN
    }
}
