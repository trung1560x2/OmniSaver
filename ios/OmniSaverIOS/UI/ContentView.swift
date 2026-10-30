import SwiftUI
import UIKit

private struct PlayableVideoTarget: Identifiable {
    let id = UUID()
    let url: URL
    let title: String
}

private struct SharePayloadTarget: Identifiable {
    let id = UUID()
    let items: [Any]
}

struct ContentView: View {
    @ObservedObject private var cookieStore = AuthCookieStore.shared
    @ObservedObject private var downloadManager = OmniDownloadManager.shared
    @ObservedObject private var otaManager = OtaUpdateManager.shared

    @State private var urlInput: String = ""
    @State private var selectedPlatform: SocialPlatform = .facebook
    @State private var isExtracting: Bool = false
    @State private var extractionError: String? = nil
    @State private var statusBanner: String? = nil

    @State private var extractedInfo: ExtractedMediaInfo? = nil
    @State private var selectedOption: MediaOption? = nil

    @State private var showPrivateBrowser: Bool = false
    @State private var showSettingsSheet: Bool = false
    @State private var showOtaSheet: Bool = false
    @State private var availableOtaRelease: OtaReleaseInfo? = nil

    @State private var activeVideoTarget: PlayableVideoTarget? = nil
    @State private var activeShareTarget: SharePayloadTarget? = nil

    var body: some View {
        ZStack {
            LiquidGlassBackgroundView()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    headerCard
                    if let otaRelease = availableOtaRelease {
                        otaUpdateBanner(release: otaRelease)
                    }
                    platformQuickBar
                    urlInputCard

                    if isExtracting || downloadManager.isDownloading {
                        progressCard
                    }

                    if let errorMsg = extractionError {
                        errorBannerCard(message: errorMsg)
                    }

                    if let banner = statusBanner {
                        successBannerCard(message: banner)
                    }

                    if let info = extractedInfo {
                        resultPreviewCard(info: info)
                    }

                    downloadedHistoryCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 36)
            }
        }
        .sheet(isPresented: $showPrivateBrowser) {
            PrivateBrowserSheet(
                initialPlatform: selectedPlatform,
                initialUrl: UrlDetector.extractFirstUrl(urlInput)
            ) { capturedInfo in
                applyExtractedInfo(capturedInfo)
                statusBanner = "Đã bắt thành công \(capturedInfo.options.count) luồng tải từ Trình duyệt Riêng tư!"
            }
        }
        .sheet(isPresented: $showSettingsSheet) {
            SettingsSheetView { platformToLogin in
                selectedPlatform = platformToLogin
                showPrivateBrowser = true
            }
        }
        .sheet(isPresented: $showOtaSheet) {
            OtaUpdateSheetView()
        }
        .sheet(item: $activeVideoTarget) { target in
            VideoPlayerSheet(url: target.url, title: target.title)
        }
        .sheet(item: $activeShareTarget) { payload in
            ShareSheet(items: payload.items)
        }
        .onAppear {
            downloadManager.loadHistory()
            Task {
                let result = await otaManager.checkForUpdates()
                if result.hasUpdate, let release = result.latestRelease {
                    await MainActor.run {
                        self.availableOtaRelease = release
                    }
                }
            }
        }
    }

    // MARK: - 1. Header Card

    private var headerCard: some View {
        LiquidGlassCard(cornerRadius: 24, padding: 16) {
            HStack(spacing: 12) {
                // App Emblem
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color(red: 0.16, green: 0.72, blue: 0.98),
                                    Color(red: 0.49, green: 0.36, blue: 0.96)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 48, height: 48)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.45), lineWidth: 1.1)
                        )

                    Image(systemName: "arrow.down.to.line.circle.fill")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text("OmniSaver")
                            .font(.system(size: 20, weight: .heavy, design: .rounded))
                            .foregroundColor(.white)

                        LiquidGlassBadge(
                            text: "NO ADS",
                            systemImage: "sparkles",
                            color: Color(red: 0.16, green: 0.85, blue: 0.60)
                        )
                    }

                    Text("v\(OtaUpdateManager.currentVersionName) • Full HD & OTA Ready")
                        .font(.system(size: 11, weight: .semibold, design: .rounded))
                        .foregroundColor(.white.opacity(0.70))
                }

                Spacer()

                HStack(spacing: 8) {
                    LiquidGlassPillButton(
                        title: "OTA",
                        systemImage: "arrow.triangle.2.circlepath",
                        statusDotColor: availableOtaRelease != nil ? .green : nil,
                        tintColor: .cyan
                    ) {
                        showOtaSheet = true
                    }

                    LiquidGlassPillButton(
                        title: "Cài đặt",
                        systemImage: "gearshape.fill",
                        tintColor: .purple
                    ) {
                        showSettingsSheet = true
                    }
                }
            }
        }
    }

    // MARK: - Subtle OTA Update Notification Banner

    private func otaUpdateBanner(release: OtaReleaseInfo) -> some View {
        LiquidGlassCard(cornerRadius: 18, padding: 12) {
            HStack(spacing: 10) {
                Image(systemName: "sparkles.tv.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.green)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Đã có bản cập nhật mới v\(release.versionName) (Build \(release.versionCode))")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Text("Nhấn để xem thay đổi và tải .IPA / cài qua AltStore, TrollStore")
                        .font(.system(size: 11))
                        .foregroundColor(.white.opacity(0.72))
                }

                Spacer()

                LiquidGlassPillButton(
                    title: "Cập nhật",
                    systemImage: "arrow.down.circle.fill",
                    tintColor: .green
                ) {
                    showOtaSheet = true
                }
            }
        }
    }

    // MARK: - 2. Platform Pills Bar

    private var platformQuickBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Nền tảng hỗ trợ (Chấm xanh = Đã có Cookie tải Video Kín)")
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundColor(.white.opacity(0.68))
                Spacer()
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SocialPlatform.supportedPlatforms) { platform in
                        let hasCookie = cookieStore.hasCookie(for: platform)
                        LiquidGlassPillButton(
                            title: platform.shortName,
                            systemImage: platform.sfSymbolName,
                            isSelected: selectedPlatform == platform,
                            statusDotColor: hasCookie ? .green : nil,
                            tintColor: platform.accentColor
                        ) {
                            selectedPlatform = platform
                            if urlInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                showPrivateBrowser = true
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: - 3. URL Input & Actions Glass Card

    private var urlInputCard: some View {
        LiquidGlassCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Image(systemName: "link.badge.plus")
                        .foregroundColor(.cyan)
                    Text("Nhập link Video / Reel / Story / Bài viết")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Spacer()

                    let detected = UrlDetector.detectPlatform(urlInput)
                    if detected != .unknown {
                        LiquidGlassBadge(
                            text: detected.shortName.uppercased(),
                            systemImage: detected.sfSymbolName,
                            color: detected.accentColor
                        )
                    }
                }

                // URL Input Field + Paste / Clear
                HStack(spacing: 8) {
                    TextField(
                        "Dán link Facebook, TikTok, Instagram, X, Threads...",
                        text: $urlInput
                    )
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.white)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .submitLabel(.go)
                    .onSubmit {
                        startExtraction()
                    }
                    .onChange(of: urlInput) { newVal in
                        let det = UrlDetector.detectPlatform(newVal)
                        if det != .unknown {
                            selectedPlatform = det
                        }
                    }

                    if !urlInput.isEmpty {
                        Button(action: {
                            urlInput = ""
                            extractionError = nil
                            statusBanner = nil
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 16))
                                .foregroundColor(.white.opacity(0.5))
                        }
                    }
                }
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.black.opacity(0.38))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.20), lineWidth: 1)
                )

                // Quick Paste & Clear Buttons
                HStack(spacing: 10) {
                    LiquidGlassPillButton(
                        title: "Dán từ Bộ nhớ tạm",
                        systemImage: "doc.on.clipboard.fill",
                        tintColor: .cyan
                    ) {
                        if let pasted = UIPasteboard.general.string,
                           !pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            urlInput = pasted
                            let detected = UrlDetector.detectPlatform(pasted)
                            if detected != .unknown {
                                selectedPlatform = detected
                            }
                            startExtraction()
                        } else {
                            extractionError = "Bộ nhớ tạm hiện đang trống. Hãy sao chép link video trước!"
                        }
                    }

                    LiquidGlassPillButton(
                        title: "Xóa",
                        systemImage: "eraser.fill",
                        tintColor: .pink
                    ) {
                        urlInput = ""
                        extractedInfo = nil
                        selectedOption = nil
                        extractionError = nil
                        statusBanner = nil
                    }

                    Spacer()
                }

                // Primary Extract Button
                LiquidGlassPrimaryButton(
                    title: isExtracting ? "Đang phân tích luồng Full HD..." : "Phân tích & Tải Full HD",
                    systemImage: "sparkles.tv.fill",
                    isDisabled: urlInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    isLoading: isExtracting
                ) {
                    startExtraction()
                }

                // Private Browser & Sniffer Launch Button
                Button(action: {
                    showPrivateBrowser = true
                }) {
                    HStack(spacing: 8) {
                        Image(systemName: "lock.laptopcomputer")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.cyan)
                        Text("Mở Trình duyệt Riêng tư (Tải Video Kín / Story / Nhóm)")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(.white.opacity(0.92))
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.white.opacity(0.55))
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Color.white.opacity(0.07))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(Color.cyan.opacity(0.32), lineWidth: 1)
                    )
                }
                .buttonStyle(LiquidSpringButtonStyle())
            }
        }
    }

    // MARK: - 4. Progress Card

    private var progressCard: some View {
        LiquidGlassCard(cornerRadius: 20, padding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .cyan))
                    Text(
                        isExtracting
                            ? "Đang bóc tách luồng Video 1080p / HD & Âm thanh gốc..."
                            : downloadManager.statusMessage
                    )
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                    Spacer()
                    if downloadManager.isDownloading {
                        Text("\(Int(downloadManager.progress * 100))%")
                            .font(.system(size: 13, weight: .heavy, design: .monospaced))
                            .foregroundColor(.cyan)
                    }
                }

                if downloadManager.isDownloading {
                    ProgressView(value: downloadManager.progress, total: 1.0)
                        .tint(.cyan)
                }
            }
        }
    }

    // MARK: - Error & Status Banners

    private func errorBannerCard(message: String) -> some View {
        LiquidGlassCard(cornerRadius: 18, padding: 14) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                        .font(.system(size: 16, weight: .bold))
                    Text(message)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white.opacity(0.95))
                    Spacer()
                }

                HStack(spacing: 10) {
                    LiquidGlassPillButton(
                        title: "Mở Trình duyệt Riêng tư để bắt Video này",
                        systemImage: "safari.fill",
                        tintColor: .cyan
                    ) {
                        showPrivateBrowser = true
                    }
                    Spacer()
                }
            }
        }
    }

    private func successBannerCard(message: String) -> some View {
        LiquidGlassCard(cornerRadius: 18, padding: 12) {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundColor(.green)
                    .font(.system(size: 16, weight: .bold))
                Text(message)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white)
                Spacer()
            }
        }
    }

    // MARK: - 5. Extracted Media Preview & Quality Selector Card

    private func resultPreviewCard(info: ExtractedMediaInfo) -> some View {
        LiquidGlassCard {
            VStack(alignment: .leading, spacing: 14) {
                // Header & Metadata
                HStack(alignment: .top, spacing: 12) {
                    // Thumbnail preview
                    ZStack(alignment: .bottomTrailing) {
                        if let thumbUrl = URL(string: info.thumbnailUrl), !info.thumbnailUrl.isEmpty {
                            AsyncImage(url: thumbUrl) { phase in
                                switch phase {
                                case .success(let image):
                                    image
                                        .resizable()
                                        .scaledToFill()
                                default:
                                    thumbnailPlaceholder(platform: info.platform)
                                }
                            }
                            .frame(width: 96, height: 96)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        } else {
                            thumbnailPlaceholder(platform: info.platform)
                                .frame(width: 96, height: 96)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }

                        Text(info.formattedDuration)
                            .font(.system(size: 10, weight: .heavy, design: .monospaced))
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color.black.opacity(0.75))
                            .cornerRadius(6)
                            .padding(5)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            LiquidGlassBadge(
                                text: info.platform.displayName,
                                systemImage: info.platform.sfSymbolName,
                                color: info.platform.accentColor
                            )
                            LiquidGlassBadge(
                                text: "\(info.options.count) chất lượng",
                                systemImage: "film.stack",
                                color: .cyan
                            )
                        }

                        Text(info.title)
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                            .lineLimit(2)

                        Text("Tác giả: \(info.author)")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.white.opacity(0.70))
                            .lineLimit(1)
                    }

                    Spacer()
                }

                Divider().background(Color.white.opacity(0.15))

                let batchOptions = info.batchDownloadOptions
                if batchOptions.count > 1 {
                    HStack(spacing: 8) {
                        Image(systemName: "square.stack.3d.down.right.fill")
                            .foregroundColor(.green)
                            .font(.system(size: 14, weight: .bold))
                        Text("Phát hiện trọn bộ \(batchOptions.count) Story / Video của \(info.author)")
                            .font(.system(size: 12, weight: .bold, design: .rounded))
                            .foregroundColor(.white.opacity(0.95))
                            .lineLimit(2)
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(Color.green.opacity(0.16))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.green.opacity(0.55), lineWidth: 1)
                    )
                }

                // Quality Selector List
                Text("Chọn chất lượng tải về:")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundColor(.white.opacity(0.90))

                VStack(spacing: 8) {
                    ForEach(info.options) { option in
                        let isSelected = selectedOption?.id == option.id
                        Button(action: {
                            selectedOption = option
                        }) {
                            HStack(spacing: 10) {
                                Image(
                                    systemName: isSelected
                                        ? "checkmark.circle.fill"
                                        : "circle"
                                )
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(isSelected ? .cyan : .white.opacity(0.45))

                                Image(
                                    systemName: option.mediaType == .audio
                                        ? "waveform.circle.fill"
                                        : (option.mediaType == .image ? "photo.fill" : "video.fill")
                                )
                                .foregroundColor(.cyan)
                                .font(.system(size: 14))

                                Text(option.qualityLabel)
                                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                                    .foregroundColor(.white)
                                    .lineLimit(1)

                                Spacer()

                                if option.audioUrl != nil {
                                    LiquidGlassBadge(
                                        text: "+ Âm thanh gốc",
                                        systemImage: "speaker.wave.2.fill",
                                        color: .purple
                                    )
                                }

                                if option.isHighestQuality {
                                    LiquidGlassBadge(
                                        text: "TỐT NHẤT",
                                        systemImage: "crown.fill",
                                        color: .green
                                    )
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(
                                        isSelected
                                            ? Color.cyan.opacity(0.22)
                                            : Color.white.opacity(0.06)
                                    )
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(
                                        isSelected
                                            ? Color.cyan.opacity(0.85)
                                            : Color.white.opacity(0.14),
                                        lineWidth: isSelected ? 1.3 : 1.0
                                    )
                            )
                        }
                        .buttonStyle(LiquidSpringButtonStyle(scaleAmount: 0.98))
                    }
                }

                // Download & Share Action Buttons
                let chosen = selectedOption ?? info.bestOption
                if let activeOption = chosen {
                    HStack(spacing: 10) {
                        LiquidGlassPrimaryButton(
                            title: downloadManager.isDownloading
                                ? "Đang tải về máy..."
                                : "Tải Ngay & Lưu vào Ảnh",
                            systemImage: "arrow.down.to.line.compact",
                            isDisabled: downloadManager.isDownloading,
                            isLoading: downloadManager.isDownloading
                        ) {
                            triggerDownload(info: info, option: activeOption)
                        }

                        if let streamUrl = URL(string: activeOption.url), activeOption.audioUrl == nil {
                            LiquidGlassPillButton(
                                title: "Xem thử",
                                systemImage: "play.fill",
                                tintColor: .cyan
                            ) {
                                activeVideoTarget = PlayableVideoTarget(
                                    url: streamUrl,
                                    title: info.title
                                )
                            }
                        }
                    }
                }

                if batchOptions.count > 1 {
                    LiquidGlassPrimaryButton(
                        title: downloadManager.isDownloading
                            ? "Đang tải trọn bộ \(batchOptions.count) mục..."
                            : "Tải tất cả \(batchOptions.count) Story / Video về Ảnh (Full HD)",
                        systemImage: "square.stack.3d.down.right.fill",
                        isDisabled: downloadManager.isDownloading,
                        isLoading: downloadManager.isDownloading,
                        gradientColors: [
                            Color(red: 0.10, green: 0.78, blue: 0.56),
                            Color(red: 0.14, green: 0.65, blue: 0.92),
                            Color(red: 0.38, green: 0.46, blue: 0.96)
                        ]
                    ) {
                        triggerBatchDownload(info: info, options: batchOptions)
                    }
                }
            }
        }
    }

    private func thumbnailPlaceholder(platform: SocialPlatform) -> some View {
        ZStack {
            LinearGradient(
                colors: [
                    platform.accentColor.opacity(0.45),
                    Color(red: 0.08, green: 0.12, blue: 0.24)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: platform.sfSymbolName)
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.white.opacity(0.85))
        }
    }

    // MARK: - 6. Downloaded History Section

    private var downloadedHistoryCard: some View {
        LiquidGlassCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Image(systemName: "tray.and.arrow.down.fill")
                        .foregroundColor(.cyan)
                    Text("Lịch sử Tải xuống (\(downloadManager.downloadedItems.count))")
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Spacer()
                }

                if downloadManager.downloadedItems.isEmpty {
                    Text("Chưa có video nào được tải. Các video tải về sẽ tự động lưu vào Thư viện Ảnh (Photos) và hiển thị tại đây.")
                        .font(.system(size: 12))
                        .foregroundColor(.white.opacity(0.60))
                        .padding(.vertical, 8)
                } else {
                    VStack(spacing: 10) {
                        ForEach(downloadManager.downloadedItems) { item in
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(item.title)
                                            .font(.system(size: 13, weight: .bold, design: .rounded))
                                            .foregroundColor(.white)
                                            .lineLimit(1)

                                        HStack(spacing: 6) {
                                            LiquidGlassBadge(
                                                text: item.platformName,
                                                color: .cyan
                                            )
                                            LiquidGlassBadge(
                                                text: item.qualityLabel,
                                                color: .purple
                                            )
                                            Text(item.formattedSize)
                                                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                                                .foregroundColor(.white.opacity(0.72))

                                            if item.savedToPhotos {
                                                LiquidGlassBadge(
                                                    text: "Đã lưu vào Ảnh",
                                                    systemImage: "photo.badge.checkmark",
                                                    color: .green
                                                )
                                            }
                                        }
                                    }
                                    Spacer()
                                }

                                HStack(spacing: 8) {
                                    LiquidGlassPillButton(
                                        title: "Phát",
                                        systemImage: "play.circle.fill",
                                        tintColor: .cyan
                                    ) {
                                        activeVideoTarget = PlayableVideoTarget(
                                            url: item.fileURL,
                                            title: item.title
                                        )
                                    }

                                    LiquidGlassPillButton(
                                        title: "Chia sẻ / Lưu Tệp",
                                        systemImage: "square.and.arrow.up",
                                        tintColor: .green
                                    ) {
                                        activeShareTarget = SharePayloadTarget(items: [item.fileURL])
                                    }

                                    Spacer()

                                    LiquidGlassPillButton(
                                        title: "Xóa",
                                        systemImage: "trash",
                                        tintColor: .pink
                                    ) {
                                        downloadManager.deleteItem(item)
                                    }
                                }
                            }
                            .padding(12)
                            .background(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(Color.white.opacity(0.05))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
                            )
                        }
                    }
                }
            }
        }
    }

    // MARK: - Actions

    private func startExtraction() {
        let trimmed = urlInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isExtracting else { return }

        extractionError = nil
        statusBanner = nil
        isExtracting = true

        Task {
            do {
                let info = try await MultiPlatformExtractor.shared.extract(rawInput: trimmed)
                await MainActor.run {
                    applyExtractedInfo(info)
                    self.isExtracting = false
                }
            } catch {
                await MainActor.run {
                    self.extractionError = error.localizedDescription
                    self.isExtracting = false
                }
            }
        }
    }

    private func applyExtractedInfo(_ info: ExtractedMediaInfo) {
        self.extractedInfo = info
        self.selectedOption = info.bestOption
        self.selectedPlatform = info.platform != .unknown ? info.platform : self.selectedPlatform
        self.extractionError = nil
    }

    private func triggerDownload(info: ExtractedMediaInfo, option: MediaOption) {
        extractionError = nil
        statusBanner = nil

        Task {
            do {
                let item = try await downloadManager.startDownload(mediaInfo: info, option: option)
                await MainActor.run {
                    self.statusBanner = item.savedToPhotos
                        ? "Đã tải xong \(item.qualityLabel) & lưu vào Thư viện Ảnh!"
                        : "Đã tải xong \(item.fileName)! Nhấn 'Chia sẻ / Lưu Tệp' để lưu."
                }
            } catch {
                await MainActor.run {
                    self.extractionError = error.localizedDescription
                }
            }
        }
    }

    private func triggerBatchDownload(info: ExtractedMediaInfo, options: [MediaOption]) {
        extractionError = nil
        statusBanner = nil

        Task {
            do {
                let items = try await downloadManager.startDownloadBatch(mediaInfo: info, options: options)
                await MainActor.run {
                    self.statusBanner = "Đã tải trọn bộ \(items.count)/\(options.count) Story / Video & lưu vào Thư viện Ảnh!"
                }
            } catch {
                await MainActor.run {
                    self.extractionError = error.localizedDescription
                }
            }
        }
    }
}
