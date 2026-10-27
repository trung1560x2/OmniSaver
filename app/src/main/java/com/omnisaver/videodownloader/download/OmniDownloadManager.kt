package com.omnisaver.videodownloader.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.omnisaver.videodownloader.model.DownloadProgressState
import com.omnisaver.videodownloader.model.ExtractedMediaInfo
import com.omnisaver.videodownloader.model.MediaOption
import com.omnisaver.videodownloader.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class OmniDownloadManager(private val context: Context) {

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val POLL_INTERVAL_MS = 300L
    }

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    fun buildFileName(mediaInfo: ExtractedMediaInfo, option: MediaOption): String {
        val sanitizedTitle = mediaInfo.title
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .take(36)
            .trimEnd('_')
            .ifBlank { System.currentTimeMillis().toString() }

        val sanitizedQuality = option.qualityLabel
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { option.mediaType.name.lowercase() }

        val extension = option.extension.trimStart('.').ifBlank {
            when (option.mediaType) {
                MediaType.VIDEO -> "mp4"
                MediaType.AUDIO -> "mp3"
                MediaType.IMAGE -> "jpg"
            }
        }

        return "${mediaInfo.platform.folderName}_${sanitizedTitle}_${sanitizedQuality}.$extension"
    }

    fun getTargetFile(mediaInfo: ExtractedMediaInfo, fileName: String): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetFile = File(publicDownloads, "OmniSaver/${mediaInfo.platform.folderName}/$fileName")
        targetFile.parentFile?.mkdirs()
        return targetFile
    }

    fun enqueueDownload(
        mediaInfo: ExtractedMediaInfo,
        option: MediaOption,
        fileName: String = buildFileName(mediaInfo, option)
    ): Long {
        getTargetFile(mediaInfo, fileName)

        val request = DownloadManager.Request(Uri.parse(option.url)).apply {
            addRequestHeader("Accept", "*/*")
            addRequestHeader("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
            addRequestHeader("Origin", mediaInfo.platform.origin)
            addRequestHeader("Referer", mediaInfo.platform.referer)
            addRequestHeader("Sec-Fetch-Dest", "empty")
            addRequestHeader("Sec-Fetch-Mode", "cors")
            addRequestHeader("Sec-Fetch-Site", "cross-site")
            addRequestHeader("User-Agent", DEFAULT_USER_AGENT)

            option.customHeaders.forEach { (key, value) ->
                addRequestHeader(key, value)
            }

            setTitle(fileName)
            setDescription("Đang tải từ ${mediaInfo.platform.displayName}...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "OmniSaver/${mediaInfo.platform.folderName}/$fileName"
            )
        }

        return downloadManager.enqueue(request)
    }

    fun observeDownloadProgress(
        downloadId: Long,
        mediaInfo: ExtractedMediaInfo,
        option: MediaOption,
        fileName: String
    ): Flow<DownloadProgressState> = flow {
        val query = DownloadManager.Query().setFilterById(downloadId)
        var isFinished = false

        while (!isFinished) {
            val cursor: Cursor? = downloadManager.query(query)
            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                emit(DownloadProgressState.Failed(downloadId, "Tải xuống thất bại (Mã lỗi: Hủy hoặc không tồn tại)"))
                break
            }

            cursor.use { c ->
                val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIndex = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                val downloadedIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                val status = if (statusIndex >= 0) c.getInt(statusIndex) else DownloadManager.STATUS_RUNNING
                val reason = if (reasonIndex >= 0) c.getInt(reasonIndex) else 0
                val bytesDownloaded = if (downloadedIndex >= 0) c.getLong(downloadedIndex).coerceAtLeast(0L) else 0L
                val totalBytes = if (totalIndex >= 0) c.getLong(totalIndex) else -1L

                val progressPercent = if (totalBytes > 0L) {
                    ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    0
                }

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val targetFile = getTargetFile(mediaInfo, fileName)
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(targetFile.absolutePath),
                            null,
                            null
                        )
                        emit(
                            DownloadProgressState.Completed(
                                downloadId = downloadId,
                                filePath = targetFile.absolutePath,
                                fileName = fileName,
                                mediaInfo = mediaInfo,
                                option = option
                            )
                        )
                        isFinished = true
                    }

                    DownloadManager.STATUS_FAILED -> {
                        emit(
                            DownloadProgressState.Failed(
                                downloadId = downloadId,
                                reason = "Tải xuống thất bại (Mã lỗi: $reason)"
                            )
                        )
                        isFinished = true
                    }

                    else -> {
                        emit(
                            DownloadProgressState.Running(
                                downloadId = downloadId,
                                fileName = fileName,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                progressPercent = progressPercent
                            )
                        )
                    }
                }
            }

            if (!isFinished) {
                delay(POLL_INTERVAL_MS)
            }
        }
    }.flowOn(Dispatchers.IO)
}
