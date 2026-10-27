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
    private val onOptionSelected: (MediaOption) -> Unit
) : BottomSheetDialog(context) {

    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        val rvOptions = root.findViewById<RecyclerView>(R.id.rvQualityOptions)
        rvOptions.layoutManager = LinearLayoutManager(context)
        rvOptions.adapter = QualityOptionAdapter(
            options = mediaInfo.options,
            platform = mediaInfo.platform
        ) { selectedOption ->
            onOptionSelected(selectedOption)
            dismiss()
        }
    }

    private fun loadRemoteThumbnail(url: String, targetImageView: ImageView) {
        dialogScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        )
                        .header("Referer", mediaInfo.platform.referer)
                        .build()

                    thumbnailHttpClient.newCall(request).execute().use { response ->
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

    private inner class QualityOptionAdapter(
        private val options: List<MediaOption>,
        private val platform: SocialPlatform,
        private val onClick: (MediaOption) -> Unit
    ) : RecyclerView.Adapter<QualityOptionAdapter.OptionViewHolder>() {

        inner class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardQualityOption: MaterialCardView = view.findViewById(R.id.cardQualityOption)
            val cardTypeIcon: MaterialCardView = view.findViewById(R.id.cardTypeIcon)
            val ivTypeIcon: ImageView = view.findViewById(R.id.ivTypeIcon)
            val tvQualityLabel: TextView = view.findViewById(R.id.tvQualityLabel)
            val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
            val tvExtensionAndSize: TextView = view.findViewById(R.id.tvExtensionAndSize)
            val tvBestQualityBadge: TextView = view.findViewById(R.id.tvBestQualityBadge)
            val tvNoWatermarkBadge: TextView = view.findViewById(R.id.tvNoWatermarkBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quality_option, parent, false)
            return OptionViewHolder(view)
        }

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
            val option = options[position]

            holder.tvQualityLabel.text = option.qualityLabel

            val normalizedExt = option.extension.trim().trimStart('.').lowercase(Locale.ROOT).ifBlank {
                when (option.mediaType) {
                    MediaType.VIDEO -> "mp4"
                    MediaType.AUDIO -> "mp3"
                    MediaType.IMAGE -> "jpg"
                }
            }

            val sizeSuffix = if (option.sizeText.isNotBlank()) " • ${option.sizeText}" else ""
            holder.tvExtensionAndSize.text = ".$normalizedExt$sizeSuffix"

            when (option.mediaType) {
                MediaType.VIDEO -> {
                    holder.tvTypeBadge.text = "VIDEO"
                    holder.tvTypeBadge.setTextColor(Color.parseColor("#E0F2FE"))
                    holder.tvTypeBadge.background = LiquidGlassHelper.createPlatformGlassPill("#38BDF8")
                    holder.ivTypeIcon.setImageResource(android.R.drawable.ic_media_play)
                    holder.ivTypeIcon.setColorFilter(Color.parseColor("#38BDF8"))
                }
                MediaType.AUDIO -> {
                    holder.tvTypeBadge.text = "AUDIO MP3"
                    holder.tvTypeBadge.setTextColor(Color.parseColor("#F3E8FF"))
                    holder.tvTypeBadge.background = LiquidGlassHelper.createPlatformGlassPill("#A855F7")
                    holder.ivTypeIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    holder.ivTypeIcon.setColorFilter(Color.parseColor("#A855F7"))
                }
                MediaType.IMAGE -> {
                    holder.tvTypeBadge.text = "ẢNH BÌA / PHOTO"
                    holder.tvTypeBadge.setTextColor(Color.parseColor("#FEF3C7"))
                    holder.tvTypeBadge.background = LiquidGlassHelper.createPlatformGlassPill("#F59E0B")
                    holder.ivTypeIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                    holder.ivTypeIcon.setColorFilter(Color.parseColor("#F59E0B"))
                }
            }

            val cardGlassRes = if (option.isHighestQuality) {
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
                onClick(option)
            }
        }

        override fun getItemCount(): Int = options.size
    }
}
