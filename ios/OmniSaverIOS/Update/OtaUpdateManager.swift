import Foundation

struct OtaReleaseInfo: Codable, Equatable, Identifiable {
    var id: String { "\(versionCode)-\(versionName)" }

    let versionCode: Int
    let versionName: String
    let apkUrl: String
    let ipaUrl: String?
    let changelog: String
    let publishedAt: String
    let fileSizeBytes: Int64
    let sha256: String
    let forceUpdate: Bool

    init(
        versionCode: Int,
        versionName: String,
        apkUrl: String,
        ipaUrl: String? = nil,
        changelog: String,
        publishedAt: String = "",
        fileSizeBytes: Int64 = -1,
        sha256: String = "",
        forceUpdate: Bool = false
    ) {
        self.versionCode = versionCode
        self.versionName = versionName
        self.apkUrl = apkUrl
        self.ipaUrl = ipaUrl
        self.changelog = changelog
        self.publishedAt = publishedAt
        self.fileSizeBytes = fileSizeBytes
        self.sha256 = sha256
        self.forceUpdate = forceUpdate
    }

    enum CodingKeys: String, CodingKey {
        case versionCode
        case versionName
        case apkUrl
        case ipaUrl
        case changelog
        case publishedAt
        case fileSizeBytes
        case sha256
        case forceUpdate
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.versionCode = (try? container.decode(Int.self, forKey: .versionCode)) ?? 0
        self.versionName = (try? container.decode(String.self, forKey: .versionName)) ?? "2.5.0-FullHD"
        self.apkUrl = (try? container.decode(String.self, forKey: .apkUrl)) ?? ""
        self.ipaUrl = try? container.decodeIfPresent(String.self, forKey: .ipaUrl)
        self.changelog = (try? container.decode(String.self, forKey: .changelog))
            ?? "Cập nhật cải thiện hiệu năng tải Full HD 1080p và độ ổn định."
        self.publishedAt = (try? container.decode(String.self, forKey: .publishedAt)) ?? ""
        self.fileSizeBytes = (try? container.decode(Int64.self, forKey: .fileSizeBytes)) ?? -1
        self.sha256 = (try? container.decode(String.self, forKey: .sha256)) ?? ""
        self.forceUpdate = (try? container.decode(Bool.self, forKey: .forceUpdate)) ?? false
    }
}

struct OtaCheckResult: Equatable {
    let hasUpdate: Bool
    let latestRelease: OtaReleaseInfo?
    let errorMessage: String?

    init(hasUpdate: Bool, latestRelease: OtaReleaseInfo?, errorMessage: String? = nil) {
        self.hasUpdate = hasUpdate
        self.latestRelease = latestRelease
        self.errorMessage = errorMessage
    }
}

@MainActor
final class OtaUpdateManager: ObservableObject {
    static let shared = OtaUpdateManager()

    static let currentVersionCode: Int = 8
    static let currentVersionName: String = "2.5.0-FullHD"

    static let defaultManifestUrl =
        "https://raw.githubusercontent.com/trung1560x2/OmniSaver/main/ota-version.json"
    static let fallbackGitHubReleasesApi =
        "https://api.github.com/repos/trung1560x2/OmniSaver/releases/latest"
    static let fallbackIpaDownloadUrl =
        "https://github.com/trung1560x2/OmniSaver/releases/latest/download/OmniSaver_iOS27_LiquidGlass_v2.5_FullHD.ipa"

    private let defaults = UserDefaults.standard
    private let manifestUrlKey = "omnisaver_ios_ota_manifest_url"
    private let lastCheckKey = "omnisaver_ios_ota_last_check_ts"

    @Published var lastCheckResult: OtaCheckResult? = nil
    @Published var isDownloadingIpa: Bool = false
    @Published var ipaDownloadProgress: Double = 0.0

    private init() {}

    // MARK: - Manifest Endpoint Configuration

    func getManifestUrl() -> String {
        let saved = defaults.string(forKey: manifestUrlKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return saved.isEmpty ? Self.defaultManifestUrl : saved
    }

    func setManifestUrl(_ url: String) {
        let cleaned = url.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(cleaned.isEmpty ? Self.defaultManifestUrl : cleaned, forKey: manifestUrlKey)
    }

    // MARK: - Check for Updates

    func checkForUpdates(customUrlOverride: String? = nil) async -> OtaCheckResult {
        let rawEndpoint = (customUrlOverride ?? getManifestUrl()).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rawEndpoint.isEmpty else {
            let result = OtaCheckResult(
                hasUpdate: false,
                latestRelease: nil,
                errorMessage: "Chưa cấu hình đường dẫn Manifest OTA."
            )
            lastCheckResult = result
            return result
        }

        let normalizedEndpoint = normalizeEndpointUrl(rawEndpoint)

        // Direct .ipa or .apk link
        let pathOnly = normalizedEndpoint.components(separatedBy: "?").first?.lowercased() ?? ""
        if pathOnly.hasSuffix(".ipa") || pathOnly.hasSuffix(".apk") {
            let directRelease = OtaReleaseInfo(
                versionCode: Self.currentVersionCode + 1,
                versionName: "Direct-IPA",
                apkUrl: normalizedEndpoint,
                ipaUrl: pathOnly.hasSuffix(".ipa") ? normalizedEndpoint : nil,
                changelog: "Cài đặt trực tiếp gói ứng dụng từ đường dẫn chỉ định:\n\(normalizedEndpoint)",
                publishedAt: "Trực tiếp"
            )
            let result = OtaCheckResult(hasUpdate: true, latestRelease: directRelease, errorMessage: nil)
            lastCheckResult = result
            return result
        }

        do {
            let release = try await fetchReleaseInfo(from: normalizedEndpoint)
            let hasUpdate = isReleaseNewer(release)
            defaults.set(Date().timeIntervalSince1970, forKey: lastCheckKey)
            let result = OtaCheckResult(hasUpdate: hasUpdate, latestRelease: release, errorMessage: nil)
            lastCheckResult = result
            return result
        } catch {
            // If default manifest failed, try GitHub Releases API fallback
            if normalizedEndpoint == Self.defaultManifestUrl {
                if let ghRelease = try? await fetchReleaseInfo(from: Self.fallbackGitHubReleasesApi) {
                    let hasUpdate = isReleaseNewer(ghRelease)
                    let result = OtaCheckResult(hasUpdate: hasUpdate, latestRelease: ghRelease, errorMessage: nil)
                    lastCheckResult = result
                    return result
                }
            }
            let result = OtaCheckResult(
                hasUpdate: false,
                latestRelease: nil,
                errorMessage: "Không thể kiểm tra OTA: \(error.localizedDescription)"
            )
            lastCheckResult = result
            return result
        }
    }

    // MARK: - iOS .IPA & 1-Tap Sideload URL Helpers

    func ipaDownloadUrl(from release: OtaReleaseInfo) -> String {
        if let explicitIpa = release.ipaUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
           !explicitIpa.isEmpty {
            return explicitIpa
        }

        let trimmedApk = release.apkUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedApk.lowercased().hasSuffix(".ipa") {
            return trimmedApk
        }
        if trimmedApk.lowercased().hasSuffix(".apk") {
            let prefix = trimmedApk.dropLast(4)
            return "\(prefix).ipa"
        }

        return Self.fallbackIpaDownloadUrl
    }

    func directIpaUrl(from release: OtaReleaseInfo) -> URL? {
        URL(string: ipaDownloadUrl(from: release))
    }

    func altStoreInstallUrl(from release: OtaReleaseInfo) -> URL? {
        let ipa = ipaDownloadUrl(from: release)
        guard let encoded = ipa.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return nil
        }
        return URL(string: "altstore://install?url=\(encoded)")
    }

    func sideStoreInstallUrl(from release: OtaReleaseInfo) -> URL? {
        let ipa = ipaDownloadUrl(from: release)
        guard let encoded = ipa.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return nil
        }
        return URL(string: "sidestore://install?url=\(encoded)")
    }

    func trollStoreInstallUrl(from release: OtaReleaseInfo) -> URL? {
        let ipa = ipaDownloadUrl(from: release)
        guard let encoded = ipa.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return nil
        }
        return URL(string: "apple-magnifier://install?url=\(encoded)")
    }

    /// Downloads the `.ipa` package directly into `Documents/OmniSaver/` so the user can share/install via Files, TrollStore, ESign, or Feather.
    func downloadIpaToFiles(from release: OtaReleaseInfo) async throws -> URL {
        guard let url = directIpaUrl(from: release) else {
            throw NSError(
                domain: "OtaUpdateManager",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Đường dẫn tải .IPA không hợp lệ."]
            )
        }

        isDownloadingIpa = true
        ipaDownloadProgress = 0.05
        defer {
            isDownloadingIpa = false
        }

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 120)
        request.setValue("OmniSaver-iOS-OTA/\(Self.currentVersionName)", forHTTPHeaderField: "User-Agent")

        let (tempURL, response) = try await URLSession.shared.download(for: request)
        if let httpResp = response as? HTTPURLResponse, !(200...299).contains(httpResp.statusCode) {
            throw NSError(
                domain: "OtaUpdateManager",
                code: httpResp.statusCode,
                userInfo: [NSLocalizedDescriptionKey: "Máy chủ trả về mã lỗi HTTP \(httpResp.statusCode) khi tải .IPA."]
            )
        }

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let targetDir = docs.appendingPathComponent("OmniSaver", isDirectory: true)
        try? FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)

        let safeVer = release.versionName.replacingOccurrences(
            of: #"[^A-Za-z0-9._-]+"#,
            with: "_",
            options: .regularExpression
        )
        let targetFile = targetDir.appendingPathComponent("OmniSaver_iOS_\(safeVer).ipa")
        try? FileManager.default.removeItem(at: targetFile)
        try FileManager.default.moveItem(at: tempURL, to: targetFile)
        ipaDownloadProgress = 1.0
        return targetFile
    }

    // MARK: - Internal Parsing & Version Comparison

    private func normalizeEndpointUrl(_ rawUrl: String) -> String {
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let pattern = #"^https?://(?:www\.)?github\.com/([^/\s]+)/([^/\s#?]+?)(?:\.git)?(?:/releases(?:/latest)?)?/?$"#
        if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) {
            let nsRange = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
            if let match = regex.firstMatch(in: trimmed, options: [], range: nsRange),
               match.numberOfRanges >= 3,
               let ownerRange = Range(match.range(at: 1), in: trimmed),
               let repoRange = Range(match.range(at: 2), in: trimmed) {
                let owner = String(trimmed[ownerRange])
                let repo = String(trimmed[repoRange])
                return "https://api.github.com/repos/\(owner)/\(repo)/releases/latest"
            }
        }
        return trimmed
    }

    private func fetchReleaseInfo(from endpoint: String) async throws -> OtaReleaseInfo {
        guard let url = URL(string: endpoint) else {
            throw NSError(
                domain: "OtaUpdateManager",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "URL máy chủ OTA không hợp lệ."]
            )
        }

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        request.setValue("OmniSaver-iOS-OTA/\(Self.currentVersionName)", forHTTPHeaderField: "User-Agent")
        if endpoint.contains("api.github.com/repos/") {
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        } else {
            request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
            throw NSError(
                domain: "OtaUpdateManager",
                code: httpResponse.statusCode,
                userInfo: [NSLocalizedDescriptionKey: "Máy chủ OTA trả về HTTP \(httpResponse.statusCode)."]
            )
        }

        if endpoint.contains("api.github.com/repos/") {
            return try parseGitHubReleaseData(data)
        }

        if let decoded = try? JSONDecoder().decode(OtaReleaseInfo.self, from: data),
           !decoded.apkUrl.isEmpty || !(decoded.ipaUrl ?? "").isEmpty {
            return decoded
        }

        return try parseCustomManifestDictionary(data, baseUrl: endpoint)
    }

    private func parseCustomManifestDictionary(_ data: Data, baseUrl: String) throws -> OtaReleaseInfo {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(
                domain: "OtaUpdateManager",
                code: -3,
                userInfo: [NSLocalizedDescriptionKey: "Phản hồi JSON OTA không đúng định dạng."]
            )
        }

        let remoteCode = (json["versionCode"] as? Int)
            ?? (json["version_code"] as? Int)
            ?? Self.currentVersionCode

        let rawVersionName = (json["versionName"] as? String)
            ?? (json["version_name"] as? String)
            ?? (json["version"] as? String)
            ?? Self.currentVersionName
        let cleanVersionName = rawVersionName
            .replacingOccurrences(of: #"^[vV]"#, with: "", options: .regularExpression)

        var rawApkUrl = ((json["apkUrl"] as? String)
            ?? (json["apk_url"] as? String)
            ?? (json["downloadUrl"] as? String)
            ?? (json["url"] as? String)
            ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        let rawIpaUrl = ((json["ipaUrl"] as? String)
            ?? (json["ipa_url"] as? String))?.trimmingCharacters(in: .whitespacesAndNewlines)

        if !rawApkUrl.isEmpty && !rawApkUrl.hasPrefix("http://") && !rawApkUrl.hasPrefix("https://") {
            if let lastSlash = baseUrl.lastIndex(of: "/") {
                let baseDir = String(baseUrl[..<lastSlash])
                let trimmedRel = rawApkUrl.hasPrefix("/") ? String(rawApkUrl.dropFirst()) : rawApkUrl
                rawApkUrl = "\(baseDir)/\(trimmedRel)"
            }
        }

        let changelog = (json["changelog"] as? String)
            ?? (json["releaseNotes"] as? String)
            ?? (json["notes"] as? String)
            ?? "Bản cập nhật cải thiện hiệu năng tải video Full HD 1080p và âm thanh."

        let publishedAt = (json["publishedAt"] as? String) ?? (json["date"] as? String) ?? ""
        let fileSize = (json["fileSizeBytes"] as? NSNumber)?.int64Value
            ?? (json["size"] as? NSNumber)?.int64Value
            ?? -1
        let sha256 = (json["sha256"] as? String) ?? ""
        let forceUpdate = (json["forceUpdate"] as? Bool) ?? (json["force"] as? Bool) ?? false

        return OtaReleaseInfo(
            versionCode: remoteCode,
            versionName: cleanVersionName,
            apkUrl: rawApkUrl,
            ipaUrl: rawIpaUrl,
            changelog: changelog,
            publishedAt: publishedAt,
            fileSizeBytes: fileSize,
            sha256: sha256,
            forceUpdate: forceUpdate
        )
    }

    private func parseGitHubReleaseData(_ data: Data) throws -> OtaReleaseInfo {
        let rootObject = try JSONSerialization.jsonObject(with: data)
        let releaseDict: [String: Any]
        if let arr = rootObject as? [[String: Any]], let first = arr.first {
            releaseDict = first
        } else if let dict = rootObject as? [String: Any] {
            releaseDict = dict
        } else {
            throw NSError(
                domain: "OtaUpdateManager",
                code: -4,
                userInfo: [NSLocalizedDescriptionKey: "Không đọc được thông tin GitHub Release."]
            )
        }

        let tagName = (releaseDict["tag_name"] as? String)
            ?? (releaseDict["name"] as? String)
            ?? Self.currentVersionName
        let cleanVersionName = tagName
            .replacingOccurrences(of: #"^[vV]"#, with: "", options: .regularExpression)
        let changelog = (releaseDict["body"] as? String)
            ?? "Bản phát hành mới trên GitHub Releases."
        let publishedRaw = (releaseDict["published_at"] as? String) ?? ""
        let publishedAt = String(publishedRaw.prefix(10))

        var foundIpaUrl: String? = nil
        var foundApkUrl: String = ""
        var assetSize: Int64 = -1

        if let assets = releaseDict["assets"] as? [[String: Any]] {
            for asset in assets {
                let name = ((asset["name"] as? String) ?? "").lowercased()
                let downloadUrl = (asset["browser_download_url"] as? String) ?? ""
                let size = (asset["size"] as? NSNumber)?.int64Value ?? -1
                if name.hasSuffix(".ipa") && !downloadUrl.isEmpty {
                    foundIpaUrl = downloadUrl
                    assetSize = size
                } else if name.hasSuffix(".apk") && !downloadUrl.isEmpty && foundApkUrl.isEmpty {
                    foundApkUrl = downloadUrl
                    if assetSize < 0 { assetSize = size }
                }
            }
        }

        if foundApkUrl.isEmpty {
            foundApkUrl = foundIpaUrl ?? Self.fallbackIpaDownloadUrl
        }

        let isNewer = compareVersionStrings(cleanVersionName, Self.currentVersionName) > 0
        let inferredCode = isNewer ? (Self.currentVersionCode + 1) : Self.currentVersionCode

        return OtaReleaseInfo(
            versionCode: inferredCode,
            versionName: cleanVersionName,
            apkUrl: foundApkUrl,
            ipaUrl: foundIpaUrl,
            changelog: changelog,
            publishedAt: publishedAt,
            fileSizeBytes: assetSize,
            sha256: "",
            forceUpdate: false
        )
    }

    private func isReleaseNewer(_ release: OtaReleaseInfo) -> Bool {
        if release.versionCode > Self.currentVersionCode {
            return true
        }
        if release.versionCode < Self.currentVersionCode && release.versionCode > 0 {
            return false
        }
        return compareVersionStrings(release.versionName, Self.currentVersionName) > 0
    }

    func compareVersionStrings(_ v1: String, _ v2: String) -> Int {
        let nums1 = extractVersionNumbers(v1)
        let nums2 = extractVersionNumbers(v2)
        let maxCount = max(nums1.count, nums2.count)
        for i in 0..<maxCount {
            let p1 = i < nums1.count ? nums1[i] : 0
            let p2 = i < nums2.count ? nums2[i] : 0
            if p1 != p2 {
                return p1 > p2 ? 1 : -1
            }
        }
        return 0
    }

    private func extractVersionNumbers(_ version: String) -> [Int] {
        guard let regex = try? NSRegularExpression(pattern: #"\d+"#) else { return [] }
        let range = NSRange(version.startIndex..<version.endIndex, in: version)
        return regex.matches(in: version, range: range).compactMap { match in
            guard let r = Range(match.range, in: version) else { return nil }
            return Int(version[r])
        }
    }
}
