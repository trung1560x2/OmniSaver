package com.omnisaver.videodownloader.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.omnisaver.videodownloader.R
import com.omnisaver.videodownloader.download.OmniDownloadManager
import com.omnisaver.videodownloader.extractor.MultiPlatformExtractor
import com.omnisaver.videodownloader.model.DownloadProgressState
import com.omnisaver.videodownloader.model.ExtractedMediaInfo
import com.omnisaver.videodownloader.model.MediaOption
import com.omnisaver.videodownloader.model.MediaType
import com.omnisaver.videodownloader.model.SocialPlatform
import com.omnisaver.videodownloader.util.AuthCookieStore
import com.omnisaver.videodownloader.util.UrlDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

class PrivateBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_URL = "extra_initial_url"
        const val EXTRA_PLATFORM = "extra_platform"

        private const val BROWSER_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var btnBack: MaterialButton
    private lateinit var btnCookieManager: MaterialButton
    private lateinit var chipFacebook: MaterialButton
    private lateinit var chipInstagram: MaterialButton
    private lateinit var chipX: MaterialButton
    private lateinit var chipTikTok: MaterialButton
    private lateinit var chipThreads: MaterialButton
    private lateinit var editBrowserUrl: EditText
    private lateinit var btnGoUrl: MaterialButton
    private lateinit var btnReload: MaterialButton
    private lateinit var progressWebView: ProgressBar
    private lateinit var webView: WebView
    private lateinit var cardSnifferBar: MaterialCardView
    private lateinit var tvSniffedCount: TextView
    private lateinit var btnDownloadFromPage: MaterialButton

    private lateinit var authCookieStore: AuthCookieStore
    private lateinit var extractor: MultiPlatformExtractor
    private lateinit var omniDownloadManager: OmniDownloadManager

    val sniffedVideoUrls: CopyOnWriteArraySet<String> = CopyOnWriteArraySet()
    private var activePlatform: SocialPlatform = SocialPlatform.FACEBOOK
    private var isAnalyzingPage: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_private_browser)
        supportActionBar?.hide()
        window.setBackgroundDrawableResource(R.drawable.bg_liquid_aurora_canvas)

        authCookieStore = AuthCookieStore(this)
        extractor = MultiPlatformExtractor(this)
        omniDownloadManager = OmniDownloadManager(this)

        bindViews()
        setupWebView()
        setupListeners()
        setupBackNavigation()

        val platformName = intent.getStringExtra(EXTRA_PLATFORM)
        activePlatform = platformName?.let { name ->
            runCatching { SocialPlatform.valueOf(name) }.getOrNull()
        }?.takeIf { it != SocialPlatform.UNKNOWN } ?: SocialPlatform.FACEBOOK

        val initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL)?.trim().orEmpty()
        if (initialUrl.isNotEmpty()) {
            val detected = UrlDetector.detectPlatform(initialUrl)
            if (detected != SocialPlatform.UNKNOWN) {
                activePlatform = detected
            }
            syncManualCookiesToWebView(activePlatform)
            updatePlatformChips()
            loadTargetUrl(initialUrl)
        } else {
            syncManualCookiesToWebView(activePlatform)
            updatePlatformChips()
            val startUrl = getPlatformStartUrl(activePlatform)
            loadTargetUrl(startUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePlatformChips()
    }

    override fun onPause() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnCookieManager = findViewById(R.id.btnCookieManager)
        chipFacebook = findViewById(R.id.chipFacebook)
        chipInstagram = findViewById(R.id.chipInstagram)
        chipX = findViewById(R.id.chipX)
        chipTikTok = findViewById(R.id.chipTikTok)
        chipThreads = findViewById(R.id.chipThreads)
        editBrowserUrl = findViewById(R.id.editBrowserUrl)
        btnGoUrl = findViewById(R.id.btnGoUrl)
        btnReload = findViewById(R.id.btnReload)
        progressWebView = findViewById(R.id.progressWebView)
        webView = findViewById(R.id.webView)
        cardSnifferBar = findViewById(R.id.cardSnifferBar)
        tvSniffedCount = findViewById(R.id.tvSniffedCount)
        btnDownloadFromPage = findViewById(R.id.btnDownloadFromPage)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = BROWSER_MOBILE_USER_AGENT
        }

        webView.addJavascriptInterface(SnifferJsBridge(), "OmniSniffer")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress in 1..99) {
                    progressWebView.visibility = View.VISIBLE
                    progressWebView.progress = newProgress
                } else {
                    progressWebView.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                // Block external custom scheme redirects (fb://, instagram://, snssdk://, intent://)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) {
                    editBrowserUrl.setText(url)
                    val detected = UrlDetector.detectPlatform(url)
                    if (detected != SocialPlatform.UNKNOWN) {
                        activePlatform = detected
                    }
                }
                updateSnifferUi()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Exception) {
                }
                if (!url.isNullOrBlank()) {
                    editBrowserUrl.setText(url)
                }
                updatePlatformChips()
                injectJsVideoSniffer()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (looksLikeDirectMediaStream(reqUrl)) {
                    val cleanedUrl = normalizeSniffedMediaUrl(reqUrl)
                    if (cleanedUrl.isNotBlank() && sniffedVideoUrls.add(cleanedUrl)) {
                        runOnUiThread {
                            updateSnifferUi()
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }
            finish()
        }

        btnCookieManager.setOnClickListener {
            showCookieManagerDialog()
        }

        chipFacebook.setOnClickListener { switchPlatform(SocialPlatform.FACEBOOK) }
        chipInstagram.setOnClickListener { switchPlatform(SocialPlatform.INSTAGRAM) }
        chipX.setOnClickListener { switchPlatform(SocialPlatform.X_TWITTER) }
        chipTikTok.setOnClickListener { switchPlatform(SocialPlatform.TIKTOK) }
        chipThreads.setOnClickListener { switchPlatform(SocialPlatform.THREADS) }

        btnGoUrl.setOnClickListener {
            navigateFromAddressBar()
        }

        editBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigateFromAddressBar()
                true
            } else {
                false
            }
        }

        btnReload.setOnClickListener {
            sniffedVideoUrls.clear()
            updateSnifferUi()
            webView.reload()
        }

        btnDownloadFromPage.setOnClickListener {
            if (!isAnalyzingPage) {
                extractAndDownloadFromCurrentPage()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {
                    }
                    finish()
                }
            }
        })
    }

    private fun switchPlatform(platform: SocialPlatform) {
        activePlatform = platform
        sniffedVideoUrls.clear()
        updateSnifferUi()
        syncManualCookiesToWebView(platform)
        updatePlatformChips()
        loadTargetUrl(getPlatformStartUrl(platform))
    }

    private fun getPlatformStartUrl(platform: SocialPlatform): String {
        val loggedIn = authCookieStore.isLoggedIn(platform)
        return when (platform) {
            SocialPlatform.FACEBOOK -> "https://m.facebook.com/"
            SocialPlatform.INSTAGRAM -> if (loggedIn) "https://www.instagram.com/" else authCookieStore.getDefaultLoginUrl(platform)
            SocialPlatform.X_TWITTER -> if (loggedIn) "https://x.com/home" else authCookieStore.getDefaultLoginUrl(platform)
            SocialPlatform.TIKTOK -> if (loggedIn) "https://www.tiktok.com/" else authCookieStore.getDefaultLoginUrl(platform)
            SocialPlatform.THREADS -> if (loggedIn) "https://www.threads.net/" else authCookieStore.getDefaultLoginUrl(platform)
            SocialPlatform.UNKNOWN -> "https://m.facebook.com/"
        }
    }

    private fun navigateFromAddressBar() {
        val rawText = editBrowserUrl.text?.toString()?.trim().orEmpty()
        if (rawText.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập hoặc dán đường dẫn URL", Toast.LENGTH_SHORT).show()
            return
        }
        val targetUrl = UrlDetector.extractFirstUrl(rawText)
            ?: if (rawText.startsWith("http://") || rawText.startsWith("https://")) {
                rawText
            } else {
                "https://$rawText"
            }

        val detected = UrlDetector.detectPlatform(targetUrl)
        if (detected != SocialPlatform.UNKNOWN) {
            activePlatform = detected
            syncManualCookiesToWebView(detected)
            updatePlatformChips()
        }

        sniffedVideoUrls.clear()
        updateSnifferUi()
        loadTargetUrl(targetUrl)
    }

    private fun loadTargetUrl(url: String) {
        editBrowserUrl.setText(url)
        webView.loadUrl(url)
    }

    private fun syncManualCookiesToWebView(platform: SocialPlatform) {
        val manualCookie = authCookieStore.getManualCookie(platform)
        if (manualCookie.isBlank()) return
        val domainUrl = when (platform) {
            SocialPlatform.FACEBOOK -> "https://.facebook.com"
            SocialPlatform.INSTAGRAM -> "https://.instagram.com"
            SocialPlatform.X_TWITTER -> "https://.x.com"
            SocialPlatform.TIKTOK -> "https://.tiktok.com"
            SocialPlatform.THREADS -> "https://.threads.net"
            SocialPlatform.UNKNOWN -> return
        }
        try {
            val cm = CookieManager.getInstance()
            manualCookie.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.contains("=")) {
                    cm.setCookie(domainUrl, "$trimmed; Domain=${domainUrl.removePrefix("https://")}; Path=/; Secure")
                }
            }
            cm.flush()
        } catch (_: Exception) {
        }
    }

    private fun updatePlatformChips() {
        val chips = listOf(
            Triple(chipFacebook, SocialPlatform.FACEBOOK, "Facebook"),
            Triple(chipInstagram, SocialPlatform.INSTAGRAM, "Instagram"),
            Triple(chipX, SocialPlatform.X_TWITTER, "X / Twitter"),
            Triple(chipTikTok, SocialPlatform.TIKTOK, "TikTok"),
            Triple(chipThreads, SocialPlatform.THREADS, "Threads")
        )

        for ((button, platform, label) in chips) {
            val loggedIn = authCookieStore.isLoggedIn(platform)
            button.text = if (loggedIn) "$label ✓" else label
            val isSelected = activePlatform == platform
            button.backgroundTintList = null
            button.background = LiquidGlassHelper.createPlatformGlassPill(
                platform.badgeColorHex,
                fillAlpha = if (isSelected) 165 else 75
            )
            button.alpha = if (isSelected) 1.0f else 0.86f
        }
    }

    private fun updateSnifferUi() {
        val count = sniffedVideoUrls.size
        if (isAnalyzingPage) {
            tvSniffedCount.text = "Đang quét HTML, Cookie và luồng Video chất lượng cao..."
            btnDownloadFromPage.text = "ĐANG PHÂN TÍCH TRANG..."
            btnDownloadFromPage.isEnabled = false
            return
        }

        btnDownloadFromPage.isEnabled = true
        btnDownloadFromPage.backgroundTintList = null
        btnDownloadFromPage.setTextColor(Color.WHITE)
        if (count > 0) {
            tvSniffedCount.text = "✦ Đã bắt được $count luồng Video trên trang"
            tvSniffedCount.setTextColor(Color.parseColor("#6EE7B7"))
            btnDownloadFromPage.text = "TẢI VIDEO TRÊN TRANG NÀY ($count)"
            btnDownloadFromPage.setBackgroundResource(R.drawable.bg_liquid_button_emerald)
        } else {
            tvSniffedCount.text = "Mở bài đăng/Story và bấm Play để bắt video"
            tvSniffedCount.setTextColor(Color.parseColor("#E2E8F0"))
            btnDownloadFromPage.text = "TẢI VIDEO TRÊN TRANG NÀY (0)"
            btnDownloadFromPage.setBackgroundResource(R.drawable.bg_liquid_button_primary)
        }
    }

    fun looksLikeDirectMediaStream(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.startsWith("blob:") || lower.startsWith("data:")) return false

        val pathPart = lower.substringBefore('?')
        if (pathPart.endsWith(".jpg") ||
            pathPart.endsWith(".jpeg") ||
            pathPart.endsWith(".png") ||
            pathPart.endsWith(".webp") ||
            pathPart.endsWith(".gif") ||
            pathPart.endsWith(".svg") ||
            pathPart.endsWith(".css") ||
            pathPart.endsWith(".js") ||
            pathPart.endsWith(".woff") ||
            pathPart.endsWith(".woff2") ||
            pathPart.endsWith(".ttf") ||
            pathPart.endsWith(".ico") ||
            pathPart.endsWith(".m3u8") ||
            pathPart.endsWith(".mpd")
        ) {
            return false
        }

        // Ignore tiny audio-only / dash init segments if possible, or check known patterns
        if (pathPart.endsWith(".mp4") || pathPart.endsWith(".m4a") || pathPart.endsWith(".mp3") || pathPart.endsWith(".mov")) {
            return true
        }

        if (lower.contains("video.twimg.com/ext_tw_video") ||
            lower.contains("video.twimg.com/amplify_video")
        ) {
            return pathPart.endsWith(".mp4") || lower.contains(".mp4")
        }

        if (lower.contains("fbcdn.net") && (lower.contains(".mp4") || lower.contains("bytestart="))) {
            return true
        }

        if (lower.contains("cdninstagram.com") && (lower.contains(".mp4") || lower.contains("bytestart="))) {
            return true
        }

        if ((lower.contains("tiktokcdn") || lower.contains("tiktokv.com") || lower.contains("muscdn.com")) &&
            (lower.contains("video_mp4") || lower.contains("mime_type=video") || lower.contains("/video/tos/"))
        ) {
            return true
        }

        return false
    }

    fun normalizeSniffedMediaUrl(rawUrl: String): String {
        var cleaned = rawUrl.trim()
            .replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val lower = cleaned.lowercase(Locale.ROOT)
        if (lower.contains("fbcdn.net") || lower.contains("cdninstagram.com")) {
            cleaned = cleaned
                .replace(Regex("""([?&])bytestart=\d+&?"""), "$1")
                .replace(Regex("""([?&])byteend=\d+&?"""), "$1")
                .replace("?&", "?")
                .trimEnd('?', '&')
        }
        return cleaned
    }

    private fun injectJsVideoSniffer() {
        val script = """
            (function() {
                try {
                    function reportUrl(u) {
                        if (!u || typeof u !== 'string') return;
                        if (u.startsWith('blob:') || u.startsWith('data:')) return;
                        if (window.OmniSniffer && window.OmniSniffer.onSniffedUrl) {
                            window.OmniSniffer.onSniffedUrl(u);
                        }
                    }

                    var mediaNodes = document.querySelectorAll('video, video source');
                    for (var i = 0; i < mediaNodes.length; i++) {
                        var node = mediaNodes[i];
                        if (node.currentSrc) reportUrl(node.currentSrc);
                        if (node.src) reportUrl(node.src);
                    }

                    if (window.performance && window.performance.getEntriesByType) {
                        var resources = window.performance.getEntriesByType('resource') || [];
                        for (var r = 0; r < resources.length; r++) {
                            var rUrl = resources[r].name || '';
                            if (rUrl && (rUrl.indexOf('.mp4') !== -1 || rUrl.indexOf('.m3u8') !== -1 || rUrl.indexOf('video_redirect') !== -1)) {
                                reportUrl(rUrl);
                            }
                        }
                    }

                    var html = document.documentElement ? document.documentElement.innerHTML : '';
                    if (html) {
                        var patterns = [
                            /"progressive_url"\s*:\s*"(https?:[^"]+)"/g,
                            /"browser_native_hd_url"\s*:\s*"(https?:[^"]+)"/g,
                            /"playable_url_quality_hd"\s*:\s*"(https?:[^"]+)"/g,
                            /"hd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)"/g,
                            /"hd_src"\s*:\s*"(https?:[^"]+)"/g,
                            /"browser_native_sd_url"\s*:\s*"(https?:[^"]+)"/g,
                            /"playable_url"\s*:\s*"(https?:[^"]+)"/g,
                            /"sd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)"/g,
                            /"sd_src"\s*:\s*"(https?:[^"]+)"/g,
                            /"video_url"\s*:\s*"(https?:[^"]+)"/g
                        ];
                        for (var p = 0; p < patterns.length; p++) {
                            var match;
                            while ((match = patterns[p].exec(html)) !== null) {
                                if (match[1]) {
                                    var decoded = match[1]
                                        .replace(/\\\//g, '/')
                                        .replace(/\\u0025/g, '%')
                                        .replace(/\\u0026/g, '&')
                                        .replace(/\\u003C/gi, '<')
                                        .replace(/\\u003E/gi, '>')
                                        .replace(/\\u003D/gi, '=')
                                        .replace(/\\u002F/gi, '/');
                                    reportUrl(decoded);
                                }
                            }
                        }
                    }

                    if (!window.__omniSnifferHooked) {
                        window.__omniSnifferHooked = true;
                        document.addEventListener('play', function(e) {
                            var t = e.target;
                            if (t) {
                                var src = t.currentSrc || t.src || '';
                                if (src && !src.startsWith('blob:') && !src.startsWith('data:')) {
                                    window.__omniLastPlayedVideoUrl = src;
                                    reportUrl(src);
                                }
                            }
                        }, true);
                    }
                } catch (err) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun extractAndDownloadFromCurrentPage() {
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: editBrowserUrl.text?.toString()?.trim().orEmpty()

        if (currentUrl.isBlank()) {
            Toast.makeText(this, "Chưa có trang web nào được mở.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }

        injectJsVideoSniffer()

        isAnalyzingPage = true
        updateSnifferUi()

        val domCaptureScript = """
            (function() {
                try {
                    var activeVideoSrc = window.__omniLastPlayedVideoUrl || "";
                    var activePermalink = "";
                    var videos = document.querySelectorAll('video');
                    var bestVideo = null;
                    var bestScore = -999999;
                    var vh = window.innerHeight || 800;

                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        var rect = v.getBoundingClientRect();
                        var centerDist = Math.abs((rect.top + rect.bottom) / 2 - (vh / 2));
                        var score = -centerDist;
                        if (!v.paused) score += 10000;
                        if (v.currentTime > 0) score += 2000;
                        if (rect.height > 80 && rect.bottom > 0 && rect.top < vh) score += 1000;
                        if (score > bestScore) {
                            bestScore = score;
                            bestVideo = v;
                        }
                    }

                    if (bestVideo) {
                        var vSrc = bestVideo.currentSrc || bestVideo.src || "";
                        if (vSrc && !vSrc.startsWith('blob:') && !vSrc.startsWith('data:')) {
                            activeVideoSrc = vSrc;
                        }
                        var container = bestVideo.closest('article, [role="article"], [data-ft], div[data-sigil]');
                        if (!container && bestVideo.parentElement && bestVideo.parentElement.parentElement) {
                            container = bestVideo.parentElement.parentElement.parentElement;
                        }
                        if (container) {
                            var links = container.querySelectorAll('a[href]');
                            for (var j = 0; j < links.length; j++) {
                                var href = links[j].href || "";
                                if (href.indexOf('/videos/') !== -1 ||
                                    href.indexOf('/watch') !== -1 ||
                                    href.indexOf('/reel/') !== -1 ||
                                    href.indexOf('story_fbid=') !== -1 ||
                                    href.indexOf('/permalink/') !== -1 ||
                                    href.indexOf('/posts/') !== -1 ||
                                    href.indexOf('/status/') !== -1) {
                                    activePermalink = href;
                                    break;
                                }
                            }
                        }
                    }

                    return JSON.stringify({
                        title: document.title || "",
                        url: window.location.href || "",
                        activeVideoSrc: activeVideoSrc || "",
                        activePermalink: activePermalink || "",
                        html: document.documentElement ? document.documentElement.outerHTML : ""
                    });
                } catch (e) {
                    return JSON.stringify({ title: "", url: "", activeVideoSrc: "", activePermalink: "", html: "" });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(domCaptureScript) { rawEvalResult ->
            lifecycleScope.launch {
                val payload = withContext(Dispatchers.Default) {
                    parseWebViewEvalPayload(rawEvalResult, currentUrl)
                }

                val pageTitle = payload[0]
                val pageHtml = payload[1]
                val resolvedUrl = payload[2]
                val activeVideoSrc = payload[3]
                val activePermalink = payload[4]

                val baseEffectiveUrl = resolvedUrl.ifBlank { currentUrl }
                val effectiveUrl = if (
                    activePermalink.isNotBlank() &&
                    !baseEffectiveUrl.contains("/videos/") &&
                    !baseEffectiveUrl.contains("/watch") &&
                    !baseEffectiveUrl.contains("/reel/") &&
                    !baseEffectiveUrl.contains("story_fbid=") &&
                    !baseEffectiveUrl.contains("/permalink/") &&
                    !baseEffectiveUrl.contains("/status/")
                ) {
                    activePermalink
                } else {
                    baseEffectiveUrl
                }

                // Prioritize the currently playing/visible video and most recently sniffed streams first
                val prioritizedSniffedList = buildList {
                    if (activeVideoSrc.isNotBlank()) add(activeVideoSrc)
                    addAll(sniffedVideoUrls.toList().asReversed())
                }.distinct()

                val mediaInfo = withContext(Dispatchers.IO) {
                    val fromPage = runCatching {
                        extractor.parseHtmlAndSniffedUrls(
                            pageHtml,
                            effectiveUrl,
                            pageTitle,
                            prioritizedSniffedList
                        )
                    }.getOrNull()

                    if (fromPage != null && fromPage.options.isNotEmpty()) {
                        fromPage
                    } else {
                        extractor.extract(effectiveUrl).getOrNull()
                    }
                }

                isAnalyzingPage = false
                updateSnifferUi()

                if (mediaInfo == null || mediaInfo.options.isEmpty()) {
                    Toast.makeText(
                        this@PrivateBrowserActivity,
                        "Chưa bắt được luồng video trên trang này. Hãy bấm Play (Phát) video hoặc mở chi tiết bài đăng rồi thử lại!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                QualityBottomSheetDialog(this@PrivateBrowserActivity, mediaInfo) { selectedOption ->
                    startDownloadInBrowser(mediaInfo, selectedOption)
                }.show()
            }
        }
    }

    private fun parseWebViewEvalPayload(
        rawEvalResult: String?,
        fallbackUrl: String
    ): List<String> {
        if (rawEvalResult.isNullOrBlank() || rawEvalResult == "null") {
            return listOf("", "", fallbackUrl, "", "")
        }
        return try {
            // evaluateJavascript returns a JSON-encoded string literal
            val jsonString = if (rawEvalResult.startsWith("\"") && rawEvalResult.endsWith("\"")) {
                org.json.JSONTokener(rawEvalResult).nextValue().toString()
            } else {
                rawEvalResult
            }
            val obj = JSONObject(jsonString)
            val title = obj.optString("title", "")
            val html = obj.optString("html", "")
            val url = obj.optString("url", fallbackUrl).ifBlank { fallbackUrl }
            val activeVideoSrc = obj.optString("activeVideoSrc", "")
            val activePermalink = obj.optString("activePermalink", "")
            listOf(title, html, url, activeVideoSrc, activePermalink)
        } catch (_: Exception) {
            listOf("", "", fallbackUrl, "", "")
        }
    }

    private fun startDownloadInBrowser(mediaInfo: ExtractedMediaInfo, selectedOption: MediaOption) {
        val currentUrl = webView.url ?: mediaInfo.cleanUrl
        val cookieHeader = authCookieStore.getEffectiveCookie(mediaInfo.platform, currentUrl)
        val mergedHeaders = LinkedHashMap<String, String>(selectedOption.customHeaders)
        if (cookieHeader.isNotBlank() && !mergedHeaders.containsKey("Cookie")) {
            mergedHeaders["Cookie"] = cookieHeader
        }
        if (!mergedHeaders.containsKey("Referer")) {
            mergedHeaders["Referer"] = currentUrl.ifBlank { mediaInfo.platform.referer }
        }

        val enrichedOption = selectedOption.copy(customHeaders = mergedHeaders)
        val fileName = omniDownloadManager.buildFileName(mediaInfo, enrichedOption)

        val downloadId = try {
            omniDownloadManager.enqueueDownload(mediaInfo, enrichedOption, fileName)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Lỗi khởi tạo tải xuống: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Đang tải xuống: $fileName (Xem trên thanh thông báo hoặc Thư viện)",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            omniDownloadManager.observeDownloadProgress(downloadId, mediaInfo, enrichedOption, fileName)
                .collectLatest { state ->
                    when (state) {
                        is DownloadProgressState.Completed -> {
                            Toast.makeText(
                                this@PrivateBrowserActivity,
                                "✓ Đã tải xong: ${state.fileName}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is DownloadProgressState.Failed -> {
                            Toast.makeText(
                                this@PrivateBrowserActivity,
                                state.reason,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> Unit
                    }
                }
        }
    }

    private fun showCookieManagerDialog() {
        val platforms = arrayOf(
            SocialPlatform.FACEBOOK,
            SocialPlatform.INSTAGRAM,
            SocialPlatform.X_TWITTER,
            SocialPlatform.TIKTOK,
            SocialPlatform.THREADS
        )
        var selectedIndex = platforms.indexOf(activePlatform).coerceAtLeast(0)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val statusSummary = TextView(this).apply {
            val summaryLines = platforms.joinToString("  •  ") { p ->
                val mark = if (authCookieStore.isLoggedIn(p)) "✓ Đã đăng nhập" else "Chưa ĐN"
                "${p.displayName}: $mark"
            }
            text = "$summaryLines\n\nChọn nền tảng bên trên để xem hoặc dán chuỗi Cookie thủ công (c_user=...; xs=... / sessionid=... / auth_token=...):"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
        }

        val inputCookie = EditText(this).apply {
            hint = "Dán Cookie thủ công (hoặc để trống nếu đã đăng nhập trên WebView)"
            setText(authCookieStore.getManualCookie(platforms[selectedIndex]))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setBackgroundResource(R.drawable.bg_liquid_glass_input)
            setPadding(32, 24, 32, 24)
            minLines = 3
            maxLines = 6
        }

        container.addView(statusSummary)
        container.addView(inputCookie)

        val labels = platforms.map { p ->
            if (authCookieStore.isLoggedIn(p)) "${p.displayName} (Đã đăng nhập ✓)" else "${p.displayName} (Chưa đăng nhập)"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Quản lý Cookie & Phiên Đăng Nhập")
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
                inputCookie.setText(authCookieStore.getManualCookie(platforms[selectedIndex]))
            }
            .setView(container)
            .setPositiveButton("Lưu Cookie") { _, _ ->
                val chosen = platforms[selectedIndex]
                val cookieValue = inputCookie.text?.toString()?.trim().orEmpty()
                authCookieStore.setManualCookie(chosen, cookieValue)
                syncManualCookiesToWebView(chosen)
                updatePlatformChips()
                Toast.makeText(
                    this,
                    "Đã cập nhật Cookie cho ${chosen.displayName}!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Đăng xuất / Xóa tất cả") { _, _ ->
                authCookieStore.clearAllCookies()
                sniffedVideoUrls.clear()
                updatePlatformChips()
                updateSnifferUi()
                webView.reload()
                Toast.makeText(
                    this,
                    "Đã xóa toàn bộ Cookie và đăng xuất khỏi mọi nền tảng!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Đóng", null)
            .create()

        dialog.show()
        LiquidGlassHelper.applyWindowBlurIfSupported(dialog.window)
    }

    inner class SnifferJsBridge {
        @JavascriptInterface
        fun onSniffedUrl(url: String?) {
            if (url.isNullOrBlank()) return
            val cleaned = normalizeSniffedMediaUrl(url)
            if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
                if (looksLikeDirectMediaStream(cleaned) || cleaned.contains(".mp4")) {
                    if (sniffedVideoUrls.add(cleaned)) {
                        runOnUiThread {
                            updateSnifferUi()
                        }
                    }
                }
            }
        }
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER", "unused", "UnusedReceiverParameter")
    private fun MultiPlatformExtractor.parseHtmlAndSniffedUrls(
        html: String,
        currentUrl: String,
        pageTitle: String,
        sniffedUrls: List<String>
    ): ExtractedMediaInfo? {
        val cleanUrl = UrlDetector.cleanUrl(currentUrl)
        val platform = UrlDetector.detectPlatform(cleanUrl).let {
            if (it == SocialPlatform.UNKNOWN) activePlatform else it
        }
        val cookieHeader = authCookieStore.getEffectiveCookie(platform, cleanUrl)
        val customHeaders = buildMap {
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
            put("Referer", cleanUrl.ifBlank { platform.referer })
            put("Origin", platform.origin)
            put("User-Agent", BROWSER_MOBILE_USER_AGENT)
        }

        val options = mutableListOf<MediaOption>()
        val seenUrls = HashSet<String>()

        fun addCandidate(rawUrl: String, qualityHint: String, isHd: Boolean, mediaType: MediaType = MediaType.VIDEO) {
            val normalized = normalizeSniffedMediaUrl(decodeEscapedUrl(rawUrl))
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return
            val dedupeKey = normalized.substringBefore('?')
            if (!seenUrls.add(dedupeKey) && !seenUrls.add(normalized)) return

            val ext = when (mediaType) {
                MediaType.AUDIO -> "mp3"
                MediaType.IMAGE -> "jpg"
                MediaType.VIDEO -> "mp4"
            }
            options.add(
                MediaOption(
                    url = normalized,
                    qualityLabel = qualityHint,
                    extension = ext,
                    mediaType = mediaType,
                    isHighestQuality = isHd,
                    isNoWatermark = true,
                    customHeaders = customHeaders
                )
            )
        }

        // 1. Parse HD / SD URLs from HTML JSON patterns
        if (html.isNotBlank()) {
            val hdPatterns = listOf(
                Regex(""""browser_native_hd_url"\s*:\s*"(https?:[^"]+)""""),
                Regex(""""playable_url_quality_hd"\s*:\s*"(https?:[^"]+)""""),
                Regex(""""hd_src(?:_no_ratelimit)?"\s*:\s*"(https?:[^"]+)"""")
            )
            for (regex in hdPatterns) {
                regex.findAll(html).forEach { match ->
                    addCandidate(match.groupValues[1], "Full HD 1080p (Trình duyệt)", isHd = true)
                }
            }

            val sdPatterns = listOf(
                Regex(""""browser_native_sd_url"\s*:\s*"(https?:[^"]+)""""),
                Regex(""""playable_url"\s*:\s*"(https?:[^"]+)""""),
                Regex(""""sd_src(?:_no_ratelimit)?"\s*:\s*"(https?:[^"]+)""""),
                Regex(""""video_url"\s*:\s*"(https?:[^"]+)""""),
                Regex(""""contentUrl"\s*:\s*"(https?:[^"]+\.mp4[^"]*)"""")
            )
            for (regex in sdPatterns) {
                regex.findAll(html).forEach { match ->
                    addCandidate(match.groupValues[1], "HD 720p (Trình duyệt)", isHd = options.isEmpty())
                }
            }
        }

        // 2. Include Sniffed network/DOM stream URLs
        sniffedUrls.forEachIndexed { idx, sniffed ->
            val lower = sniffed.lowercase(Locale.ROOT)
            val isAudio = lower.substringBefore('?').endsWith(".m4a") || lower.substringBefore('?').endsWith(".mp3")
            if (isAudio) {
                addCandidate(sniffed, "Âm thanh gốc / MP3 #${idx + 1}", isHd = false, mediaType = MediaType.AUDIO)
            } else {
                val label = if (options.isEmpty() && idx == 0) {
                    "Video gốc Chất lượng cao (Sniffer #1)"
                } else {
                    "Video luồng trực tiếp (Sniffer #${idx + 1})"
                }
                addCandidate(sniffed, label, isHd = options.isEmpty() && idx == 0, mediaType = MediaType.VIDEO)
            }
        }

        if (options.isEmpty()) return null

        val firstVideoUrl = options.firstOrNull { it.mediaType == MediaType.VIDEO }?.url
        if (firstVideoUrl != null && options.none { it.mediaType == MediaType.AUDIO }) {
            options.add(
                MediaOption(
                    url = firstVideoUrl,
                    qualityLabel = "Tách nhạc nền (MP3 Audio)",
                    extension = "mp3",
                    mediaType = MediaType.AUDIO,
                    isHighestQuality = false,
                    isNoWatermark = true,
                    customHeaders = customHeaders
                )
            )
        }

        val resolvedTitle = pageTitle.trim().ifBlank {
            "Video ${platform.displayName} (${System.currentTimeMillis() / 1000})"
        }

        val ogImage = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']og:image["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { decodeEscapedUrl(it) }.orEmpty()

        return ExtractedMediaInfo(
            originalUrl = currentUrl,
            cleanUrl = cleanUrl,
            platform = platform,
            title = resolvedTitle,
            author = platform.displayName,
            thumbnailUrl = ogImage,
            durationSeconds = 0L,
            options = options
        )
    }

    private fun decodeEscapedUrl(raw: String): String {
        var res = raw
            .replace("""\/""", "/")
            .replace("""\u0025""", "%")
            .replace("""\u0026""", "&")
            .replace("""\u003C""", "<")
            .replace("""\u003E""", ">")
            .replace("""\u003D""", "=")
            .replace("""\u002F""", "/")
            .replace("&amp;", "&")
        if (res.contains("%3A%2F%2F", ignoreCase = true)) {
            runCatching { res = URLDecoder.decode(res, "UTF-8") }
        }
        return res.trim()
    }
}
