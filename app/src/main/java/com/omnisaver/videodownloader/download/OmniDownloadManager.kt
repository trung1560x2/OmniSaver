package com.omnisaver.videodownloader.download

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class OmniDownloadManager(private val context: Context) {

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val POLL_INTERVAL_MS = 300L
    }

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
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

        // If this is a DASH Full HD Video + Audio pair, handle downloading & muxing directly in observeDownloadProgress
        if (!option.audioUrl.isNullOrBlank() && option.mediaType == MediaType.VIDEO) {
            return -System.currentTimeMillis()
        }

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
        // DASH Video + Audio Muxing Pipeline (1080p / 720p Full HD with sound)
        if (downloadId < 0L || (!option.audioUrl.isNullOrBlank() && option.mediaType == MediaType.VIDEO)) {
            val targetFile = getTargetFile(mediaInfo, fileName)
            val cacheDir = File(context.externalCacheDir ?: context.cacheDir, "dash_mux")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val ts = System.currentTimeMillis()
            val tempVideoFile = File(cacheDir, "dash_video_$ts.mp4")
            val tempAudioFile = File(cacheDir, "dash_audio_$ts.m4a")

            try {
                var videoTotalBytes = 0L
                var videoDownloaded = 0L

                // 1. Download DASH Video track (0% -> 82%)
                val videoReqBuilder = Request.Builder()
                    .url(option.url)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Origin", mediaInfo.platform.origin)
                    .header("Referer", mediaInfo.platform.referer)
                option.customHeaders.forEach { (k, v) -> videoReqBuilder.header(k, v) }

                httpClient.newCall(videoReqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Không thể tải luồng Video Full HD (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IllegalStateException("Luồng Video Full HD trống.")
                    videoTotalBytes = body.contentLength().coerceAtLeast(0L)

                    body.byteStream().use { input ->
                        FileOutputStream(tempVideoFile).use { output ->
                            val buf = ByteArray(32 * 1024)
                            var read: Int
                            var lastEmitTime = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                videoDownloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 180L) {
                                    lastEmitTime = now
                                    val pct = if (videoTotalBytes > 0L) {
                                        ((videoDownloaded * 82L) / videoTotalBytes).toInt().coerceIn(1, 82)
                                    } else {
                                        40
                                    }
                                    emit(
                                        DownloadProgressState.Running(
                                            downloadId = downloadId,
                                            fileName = fileName,
                                            bytesDownloaded = videoDownloaded,
                                            totalBytes = videoTotalBytes,
                                            progressPercent = pct
                                        )
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }

                // 2. Download DASH Audio track (82% -> 95%)
                val audioUrl = option.audioUrl
                var audioDownloaded = 0L
                if (!audioUrl.isNullOrBlank()) {
                    val audioReqBuilder = Request.Builder()
                        .url(audioUrl)
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Accept", "*/*")
                        .header("Origin", mediaInfo.platform.origin)
                        .header("Referer", mediaInfo.platform.referer)
                    option.customHeaders.forEach { (k, v) -> audioReqBuilder.header(k, v) }

                    runCatching {
                        httpClient.newCall(audioReqBuilder.build()).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body
                                val audioTotal = body?.contentLength()?.coerceAtLeast(0L) ?: 0L
                                body?.byteStream()?.use { input ->
                                    FileOutputStream(tempAudioFile).use { output ->
                                        val buf = ByteArray(32 * 1024)
                                        var read: Int
                                        while (input.read(buf).also { read = it } != -1) {
                                            output.write(buf, 0, read)
                                            audioDownloaded += read
                                            val pct = if (audioTotal > 0L) {
                                                82 + ((audioDownloaded * 13L) / audioTotal).toInt().coerceIn(0, 13)
                                            } else {
                                                90
                                            }
                                            emit(
                                                DownloadProgressState.Running(
                                                    downloadId = downloadId,
                                                    fileName = fileName,
                                                    bytesDownloaded = videoDownloaded + audioDownloaded,
                                                    totalBytes = if (videoTotalBytes > 0L) videoTotalBytes + audioTotal else -1L,
                                                    progressPercent = pct
                                                )
                                            )
                                        }
                                        output.flush()
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Mux Video + Audio into final MP4 (95% -> 100%)
                emit(
                    DownloadProgressState.Running(
                        downloadId = downloadId,
                        fileName = fileName,
                        bytesDownloaded = videoDownloaded + audioDownloaded,
                        totalBytes = videoDownloaded + audioDownloaded,
                        progressPercent = 96
                    )
                )

                if (targetFile.exists()) targetFile.delete()
                val muxSuccess = if (tempAudioFile.exists() && tempAudioFile.length() > 1024L) {
                    runCatching {
                        muxDashVideoAndAudio(tempVideoFile, tempAudioFile, targetFile)
                    }.getOrDefault(false)
                } else {
                    false
                }

                if (!muxSuccess || !targetFile.exists() || targetFile.length() < 1024L) {
                    if (targetFile.exists()) targetFile.delete()
                    tempVideoFile.copyTo(targetFile, overwrite = true)
                }

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
            } catch (e: Exception) {
                emit(
                    DownloadProgressState.Failed(
                        downloadId = downloadId,
                        reason = "Tải xuống Full HD thất bại: ${e.message}"
                    )
                )
            } finally {
                runCatching { tempVideoFile.delete() }
                runCatching { tempAudioFile.delete() }
            }
            return@flow
        }

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

    /**
     * Remuxes a DASH Video track (.mp4) and DASH Audio track (.m4a) into a single MP4 file
     * without re-encoding, using Android's hardware-native MediaExtractor and MediaMuxer.
     */
    @SuppressLint("WrongConstant")
    private fun muxDashVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) return false

            audioExtractor.setDataSource(audioFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return false

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                runCatching {
                    muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
                }
            }

            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var lastVideoPts = -1L
            while (true) {
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTime = videoExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = if (sampleTime <= lastVideoPts) lastVideoPts + 1L else sampleTime
                lastVideoPts = bufferInfo.presentationTimeUs
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            var lastAudioPts = -1L
            while (true) {
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTime = audioExtractor.sampleTime.coerceAtLeast(0L)
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = if (sampleTime <= lastAudioPts) lastAudioPts + 1L else sampleTime
                lastAudioPts = bufferInfo.presentationTimeUs
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            return outputFile.exists() && outputFile.length() > 1024L
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }
}
