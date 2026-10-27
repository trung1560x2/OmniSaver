package com.omnisaver.videodownloader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.omnisaver.videodownloader.R
import com.omnisaver.videodownloader.download.MediaStorageRepository
import com.omnisaver.videodownloader.model.DownloadedFileItem
import com.omnisaver.videodownloader.model.MediaType
import com.omnisaver.videodownloader.model.SocialPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActivity : AppCompatActivity() {

    private lateinit var mediaStorageRepository: MediaStorageRepository
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rvDownloadedFiles: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvFileCountBadge: TextView
    private lateinit var tvLibrarySubtitle: TextView
    private lateinit var chipGroupMediaType: ChipGroup
    private lateinit var chipGroupPlatform: ChipGroup

    private var selectedMediaType: MediaType? = null
    private var selectedPlatform: SocialPlatform? = null

    private val thumbnailCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val fileAdapter = DownloadedFileAdapter()
    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        mediaStorageRepository = MediaStorageRepository(this)

        bindViews()
        setupFilters()
        refreshFilesList()
    }

    override fun onResume() {
        super.onResume()
        refreshFilesList()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun bindViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        rvDownloadedFiles = findViewById(R.id.rvDownloadedFiles)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvFileCountBadge = findViewById(R.id.tvFileCountBadge)
        tvLibrarySubtitle = findViewById(R.id.tvLibrarySubtitle)
        chipGroupMediaType = findViewById(R.id.chipGroupMediaType)
        chipGroupPlatform = findViewById(R.id.chipGroupPlatform)

        btnBack.setOnClickListener { finish() }

        tvLibrarySubtitle.text = "/Download/OmniSaver"
        tvFileCountBadge.background = LiquidGlassHelper.createPlatformGlassPill("#22C55E")

        rvDownloadedFiles.layoutManager = LinearLayoutManager(this)
        rvDownloadedFiles.adapter = fileAdapter
    }

    private fun setupFilters() {
        chipGroupMediaType.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipTypeAll
            selectedMediaType = when (checkedId) {
                R.id.chipTypeVideo -> MediaType.VIDEO
                R.id.chipTypeAudio -> MediaType.AUDIO
                R.id.chipTypeImage -> MediaType.IMAGE
                else -> null
            }
            refreshFilesList()
        }

        chipGroupPlatform.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipPlatformAll
            selectedPlatform = when (checkedId) {
                R.id.chipPlatformFacebook -> SocialPlatform.FACEBOOK
                R.id.chipPlatformX -> SocialPlatform.X_TWITTER
                R.id.chipPlatformInstagram -> SocialPlatform.INSTAGRAM
                R.id.chipPlatformTikTok -> SocialPlatform.TIKTOK
                R.id.chipPlatformThreads -> SocialPlatform.THREADS
                else -> null
            }
            refreshFilesList()
        }
    }

    private fun refreshFilesList() {
        activityScope.launch {
            val files = withContext(Dispatchers.IO) {
                mediaStorageRepository.listDownloadedFiles(
                    platformFilter = selectedPlatform,
                    typeFilter = selectedMediaType
                )
            }

            tvFileCountBadge.text = "${files.size} tệp"
            fileAdapter.submitList(files)

            if (files.isEmpty()) {
                rvDownloadedFiles.visibility = View.GONE
                layoutEmptyState.visibility = View.VISIBLE
                tvEmptyTitle.text = if (selectedMediaType != null || selectedPlatform != null) {
                    "Không có tệp nào khớp bộ lọc hiện tại"
                } else {
                    "Thư viện trống — Chưa có tệp nào được tải về"
                }
            } else {
                rvDownloadedFiles.visibility = View.VISIBLE
                layoutEmptyState.visibility = View.GONE
            }
        }
    }

    private fun openInPlayer(item: DownloadedFileItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_FILE_PATH, item.filePath)
            putExtra(PlayerActivity.EXTRA_FILE_NAME, item.fileName)
            putExtra(PlayerActivity.EXTRA_MEDIA_TYPE, item.mediaType.name)
            putExtra(PlayerActivity.EXTRA_PLATFORM, item.platform.displayName)
        }
        startActivity(intent)
    }

    private fun confirmAndDeleteFile(item: DownloadedFileItem) {
        AlertDialog.Builder(this)
            .setTitle("Xóa tệp đã tải?")
            .setMessage("Bạn có chắc chắn muốn xóa vĩnh viễn tệp \"${item.fileName}\" khỏi bộ nhớ thiết bị?")
            .setPositiveButton("Xóa") { _, _ ->
                val deleted = mediaStorageRepository.deleteFile(item)
                if (deleted) {
                    thumbnailCache.remove(item.filePath)
                    Toast.makeText(this, "Đã xóa: ${item.fileName}", Toast.LENGTH_SHORT).show()
                    refreshFilesList()
                } else {
                    Toast.makeText(this, "Không thể xóa tệp này", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun loadLocalThumbnail(item: DownloadedFileItem, imageView: ImageView) {
        imageView.tag = item.filePath

        if (item.mediaType == MediaType.AUDIO) {
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageView.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            imageView.setColorFilter(Color.parseColor("#A855F7"))
            return
        }

        imageView.clearColorFilter()
        val cached = thumbnailCache.get(item.filePath)
        if (cached != null) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setImageBitmap(cached)
            return
        }

        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setImageResource(
            if (item.mediaType == MediaType.VIDEO) {
                android.R.drawable.ic_media_play
            } else {
                android.R.drawable.ic_menu_gallery
            }
        )

        activityScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                extractThumbnailBitmap(item)
            }

            if (bitmap != null) {
                thumbnailCache.put(item.filePath, bitmap)
                if (imageView.tag == item.filePath) {
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractThumbnailBitmap(item: DownloadedFileItem): Bitmap? {
        val file = File(item.filePath)
        if (!file.exists()) return null

        return runCatching {
            when (item.mediaType) {
                MediaType.IMAGE -> decodeDownsampledBitmap(file.absolutePath, 256, 256)
                MediaType.VIDEO -> {
                    val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(256, 256), null)
                    } else {
                        ThumbnailUtils.createVideoThumbnail(
                            file.absolutePath,
                            MediaStore.Images.Thumbnails.MINI_KIND
                        )
                    }
                    thumb ?: run {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(0)
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }
                }
                MediaType.AUDIO -> null
            }
        }.getOrNull()
    }

    private fun decodeDownsampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        var inSampleSize = 1
        val (height, width) = options.outHeight to options.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, decodeOptions)
    }

    private fun createRoundedBadgeDrawable(fillColor: Int, radiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
        }
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
    }

    private inner class DownloadedFileAdapter :
        RecyclerView.Adapter<DownloadedFileAdapter.FileViewHolder>() {

        private val items = mutableListOf<DownloadedFileItem>()

        fun submitList(newItems: List<DownloadedFileItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardDownloadedFile: MaterialCardView = view.findViewById(R.id.cardDownloadedFile)
            val ivFileThumbnail: ImageView = view.findViewById(R.id.ivFileThumbnail)
            val ivMediaTypeOverlay: ImageView = view.findViewById(R.id.ivMediaTypeOverlay)
            val tvMediaTypeTag: TextView = view.findViewById(R.id.tvMediaTypeTag)
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            val tvFilePlatformBadge: TextView = view.findViewById(R.id.tvFilePlatformBadge)
            val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)
            val tvFileDate: TextView = view.findViewById(R.id.tvFileDate)
            val btnPlayFile: MaterialButton = view.findViewById(R.id.btnPlayFile)
            val btnShareFile: MaterialButton = view.findViewById(R.id.btnShareFile)
            val btnDeleteFile: MaterialButton = view.findViewById(R.id.btnDeleteFile)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_downloaded_file, parent, false)
            return FileViewHolder(view)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            val item = items[position]

            holder.cardDownloadedFile.setCardBackgroundColor(Color.TRANSPARENT)
            holder.cardDownloadedFile.strokeWidth = 0
            holder.cardDownloadedFile.setBackgroundResource(R.drawable.bg_liquid_glass_card)
            holder.cardDownloadedFile.getChildAt(0)?.setBackgroundResource(R.drawable.bg_liquid_glass_card)

            holder.tvFileName.text = item.fileName
            holder.tvFilePlatformBadge.text = item.platform.displayName
            holder.tvFilePlatformBadge.background =
                LiquidGlassHelper.createPlatformGlassPill(item.platform.badgeColorHex)

            holder.tvFileSize.text = mediaStorageRepository.formatFileSize(item.sizeBytes)
            holder.tvFileSize.setBackgroundResource(R.drawable.bg_liquid_glass_pill)

            holder.tvFileDate.text = dateFormatter.format(Date(item.lastModified))

            when (item.mediaType) {
                MediaType.VIDEO -> {
                    holder.tvMediaTypeTag.text = "VIDEO"
                    holder.tvMediaTypeTag.setTextColor(Color.parseColor("#7DD3FC"))
                    holder.tvMediaTypeTag.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                    holder.ivMediaTypeOverlay.visibility = View.VISIBLE
                    holder.ivMediaTypeOverlay.setImageResource(android.R.drawable.ic_media_play)
                    holder.ivMediaTypeOverlay.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                    holder.btnPlayFile.text = "Phát video"
                }
                MediaType.AUDIO -> {
                    holder.tvMediaTypeTag.text = "MP3"
                    holder.tvMediaTypeTag.setTextColor(Color.parseColor("#E9D5FF"))
                    holder.tvMediaTypeTag.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                    holder.ivMediaTypeOverlay.visibility = View.GONE
                    holder.btnPlayFile.text = "Nghe nhạc"
                }
                MediaType.IMAGE -> {
                    holder.tvMediaTypeTag.text = "ẢNH"
                    holder.tvMediaTypeTag.setTextColor(Color.parseColor("#FDE68A"))
                    holder.tvMediaTypeTag.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                    holder.ivMediaTypeOverlay.visibility = View.GONE
                    holder.btnPlayFile.text = "Xem ảnh"
                }
            }

            loadLocalThumbnail(item, holder.ivFileThumbnail)

            holder.cardDownloadedFile.setOnClickListener { openInPlayer(item) }
            holder.btnPlayFile.setOnClickListener { openInPlayer(item) }
            holder.btnShareFile.setOnClickListener {
                mediaStorageRepository.shareFile(this@LibraryActivity, item)
            }
            holder.btnDeleteFile.setOnClickListener {
                confirmAndDeleteFile(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
