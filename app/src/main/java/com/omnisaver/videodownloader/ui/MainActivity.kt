package com.omnisaver.videodownloader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.omnisaver.videodownloader.R
import com.omnisaver.videodownloader.download.MediaStorageRepository
import com.omnisaver.videodownloader.download.OmniDownloadManager
import com.omnisaver.videodownloader.extractor.MultiPlatformExtractor
import com.omnisaver.videodownloader.model.DownloadProgressState
import com.omnisaver.videodownloader.model.DownloadedFileItem
import com.omnisaver.videodownloader.model.ExtractedMediaInfo
import com.omnisaver.videodownloader.model.MediaOption
import com.omnisaver.videodownloader.model.SocialPlatform
import com.omnisaver.videodownloader.model.UrlSource
import com.omnisaver.videodownloader.ota.OtaReleaseInfo
import com.omnisaver.videodownloader.ota.OtaUpdateManager
import com.omnisaver.videodownloader.util.AuthCookieStore
import com.omnisaver.videodownloader.util.UrlDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var editUrl: EditText
    private lateinit var tvDetectedPlatform: TextView
    private lateinit var btnPaste: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnExtract: MaterialButton
    private lateinit var tvAppVersionSubtitle: TextView
    private lateinit var btnOtaUpdate: MaterialButton
    private lateinit var btnOpenLibrary: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var switchAutoClipboard: MaterialSwitch

    private lateinit var cardOtaBanner: MaterialCardView
    private lateinit var tvOtaBannerTitle: TextView
    private lateinit var tvOtaBannerSub: TextView
    private lateinit var btnOtaBannerAction: MaterialButton

    private lateinit var tvLoginSummary: TextView
    private lateinit var btnOpenPrivateBrowser: MaterialButton
    private lateinit var btnManageCookies: MaterialButton

    private lateinit var badgeFacebook: TextView
    private lateinit var badgeX: TextView
    private lateinit var badgeInstagram: TextView
    private lateinit var badgeTikTok: TextView
    private lateinit var badgeThreads: TextView

    private lateinit var cardExtracting: MaterialCardView
    private lateinit var tvExtractingStatus: TextView

    private lateinit var cardDownloadProgress: MaterialCardView
    private lateinit var tvDownloadingFileName: TextView
    private lateinit var tvDownloadPercent: TextView
    private lateinit var progressBarDownload: ProgressBar
    private lateinit var tvDownloadBytes: TextView

    private lateinit var cardCompleted: MaterialCardView
    private lateinit var tvCompletedPlatform: TextView
    private lateinit var tvCompletedTitle: TextView
    private lateinit var tvCompletedPath: TextView
    private lateinit var btnPlayCompleted: MaterialButton
    private lateinit var btnShareCompleted: MaterialButton

    private lateinit var extractor: MultiPlatformExtractor
    private lateinit var omniDownloadManager: OmniDownloadManager
    private lateinit var mediaStorageRepository: MediaStorageRepository
    private lateinit var authCookieStore: AuthCookieStore
    private lateinit var otaUpdateManager: OtaUpdateManager

    private var discoveredOtaRelease: OtaReleaseInfo? = null
    private var lastClipboardUrl: String? = null
    private var lastExtractedInfo: ExtractedMediaInfo? = null
    private var pendingDownloadPair: Pair<ExtractedMediaInfo, MediaOption>? = null
    private var activeProgressJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        pendingDownloadPair?.let { (info, option) ->
            pendingDownloadPair = null
            startDownloadNow(info, option)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        extractor = MultiPlatformExtractor(this)
        omniDownloadManager = OmniDownloadManager(this)
        mediaStorageRepository = MediaStorageRepository(this)
        authCookieStore = AuthCookieStore(this)
        otaUpdateManager = OtaUpdateManager(this)
        mediaStorageRepository.getRootDirectory()

        bindViews()
        setupListeners()
        refreshLoginStatusSummary()
        handleIncomingShareIntent(intent)
        checkOtaOnStartupIfEnabled()
    }

    override fun onResume() {
        super.onResume()
        refreshLoginStatusSummary()
        otaUpdateManager.resumePendingInstallIfPermitted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && switchAutoClipboard.isChecked) {
            checkClipboardAutomatically()
        }
    }

    private fun bindViews() {
        editUrl = findViewById(R.id.editUrl)
        tvDetectedPlatform = findViewById(R.id.tvDetectedPlatform)
        btnPaste = findViewById(R.id.btnPaste)
        btnClear = findViewById(R.id.btnClear)
        btnExtract = findViewById(R.id.btnExtract)
        tvAppVersionSubtitle = findViewById(R.id.tvAppVersionSubtitle)
        btnOtaUpdate = findViewById(R.id.btnOtaUpdate)
        btnOpenLibrary = findViewById(R.id.btnOpenLibrary)
        btnSettings = findViewById(R.id.btnSettings)
        switchAutoClipboard = findViewById(R.id.switchAutoClipboard)

        cardOtaBanner = findViewById(R.id.cardOtaBanner)
        tvOtaBannerTitle = findViewById(R.id.tvOtaBannerTitle)
        tvOtaBannerSub = findViewById(R.id.tvOtaBannerSub)
        btnOtaBannerAction = findViewById(R.id.btnOtaBannerAction)

        tvLoginSummary = findViewById(R.id.tvLoginSummary)
        btnOpenPrivateBrowser = findViewById(R.id.btnOpenPrivateBrowser)
        btnManageCookies = findViewById(R.id.btnManageCookies)

        badgeFacebook = findViewById(R.id.badgeFacebook)
        badgeX = findViewById(R.id.badgeX)
        badgeInstagram = findViewById(R.id.badgeInstagram)
        badgeTikTok = findViewById(R.id.badgeTikTok)
        badgeThreads = findViewById(R.id.badgeThreads)

        cardExtracting = findViewById(R.id.cardExtracting)
        tvExtractingStatus = findViewById(R.id.tvExtractingStatus)

        cardDownloadProgress = findViewById(R.id.cardDownloadProgress)
        tvDownloadingFileName = findViewById(R.id.tvDownloadingFileName)
        tvDownloadPercent = findViewById(R.id.tvDownloadPercent)
        progressBarDownload = findViewById(R.id.progressBarDownload)
        tvDownloadBytes = findViewById(R.id.tvDownloadBytes)

        cardCompleted = findViewById(R.id.cardCompleted)
        tvCompletedPlatform = findViewById(R.id.tvCompletedPlatform)
        tvCompletedTitle = findViewById(R.id.tvCompletedTitle)
        tvCompletedPath = findViewById(R.id.tvCompletedPath)
        btnPlayCompleted = findViewById(R.id.btnPlayCompleted)
        btnShareCompleted = findViewById(R.id.btnShareCompleted)

        tvAppVersionSubtitle.text = "v${otaUpdateManager.getCurrentVersionName()} • 5 Nền tảng"

        val prefs = getSharedPreferences("omni_prefs", Context.MODE_PRIVATE)
        switchAutoClipboard.isChecked = prefs.getBoolean("auto_clipboard", true)
    }

    private fun setupListeners() {
        editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePlatformBadge(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        switchAutoClipboard.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("omni_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_clipboard", isChecked)
                .apply()
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clipText.isNullOrBlank()) {
                Toast.makeText(this, "Bộ nhớ tạm đang trống", Toast.LENGTH_SHORT).show()
            } else {
                val extractedUrl = UrlDetector.extractFirstUrl(clipText) ?: clipText.trim()
                editUrl.setText(extractedUrl)
                editUrl.setSelection(editUrl.text.length)
                triggerExtraction(extractedUrl, UrlSource.MANUAL)
            }
        }

        btnClear.setOnClickListener {
            editUrl.setText("")
            lastExtractedInfo = null
            cardCompleted.visibility = View.GONE
        }

        btnExtract.setOnClickListener {
            val input = editUrl.text?.toString()?.trim().orEmpty()
            if (input.isBlank()) {
                Toast.makeText(this, "Vui lòng dán đường dẫn Facebook, X, Instagram, TikTok hoặc Threads!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            triggerExtraction(input, UrlSource.MANUAL)
        }

        btnOtaUpdate.setOnClickListener {
            openOtaUpdateCenter()
        }

        cardOtaBanner.setOnClickListener {
            openOtaUpdateCenter()
        }

        btnOtaBannerAction.setOnClickListener {
            openOtaUpdateCenter()
        }

        btnOpenLibrary.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        btnSettings.setOnClickListener {
            showApiSettingsDialog()
        }

        btnOpenPrivateBrowser.setOnClickListener {
            val currentInputUrl = UrlDetector.extractFirstUrl(editUrl.text?.toString())
            val platform = if (currentInputUrl != null) {
                UrlDetector.detectPlatform(currentInputUrl)
            } else {
                SocialPlatform.FACEBOOK
            }
            openPrivateBrowser(currentInputUrl, platform)
        }

        btnManageCookies.setOnClickListener {
            showManualCookieDialog()
        }

        badgeFacebook.setOnClickListener { openPrivateBrowser(null, SocialPlatform.FACEBOOK) }
        badgeX.setOnClickListener { openPrivateBrowser(null, SocialPlatform.X_TWITTER) }
        badgeInstagram.setOnClickListener { openPrivateBrowser(null, SocialPlatform.INSTAGRAM) }
        badgeTikTok.setOnClickListener { openPrivateBrowser(null, SocialPlatform.TIKTOK) }
        badgeThreads.setOnClickListener { openPrivateBrowser(null, SocialPlatform.THREADS) }
    }

    private fun refreshLoginStatusSummary() {
        val platforms = listOf(
            SocialPlatform.FACEBOOK,
            SocialPlatform.INSTAGRAM,
            SocialPlatform.X_TWITTER,
            SocialPlatform.TIKTOK,
            SocialPlatform.THREADS
        )
        val badgeMap = mapOf(
            SocialPlatform.FACEBOOK to badgeFacebook,
            SocialPlatform.X_TWITTER to badgeX,
            SocialPlatform.INSTAGRAM to badgeInstagram,
            SocialPlatform.TIKTOK to badgeTikTok,
            SocialPlatform.THREADS to badgeThreads
        )
        badgeMap.forEach { (platform, view) ->
            val loggedIn = authCookieStore.isLoggedIn(platform)
            val baseLabel = if (platform == SocialPlatform.TIKTOK) "TikTok (No Logo)" else platform.displayName
            view.text = if (loggedIn) "$baseLabel ✓" else baseLabel
            view.background = LiquidGlassHelper.createPlatformGlassPill(
                platform.badgeColorHex,
                fillAlpha = if (loggedIn) 145 else 95
            )
        }

        val loggedInList = platforms.filter { authCookieStore.isLoggedIn(it) }
        if (loggedInList.isEmpty()) {
            tvLoginSummary.text = "Phiên đăng nhập: Chưa có Cookie (Chỉ tải bài Công khai). Bấm 'Mở Trình Duyệt' hoặc 'Dán Cookie' để tải video Bạn bè / Nhóm kín / Story."
            tvLoginSummary.setTextColor(Color.parseColor("#CBD5E1"))
        } else {
            val names = loggedInList.joinToString(", ") { "${it.displayName} ✓" }
            tvLoginSummary.text = "Đã kích hoạt Cookie Riêng Tư: $names (Hỗ trợ tải Video Bạn bè, Nhóm kín, Story & 18+)"
            tvLoginSummary.setTextColor(Color.parseColor("#4ADE80"))
        }
    }

    private fun openPrivateBrowser(initialUrl: String?, platform: SocialPlatform) {
        val intent = Intent(this, PrivateBrowserActivity::class.java).apply {
            if (!initialUrl.isNullOrBlank()) {
                putExtra(PrivateBrowserActivity.EXTRA_INITIAL_URL, initialUrl)
            }
            putExtra(PrivateBrowserActivity.EXTRA_PLATFORM, platform.name)
        }
        startActivity(intent)
    }

    private fun updatePlatformBadge(rawText: String) {
        val url = UrlDetector.extractFirstUrl(rawText)
        if (url == null) {
            tvDetectedPlatform.text = "Tự động nhận diện"
            tvDetectedPlatform.background = LiquidGlassHelper.createPlatformGlassPill("#334155", fillAlpha = 85)
            return
        }
        val platform = UrlDetector.detectPlatform(url)
        val hasCookie = authCookieStore.isLoggedIn(platform)
        tvDetectedPlatform.text = if (hasCookie) "${platform.displayName} • Có Cookie ✓" else platform.displayName
        tvDetectedPlatform.background = LiquidGlassHelper.createPlatformGlassPill(platform.badgeColorHex, fillAlpha = 140)
    }

    private fun handleIncomingShareIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action == Intent.ACTION_SEND && incomingIntent.type == "text/plain") {
            val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
            val url = UrlDetector.extractFirstUrl(sharedText)
            if (!url.isNullOrBlank()) {
                incomingIntent.removeExtra(Intent.EXTRA_TEXT)
                editUrl.setText(url)
                editUrl.setSelection(editUrl.text.length)
                triggerExtraction(url, UrlSource.INTENT)
            }
        }
    }

    private fun checkClipboardAutomatically() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val url = UrlDetector.extractFirstUrl(clipText) ?: return
        if (!UrlDetector.isSupportedUrl(url)) return

        val cleaned = UrlDetector.cleanUrl(url)
        if (cleaned == lastClipboardUrl) return
        lastClipboardUrl = cleaned

        if (editUrl.text.isNullOrBlank() || editUrl.text.toString() != cleaned) {
            editUrl.setText(cleaned)
            editUrl.setSelection(editUrl.text.length)
            Toast.makeText(
                this,
                "Đã tự động nhận link ${UrlDetector.detectPlatform(cleaned).displayName} từ Clipboard",
                Toast.LENGTH_SHORT
            ).show()
            triggerExtraction(cleaned, UrlSource.CLIPBOARD)
        }
    }

    private fun triggerExtraction(rawInput: String, source: UrlSource) {
        val extractedUrl = UrlDetector.extractFirstUrl(rawInput)
        if (extractedUrl.isNullOrBlank()) {
            if (source != UrlSource.CLIPBOARD) {
                Toast.makeText(this, "Không tìm thấy đường dẫn hợp lệ!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val cleanUrl = UrlDetector.cleanUrl(extractedUrl)
        val cached = lastExtractedInfo
        if (cached != null && cached.cleanUrl == cleanUrl && cached.options.isNotEmpty()) {
            showQualityBottomSheet(cached)
            return
        }

        val platform = UrlDetector.detectPlatform(cleanUrl)
        val usingCookie = authCookieStore.isLoggedIn(platform)
        cardExtracting.visibility = View.VISIBLE
        tvExtractingStatus.text = if (usingCookie) {
            "Đang bóc tách luồng media từ ${platform.displayName} (Kèm Cookie xác thực)..."
        } else {
            "Đang bóc tách luồng media từ ${platform.displayName}..."
        }
        btnExtract.isEnabled = false

        lifecycleScope.launch {
            val result = extractor.extract(cleanUrl)
            cardExtracting.visibility = View.GONE
            btnExtract.isEnabled = true

            result.onSuccess { mediaInfo ->
                lastExtractedInfo = mediaInfo
                showQualityBottomSheet(mediaInfo)
            }.onFailure { error ->
                showPrivateFallbackPrompt(cleanUrl, platform, error.message)
            }
        }
    }

    private fun showPrivateFallbackPrompt(cleanUrl: String, platform: SocialPlatform, errorMessage: String?) {
        AlertDialog.Builder(this)
            .setTitle("Không lấy được bằng chế độ Công khai")
            .setMessage(
                "${errorMessage ?: "Không thể bóc tách trực tiếp."}\n\n" +
                    "Nếu đây là Video Riêng Tư (Bạn bè, Nhóm kín Facebook, Story, tài khoản Private hoặc X 18+), " +
                    "bạn có muốn mở trực tiếp trong Trình duyệt Nội bộ để đăng nhập và bắt luồng Video ngay không?"
            )
            .setPositiveButton("Mở Trình Duyệt & Bắt Video") { _, _ ->
                openPrivateBrowser(cleanUrl, platform)
            }
            .setNeutralButton("Dán Cookie", { _, _ ->
                showManualCookieDialog(platform)
            })
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showQualityBottomSheet(mediaInfo: ExtractedMediaInfo) {
        val dialog = QualityBottomSheetDialog(this, mediaInfo) { selectedOption ->
            requestPermissionsAndDownload(mediaInfo, selectedOption)
        }
        dialog.show()
    }

    private fun requestPermissionsAndDownload(mediaInfo: ExtractedMediaInfo, option: MediaOption) {
        val neededPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            pendingDownloadPair = mediaInfo to option
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            startDownloadNow(mediaInfo, option)
        }
    }

    private fun startDownloadNow(mediaInfo: ExtractedMediaInfo, option: MediaOption) {
        val fileName = omniDownloadManager.buildFileName(mediaInfo, option)
        val targetFile = omniDownloadManager.getTargetFile(mediaInfo, fileName)
        if (targetFile.exists() && targetFile.length() > 0L) {
            Toast.makeText(this, "Tệp đã có sẵn trong thư viện!", Toast.LENGTH_SHORT).show()
            showCompletedCard(targetFile, mediaInfo, option)
            return
        }

        val downloadId = try {
            omniDownloadManager.enqueueDownload(mediaInfo, option, fileName)
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khởi tạo tải xuống: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        cardCompleted.visibility = View.GONE
        cardDownloadProgress.visibility = View.VISIBLE
        tvDownloadingFileName.text = fileName
        tvDownloadPercent.text = "0%"
        progressBarDownload.progress = 0
        tvDownloadBytes.text = "Đang tải từ ${mediaInfo.platform.displayName}..."

        activeProgressJob?.cancel()
        activeProgressJob = lifecycleScope.launch {
            omniDownloadManager.observeDownloadProgress(downloadId, mediaInfo, option, fileName)
                .collectLatest { state ->
                    when (state) {
                        is DownloadProgressState.Idle -> Unit
                        is DownloadProgressState.Running -> {
                            cardDownloadProgress.visibility = View.VISIBLE
                            tvDownloadingFileName.text = state.fileName
                            tvDownloadPercent.text = "${state.progressPercent}%"
                            progressBarDownload.progress = state.progressPercent
                            val downloadedStr = mediaStorageRepository.formatFileSize(state.bytesDownloaded)
                            val totalStr = if (state.totalBytes > 0) {
                                mediaStorageRepository.formatFileSize(state.totalBytes)
                            } else {
                                "..."
                            }
                            tvDownloadBytes.text = "$downloadedStr / $totalStr"
                        }
                        is DownloadProgressState.Completed -> {
                            cardDownloadProgress.visibility = View.GONE
                            showCompletedCard(File(state.filePath), state.mediaInfo, state.option)
                            Toast.makeText(this@MainActivity, "Đã tải xong: ${state.fileName}", Toast.LENGTH_SHORT).show()
                        }
                        is DownloadProgressState.Failed -> {
                            cardDownloadProgress.visibility = View.GONE
                            Toast.makeText(this@MainActivity, state.reason, Toast.LENGTH_LONG).show()
                        }
                    }
                }
        }
    }

    private fun showCompletedCard(file: File, mediaInfo: ExtractedMediaInfo, option: MediaOption) {
        cardCompleted.visibility = View.VISIBLE
        tvCompletedPlatform.text = mediaInfo.platform.displayName
        tvCompletedPlatform.background = LiquidGlassHelper.createPlatformGlassPill(mediaInfo.platform.badgeColorHex, fillAlpha = 135)
        tvCompletedTitle.text = file.name
        tvCompletedPath.text = file.absolutePath

        val item = DownloadedFileItem(
            filePath = file.absolutePath,
            fileName = file.name,
            platform = mediaInfo.platform,
            mediaType = option.mediaType,
            sizeBytes = file.length(),
            lastModified = file.lastModified()
        )

        btnPlayCompleted.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_FILE_PATH, item.filePath)
                putExtra(PlayerActivity.EXTRA_FILE_NAME, item.fileName)
                putExtra(PlayerActivity.EXTRA_MEDIA_TYPE, item.mediaType.name)
                putExtra(PlayerActivity.EXTRA_PLATFORM, item.platform.displayName)
            }
            startActivity(intent)
        }

        btnShareCompleted.setOnClickListener {
            mediaStorageRepository.shareFile(this, item)
        }
    }

    private fun showManualCookieDialog(defaultPlatform: SocialPlatform = SocialPlatform.FACEBOOK) {
        val platforms = arrayOf(
            SocialPlatform.FACEBOOK,
            SocialPlatform.INSTAGRAM,
            SocialPlatform.X_TWITTER,
            SocialPlatform.TIKTOK,
            SocialPlatform.THREADS
        )
        var selectedIndex = platforms.indexOf(defaultPlatform).coerceAtLeast(0)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val guide = TextView(this).apply {
            text = "Chọn nền tảng và dán chuỗi Cookie (ví dụ: 'c_user=...; xs=...' cho Facebook, 'sessionid=...' cho Instagram/Threads, hoặc 'auth_token=...; ct0=...' cho X):"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
        }

        val inputCookie = EditText(this).apply {
            hint = "c_user=...; xs=... / sessionid=... / auth_token=..."
            setText(authCookieStore.getManualCookie(platforms[selectedIndex]))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 3
            maxLines = 6
        }

        container.addView(guide)
        container.addView(inputCookie)

        val labels = platforms.map { p ->
            if (authCookieStore.isLoggedIn(p)) "${p.displayName} (Đã có Cookie ✓)" else p.displayName
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Quản lý Cookie Tải Video Riêng Tư")
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
                inputCookie.setText(authCookieStore.getManualCookie(platforms[selectedIndex]))
            }
            .setView(container)
            .setPositiveButton("Lưu Cookie") { _, _ ->
                val chosen = platforms[selectedIndex]
                authCookieStore.setManualCookie(chosen, inputCookie.text?.toString().orEmpty())
                lastExtractedInfo = null
                refreshLoginStatusSummary()
                updatePlatformBadge(editUrl.text?.toString().orEmpty())
                Toast.makeText(this, "Đã lưu Cookie cho ${chosen.displayName}!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Xóa tất cả Cookie") { _, _ ->
                authCookieStore.clearAllCookies()
                lastExtractedInfo = null
                refreshLoginStatusSummary()
                updatePlatformBadge(editUrl.text?.toString().orEmpty())
                Toast.makeText(this, "Đã xóa toàn bộ phiên đăng nhập & Cookie!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showApiSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val infoText = TextView(this).apply {
            text = "OmniSaver ưu tiên bóc tách trực tiếp trên thiết bị kèm Cookie cá nhân (miễn phí 100%, không giới hạn). Khi cần dự phòng, ứng dụng sẽ chuyển sang RapidAPI All-in-One Autolink.\n\nBạn có thể giữ nguyên khóa mặc định hoặc nhập RapidAPI Key cá nhân:"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
        }

        val inputKey = EditText(this).apply {
            hint = "Để trống để dùng khóa mặc định tích hợp sẵn"
            setText(extractor.getCustomApiKey())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        container.addView(infoText)
        container.addView(inputKey)

        AlertDialog.Builder(this)
            .setTitle("Cấu hình lõi bóc tách (Extractor)")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                extractor.setCustomApiKey(inputKey.text?.toString()?.trim().orEmpty())
                Toast.makeText(this, "Đã lưu cấu hình!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }


    private fun openOtaUpdateCenter(releaseInfo: OtaReleaseInfo? = discoveredOtaRelease) {
        OtaUpdateBottomSheetDialog(this, otaUpdateManager, releaseInfo).show()
    }

    private fun checkOtaOnStartupIfEnabled() {
        if (!otaUpdateManager.isAutoCheckEnabled()) return
        lifecycleScope.launch {
            val result = otaUpdateManager.checkForUpdate()
            result.onSuccess { info ->
                discoveredOtaRelease = info
                if (info.isNewerThanCurrent) {
                    cardOtaBanner.visibility = View.VISIBLE
                    tvOtaBannerTitle.text = "✦ Có bản cập nhật OTA mới: v${info.versionName}"
                    tvOtaBannerSub.text = info.changelog.lines().firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: "Chạm để tải và cài đặt trực tiếp"
                    btnOtaUpdate.text = "OTA • MỚI"
                    if (info.forceUpdate) {
                        openOtaUpdateCenter(info)
                    }
                }
            }
        }
    }
}
