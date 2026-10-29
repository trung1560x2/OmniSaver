import SwiftUI
import UIKit

// MARK: - Settings & Cookie Manager Sheet

struct SettingsSheetView: View {
    @ObservedObject private var cookieStore = AuthCookieStore.shared
    let onOpenWebLogin: (SocialPlatform) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedPlatform: SocialPlatform = .facebook
    @State private var cookieInput: String = ""
    @State private var customCobaltUrl: String = ""
    @State private var statusBanner: String? = nil

    var body: some View {
        ZStack {
            LiquidGlassBackgroundView()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    // Header
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Image(systemName: "key.shield.fill")
                                    .foregroundColor(.cyan)
                                Text("Quản lý Cookie & Máy chủ")
                                    .font(.system(size: 18, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                            }
                            Text("Đăng nhập hoặc dán Cookie để tải Video Kín, Story, Nhóm & 1080p")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.white.opacity(0.68))
                        }
                        Spacer()
                        LiquidGlassPillButton(
                            title: "Đóng",
                            systemImage: "xmark",
                            tintColor: .pink
                        ) {
                            dismiss()
                        }
                    }

                    if let banner = statusBanner {
                        HStack(spacing: 8) {
                            Image(systemName: "checkmark.seal.fill")
                                .foregroundColor(.green)
                            Text(banner)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(.white)
                            Spacer()
                        }
                        .padding(12)
                        .background(Color.green.opacity(0.18))
                        .cornerRadius(12)
                    }

                    // Platform Cookie Status & Editor Card
                    LiquidGlassCard {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("1. Chọn Nền tảng Mạng xã hội")
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundColor(.white)

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(SocialPlatform.supportedPlatforms) { platform in
                                        let hasCookie = cookieStore.hasCookie(for: platform)
                                        LiquidGlassPillButton(
                                            title: platform.shortName,
                                            systemImage: platform.sfSymbolName,
                                            isSelected: selectedPlatform == platform,
                                            statusDotColor: hasCookie ? .green : .orange.opacity(0.6),
                                            tintColor: platform.accentColor
                                        ) {
                                            selectedPlatform = platform
                                            cookieInput = cookieStore.getCookie(for: platform)
                                        }
                                    }
                                }
                            }

                            HStack {
                                let active = cookieStore.hasCookie(for: selectedPlatform)
                                Circle()
                                    .fill(active ? Color.green : Color.orange)
                                    .frame(width: 9, height: 9)
                                Text(
                                    active
                                        ? "Đã có Cookie \(selectedPlatform.displayName) (Sẵn sàng tải Video Kín & Full HD)"
                                        : "Chưa có Cookie \(selectedPlatform.displayName)"
                                )
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(active ? .green : .orange)
                                Spacer()
                            }

                            // 1-Tap WebView Login button
                            LiquidGlassPrimaryButton(
                                title: "Đăng nhập \(selectedPlatform.shortName) bằng WebView (Tự lấy Cookie)",
                                systemImage: "safari.fill"
                            ) {
                                let targetPlatform = selectedPlatform
                                dismiss()
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                    onOpenWebLogin(targetPlatform)
                                }
                            }

                            Divider().background(Color.white.opacity(0.15))

                            Text("Hoặc dán Cookie thủ công cho \(selectedPlatform.displayName):")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(.white.opacity(0.8))

                            TextEditor(text: $cookieInput)
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(.white)
                                .scrollContentBackground(.hidden)
                                .frame(minHeight: 88, maxHeight: 120)
                                .padding(10)
                                .background(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(Color.black.opacity(0.40))
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
                                )

                            HStack(spacing: 8) {
                                LiquidGlassPillButton(
                                    title: "Dán",
                                    systemImage: "doc.on.clipboard",
                                    tintColor: .cyan
                                ) {
                                    if let clip = UIPasteboard.general.string {
                                        cookieInput = clip
                                    }
                                }

                                LiquidGlassPillButton(
                                    title: "Lưu Cookie",
                                    systemImage: "checkmark.circle.fill",
                                    tintColor: .green
                                ) {
                                    cookieStore.saveCookie(cookieInput, for: selectedPlatform)
                                    statusBanner = "Đã lưu Cookie cho \(selectedPlatform.displayName)!"
                                }

                                LiquidGlassPillButton(
                                    title: "Xóa",
                                    systemImage: "trash",
                                    tintColor: .pink
                                ) {
                                    cookieInput = ""
                                    cookieStore.clearCookie(for: selectedPlatform)
                                    statusBanner = "Đã xóa Cookie \(selectedPlatform.displayName)."
                                }
                            }
                        }
                    }

                    // Custom Cobalt API Instance Card
                    LiquidGlassCard {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack(spacing: 6) {
                                Image(systemName: "server.rack")
                                    .foregroundColor(.purple)
                                Text("2. Máy chủ Cobalt API Tùy chỉnh (Tùy chọn)")
                                    .font(.system(size: 14, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                            }

                            Text("Mặc định ứng dụng tự động dùng GraphQL/Direct Parser + danh sách Cobalt dự phòng. Bạn có thể thêm máy chủ riêng nếu muốn:")
                                .font(.system(size: 12))
                                .foregroundColor(.white.opacity(0.68))

                            TextField("https://cobalt.example.com", text: $customCobaltUrl)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(.white)
                                .textInputAutocapitalization(.never)
                                .disableAutocorrection(true)
                                .padding(12)
                                .background(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .fill(Color.black.opacity(0.38))
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
                                )

                            HStack(spacing: 10) {
                                LiquidGlassPillButton(
                                    title: "Lưu Máy chủ",
                                    systemImage: "externaldrive.badge.checkmark",
                                    tintColor: .cyan
                                ) {
                                    cookieStore.setCustomCobaltUrl(customCobaltUrl)
                                    statusBanner = "Đã cập nhật địa chỉ Cobalt API tùy chỉnh!"
                                }

                                LiquidGlassPillButton(
                                    title: "Xóa toàn bộ Cookie",
                                    systemImage: "trash.slash.fill",
                                    tintColor: .red
                                ) {
                                    cookieStore.clearAllCookies()
                                    cookieInput = ""
                                    statusBanner = "Đã xóa toàn bộ Cookie trên thiết bị."
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
        }
        .onAppear {
            cookieInput = cookieStore.getCookie(for: selectedPlatform)
            customCobaltUrl = cookieStore.getCustomCobaltUrl()
        }
    }
}

// MARK: - GitHub OTA Update Sheet View (iOS .ipa / AltStore / SideStore / TrollStore)

struct OtaUpdateSheetView: View {
    @ObservedObject private var otaManager = OtaUpdateManager.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var manifestUrlInput: String = ""
    @State private var isChecking: Bool = false
    @State private var checkResult: OtaCheckResult? = nil
    @State private var statusBanner: String? = nil

    var body: some View {
        ZStack {
            LiquidGlassBackgroundView()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    // Header
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                                    .foregroundColor(.cyan)
                                Text("Cập nhật OTA Trực tuyến (iOS)")
                                    .font(.system(size: 18, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                            }
                            Text("Phiên bản hiện tại: v\(OtaUpdateManager.currentVersionName) • Build \(OtaUpdateManager.currentVersionCode)")
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                .foregroundColor(.cyan)
                        }
                        Spacer()
                        LiquidGlassPillButton(
                            title: "Đóng",
                            systemImage: "xmark",
                            tintColor: .pink
                        ) {
                            dismiss()
                        }
                    }

                    // Check for Updates Card
                    LiquidGlassCard {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Kiểm tra bản phát hành mới từ GitHub")
                                        .font(.system(size: 14, weight: .bold, design: .rounded))
                                        .foregroundColor(.white)
                                    Text("Hỗ trợ tải trực tiếp .IPA hoặc cài nhanh qua AltStore, SideStore, TrollStore")
                                        .font(.system(size: 12))
                                        .foregroundColor(.white.opacity(0.68))
                                }
                                Spacer()
                            }

                            LiquidGlassPrimaryButton(
                                title: isChecking ? "Đang kiểm tra GitHub OTA..." : "Kiểm tra Cập nhật Ngay",
                                systemImage: "icloud.and.arrow.down.fill",
                                isLoading: isChecking
                            ) {
                                runOtaCheck()
                            }

                            if let result = checkResult {
                                Divider().background(Color.white.opacity(0.15))

                                if let err = result.errorMessage {
                                    HStack(spacing: 8) {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundColor(.orange)
                                        Text(err)
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundColor(.orange)
                                    }
                                } else if let release = result.latestRelease {
                                    VStack(alignment: .leading, spacing: 10) {
                                        HStack {
                                            LiquidGlassBadge(
                                                text: result.hasUpdate ? "CÓ BẢN MỚI" : "ĐÃ MỚI NHẤT",
                                                systemImage: result.hasUpdate ? "sparkles" : "checkmark.seal.fill",
                                                color: result.hasUpdate ? .green : .cyan
                                            )
                                            Text("v\(release.versionName) (Build \(release.versionCode))")
                                                .font(.system(size: 15, weight: .bold, design: .rounded))
                                                .foregroundColor(.white)
                                            Spacer()
                                        }

                                        if !release.publishedAt.isEmpty {
                                            Text("Ngày phát hành: \(release.publishedAt)")
                                                .font(.system(size: 11, weight: .medium))
                                                .foregroundColor(.white.opacity(0.65))
                                        }

                                        Text("Nội dung cập nhật:")
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(.cyan)

                                        Text(release.changelog)
                                            .font(.system(size: 12))
                                            .foregroundColor(.white.opacity(0.88))
                                            .padding(10)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .background(Color.black.opacity(0.32))
                                            .cornerRadius(12)

                                        if !release.sha256.isEmpty {
                                            Text("SHA-256: \(release.sha256)")
                                                .font(.system(size: 10, design: .monospaced))
                                                .foregroundColor(.white.opacity(0.55))
                                                .lineLimit(1)
                                                .truncationMode(.middle)
                                        }

                                        // Direct .IPA & Sideload Install Actions
                                        VStack(spacing: 8) {
                                            LiquidGlassPrimaryButton(
                                                title: "Tải trực tiếp file .IPA (Sideloadly / ESign / Feather)",
                                                systemImage: "arrow.down.circle.fill"
                                            ) {
                                                let ipaUrlStr = otaManager.ipaDownloadUrl(from: release)
                                                if let url = URL(string: ipaUrlStr) {
                                                    openURL(url)
                                                }
                                            }

                                            HStack(spacing: 8) {
                                                if let altUrl = otaManager.altStoreInstallUrl(from: release) {
                                                    LiquidGlassPillButton(
                                                        title: "AltStore",
                                                        systemImage: "iphone.badge.play",
                                                        tintColor: .cyan
                                                    ) {
                                                        openURL(altUrl)
                                                    }
                                                }

                                                if let sideUrl = otaManager.sideStoreInstallUrl(from: release) {
                                                    LiquidGlassPillButton(
                                                        title: "SideStore",
                                                        systemImage: "bolt.horizontal.circle.fill",
                                                        tintColor: .purple
                                                    ) {
                                                        openURL(sideUrl)
                                                    }
                                                }

                                                if let trollUrl = otaManager.trollStoreInstallUrl(from: release) {
                                                    LiquidGlassPillButton(
                                                        title: "TrollStore",
                                                        systemImage: "hammer.circle.fill",
                                                        tintColor: .green
                                                    ) {
                                                        openURL(trollUrl)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Custom OTA Manifest URL Configuration Card
                    LiquidGlassCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Nguồn Manifest OTA (JSON)")
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundColor(.white)

                            TextField("https://raw.githubusercontent.com/.../version.json", text: $manifestUrlInput)
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(.white)
                                .textInputAutocapitalization(.never)
                                .disableAutocorrection(true)
                                .padding(10)
                                .background(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .fill(Color.black.opacity(0.38))
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
                                )

                            HStack(spacing: 10) {
                                LiquidGlassPillButton(
                                    title: "Lưu nguồn OTA",
                                    systemImage: "checkmark.circle.fill",
                                    tintColor: .cyan
                                ) {
                                    otaManager.setManifestUrl(manifestUrlInput)
                                    statusBanner = "Đã lưu địa chỉ Manifest OTA!"
                                    runOtaCheck()
                                }

                                if let banner = statusBanner {
                                    Text(banner)
                                        .font(.system(size: 11, weight: .medium))
                                        .foregroundColor(.green)
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
        }
        .onAppear {
            manifestUrlInput = otaManager.getManifestUrl()
            runOtaCheck()
        }
    }

    private func runOtaCheck() {
        isChecking = true
        Task {
            let result = await otaManager.checkForUpdates()
            await MainActor.run {
                self.checkResult = result
                self.isChecking = false
            }
        }
    }
}
