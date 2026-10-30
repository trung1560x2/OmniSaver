import Foundation

enum SocialPlatform: String, CaseIterable, Codable, Identifiable {
    case facebook = "FACEBOOK"
    case instagram = "INSTAGRAM"
    case tiktok = "TIKTOK"
    case xTwitter = "X_TWITTER"
    case threads = "THREADS"
    case unknown = "UNKNOWN"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .facebook: return "Facebook"
        case .instagram: return "Instagram"
        case .tiktok: return "TikTok"
        case .xTwitter: return "X (Twitter)"
        case .threads: return "Threads"
        case .unknown: return "Web Media"
        }
    }

    var shortName: String {
        switch self {
        case .facebook: return "Facebook"
        case .instagram: return "Instagram"
        case .tiktok: return "TikTok"
        case .xTwitter: return "X"
        case .threads: return "Threads"
        case .unknown: return "Web"
        }
    }

    var loginUrl: String {
        switch self {
        case .facebook: return "https://m.facebook.com/"
        case .instagram: return "https://www.instagram.com/accounts/login/"
        case .tiktok: return "https://www.tiktok.com/login"
        case .xTwitter: return "https://x.com/i/flow/login"
        case .threads: return "https://www.threads.net/login"
        case .unknown: return "https://www.facebook.com/"
        }
    }

    var cookieDomainKeywords: [String] {
        switch self {
        case .facebook: return ["facebook.com", "fb.com"]
        case .instagram: return ["instagram.com"]
        case .tiktok: return ["tiktok.com"]
        case .xTwitter: return ["x.com", "twitter.com"]
        case .threads: return ["threads.net", "threads.com", "instagram.com"]
        case .unknown: return []
        }
    }

    static var supportedPlatforms: [SocialPlatform] {
        [.facebook, .tiktok, .instagram, .xTwitter, .threads]
    }
}

enum MediaType: String, Codable, Hashable {
    case video = "VIDEO"
    case audio = "AUDIO"
    case image = "IMAGE"
}

struct MediaOption: Identifiable, Hashable, Codable {
    var id: String { "\(url)|\(qualityLabel)|\(audioUrl ?? "")" }
    let url: String
    let qualityLabel: String
    let fileExtension: String
    let mediaType: MediaType
    let isHighestQuality: Bool
    let isNoWatermark: Bool
    let customHeaders: [String: String]
    let audioUrl: String?

    init(
        url: String,
        qualityLabel: String,
        fileExtension: String = "mp4",
        mediaType: MediaType = .video,
        isHighestQuality: Bool = false,
        isNoWatermark: Bool = true,
        customHeaders: [String: String] = [:],
        audioUrl: String? = nil
    ) {
        self.url = url
        self.qualityLabel = qualityLabel
        self.fileExtension = fileExtension
        self.mediaType = mediaType
        self.isHighestQuality = isHighestQuality
        self.isNoWatermark = isNoWatermark
        self.customHeaders = customHeaders
        self.audioUrl = audioUrl
    }
}

struct ExtractedMediaInfo: Identifiable, Hashable {
    let id = UUID()
    let originalUrl: String
    let platform: SocialPlatform
    let title: String
    let author: String
    let thumbnailUrl: String
    let durationSeconds: Int
    let options: [MediaOption]

    var formattedDuration: String {
        guard durationSeconds > 0 else { return "HD" }
        let mins = durationSeconds / 60
        let secs = durationSeconds % 60
        return String(format: "%02d:%02d", mins, secs)
    }

    var bestOption: MediaOption? {
        options.first(where: { $0.isHighestQuality }) ?? options.first
    }

    var batchDownloadOptions: [MediaOption] {
        let pattern = #"(?:Story|Slide|Video|Ảnh(?:\s*gốc)?(?:\s*HD)?)\s*#(\d+)"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return []
        }
        var bestByIndex: [Int: MediaOption] = [:]
        for option in options {
            let label = option.qualityLabel
            let nsRange = NSRange(label.startIndex..<label.endIndex, in: label)
            guard let match = regex.firstMatch(in: label, options: [], range: nsRange),
                  match.numberOfRanges >= 2,
                  let idxRange = Range(match.range(at: 1), in: label),
                  let idx = Int(label[idxRange]) else {
                continue
            }
            if bestByIndex[idx] == nil {
                bestByIndex[idx] = option
            }
        }
        guard bestByIndex.count >= 2 else { return [] }
        return bestByIndex.keys.sorted().compactMap { bestByIndex[$0] }
    }
}

struct DownloadedItem: Identifiable, Codable, Hashable {
    let id: String
    let title: String
    let platformName: String
    let qualityLabel: String
    let fileName: String
    let localRelativePath: String
    let fileSizeBytes: Int64
    let createdAt: Date
    let savedToPhotos: Bool
    let mediaType: MediaType

    var fileURL: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent(localRelativePath)
    }

    var formattedSize: String {
        let mb = Double(fileSizeBytes) / (1024.0 * 1024.0)
        if mb >= 1.0 {
            return String(format: "%.2f MB", mb)
        } else {
            let kb = max(1.0, Double(fileSizeBytes) / 1024.0)
            return String(format: "%.0f KB", kb)
        }
    }
}

enum UrlDetector {
    static func extractFirstUrl(_ input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let pattern = #"https?://[^\s"'<>]+"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return trimmed.hasPrefix("http") ? trimmed : nil
        }
        let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        if let match = regex.firstMatch(in: trimmed, options: [], range: range),
           let swiftRange = Range(match.range, in: trimmed) {
            var candidate = String(trimmed[swiftRange])
            while let last = candidate.last, ".,;!)']".contains(last) {
                candidate.removeLast()
            }
            return candidate
        }
        return trimmed.hasPrefix("http") ? trimmed : nil
    }

    static func cleanUrl(_ rawUrl: String) -> String {
        let extracted = extractFirstUrl(rawUrl) ?? rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: extracted) else {
            return extracted
        }
        let trackingParams: Set<String> = [
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "igshid", "igsh", "fbclid", "s", "t", "_r", "is_from_webapp", "sender_device"
        ]
        if let queryItems = components.queryItems, !queryItems.isEmpty {
            let filtered = queryItems.filter { !trackingParams.contains($0.name.lowercased()) }
            components.queryItems = filtered.isEmpty ? nil : filtered
        }
        return components.url?.absoluteString ?? extracted
    }

    static func detectPlatform(_ url: String) -> SocialPlatform {
        let lower = url.lowercased()
        if lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") {
            return .facebook
        }
        if lower.contains("instagram.com") || lower.contains("instagr.am") {
            return .instagram
        }
        if lower.contains("tiktok.com") || lower.contains("douyin.com") {
            return .tiktok
        }
        if lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("t.co/") {
            return .xTwitter
        }
        if lower.contains("threads.net") || lower.contains("threads.com") {
            return .threads
        }
        return .unknown
    }
}
