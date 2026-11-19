package com.omnisaver.videodownloader.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.omnisaver.videodownloader.R
import com.omnisaver.videodownloader.model.ExtractedMediaInfo
import com.omnisaver.videodownloader.model.MediaOption
import com.omnisaver.videodownloader.model.MediaType
import com.omnisaver.videodownloader.model.SocialPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class QualityBottomSheetDialog(
    context: Context,
    private val mediaInfo: ExtractedMediaInfo,
    private val onBatchSelected: ((List<MediaOption>) -> Unit)? = null,
    private val onOptionSelected: (MediaOption) -> Unit
) : BottomSheetDialog(context) {

    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rowThumbCache = android.util.LruCache<String, android.graphics.Bitmap>(36)

    private val thumbnailHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentView = layoutInflater.inflate(R.layout.dialog_quality_picker, null)
        setContentView(contentView)

        LiquidGlassHelper.applyWindowBlurIfSupported(window)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        (contentView.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        bindHeaderAndPreview(contentView)
        setupOptionsList(contentView)
    }

    private fun bindHeaderAndPreview(root: View) {
        val btnCloseSheet = root.findViewById<ImageButton>(R.id.btnCloseSheet)
        val ivThumbnail = root.findViewById<ImageView>(R.id.ivMediaThumbnail)
        val tvPlatformBadge = root.findViewById<TextView>(R.id.tvPlatformBadge)
        val tvDurationBadge = root.findViewById<TextView>(R.id.tvDurationBadge)
        val tvTitle = root.findViewById<TextView>(R.id.tvMediaTitle)
        val tvAuthor = root.findViewById<TextView>(R.id.tvMediaAuthor)

        btnCloseSheet.setOnClickListener { dismiss() }

        tvPlatformBadge.text = mediaInfo.platform.displayName
        tvPlatformBadge.background = LiquidGlassHelper.createPlatformGlassPill(mediaInfo.platform.badgeColorHex)

        if (mediaInfo.durationSeconds > 0L) {
            tvDurationBadge.visibility = View.VISIBLE
            tvDurationBadge.text = formatDuration(mediaInfo.durationSeconds)
            tvDurationBadge.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
        } else {
            tvDurationBadge.visibility = View.GONE
        }

        tvTitle.text = mediaInfo.title.ifBlank { "Video từ ${mediaInfo.platform.displayName}" }
        tvAuthor.text = if (mediaInfo.author.isNotBlank()) {
            "Tác giả: ${mediaInfo.author}"
        } else {
            mediaInfo.cleanUrl
        }

        val thumbUrl = mediaInfo.thumbnailUrl.trim()
        if (thumbUrl.isNotBlank() && (thumbUrl.startsWith("http://") || thumbUrl.startsWith("https://"))) {
            loadRemoteThumbnail(thumbUrl, ivThumbnail)
        } else {
            ivThumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ivThumbnail.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun setupOptionsList(root: View) {
        val btnDownloadAllBatch = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownloadAllBatch)
        val tvMultiSelectHint = root.findViewById<TextView>(R.id.tvMultiSelectHint)
        val btnToggleSelectAll = root.findViewById<TextView>(R.id.btnToggleSelectAll)
        val btnDownloadSelected = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownloadSelected)

        val batchOptions = mediaInfo.batchDownloadOptions()
        if (batchOptions.size >= 2 && btnDownloadAllBatch != null) {
            btnDownloadAllBatch.visibility = View.VISIBLE
            btnDownloadAllBatch.text = "TẢI TRỌN BỘ ${batchOptions.size} STORY / VIDEO (FULL HD)"
            btnDownloadAllBatch.setOnClickListener {
                if (onBatchSelected != null) {
                    onBatchSelected.invoke(batchOptions)
                } else {
                    batchOptions.forEach { opt -> onOptionSelected(opt) }
                }
                dismiss()
            }
        } else if (btnDownloadAllBatch != null) {
            btnDownloadAllBatch.visibility = View.GONE
        }

        val selectedIndices = LinkedHashSet<Int>()
        val initialIdx = mediaInfo.options.indexOfFirst { it.isHighestQuality }.takeIf { it >= 0 } ?: 0
        if (mediaInfo.options.isNotEmpty()) {
            selectedIndices.add(initialIdx)
        }

        val selectAllTargetIndices: List<Int> = if (batchOptions.size >= 2) {
            val batchSet = batchOptions.toSet()
            mediaInfo.options.indices.filter { mediaInfo.options[it] in batchSet }
        } else {
            mediaInfo.options.indices.filter { mediaInfo.options[it].mediaType != MediaType.AUDIO }
                .ifEmpty { mediaInfo.options.indices.toList() }
        }

        val rvOptions = root.findViewById<RecyclerView>(R.id.rvQualityOptions)
        rvOptions.layoutManager = LinearLayoutManager(context)

        fun refreshMultiSelectFooter() {
            val count = selectedIndices.size
            tvMultiSelectHint?.text = "TÍCH CHỌN ĐA MỤC (ĐÃ CHỌN $count)"
            val allSelected = selectAllTargetIndices.isNotEmpty() && selectAllTargetIndices.all { it in selectedIndices }
            btnToggleSelectAll?.text = if (allSelected) {
                "Chỉ chọn tốt nhất"
            } else {
                "Chọn tất cả (${selectAllTargetIndices.size})"
            }
            if (btnDownloadSelected != null) {
                btnDownloadSelected.isEnabled = count > 0
                btnDownloadSelected.alpha = if (count > 0) 1.0f else 0.5f
                btnDownloadSelected.text = when {
                    count == 0 -> "HÃY TÍCH CHỌN ÍT NHẤT 1 MỤC"
                    count == 1 -> "TẢI NGAY MỤC ĐÃ CHỌN"
                    else -> "TẢI CÙNG LÚC $count MỤC ĐÃ CHỌN"
                }
            }
        }

        val adapter = QualityOptionAdapter(
            options = mediaInfo.options,
            platform = mediaInfo.platform,
            selectedIndices = selectedIndices,
            onToggleIndex = { pos ->
                if (selectedIndices.contains(pos)) {
                    selectedIndices.remove(pos)
                } else {
                    selectedIndices.add(pos)
                }
                refreshMultiSelectFooter()
            },
            onInstantSingleDownload = { singleOpt ->
                onOptionSelected(singleOpt)
                dismiss()
            }
        )
        rvOptions.adapter = adapter

        btnToggleSelectAll?.setOnClickListener {
            val allSelected = selectAllTargetIndices.isNotEmpty() && selectAllTargetIndices.all { it in selectedIndices }
            selectedIndices.clear()
            if (allSelected) {
                if (mediaInfo.options.isNotEmpty()) selectedIndices.add(initialIdx)
            } else {
                selectedIndices.addAll(selectAllTargetIndices)
            }
            adapter.notifyDataSetChanged()
            refreshMultiSelectFooter()
        }

        btnDownloadSelected?.setOnClickListener {
            val chosen = selectedIndices.sorted().mapNotNull { mediaInfo.options.getOrNull(it) }
            if (chosen.isEmpty()) return@setOnClickListener
            if (chosen.size == 1) {
                onOptionSelected(chosen.first())
            } else if (onBatchSelected != null) {
                onBatchSelected.invoke(chosen)
            } else {
                chosen.forEach { opt -> onOptionSelected(opt) }
            }
            dismiss()
        }

        refreshMultiSelectFooter()
    }

    private fun loadRemoteThumbnail(url: String, targetImageView: ImageView) {
        dialogScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val customHeaders = mediaInfo.options.firstOrNull()?.customHeaders.orEmpty()
                    val reqBuilder = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            customHeaders["User-Agent"]
                                ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        )
                        .header("Referer", customHeaders["Referer"] ?: mediaInfo.platform.referer)
                    val cookieVal = customHeaders["Cookie"].orEmpty()
                    if (cookieVal.isNotBlank()) {
                        reqBuilder.header("Cookie", cookieVal)
                    }

                    thumbnailHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val bytes = response.body?.bytes() ?: return@use null
                        if (bytes.isEmpty()) return@use null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }

            if (bitmap != null) {
                targetImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                targetImageView.setImageBitmap(bitmap)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        dialogScope.cancel()
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
    }

    private fun createRoundedBadgeDrawable(fillColor: Int, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
        }
    }

    private fun showInlineVideoPreviewDialog(option: MediaOption) {
        val density = context.resources.displayMetrics.density
        val container = android.widget.FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
            val hPx = (360 * density).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx)
        }
        val videoView = android.widget.VideoView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (360 * density).toInt(),
                android.view.Gravity.CENTER
            )
        }
        val progress = android.widget.ProgressBar(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        container.addView(videoView)
        container.addView(progress)

        val controller = android.widget.MediaController(context)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { mp ->
            progress.visibility = View.GONE
            mp.isLooping = true
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            android.widget.Toast.makeText(
                context,
                "Không thể phát xem thử luồng này (luồng DASH cần tải xuống để ghép kênh).",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }

        val headers = HashMap<String, String>(option.customHeaders)
        if (!headers.containsKey("Referer") && mediaInfo.platform.referer.isNotBlank()) {
            headers["Referer"] = mediaInfo.platform.referer
        }
        videoView.setVideoURI(android.net.Uri.parse(option.url), headers)

        val previewDialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Xem thử • ${option.qualityLabel}")
            .setView(container)
            .setPositiveButton("Tải ngay") { d, _ ->
                videoView.stopPlayback()
                d.dismiss()
                onOptionSelected(option)
                dismiss()
            }
            .setNegativeButton("Đóng") { d, _ ->
                videoView.stopPlayback()
                d.dismiss()
            }
            .create()
        previewDialog.setOnDismissListener { videoView.stopPlayback() }
        previewDialog.show()
    }

    private fun showInlineImagePreviewDialog(option: MediaOption) {
        val density = context.resources.displayMetrics.density
        val container = android.widget.FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (440 * density).toInt()
            )
        }
        val imageView = ImageView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (440 * density).toInt(),
                android.view.Gravity.CENTER
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val progress = android.widget.ProgressBar(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        container.addView(imageView)
        container.addView(progress)

        val cached = rowThumbCache.get(option.url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            progress.visibility = View.GONE
        }

        dialogScope.launch {
            val fullBmp = withContext(Dispatchers.IO) {
                fetchBitmapWithHeaders(option.url, option.customHeaders)
            }
            progress.visibility = View.GONE
            if (fullBmp != null) {
                rowThumbCache.put(option.url, fullBmp)
                imageView.setImageBitmap(fullBmp)
            } else if (cached == null) {
                android.widget.Toast.makeText(
                    context,
                    "Không thể tải xem trước tấm ảnh này.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Xem ảnh • ${option.qualityLabel}")
            .setView(container)
            .setPositiveButton("Tải ảnh này") { d, _ ->
                d.dismiss()
                onOptionSelected(option)
                dismiss()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun fetchBitmapWithHeaders(url: String, customHeaders: Map<String, String>): android.graphics.Bitmap? {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    customHeaders["User-Agent"]
                        ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                .header("Referer", customHeaders["Referer"] ?: mediaInfo.platform.referer)
            val cookieVal = customHeaders["Cookie"].orEmpty()
            if (cookieVal.isNotBlank()) {
                reqBuilder.header("Cookie", cookieVal)
            }
            thumbnailHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                if (bytes.isEmpty()) return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    private inner class QualityOptionAdapter(
        private val options: List<MediaOption>,
        private val platform: SocialPlatform,
        private val selectedIndices: Set<Int>,
        private val onToggleIndex: (Int) -> Unit,
        private val onInstantSingleDownload: (MediaOption) -> Unit
    ) : RecyclerView.Adapter<QualityOptionAdapter.OptionViewHolder>() {

        inner class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardQualityOption: MaterialCardView = view.findViewById(R.id.cardQualityOption)
            val cbSelectOption: android.widget.CheckBox = view.findViewById(R.id.cbSelectOption)
            val cardTypeIcon: MaterialCardView = view.findViewById(R.id.cardTypeIcon)
            val ivTypeIcon: ImageView = view.findViewById(R.id.ivTypeIcon)
            val tvQualityLabel: TextView = view.findViewById(R.id.tvQualityLabel)
            val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
            val tvExtensionAndSize: TextView = view.findViewById(R.id.tvExtensionAndSize)
            val tvBestQualityBadge: TextView = view.findViewById(R.id.tvBestQualityBadge)
            val tvNoWatermarkBadge: TextView = view.findViewById(R.id.tvNoWatermarkBadge)
            val ivDownloadAction: ImageView = view.findViewById(R.id.ivDownloadAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quality_option, parent, false)
            return OptionViewHolder(view)
        }

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
            val option = options[position]
            val isChecked = position in selectedIndices
            holder.cbSelectOption.isChecked = isChecked

            holder.tvQualityLabel.text = option.qualityLabel

            val normalizedExt = option.extension.trim().trimStart('.').lowercase(Locale.ROOT).ifBlank {
                when (option.mediaType) {
                    MediaType.VIDEO -> "mp4"
                    MediaType.AUDIO -> "mp3"
                    MediaType.IMAGE -> "jpg"
                }
            }

            val sizeSuffix = if (option.sizeText.isNotBlank()) " • ${option.sizeText}" else ""
            val previewHint = when (option.mediaType) {
                MediaType.VIDEO -> " • ▶ Xem thử"
                MediaType.IMAGE -> " • 🔍 Xem ảnh"
                MediaType.AUDIO -> ""
            }
            holder.tvExtensionAndSize.text = ".$normalizedExt$sizeSuffix$previewHint"

            val iconPadPx = (11 * holder.itemView.resources.displayMetrics.density).toInt()
            holder.ivTypeIcon.tag = option.url

            when (option.mediaType) {
                MediaType.VIDEO -> {
                    holder.tvTypeBadge.text = "VIDEO"
                    holder.tvTypeBadge.setTextColor(Color.parseColor("#E0F2FE"))
                    holder.tvTypeBadge.background = LiquidGlassHelper.createPlatformGlassPill("#38BDF8")
                    holder.ivTypeIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    holder.ivTypeIcon.setPadding(iconPadPx, iconPadPx, iconPadPx, iconPadPx)
                    holder.ivTypeIcon.setImageResource(android.R.drawable.ic_media_play)
                    holder.ivTypeIcon.setColorFilter(Color.parseColor("#38BDF8"))
                }
                MediaType.AUDIO -> {
                    holder.tvTypeBadge.text = "AUDIO MP3"
                    holder.tvTypeBadge.setTextColor(Color.parseColor("#F3E8FF"))
                    holder.tvTypeBadge.background = LiquidGlassHelper.createPlatformGlassPill("#A855F7")
                    holder.ivTypeIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    holder.ivTypeIcon.setPadding(iconPadPx, iconPadPx, iconPadPx, iconPadPx)
                    holder.ivTypeIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    holder.ivTypeIcon.setColorFilter(Color.parseColor("#A855F7"))
                }
                MediaType.IMAGE -> {
                    holder.tvTypeBadge.text = "ẢNH HD"
                    holder.tvTypeBadge.setTextColor(Color.parseColor("#FEF3C7"))
                    holder.tvTypeBadge.background = LiquidGlassHelper.createPlatformGlassPill("#F59E0B")
                    val cachedBmp = rowThumbCache.get(option.url)
                    if (cachedBmp != null) {
                        holder.ivTypeIcon.clearColorFilter()
                        holder.ivTypeIcon.setPadding(0, 0, 0, 0)
                        holder.ivTypeIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                        holder.ivTypeIcon.setImageBitmap(cachedBmp)
                    } else {
                        holder.ivTypeIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        holder.ivTypeIcon.setPadding(iconPadPx, iconPadPx, iconPadPx, iconPadPx)
                        holder.ivTypeIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                        holder.ivTypeIcon.setColorFilter(Color.parseColor("#F59E0B"))
                        val expectedUrl = option.url
                        dialogScope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                fetchBitmapWithHeaders(expectedUrl, option.customHeaders)
                            }
                            if (bmp != null) {
                                rowThumbCache.put(expectedUrl, bmp)
                                if (holder.ivTypeIcon.tag == expectedUrl) {
                                    holder.ivTypeIcon.clearColorFilter()
                                    holder.ivTypeIcon.setPadding(0, 0, 0, 0)
                                    holder.ivTypeIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                                    holder.ivTypeIcon.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }
            }

            val cardGlassRes = if (isChecked || option.isHighestQuality) {
                R.drawable.bg_liquid_glass_card_accent
            } else {
                R.drawable.bg_liquid_glass_card
            }
            holder.cardQualityOption.setCardBackgroundColor(Color.TRANSPARENT)
            holder.cardQualityOption.strokeWidth = 0
            holder.cardQualityOption.setBackgroundResource(cardGlassRes)
            holder.cardQualityOption.getChildAt(0)?.setBackgroundResource(cardGlassRes)

            if (option.isHighestQuality) {
                holder.tvBestQualityBadge.visibility = View.VISIBLE
                holder.tvBestQualityBadge.background = LiquidGlassHelper.createPlatformGlassPill("#22C55E")
            } else {
                holder.tvBestQualityBadge.visibility = View.GONE
            }

            if (option.isNoWatermark && platform == SocialPlatform.TIKTOK) {
                holder.tvNoWatermarkBadge.visibility = View.VISIBLE
                holder.tvNoWatermarkBadge.background = LiquidGlassHelper.createPlatformGlassPill("#06B6D4")
            } else {
                holder.tvNoWatermarkBadge.visibility = View.GONE
            }

            holder.cardQualityOption.setOnClickListener {
                onToggleIndex(position)
                notifyItemChanged(position)
            }

            holder.cardTypeIcon.setOnClickListener {
                when (option.mediaType) {
                    MediaType.VIDEO -> showInlineVideoPreviewDialog(option)
                    MediaType.IMAGE -> showInlineImagePreviewDialog(option)
                    MediaType.AUDIO -> Unit
                }
            }

            holder.ivDownloadAction.setOnClickListener {
                onInstantSingleDownload(option)
            }
        }

        override fun getItemCount(): Int = options.size
    }
}
