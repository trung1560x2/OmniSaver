package com.omnisaver.videodownloader.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.omnisaver.videodownloader.R
import com.omnisaver.videodownloader.ota.OtaReleaseInfo
import com.omnisaver.videodownloader.ota.OtaUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class OtaUpdateBottomSheetDialog(
    context: Context,
    private val otaUpdateManager: OtaUpdateManager,
    private val initialReleaseInfo: OtaReleaseInfo? = null
) : BottomSheetDialog(context) {

    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var latestRelease: OtaReleaseInfo? = initialReleaseInfo
    private var downloadedApkFile: File? = null
    private var isDownloading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.dialog_ota_update, null)
        setContentView(root)

        LiquidGlassHelper.applyWindowBlurIfSupported(window)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        bindViews(root)
    }

    private fun bindViews(root: View) {
        val btnClose = root.findViewById<ImageButton>(R.id.btnCloseOtaSheet)
        val tvOtaSourceBadge = root.findViewById<TextView>(R.id.tvOtaSourceBadge)
        val tvCurrentVersionInfo = root.findViewById<TextView>(R.id.tvCurrentVersionInfo)
        val progressCheckingOta = root.findViewById<ProgressBar>(R.id.progressCheckingOta)
        val tvOtaStatusHeadline = root.findViewById<TextView>(R.id.tvOtaStatusHeadline)
        val tvOtaNewVersionPill = root.findViewById<TextView>(R.id.tvOtaNewVersionPill)
        val tvOtaChangelog = root.findViewById<TextView>(R.id.tvOtaChangelog)

        val layoutOtaDownloadProgress = root.findViewById<LinearLayout>(R.id.layoutOtaDownloadProgress)
        val tvOtaDownloadStatus = root.findViewById<TextView>(R.id.tvOtaDownloadStatus)
        val tvOtaDownloadPercent = root.findViewById<TextView>(R.id.tvOtaDownloadPercent)
        val progressOtaDownload = root.findViewById<ProgressBar>(R.id.progressOtaDownload)
        val tvOtaDownloadBytes = root.findViewById<TextView>(R.id.tvOtaDownloadBytes)

        val btnOtaPrimaryAction = root.findViewById<MaterialButton>(R.id.btnOtaPrimaryAction)
        val btnOtaInstallNow = root.findViewById<MaterialButton>(R.id.btnOtaInstallNow)

        val editOtaEndpointUrl = root.findViewById<EditText>(R.id.editOtaEndpointUrl)
        val switchAutoCheckOta = root.findViewById<MaterialSwitch>(R.id.switchAutoCheckOta)
        val btnSaveOtaConfig = root.findViewById<MaterialButton>(R.id.btnSaveOtaConfig)
        val btnResetOtaConfig = root.findViewById<MaterialButton>(R.id.btnResetOtaConfig)

        btnClose.setOnClickListener {
            if (!isDownloading) dismiss()
        }

        val currentVersionName = otaUpdateManager.getCurrentVersionName()
        val currentVersionCode = otaUpdateManager.getCurrentVersionCode()
        tvCurrentVersionInfo.text = "Phiên bản đang cài: v$currentVersionName (Build $currentVersionCode)"

        editOtaEndpointUrl.setText(otaUpdateManager.getOtaEndpointUrl())
        switchAutoCheckOta.isChecked = otaUpdateManager.isAutoCheckEnabled()

        switchAutoCheckOta.setOnCheckedChangeListener { _, isChecked ->
            otaUpdateManager.setAutoCheckEnabled(isChecked)
        }

        btnSaveOtaConfig.setOnClickListener {
            val customUrl = editOtaEndpointUrl.text?.toString()?.trim().orEmpty()
            otaUpdateManager.setOtaEndpointUrl(customUrl)
            editOtaEndpointUrl.setText(otaUpdateManager.getOtaEndpointUrl())
            Toast.makeText(context, "Đã lưu nguồn máy chủ OTA!", Toast.LENGTH_SHORT).show()
        }

        btnResetOtaConfig.setOnClickListener {
            otaUpdateManager.setOtaEndpointUrl(OtaUpdateManager.DEFAULT_OTA_ENDPOINT)
            editOtaEndpointUrl.setText(OtaUpdateManager.DEFAULT_OTA_ENDPOINT)
            Toast.makeText(context, "Đã khôi phục nguồn OTA mặc định.", Toast.LENGTH_SHORT).show()
        }

        fun renderReleaseInfo(info: OtaReleaseInfo) {
            latestRelease = info
            tvOtaSourceBadge.text = info.sourceLabel.uppercase(Locale.ROOT)
            val sizeLabel = if (info.fileSizeBytes > 0) {
                " • ${formatBytes(info.fileSizeBytes)}"
            } else {
                ""
            }
            val dateLabel = if (info.publishedAt.isNotBlank()) {
                " (${info.publishedAt})"
            } else {
                ""
            }

            if (info.isNewerThanCurrent) {
                tvOtaStatusHeadline.text = "Phát hiện bản mới: v${info.versionName}$sizeLabel"
                tvOtaStatusHeadline.setTextColor(Color.parseColor("#4ADE80"))
                tvOtaNewVersionPill.text = "CÓ BẢN MỚI"
                tvOtaNewVersionPill.setTextColor(Color.parseColor("#4ADE80"))
                tvOtaChangelog.text = "Nhật ký thay đổi v${info.versionName}$dateLabel:\n${info.changelog}"
                btnOtaInstallNow.visibility = View.VISIBLE
                btnOtaInstallNow.text = "TẢI & CẬP NHẬT NGAY"
            } else {
                tvOtaStatusHeadline.text = "Bạn đang ở phiên bản mới nhất (v${info.versionName})"
                tvOtaStatusHeadline.setTextColor(Color.parseColor("#7DD3FC"))
                tvOtaNewVersionPill.text = "ĐÃ MỚI NHẤT"
                tvOtaNewVersionPill.setTextColor(Color.parseColor("#7DD3FC"))
                tvOtaChangelog.text = "Thông tin bản v${info.versionName}$dateLabel$sizeLabel:\n${info.changelog}"
                btnOtaInstallNow.visibility = View.VISIBLE
                btnOtaInstallNow.text = "TẢI LẠI BẢN NÀY"
            }
        }

        fun triggerOtaCheck() {
            if (isDownloading) return
            val enteredEndpoint = editOtaEndpointUrl.text?.toString()?.trim().orEmpty()
            if (enteredEndpoint.isNotBlank()) {
                otaUpdateManager.setOtaEndpointUrl(enteredEndpoint)
            }

            progressCheckingOta.visibility = View.VISIBLE
            btnOtaPrimaryAction.isEnabled = false
            btnOtaPrimaryAction.text = "ĐANG KIỂM TRA..."
            tvOtaStatusHeadline.text = "Đang kết nối máy chủ OTA..."
            tvOtaStatusHeadline.setTextColor(Color.WHITE)

            dialogScope.launch {
                val result = otaUpdateManager.checkForUpdate(enteredEndpoint.ifBlank { null })
                progressCheckingOta.visibility = View.GONE
                btnOtaPrimaryAction.isEnabled = true
                btnOtaPrimaryAction.text = "KIỂM TRA LẠI"

                result.onSuccess { releaseInfo ->
                    renderReleaseInfo(releaseInfo)
                }.onFailure { err ->
                    tvOtaStatusHeadline.text = "Đang dùng bản mới nhất trên máy (v$currentVersionName)"
                    tvOtaStatusHeadline.setTextColor(Color.parseColor("#F8FAFC"))
                    tvOtaNewVersionPill.text = "SẴN SÀNG"
                    tvOtaNewVersionPill.setTextColor(Color.parseColor("#7DD3FC"))
                    tvOtaChangelog.text = "${err.message ?: "Không thể kết nối máy chủ OTA."}\n\n" +
                        "💡 Mẹo: Bạn có thể dán trực tiếp link GitHub Repo, link file ota-version.json, hoặc link .apk vào ô Nguồn Máy chủ OTA bên dưới rồi bấm 'Kiểm tra lại'."
                }
            }
        }

        btnOtaPrimaryAction.setOnClickListener {
            triggerOtaCheck()
        }

        btnOtaInstallNow.setOnClickListener {
            if (isDownloading) return@setOnClickListener

            // If APK was already downloaded, install immediately
            val existingApk = downloadedApkFile
            if (existingApk != null && existingApk.exists()) {
                val launched = otaUpdateManager.installApk(existingApk)
                if (!launched) {
                    Toast.makeText(
                        context,
                        "Vui lòng bật 'Cho phép từ nguồn này' để cài đặt bản cập nhật OTA!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@setOnClickListener
            }

            val release = latestRelease ?: return@setOnClickListener
            isDownloading = true
            setCancelable(false)
            btnOtaPrimaryAction.isEnabled = false
            btnOtaInstallNow.isEnabled = false
            btnOtaInstallNow.text = "ĐANG TẢI APK..."

            layoutOtaDownloadProgress.visibility = View.VISIBLE
            progressOtaDownload.isIndeterminate = true
            tvOtaDownloadStatus.text = "Đang tải v${release.versionName}..."
            tvOtaDownloadPercent.text = "0%"
            tvOtaDownloadBytes.text = "Đang kết nối máy chủ..."

            dialogScope.launch {
                val downloadResult = otaUpdateManager.downloadUpdateApk(release) { percent, downloaded, total ->
                    if (percent >= 0) {
                        progressOtaDownload.isIndeterminate = false
                        progressOtaDownload.progress = percent
                        tvOtaDownloadPercent.text = "$percent%"
                        tvOtaDownloadBytes.text = "${formatBytes(downloaded)} / ${formatBytes(total)}"
                    } else {
                        progressOtaDownload.isIndeterminate = true
                        tvOtaDownloadPercent.text = "..."
                        tvOtaDownloadBytes.text = "Đã tải ${formatBytes(downloaded)}"
                    }
                }

                isDownloading = false
                setCancelable(true)
                btnOtaPrimaryAction.isEnabled = true
                btnOtaInstallNow.isEnabled = true

                downloadResult.onSuccess { apkFile ->
                    downloadedApkFile = apkFile
                    tvOtaDownloadStatus.text = "✦ Đã tải xong gói cập nhật! Đang mở trình cài đặt..."
                    tvOtaDownloadPercent.text = "100%"
                    progressOtaDownload.isIndeterminate = false
                    progressOtaDownload.progress = 100
                    btnOtaInstallNow.text = "CÀI ĐẶT GÓI APK ĐÃ TẢI"

                    val launched = otaUpdateManager.installApk(apkFile)
                    if (!launched) {
                        Toast.makeText(
                            context,
                            "Hãy bật quyền 'Cài đặt ứng dụng không xác định' cho OmniSaver, ứng dụng sẽ tự động cài đặt ngay!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { err ->
                    tvOtaDownloadStatus.text = "Lỗi tải gói cập nhật: ${err.message}"
                    btnOtaInstallNow.text = "THỬ TẢI LẠI"
                    Toast.makeText(
                        context,
                        "Lỗi tải OTA: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        if (initialReleaseInfo != null) {
            renderReleaseInfo(initialReleaseInfo)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isDownloading) {
            dialogScope.cancel()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
