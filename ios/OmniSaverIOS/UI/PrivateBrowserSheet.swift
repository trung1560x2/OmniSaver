import SwiftUI
import WebKit

// MARK: - Private Browser ViewModel & WKWebView Coordinator

@MainActor
final class PrivateBrowserViewModel: NSObject, ObservableObject {
    @Published var selectedPlatform: SocialPlatform
    @Published var addressText: String
    @Published var currentUrl: String
    @Published var pageTitle: String = "Trình duyệt Riêng tư"
    @Published var isLoading: Bool = false
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false
    @Published var sniffedUrls: [String] = []
    @Published var isCapturing: Bool = false
    @Published var statusBanner: String? = nil
    @Published var cookieSynced: Bool = false

    weak var webView: WKWebView?

    init(initialPlatform: SocialPlatform = .facebook, initialUrl: String? = nil) {
        self.selectedPlatform = initialPlatform == .unknown ? .facebook : initialPlatform
        let startUrl: String
        if let raw = initialUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
           !raw.isEmpty,
           let extracted = UrlDetector.extractFirstUrl(raw) {
            startUrl = extracted
        } else {
            startUrl = (initialPlatform == .unknown ? SocialPlatform.facebook : initialPlatform).loginUrl
        }
        self.addressText = startUrl
        self.currentUrl = startUrl
        super.init()
        self.cookieSynced = AuthCookieStore.shared.hasCookie(for: self.selectedPlatform)
    }

    func switchPlatform(_ platform: SocialPlatform) {
        selectedPlatform = platform
        cookieSynced = AuthCookieStore.shared.hasCookie(for: platform)
        sniffedUrls.removeAll()
        loadUrlString(platform.loginUrl)
    }

    func loadUrlString(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let target: String
        if let extracted = UrlDetector.extractFirstUrl(trimmed) {
            target = extracted
        } else if trimmed.contains(".") && !trimmed.contains(" ") {
            target = "https://" + trimmed
        } else {
            let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? trimmed
            target = "https://www.google.com/search?q=\(encoded)"
        }

        guard let url = URL(string: target) else { return }
        sniffedUrls.removeAll()
        addressText = target
        currentUrl = target
        let detected = UrlDetector.detectPlatform(target)
        if detected != .unknown {
            selectedPlatform = detected
        }
        webView?.load(URLRequest(url: url))
    }

    func goBack() {
        webView?.goBack()
    }

    func goForward() {
        webView?.goForward()
    }

    func reload() {
        syncCookiesNow()
        webView?.reload()
    }

    func recordSniffedUrl(_ candidate: String) {
        let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("http"),
              !trimmed.hasPrefix("blob:") else { return }
        let lower = trimmed.lowercased()
        let isLikelyMedia = lower.contains(".mp4")
            || lower.contains(".m4a")
            || lower.contains("video")
            || lower.contains("fbcdn.net")
            || lower.contains("cdninstagram.com")
            || lower.contains("tiktokcdn")
            || lower.contains("twimg.com")
            || lower.contains("mime_type=video")
        guard isLikelyMedia else { return }
        if !sniffedUrls.contains(trimmed) {
            sniffedUrls.insert(trimmed, at: 0)
            if sniffedUrls.count > 40 {
                sniffedUrls.removeLast()
            }
        }
    }

    func syncCookiesNow(completion: ((String) -> Void)? = nil) {
        let target = currentUrl
        AuthCookieStore.shared.syncFromWebKitCookieStore(for: target) { [weak self] cookie in
            guard let self = self else { return }
            let detected = UrlDetector.detectPlatform(target)
            let checkPlatform = detected != .unknown ? detected : self.selectedPlatform
            self.cookieSynced = AuthCookieStore.shared.hasCookie(for: checkPlatform)
            completion?(cookie)
        }
    }

    func captureMediaFromCurrentPage() async -> ExtractedMediaInfo? {
        guard let webView = webView else { return nil }
        isCapturing = true
        statusBanner = "Đang đồng bộ Cookie & quét luồng Video Full HD trên trang..."
        defer { isCapturing = false }

        // 1. Sync cookies from WKHTTPCookieStore
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            syncCookiesNow { _ in
                continuation.resume()
            }
        }

        // 2. Evaluate DOM HTML, Title, and live media elements in WKWebView
        let snapshotScript = """
        (function() {
            var urls = [];
            function pushUrl(u) {
                if (u && typeof u === 'string' && u.indexOf('http') === 0 && urls.indexOf(u) === -1) {
                    urls.push(u);
                }
            }
            document.querySelectorAll('video, audio, source').forEach(function(el) {
                pushUrl(el.currentSrc);
                pushUrl(el.src);
            });
            try {
                var resources = performance.getEntriesByType('resource') || [];
                for (var i = 0; i < resources.length; i++) {
                    var name = resources[i].name || '';
                    if (name.indexOf('.mp4') !== -1 || name.indexOf('.m4a') !== -1 ||
                        name.indexOf('fbcdn') !== -1 || name.indexOf('cdninstagram') !== -1 ||
                        name.indexOf('video') !== -1) {
                        pushUrl(name);
                    }
                }
            } catch (e) {}
            return {
                html: document.documentElement ? document.documentElement.outerHTML : '',
                title: document.title || '',
                url: window.location.href || '',
                urls: urls
            };
        })();
        """

        var rawHtml = ""
        var domTitle = pageTitle
        var activePageUrl = currentUrl

        if let dict = try? await webView.evaluateJavaScript(snapshotScript) as? [String: Any] {
            if let h = dict["html"] as? String {
                rawHtml = h
            }
            if let t = dict["title"] as? String, !t.isEmpty {
                domTitle = t
            }
            if let u = dict["url"] as? String, !u.isEmpty {
                activePageUrl = u
            }
            if let jsUrls = dict["urls"] as? [String] {
                for u in jsUrls {
                    recordSniffedUrl(u)
                }
            }
        }

        // 3. Parse HTML + sniffed URLs first
        if let parsed = await MultiPlatformExtractor.shared.parseHtmlAndSniffedUrls(
            rawHtml: rawHtml,
            pageUrl: activePageUrl,
            pageTitle: domTitle,
            sniffedUrls: sniffedUrls
        ) {
            statusBanner = "Đã bắt được \(parsed.options.count) luồng tải Full HD!"
            return parsed
        }

        // 4. Fallback to MultiPlatformExtractor.extract with fresh cookies
        do {
            let extracted = try await MultiPlatformExtractor.shared.extract(rawInput: activePageUrl)
            statusBanner = "Đã phân tích thành công \(extracted.options.count) luồng tải!"
            return extracted
        } catch {
            statusBanner = "Chưa tìm thấy video trên trang này. Hãy nhấn Play video rồi bấm Bắt Video lại."
            return nil
        }
    }
}

// MARK: - Private Browser Sheet View

struct PrivateBrowserSheet: View {
    @StateObject private var viewModel: PrivateBrowserViewModel
    let onMediaCaptured: (ExtractedMediaInfo) -> Void
    @Environment(\.dismiss) private var dismiss

    init(
        initialPlatform: SocialPlatform = .facebook,
        initialUrl: String? = nil,
        onMediaCaptured: @escaping (ExtractedMediaInfo) -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: PrivateBrowserViewModel(
                initialPlatform: initialPlatform,
                initialUrl: initialUrl
            )
        )
        self.onMediaCaptured = onMediaCaptured
    }

    var body: some View {
        ZStack {
            LiquidGlassBackgroundView()

            VStack(spacing: 10) {
                // Top Header Bar
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Image(systemName: "lock.shield.fill")
                                .foregroundColor(.cyan)
                                .font(.system(size: 15, weight: .bold))
                            Text("Trình duyệt Riêng tư & Bắt Video")
                                .font(.system(size: 15, weight: .bold, design: .rounded))
                                .foregroundColor(.white)
                                .lineLimit(1)
                        }
                        Text("Đăng nhập tài khoản để tải Video Kín, Story, Nhóm & Reels Full HD")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundColor(.white.opacity(0.68))
                            .lineLimit(1)
                    }

                    Spacer()

                    LiquidGlassPillButton(
                        title: "Lưu Cookie & Đóng",
                        systemImage: "checkmark.shield.fill",
                        tintColor: .green
                    ) {
                        viewModel.syncCookiesNow { _ in
                            dismiss()
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, 12)

                // Platform Quick-Switch Bar
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SocialPlatform.supportedPlatforms) { platform in
                            let hasCookie = AuthCookieStore.shared.hasCookie(for: platform)
                            LiquidGlassPillButton(
                                title: platform.shortName,
                                systemImage: platform.sfSymbolName,
                                isSelected: viewModel.selectedPlatform == platform,
                                statusDotColor: hasCookie ? .green : nil,
                                tintColor: platform.accentColor
                            ) {
                                viewModel.switchPlatform(platform)
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                }

                // Address Bar + Navigation Controls
                HStack(spacing: 8) {
                    Button(action: { viewModel.goBack() }) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(viewModel.canGoBack ? .white : .white.opacity(0.3))
                            .frame(width: 34, height: 34)
                            .background(Color.white.opacity(0.08))
                            .clipShape(Circle())
                    }
                    .disabled(!viewModel.canGoBack)

                    Button(action: { viewModel.goForward() }) {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(viewModel.canGoForward ? .white : .white.opacity(0.3))
                            .frame(width: 34, height: 34)
                            .background(Color.white.opacity(0.08))
                            .clipShape(Circle())
                    }
                    .disabled(!viewModel.canGoForward)

                    HStack(spacing: 6) {
                        Image(systemName: viewModel.cookieSynced ? "lock.fill" : "globe")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(viewModel.cookieSynced ? .green : .cyan)

                        TextField("Nhập link bài viết hoặc trang web...", text: $viewModel.addressText)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundColor(.white)
                            .textInputAutocapitalization(.never)
                            .disableAutocorrection(true)
                            .submitLabel(.go)
                            .onSubmit {
                                viewModel.loadUrlString(viewModel.addressText)
                            }

                        if viewModel.isLoading {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .cyan))
                                .scaleEffect(0.75)
                        } else if !viewModel.addressText.isEmpty {
                            Button(action: { viewModel.addressText = "" }) {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 13))
                                    .foregroundColor(.white.opacity(0.45))
                            }
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(Color.black.opacity(0.38))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
                    )

                    Button(action: { viewModel.reload() }) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 34, height: 34)
                            .background(Color.white.opacity(0.10))
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal, 14)

                // Status Banner if present
                if let banner = viewModel.statusBanner {
                    HStack(spacing: 8) {
                        Image(systemName: "sparkles.tv.fill")
                            .foregroundColor(.cyan)
                            .font(.system(size: 13))
                        Text(banner)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.white.opacity(0.92))
                            .lineLimit(2)
                        Spacer()
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Color.cyan.opacity(0.14))
                    .cornerRadius(10)
                    .padding(.horizontal, 14)
                }

                // WebView Container with Floating Bottom Action Bar
                ZStack(alignment: .bottom) {
                    SniffingWebViewRepresentable(viewModel: viewModel)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.20), lineWidth: 1)
                        )
                        .padding(.horizontal, 10)
                        .padding(.bottom, 8)

                    // Floating Liquid Glass Capture Bar
                    VStack(spacing: 6) {
                        if !viewModel.sniffedUrls.isEmpty {
                            LiquidGlassBadge(
                                text: "Đã phát hiện \(viewModel.sniffedUrls.count) luồng media trên trang",
                                systemImage: "dot.radiowaves.left.and.right",
                                color: .green
                            )
                        }

                        LiquidGlassPrimaryButton(
                            title: "Bắt Video Full HD ở trang này",
                            systemImage: "arrow.down.doc.fill",
                            isLoading: viewModel.isCapturing
                        ) {
                            Task {
                                if let captured = await viewModel.captureMediaFromCurrentPage() {
                                    onMediaCaptured(captured)
                                    dismiss()
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 22)
                    .padding(.bottom, 18)
                }
            }
        }
    }
}

// MARK: - WKWebView UIViewRepresentable with Media Sniffer Injection

private struct SniffingWebViewRepresentable: UIViewRepresentable {
    @ObservedObject var viewModel: PrivateBrowserViewModel

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        let userContent = WKUserContentController()
        let snifferJs = """
        (function() {
            if (window.__omniSnifferInstalled) return;
            window.__omniSnifferInstalled = true;

            function notifyUrl(u) {
                try {
                    if (u && typeof u === 'string' && u.indexOf('http') === 0) {
                        window.webkit.messageHandlers.omniMediaSniffer.postMessage(u);
                    }
                } catch (e) {}
            }

            function scanMediaElements() {
                try {
                    var els = document.querySelectorAll('video, audio, source');
                    for (var i = 0; i < els.length; i++) {
                        if (els[i].currentSrc) notifyUrl(els[i].currentSrc);
                        if (els[i].src) notifyUrl(els[i].src);
                    }
                    var entries = performance.getEntriesByType('resource') || [];
                    for (var j = 0; j < entries.length; j++) {
                        var name = entries[j].name || '';
                        if (name.indexOf('.mp4') !== -1 || name.indexOf('.m4a') !== -1 ||
                            name.indexOf('fbcdn.net') !== -1 || name.indexOf('cdninstagram.com') !== -1) {
                            notifyUrl(name);
                        }
                    }
                } catch (e) {}
            }

            document.addEventListener('play', scanMediaElements, true);
            document.addEventListener('loadedmetadata', scanMediaElements, true);
            setInterval(scanMediaElements, 1800);
        })();
        """

        let script = WKUserScript(
            source: snifferJs,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: false
        )
        userContent.addUserScript(script)
        userContent.add(context.coordinator, name: "omniMediaSniffer")
        config.userContentController = userContent

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        webView.isOpaque = false
        webView.backgroundColor = UIColor(red: 0.03, green: 0.05, blue: 0.11, alpha: 1.0)

        viewModel.webView = webView
        if let url = URL(string: viewModel.currentUrl) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "omniMediaSniffer")
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        private weak var viewModel: PrivateBrowserViewModel?

        init(viewModel: PrivateBrowserViewModel) {
            self.viewModel = viewModel
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "omniMediaSniffer",
                  let urlStr = message.body as? String else { return }
            Task { @MainActor [weak self] in
                self?.viewModel?.recordSniffedUrl(urlStr)
            }
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            Task { @MainActor [weak self] in
                guard let vm = self?.viewModel else { return }
                vm.isLoading = true
                if let urlStr = webView.url?.absoluteString {
                    vm.currentUrl = urlStr
                    vm.addressText = urlStr
                }
                vm.canGoBack = webView.canGoBack
                vm.canGoForward = webView.canGoForward
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor [weak self] in
                guard let vm = self?.viewModel else { return }
                vm.isLoading = false
                if let urlStr = webView.url?.absoluteString {
                    vm.currentUrl = urlStr
                    vm.addressText = urlStr
                }
                if let title = webView.title, !title.isEmpty {
                    vm.pageTitle = title
                }
                vm.canGoBack = webView.canGoBack
                vm.canGoForward = webView.canGoForward
                vm.syncCookiesNow()
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            Task { @MainActor [weak self] in
                self?.viewModel?.isLoading = false
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            Task { @MainActor [weak self] in
                self?.viewModel?.isLoading = false
            }
        }
    }
}
