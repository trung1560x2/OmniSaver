package com.omnisaver.videodownloader.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.omnisaver.videodownloader.R
import com.omnisaver.videodownloader.download.MediaStorageRepository
import com.omnisaver.videodownloader.model.DownloadedFileItem
import com.omnisaver.videodownloader.model.MediaType
import com.omnisaver.videodownloader.model.SocialPlatform
import java.io.File
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_PLATFORM = "extra_platform"
    }

    private lateinit var mediaStorageRepository: MediaStorageRepository

    private lateinit var rootPlayerContainer: View
    private lateinit var videoView: VideoView
    private lateinit var layoutAudioArtwork: LinearLayout
    private lateinit var tvAudioNowPlayingTitle: TextView
    private lateinit var tvAudioPlatformSubtitle: TextView
    private lateinit var ivFullImage: ImageView
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var bottomControlsBar: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerSubtitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var seekBarProgress: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnFastForward: ImageButton

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentMediaType: MediaType = MediaType.VIDEO
    private var currentFileItem: DownloadedFileItem? = null

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && ::videoView.isInitialized && currentMediaType != MediaType.IMAGE) {
                runCatching {
                    val duration = videoView.duration
                    val currentPos = videoView.currentPosition
                    if (duration > 0) {
                        tvCurrentTime.text = formatTimeMillis(currentPos)
                        tvTotalDuration.text = formatTimeMillis(duration)
                        if (!isUserSeeking) {
                            val progress = ((currentPos.toLong() * 1000L) / duration.toLong()).toInt()
                            seekBarProgress.progress = progress.coerceIn(0, 1000)
                        }
                    }
                    updatePlayPauseIcon()
                }
                uiHandler.postDelayed(this, 350L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        supportActionBar?.hide()
        window.setBackgroundDrawableResource(R.drawable.bg_liquid_aurora_canvas)

        mediaStorageRepository = MediaStorageRepository(this)
        bindViews()

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        val file = File(filePath)
        if (filePath.isBlank() || !file.exists()) {
            Toast.makeText(this, "Không tìm thấy tệp trên thiết bị", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)?.ifBlank { file.name } ?: file.name
        val mediaTypeStr = intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty()
        val platformDisplay = intent.getStringExtra(EXTRA_PLATFORM).orEmpty()

        currentMediaType = runCatching { MediaType.valueOf(mediaTypeStr) }.getOrElse {
            inferMediaTypeFromFile(file)
        }

        val matchedPlatform = SocialPlatform.values().firstOrNull {
            it.displayName.equals(platformDisplay, ignoreCase = true)
        } ?: SocialPlatform.UNKNOWN

        currentFileItem = DownloadedFileItem(
            filePath = file.absolutePath,
            fileName = fileName,
            platform = matchedPlatform,
            mediaType = currentMediaType,
            sizeBytes = file.length(),
            lastModified = file.lastModified()
        )

        tvPlayerTitle.text = fileName
        val formattedSize = mediaStorageRepository.formatFileSize(file.length())
        tvPlayerSubtitle.text = buildString {
            if (platformDisplay.isNotBlank()) {
                append(platformDisplay)
                append(" • ")
            }
            append(formattedSize)
        }
        tvAudioPlatformSubtitle.background = LiquidGlassHelper.createPlatformGlassPill(
            matchedPlatform.badgeColorHex,
            fillAlpha = 115
        )

        when (currentMediaType) {
            MediaType.IMAGE -> setupImageViewer(file)
            MediaType.AUDIO -> setupVideoOrAudioPlayer(file, isAudio = true, fileName = fileName, platformDisplay = platformDisplay)
            MediaType.VIDEO -> setupVideoOrAudioPlayer(file, isAudio = false, fileName = fileName, platformDisplay = platformDisplay)
        }
    }

    private fun bindViews() {
        rootPlayerContainer = findViewById(R.id.rootPlayerContainer)
        videoView = findViewById(R.id.videoView)
        layoutAudioArtwork = findViewById(R.id.layoutAudioArtwork)
        tvAudioNowPlayingTitle = findViewById(R.id.tvAudioNowPlayingTitle)
        tvAudioPlatformSubtitle = findViewById(R.id.tvAudioPlatformSubtitle)
        ivFullImage = findViewById(R.id.ivFullImage)
        topOverlayBar = findViewById(R.id.topOverlayBar)
        bottomControlsBar = findViewById(R.id.bottomControlsBar)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvPlayerSubtitle = findViewById(R.id.tvPlayerSubtitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalDuration = findViewById(R.id.tvTotalDuration)
        seekBarProgress = findViewById(R.id.seekBarProgress)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnRewind = findViewById(R.id.btnRewind)
        btnFastForward = findViewById(R.id.btnFastForward)

        val btnPlayerBack = findViewById<ImageButton>(R.id.btnPlayerBack)
        val btnPlayerShare = findViewById<MaterialButton>(R.id.btnPlayerShare)

        btnPlayerBack.setOnClickListener { finish() }
        btnPlayerShare.setOnClickListener {
            currentFileItem?.let { item ->
                mediaStorageRepository.shareFile(this, item)
            }
        }
    }

    private fun setupImageViewer(file: File) {
        videoView.visibility = View.GONE
        layoutAudioArtwork.visibility = View.GONE
        bottomControlsBar.visibility = View.GONE
        ivFullImage.visibility = View.VISIBLE

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            ivFullImage.setImageBitmap(bitmap)
        } else {
            ivFullImage.setImageURI(Uri.fromFile(file))
        }

        ivFullImage.setOnClickListener {
            topOverlayBar.visibility = if (topOverlayBar.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    private fun setupVideoOrAudioPlayer(
        file: File,
        isAudio: Boolean,
        fileName: String,
        platformDisplay: String
    ) {
        ivFullImage.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        bottomControlsBar.visibility = View.VISIBLE

        if (isAudio) {
            layoutAudioArtwork.visibility = View.VISIBLE
            tvAudioNowPlayingTitle.text = fileName
            tvAudioPlatformSubtitle.text = if (platformDisplay.isNotBlank()) {
                "Nguồn: $platformDisplay • Âm thanh chất lượng cao"
            } else {
                "Trình phát âm thanh OmniSaver"
            }
        } else {
            layoutAudioArtwork.visibility = View.GONE
        }

        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        videoView.setVideoPath(file.absolutePath)

        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = false
            val duration = videoView.duration
            tvTotalDuration.text = formatTimeMillis(duration)
            videoView.start()
            updatePlayPauseIcon()
            uiHandler.removeCallbacks(progressUpdateRunnable)
            uiHandler.post(progressUpdateRunnable)
        }

        videoView.setOnCompletionListener {
            seekBarProgress.progress = 1000
            updatePlayPauseIcon()
        }

        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Không thể phát tệp phương tiện này", Toast.LENGTH_SHORT).show()
            true
        }

        btnPlayPause.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
            } else {
                videoView.start()
            }
            updatePlayPauseIcon()
        }

        btnRewind.setOnClickListener {
            val target = (videoView.currentPosition - 10_000).coerceAtLeast(0)
            videoView.seekTo(target)
        }

        btnFastForward.setOnClickListener {
            val duration = videoView.duration
            if (duration > 0) {
                val target = (videoView.currentPosition + 10_000).coerceAtMost(duration)
                videoView.seekTo(target)
            }
        }

        seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && videoView.duration > 0) {
                    val targetPos = ((progress.toLong() * videoView.duration.toLong()) / 1000L).toInt()
                    tvCurrentTime.text = formatTimeMillis(targetPos)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = videoView.duration
                if (seekBar != null && duration > 0) {
                    val targetPos = ((seekBar.progress.toLong() * duration.toLong()) / 1000L).toInt()
                    videoView.seekTo(targetPos)
                }
                isUserSeeking = false
            }
        })

        rootPlayerContainer.setOnClickListener {
            if (!isAudio) {
                val newVisibility = if (topOverlayBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                topOverlayBar.visibility = newVisibility
                bottomControlsBar.visibility = newVisibility
            }
        }
    }

    private fun updatePlayPauseIcon() {
        val iconRes = if (videoView.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        btnPlayPause.setImageResource(iconRes)
    }

    private fun inferMediaTypeFromFile(file: File): MediaType {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "mp3", "m4a", "wav", "aac", "ogg" -> MediaType.AUDIO
            "jpg", "jpeg", "png", "webp", "gif" -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }
    }

    private fun formatTimeMillis(millis: Int): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.pause()
            updatePlayPauseIcon()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(progressUpdateRunnable)
        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }
    }
}
