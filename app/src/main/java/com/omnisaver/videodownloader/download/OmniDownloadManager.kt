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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
            .take(30)
            .trimEnd('_')
            .ifBlank { System.currentTimeMillis().toString() }

        val sanitizedQuality = option.qualityLabel
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .take(26)
            .trimEnd('_')
            .ifBlank { option.mediaType.name.lowercase() }

        val baseAssetPath = option.url.substringBefore('?').ifBlank { option.url }
        val streamHash = ((baseAssetPath.hashCode().toLong() and 0x7FFFFFFFL) % 1_000_000L)
            .toString()
            .padStart(6, '0')

        val extension = option.extension.trimStart('.').ifBlank {
            when (option.mediaType) {
                MediaType.VIDEO -> "mp4"
                MediaType.AUDIO -> "mp3"
                MediaType.IMAGE -> "jpg"
            }
        }

        return "${mediaInfo.platform.folderName}_${sanitizedTitle}_${sanitizedQuality}_${streamHash}.$extension"
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
                val muxSuccess = if (tempAudioFile.exists() && tempAudioFile.length() > 512L) {
                    val isoSuccess = runCatching {
                        muxIsoBmffFragmentedMp4(tempVideoFile, tempAudioFile, targetFile)
                    }.getOrDefault(false)

                    if (isoSuccess && targetFile.exists() && targetFile.length() > tempVideoFile.length()) {
                        true
                    } else {
                        if (targetFile.exists()) targetFile.delete()
                        runCatching {
                            muxDashVideoAndAudio(tempVideoFile, tempAudioFile, targetFile)
                        }.getOrDefault(false)
                    }
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


    private class Fmp4ChunkRun(
        val sourceOffset: Long,
        val byteLength: Int,
        val sampleCount: Int
    )

    private class ParsedFmp4Track {
        var ftypBox: ByteArray? = null
        var mvhdBox: ByteArray? = null
        var tkhdBox: ByteArray? = null
        var mdhdBox: ByteArray? = null
        var hdlrBox: ByteArray? = null
        var mediaHeaderBox: ByteArray? = null
        var dinfBox: ByteArray? = null
        var stsdBox: ByteArray? = null
        var mvhdTimescale: Long = 0L
        var mdhdTimescale: Long = 0L
        var defaultSampleDescIndex: Long = 1L
        var defaultSampleDuration: Long = 0L
        var defaultSampleSize: Long = 0L
        var defaultSampleFlags: Long = 0L
        val sampleDurations = ArrayList<Long>()
        val sampleSizes = ArrayList<Long>()
        val sampleFlags = ArrayList<Long>()
        val sampleCtsOffsets = ArrayList<Int>()
        val chunks = ArrayList<Fmp4ChunkRun>()
        var totalDuration: Long = 0L
        var totalDataBytes: Long = 0L
    }

    /**
     * Codec-agnostic ISO-BMFF Fragmented MP4 (fMP4 / CMAF) De-fragmenting & Track Muxer.
     * Combines a DASH Video fMP4 (`av01` AV1, `vp09` VP9, `avc1` H.264, `hvc1` HEVC)
     * and a DASH Audio fMP4 (`mp4a.40.5` HE-AAC, `mp4a.40.2` AAC-LC, `opus`)
     * directly into a universal non-fragmented `.mp4` (`ftyp` + `moov` + `mdat`)
     * with complete `stts`, `ctts`, `stss`, `stsc`, `stsz`, `stco` sample tables.
     */
    private fun muxIsoBmffFragmentedMp4(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val v = parseFmp4File(videoFile)
        val a = parseFmp4File(audioFile)

        val ftyp = v.ftypBox ?: return false
        val vMvhd = v.mvhdBox ?: return false
        if (v.tkhdBox == null || v.mdhdBox == null || v.hdlrBox == null || v.mediaHeaderBox == null || v.dinfBox == null || v.stsdBox == null) return false
        if (a.tkhdBox == null || a.mdhdBox == null || a.hdlrBox == null || a.mediaHeaderBox == null || a.dinfBox == null || a.stsdBox == null) return false
        if (v.chunks.isEmpty() || a.chunks.isEmpty()) return false

        val mvhdScale = when {
            v.mvhdTimescale > 0L -> v.mvhdTimescale
            v.mdhdTimescale > 0L -> v.mdhdTimescale
            else -> 1000L
        }
        val mvhdDur = if (v.mdhdTimescale > 0L) {
            (v.totalDuration * mvhdScale) / v.mdhdTimescale
        } else {
            v.totalDuration
        }

        val mvhd = vMvhd.clone()
        val mvhdVer = mvhd[8].toInt() and 0xFF
        if (mvhdVer == 1 && mvhd.size >= 40) {
            writeU64(mvhd, 32, mvhdDur)
        } else if (mvhd.size >= 28) {
            writeU32(mvhd, 24, mvhdDur)
        }
        writeU32(mvhd, mvhd.size - 4, 3L) // next_track_ID = 3

        val dummyVOffsets = LongArray(v.chunks.size)
        val dummyAOffsets = LongArray(a.chunks.size)
        val dummyMoov = makeMp4Box(
            "moov",
            combineMp4Boxes(
                mvhd,
                buildStandardTrakBox(v, 1L, mvhdScale, dummyVOffsets, isVideo = true),
                buildStandardTrakBox(a, 2L, mvhdScale, dummyAOffsets, isVideo = false)
            )
        )

        val mdatDataStart = (ftyp.size + dummyMoov.size + 8).toLong()
        val realVOffsets = LongArray(v.chunks.size)
        var curOffset = mdatDataStart
        for (i in v.chunks.indices) {
            realVOffsets[i] = curOffset
            curOffset += v.chunks[i].byteLength.toLong()
        }
        val realAOffsets = LongArray(a.chunks.size)
        for (i in a.chunks.indices) {
            realAOffsets[i] = curOffset
            curOffset += a.chunks[i].byteLength.toLong()
        }

        val finalMoov = makeMp4Box(
            "moov",
            combineMp4Boxes(
                mvhd,
                buildStandardTrakBox(v, 1L, mvhdScale, realVOffsets, isVideo = true),
                buildStandardTrakBox(a, 2L, mvhdScale, realAOffsets, isVideo = false)
            )
        )

        FileOutputStream(outputFile).use { out ->
            out.write(ftyp)
            out.write(finalMoov)

            val mdatHeader = ByteArray(8)
            val mdatTotalSize = 8L + v.totalDataBytes + a.totalDataBytes
            writeU32(mdatHeader, 0, mdatTotalSize)
            mdatHeader[4] = 'm'.code.toByte()
            mdatHeader[5] = 'd'.code.toByte()
            mdatHeader[6] = 'a'.code.toByte()
            mdatHeader[7] = 't'.code.toByte()
            out.write(mdatHeader)

            val buf = ByteArray(64 * 1024)
            RandomAccessFile(videoFile, "r").use { vRaf ->
                for (ch in v.chunks) {
                    vRaf.seek(ch.sourceOffset)
                    var rem = ch.byteLength
                    while (rem > 0) {
                        val r = vRaf.read(buf, 0, minOf(buf.size, rem))
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        rem -= r
                    }
                }
            }
            RandomAccessFile(audioFile, "r").use { aRaf ->
                for (ch in a.chunks) {
                    aRaf.seek(ch.sourceOffset)
                    var rem = ch.byteLength
                    while (rem > 0) {
                        val r = aRaf.read(buf, 0, minOf(buf.size, rem))
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        rem -= r
                    }
                }
            }
            out.flush()
        }

        return outputFile.exists() && outputFile.length() > (videoFile.length() + 1024L)
    }

    private fun parseFmp4File(file: File): ParsedFmp4Track {
        val t = ParsedFmp4Track()
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(16)
            val fileLen = raf.length()
            var pos = 0L
            while (pos + 8L <= fileLen) {
                raf.seek(pos)
                if (raf.read(hdr, 0, 8) != 8) break
                var sz = readU32(hdr, 0)
                val type = String(hdr, 4, 4, Charsets.US_ASCII)
                if (sz == 1L) {
                    if (raf.read(hdr, 8, 8) != 8) break
                    sz = readU64(hdr, 8)
                }
                if (sz < 8L || pos + sz > fileLen) break

                when (type) {
                    "ftyp" -> {
                        if (sz <= 4096L) {
                            val b = ByteArray(sz.toInt())
                            raf.seek(pos)
                            raf.readFully(b)
                            t.ftypBox = b
                        }
                    }
                    "moov" -> {
                        if (sz <= 4 * 1024 * 1024L) {
                            val b = ByteArray(sz.toInt())
                            raf.seek(pos)
                            raf.readFully(b)
                            parseMoovBox(b, 8, b.size, t)
                        }
                    }
                    "moof" -> {
                        if (sz <= 2 * 1024 * 1024L) {
                            val b = ByteArray(sz.toInt())
                            raf.seek(pos)
                            raf.readFully(b)
                            parseMoofBox(b, pos, b.size, t)
                        }
                    }
                }
                pos += sz
            }
        }
        return t
    }

    private fun parseMoovBox(moov: ByteArray, offset: Int, end: Int, t: ParsedFmp4Track) {
        var pos = offset
        while (pos + 8 <= end) {
            val sz = readU32(moov, pos).toInt()
            val type = String(moov, pos + 4, 4, Charsets.US_ASCII)
            if (sz < 8 || pos + sz > end) break
            val boxEnd = pos + sz
            when (type) {
                "mvhd" -> {
                    t.mvhdBox = moov.copyOfRange(pos, boxEnd)
                    val ver = moov[pos + 8].toInt() and 0xFF
                    t.mvhdTimescale = if (ver == 1 && sz >= 32) readU32(moov, pos + 28) else readU32(moov, pos + 20)
                }
                "tkhd" -> t.tkhdBox = moov.copyOfRange(pos, boxEnd)
                "mdhd" -> {
                    t.mdhdBox = moov.copyOfRange(pos, boxEnd)
                    val ver = moov[pos + 8].toInt() and 0xFF
                    t.mdhdTimescale = if (ver == 1 && sz >= 32) readU32(moov, pos + 28) else readU32(moov, pos + 20)
                }
                "hdlr" -> if (t.hdlrBox == null) t.hdlrBox = moov.copyOfRange(pos, boxEnd)
                "vmhd", "smhd" -> t.mediaHeaderBox = moov.copyOfRange(pos, boxEnd)
                "dinf" -> t.dinfBox = moov.copyOfRange(pos, boxEnd)
                "stsd" -> t.stsdBox = moov.copyOfRange(pos, boxEnd)
                "trex" -> {
                    if (sz >= 32) {
                        t.defaultSampleDescIndex = readU32(moov, pos + 16)
                        t.defaultSampleDuration = readU32(moov, pos + 20)
                        t.defaultSampleSize = readU32(moov, pos + 24)
                        t.defaultSampleFlags = readU32(moov, pos + 28)
                    }
                }
                "trak", "mdia", "minf", "stbl", "mvex" -> parseMoovBox(moov, pos + 8, boxEnd, t)
            }
            pos = boxEnd
        }
    }

    private fun parseMoofBox(moof: ByteArray, moofFileOffset: Long, moofSize: Int, t: ParsedFmp4Track) {
        var pos = 8
        while (pos + 8 <= moofSize) {
            val sz = readU32(moof, pos).toInt()
            val type = String(moof, pos + 4, 4, Charsets.US_ASCII)
            if (sz < 8 || pos + sz > moofSize) break
            if (type == "traf") {
                var c = pos + 8
                val trafEnd = pos + sz
                var baseDataOffset = moofFileOffset
                var defDur = t.defaultSampleDuration
                var defSize = t.defaultSampleSize
                var defFlags = t.defaultSampleFlags

                while (c + 8 <= trafEnd) {
                    val sSz = readU32(moof, c).toInt()
                    val sType = String(moof, c + 4, 4, Charsets.US_ASCII)
                    if (sSz < 8 || c + sSz > trafEnd) break
                    val flags = ((moof[c + 9].toInt() and 0xFF) shl 16) or
                        ((moof[c + 10].toInt() and 0xFF) shl 8) or
                        (moof[c + 11].toInt() and 0xFF)

                    if (sType == "tfhd") {
                        var p = c + 16
                        if ((flags and 0x01) != 0) { baseDataOffset = readU64(moof, p); p += 8 }
                        if ((flags and 0x02) != 0) { p += 4 }
                        if ((flags and 0x08) != 0) { defDur = readU32(moof, p); p += 4 }
                        if ((flags and 0x10) != 0) { defSize = readU32(moof, p); p += 4 }
                        if ((flags and 0x20) != 0) { defFlags = readU32(moof, p); p += 4 }
                    } else if (sType == "trun") {
                        val sc = readU32(moof, c + 12).toInt()
                        var p = c + 16
                        var dataOff = moofSize + 8
                        if ((flags and 0x01) != 0) { dataOff = readI32(moof, p); p += 4 }
                        var firstFlags = defFlags
                        if ((flags and 0x04) != 0) { firstFlags = readU32(moof, p); p += 4 }

                        var chunkBytes = 0
                        for (i in 0 until sc) {
                            val dur = if ((flags and 0x100) != 0) readU32(moof, p).also { p += 4 } else defDur
                            val smpSz = if ((flags and 0x200) != 0) readU32(moof, p).also { p += 4 } else defSize
                            val smpFl = if ((flags and 0x400) != 0) readU32(moof, p).also { p += 4 } else if (i == 0) firstFlags else defFlags
                            val cts = if ((flags and 0x800) != 0) readI32(moof, p).also { p += 4 } else 0

                            t.sampleDurations.add(dur)
                            t.sampleSizes.add(smpSz)
                            t.sampleFlags.add(smpFl)
                            t.sampleCtsOffsets.add(cts)
                            t.totalDuration += dur
                            chunkBytes += smpSz.toInt()
                        }
                        if (sc > 0 && chunkBytes > 0) {
                            t.chunks.add(
                                Fmp4ChunkRun(
                                    sourceOffset = baseDataOffset + dataOff.toLong(),
                                    byteLength = chunkBytes,
                                    sampleCount = sc
                                )
                            )
                            t.totalDataBytes += chunkBytes.toLong()
                        }
                    }
                    c += sSz
                }
            }
            pos += sz
        }
    }

    private fun buildStandardTrakBox(
        t: ParsedFmp4Track,
        trackId: Long,
        mvhdTimescale: Long,
        chunkOffsets: LongArray,
        isVideo: Boolean
    ): ByteArray {
        val tkhd = t.tkhdBox!!.clone()
        val tkhdVer = tkhd[8].toInt() and 0xFF
        tkhd[9] = 0
        tkhd[10] = 0
        tkhd[11] = 3 // track_enabled | track_in_movie
        val scaledDur = if (t.mdhdTimescale > 0L) (t.totalDuration * mvhdTimescale) / t.mdhdTimescale else t.totalDuration
        if (tkhdVer == 1 && tkhd.size >= 44) {
            writeU32(tkhd, 28, trackId)
            writeU64(tkhd, 36, scaledDur)
        } else if (tkhd.size >= 32) {
            writeU32(tkhd, 20, trackId)
            writeU32(tkhd, 28, scaledDur)
        }

        val mdhd = t.mdhdBox!!.clone()
        val mdhdVer = mdhd[8].toInt() and 0xFF
        if (mdhdVer == 1 && mdhd.size >= 40) {
            writeU64(mdhd, 32, t.totalDuration)
        } else if (mdhd.size >= 28) {
            writeU32(mdhd, 24, t.totalDuration)
        }

        val stts = buildSttsBox(t.sampleDurations)
        val ctts = buildCttsBox(t.sampleCtsOffsets)
        val stss = if (isVideo) buildStssBox(t.sampleFlags) else ByteArray(0)
        val stsc = buildStscBox(t.chunks)
        val stsz = buildStszBox(t.sampleSizes)
        val stco = buildStcoBox(chunkOffsets)

        val stbl = makeMp4Box("stbl", combineMp4Boxes(t.stsdBox!!, stts, ctts, stss, stsc, stsz, stco))
        val minf = makeMp4Box("minf", combineMp4Boxes(t.mediaHeaderBox!!, t.dinfBox!!, stbl))
        val mdia = makeMp4Box("mdia", combineMp4Boxes(mdhd, t.hdlrBox!!, minf))
        return makeMp4Box("trak", combineMp4Boxes(tkhd, mdia))
    }

    private fun buildSttsBox(durations: List<Long>): ByteArray {
        val counts = ArrayList<Long>()
        val deltas = ArrayList<Long>()
        for (d in durations) {
            if (deltas.isNotEmpty() && deltas[deltas.size - 1] == d) {
                counts[counts.size - 1] = counts[counts.size - 1] + 1L
            } else {
                counts.add(1L)
                deltas.add(d)
            }
        }
        val out = ByteArrayOutputStream(16 + counts.size * 8)
        writeU32Stream(out, 0L)
        writeU32Stream(out, counts.size.toLong())
        for (i in counts.indices) {
            writeU32Stream(out, counts[i])
            writeU32Stream(out, deltas[i])
        }
        return makeMp4Box("stts", out.toByteArray())
    }

    private fun buildCttsBox(offsets: List<Int>): ByteArray {
        var hasNonZero = false
        var hasNegative = false
        for (o in offsets) {
            if (o != 0) hasNonZero = true
            if (o < 0) hasNegative = true
        }
        if (!hasNonZero) return ByteArray(0)
        val counts = ArrayList<Long>()
        val vals = ArrayList<Int>()
        for (o in offsets) {
            if (vals.isNotEmpty() && vals[vals.size - 1] == o) {
                counts[counts.size - 1] = counts[counts.size - 1] + 1L
            } else {
                counts.add(1L)
                vals.add(o)
            }
        }
        val out = ByteArrayOutputStream(16 + counts.size * 8)
        writeU32Stream(out, if (hasNegative) 0x01000000L else 0L)
        writeU32Stream(out, counts.size.toLong())
        for (i in counts.indices) {
            writeU32Stream(out, counts[i])
            writeU32Stream(out, vals[i].toLong() and 0xFFFFFFFFL)
        }
        return makeMp4Box("ctts", out.toByteArray())
    }

    private fun buildStssBox(flags: List<Long>): ByteArray {
        val syncs = ArrayList<Long>()
        for (i in flags.indices) {
            if ((flags[i] and 0x00010000L) == 0L) {
                syncs.add((i + 1).toLong())
            }
        }
        if (syncs.isEmpty() || syncs.size == flags.size) return ByteArray(0)
        val out = ByteArrayOutputStream(16 + syncs.size * 4)
        writeU32Stream(out, 0L)
        writeU32Stream(out, syncs.size.toLong())
        for (s in syncs) writeU32Stream(out, s)
        return makeMp4Box("stss", out.toByteArray())
    }

    private fun buildStscBox(chunks: List<Fmp4ChunkRun>): ByteArray {
        val out = ByteArrayOutputStream(16 + chunks.size * 12)
        writeU32Stream(out, 0L)
        writeU32Stream(out, chunks.size.toLong())
        for (i in chunks.indices) {
            writeU32Stream(out, (i + 1).toLong())
            writeU32Stream(out, chunks[i].sampleCount.toLong())
            writeU32Stream(out, 1L)
        }
        return makeMp4Box("stsc", out.toByteArray())
    }

    private fun buildStszBox(sizes: List<Long>): ByteArray {
        val out = ByteArrayOutputStream(20 + sizes.size * 4)
        writeU32Stream(out, 0L)
        writeU32Stream(out, 0L)
        writeU32Stream(out, sizes.size.toLong())
        for (s in sizes) writeU32Stream(out, s)
        return makeMp4Box("stsz", out.toByteArray())
    }

    private fun buildStcoBox(chunkOffsets: LongArray): ByteArray {
        val out = ByteArrayOutputStream(16 + chunkOffsets.size * 4)
        writeU32Stream(out, 0L)
        writeU32Stream(out, chunkOffsets.size.toLong())
        for (o in chunkOffsets) writeU32Stream(out, o)
        return makeMp4Box("stco", out.toByteArray())
    }

    private fun makeMp4Box(type: String, payload: ByteArray): ByteArray {
        val res = ByteArray(8 + payload.size)
        writeU32(res, 0, res.size.toLong())
        val t = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(t, 0, res, 4, 4)
        System.arraycopy(payload, 0, res, 8, payload.size)
        return res
    }

    private fun combineMp4Boxes(vararg boxes: ByteArray): ByteArray {
        var total = 0
        for (b in boxes) total += b.size
        val res = ByteArray(total)
        var pos = 0
        for (b in boxes) {
            if (b.isNotEmpty()) {
                System.arraycopy(b, 0, res, pos, b.size)
                pos += b.size
            }
        }
        return res
    }

    private fun readU32(b: ByteArray, o: Int): Long {
        return ((b[o].toLong() and 0xFFL) shl 24) or
            ((b[o + 1].toLong() and 0xFFL) shl 16) or
            ((b[o + 2].toLong() and 0xFFL) shl 8) or
            (b[o + 3].toLong() and 0xFFL)
    }

    private fun readI32(b: ByteArray, o: Int): Int = readU32(b, o).toInt()

    private fun readU64(b: ByteArray, o: Int): Long {
        return (readU32(b, o) shl 32) or (readU32(b, o + 4) and 0xFFFFFFFFL)
    }

    private fun writeU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFFL).toByte()
        b[o + 1] = ((v ushr 16) and 0xFFL).toByte()
        b[o + 2] = ((v ushr 8) and 0xFFL).toByte()
        b[o + 3] = (v and 0xFFL).toByte()
    }

    private fun writeU64(b: ByteArray, o: Int, v: Long) {
        writeU32(b, o, v ushr 32)
        writeU32(b, o + 4, v and 0xFFFFFFFFL)
    }

    private fun writeU32Stream(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFFL).toInt())
        out.write(((v ushr 16) and 0xFFL).toInt())
        out.write(((v ushr 8) and 0xFFL).toInt())
        out.write((v and 0xFFL).toInt())
    }
}
