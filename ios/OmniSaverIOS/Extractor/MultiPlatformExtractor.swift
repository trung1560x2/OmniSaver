import Foundation

enum ExtractorError: LocalizedError {
    case invalidUrl(String)
    case extractionFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidUrl(let message), .extractionFailed(let message):
            return message
        }
    }
}

final class MultiPlatformExtractor {
    static let shared = MultiPlatformExtractor()

    private static let desktopChromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private static let mobileSafariUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    private static let instagramAndroidAppUA =
        "Instagram 312.0.0.34.111 Android (34/14; 480dpi; 1080x2400; samsung; SM-S918B; dm3q; qcom; en_US; 559996041)"

    private static let xPublicBearerToken =
        "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
    private static let igWebAppId = "936619743392459"

    private static let defaultRapidApiKey =
        "ca5c6d6fa3mshfcd2b0a0feac6b7p140e57jsn72684628152a"
    private static let rapidApiHost = "auto-download-all-in-one.p.rapidapi.com"
    private static let rapidApiEndpoint =
        "https://auto-download-all-in-one.p.rapidapi.com/v1/social/autolink"

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        config.httpShouldSetCookies = false
        self.session = URLSession(configuration: config)
    }

    // MARK: - Public API

    func extract(rawInput: String) async throws -> ExtractedMediaInfo {
        guard let extractedUrl = UrlDetector.extractFirstUrl(rawInput) else {
            throw ExtractorError.invalidUrl("Không tìm thấy đường dẫn URL hợp lệ trong văn bản.")
        }

        let cleanUrl = UrlDetector.cleanUrl(extractedUrl)
        let initialPlatform = UrlDetector.detectPlatform(cleanUrl)

        var directError: Error?
        var directInfo: ExtractedMediaInfo?
        do {
            directInfo = try await extractDirect(
                originalInput: rawInput,
                cleanUrl: cleanUrl,
                platform: initialPlatform
            )
        } catch {
            directError = error
        }

        if let info = directInfo {
            let hasPrimaryMedia = info.options.contains(where: { $0.mediaType == .video || $0.mediaType == .audio }) ||
                (!looksLikeVideoPost(url: cleanUrl, platform: info.platform) && !info.options.isEmpty)
            if hasPrimaryMedia {
                return info
            }
        }

        if let cobaltInfo = try? await extractViaCobalt(
            cleanUrl: cleanUrl,
            detectedPlatform: initialPlatform,
            originalInput: rawInput
        ), !cobaltInfo.options.isEmpty {
            return cobaltInfo
        }

        var rapidError: Error?
        do {
            let rapidInfo = try await extractViaRapidApi(
                cleanUrl: cleanUrl,
                detectedPlatform: initialPlatform,
                originalInput: rawInput
            )
            if !rapidInfo.options.isEmpty {
                return rapidInfo
            }
        } catch {
            rapidError = error
        }

        if let info = directInfo, !info.options.isEmpty {
            return info
        }

        let errorMessage = rapidError?.localizedDescription
            ?? directError?.localizedDescription
            ?? "Không thể bóc tách video/ảnh từ liên kết này. Nếu đây là video Riêng tư / Nhóm kín / 18+, hãy đăng nhập tài khoản trong mục Trình duyệt & Cookie."
        throw ExtractorError.extractionFailed(errorMessage)
    }

    /// Parses raw HTML and sniffed media URLs captured from the in-app WebView,
    /// and automatically upgrades to 1080p/720p Full HD via Desktop/API extraction using any
    /// `video_id` discovered in the page or inside the `efg=` metadata of sniffed streams.
    func parseHtmlAndSniffedUrls(
        rawHtml: String,
        pageUrl: String,
        pageTitle: String? = nil,
        sniffedUrls: [String] = []
    ) async -> ExtractedMediaInfo? {
        let effectivePageUrl = pageUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "https://www.facebook.com/"
            : pageUrl
        let cleanUrl = UrlDetector.cleanUrl(effectivePageUrl)
        let platform = UrlDetector.detectPlatform(cleanUrl)
        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: platform, targetUrl: effectivePageUrl)
        let customHeaders = buildMediaHeaders(for: platform, cookie: cookie)

        let unescaped = unescapeHtmlAndJson(rawHtml)
        let isFbStoryUrl = platform == .facebook && cleanUrl.lowercased().contains("/stories/")
        var candidates: [ScoredStreamCandidate] = []
        var seenUrls = Set<String>()
        var discoveredFbVideoId: String? = extractFacebookVideoIdFromText(cleanUrl)
            ?? (isFbStoryUrl ? nil : extractFacebookVideoIdFromText(unescaped))

        func addCandidate(
            rawCandidateUrl: String?,
            defaultLabel: String,
            defaultScore: Int,
            mediaType: MediaType = .video,
            fileExtension: String? = nil,
            pairedAudioUrl: String? = nil,
            allowDiscoverVideoId: Bool = true
        ) {
            guard let rawCandidateUrl = rawCandidateUrl else { return }
            let cleaned = sanitizeCandidateUrl(rawCandidateUrl)
            guard !cleaned.isEmpty,
                  !cleaned.lowercased().hasPrefix("blob:"),
                  cleaned.lowercased().hasPrefix("http") else {
                return
            }

            let ext = fileExtension ?? (mediaType == .audio ? "m4a" : (mediaType == .image ? "jpg" : "mp4"))

            if !seenUrls.insert(cleaned).inserted {
                if let existingIdx = candidates.firstIndex(where: { $0.url == cleaned }) {
                    let existing = candidates[existingIdx]
                    let newAudio = existing.audioUrl ?? pairedAudioUrl
                    let newScore = max(existing.score, defaultScore)
                    let newLabel = defaultScore > existing.score ? defaultLabel : existing.label
                    if newAudio != existing.audioUrl || newScore != existing.score {
                        candidates[existingIdx] = ScoredStreamCandidate(
                            url: existing.url,
                            label: newLabel,
                            score: newScore,
                            mediaType: existing.mediaType,
                            fileExtension: existing.fileExtension,
                            audioUrl: newAudio,
                            videoId: existing.videoId
                        )
                    }
                }
                return
            }

            if mediaType == .video {
                let efgMeta = inspectFacebookStreamUrl(cleaned)
                if allowDiscoverVideoId, discoveredFbVideoId == nil, let vid = efgMeta.videoId, !vid.isEmpty {
                    discoveredFbVideoId = vid
                }
                if efgMeta.isAudioOnly {
                    candidates.append(
                        ScoredStreamCandidate(
                            url: cleaned,
                            label: "Âm thanh gốc (M4A)",
                            score: 100,
                            mediaType: .audio,
                            fileExtension: "m4a",
                            audioUrl: nil,
                            videoId: efgMeta.videoId
                        )
                    )
                    return
                }
                let resolvedScore = max(defaultScore, efgMeta.resolutionScore)
                let resolvedLabel: String
                if resolvedScore >= 1080 {
                    resolvedLabel = (defaultScore >= efgMeta.resolutionScore && defaultScore >= 1080)
                        ? defaultLabel
                        : (efgMeta.qualityLabel ?? "Full HD 1080p (MP4)")
                } else if resolvedScore >= 720 {
                    resolvedLabel = (defaultScore >= efgMeta.resolutionScore && defaultScore >= 720)
                        ? defaultLabel
                        : (efgMeta.qualityLabel ?? "HD 720p (MP4)")
                } else if defaultScore > efgMeta.resolutionScore {
                    resolvedLabel = defaultLabel
                } else {
                    resolvedLabel = efgMeta.qualityLabel ?? defaultLabel
                }
                candidates.append(
                    ScoredStreamCandidate(
                        url: cleaned,
                        label: resolvedLabel,
                        score: resolvedScore,
                        mediaType: .video,
                        fileExtension: ext,
                        audioUrl: pairedAudioUrl,
                        videoId: efgMeta.videoId
                    )
                )
            } else {
                candidates.append(
                    ScoredStreamCandidate(
                        url: cleaned,
                        label: defaultLabel,
                        score: defaultScore,
                        mediaType: mediaType,
                        fileExtension: ext,
                        audioUrl: nil
                    )
                )
            }
        }

        // 1. Inspect WebView sniffed URLs first so we extract any embedded `video_id` from their `efg=` param
        for (idx, sniffed) in sniffedUrls.enumerated() {
            let lower = sniffed.lowercased()
            let isAudio = lower.contains(".m4a") || lower.contains(".mp3") || lower.contains("mime=audio")
            if isAudio {
                addCandidate(
                    rawCandidateUrl: sniffed,
                    defaultLabel: "Âm thanh Stream #\(idx + 1) (M4A)",
                    defaultScore: 100,
                    mediaType: .audio,
                    fileExtension: "m4a",
                    allowDiscoverVideoId: idx == 0 || !isFbStoryUrl
                )
            } else {
                addCandidate(
                    rawCandidateUrl: sniffed,
                    defaultLabel: "Video Bắt Trực Tiếp #\(idx + 1) (MP4)",
                    defaultScore: 450,
                    mediaType: .video,
                    fileExtension: "mp4",
                    allowDiscoverVideoId: idx == 0 || !isFbStoryUrl
                )
            }
        }

        // 2. Parse Facebook / Meta progressive_urls, DASH 1080p `<Representation><BaseURL>`, and HD/SD fields
        extractFacebookStreamsFromSlice(region: unescaped, isTargetSlice: false) { u, lbl, sc, _, audUrl in
            let mt: MediaType = lbl.uppercased().contains("M4A") ? .audio : .video
            let ext = mt == .audio ? "m4a" : "mp4"
            addCandidate(
                rawCandidateUrl: u,
                defaultLabel: lbl,
                defaultScore: sc,
                mediaType: mt,
                fileExtension: ext,
                pairedAudioUrl: audUrl,
                allowDiscoverVideoId: !isFbStoryUrl
            )
        }

        let videoRedirectPattern = #"/video_redirect/\?src=([^"&'\s<>]+)"#
        let redirectMatches = regexMatches(pattern: videoRedirectPattern, in: rawHtml) +
            regexMatches(pattern: videoRedirectPattern, in: unescaped)
        for match in redirectMatches {
            if let rawSrc = match.group(1), !rawSrc.isEmpty {
                let replaced = rawSrc.replacingOccurrences(of: "&amp;", with: "&")
                let decoded = replaced.removingPercentEncoding ?? unescapeHtmlAndJson(rawSrc)
                addCandidate(rawCandidateUrl: decoded, defaultLabel: "SD Trực Tiếp (MP4)", defaultScore: 360)
            }
        }

        // 3. Parse Instagram / Threads / X / TikTok JSON video fields from HTML
        for cand in extractInstagramVideoVersionsFromText(unescaped) {
            addCandidate(
                rawCandidateUrl: cand.url,
                defaultLabel: cand.label,
                defaultScore: cand.score,
                mediaType: cand.mediaType,
                fileExtension: cand.fileExtension,
                pairedAudioUrl: cand.audioUrl
            )
        }

        for match in regexMatches(pattern: #""video_url"\s*:\s*"([^"]+)""#, in: unescaped) {
            addCandidate(rawCandidateUrl: match.group(1), defaultLabel: "Full HD Video (MP4)", defaultScore: 900)
        }

        for match in regexMatches(pattern: #"\{[^{}]*"content_type"\s*:\s*"video/mp4"[^{}]*\}"#, in: unescaped) {
            let block = match.fullMatch
            let url = firstRegexMatch(pattern: #""url"\s*:\s*"([^"]+)""#, in: block)?.group(1)
            let bitrateStr = firstRegexMatch(pattern: #""bitrate"\s*:\s*(\d+)"#, in: block)?.group(1)
            let bitrate = Int(bitrateStr ?? "") ?? 0
            let score = bitrate >= 2_000_000 ? 1080 : (bitrate >= 800_000 ? 720 : 480)
            let label = score >= 1080 ? "Full HD 1080p (MP4)" : (score >= 720 ? "HD 720p (MP4)" : "SD Video (MP4)")
            addCandidate(rawCandidateUrl: url, defaultLabel: label, defaultScore: score)
        }

        for match in regexMatches(pattern: #""(?:playAddr|downloadAddr)"\s*:\s*"([^"]+)""#, in: unescaped) {
            addCandidate(rawCandidateUrl: match.group(1), defaultLabel: "Full HD TikTok (MP4)", defaultScore: 900)
        }

        // 4. Parse OpenGraph and HTML5 <video> / <source> tags
        let ogVideo = firstNonEmpty(
            extractMetaContent(html: rawHtml, property: "og:video:secure_url"),
            extractMetaContent(html: rawHtml, property: "og:video"),
            extractMetaContent(html: rawHtml, property: "og:video:url")
        )
        if !ogVideo.isEmpty {
            addCandidate(rawCandidateUrl: ogVideo, defaultLabel: "HD Video (MP4)", defaultScore: 700)
        }

        for match in regexMatches(
            pattern: #"<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']"#,
            in: rawHtml,
            options: [.caseInsensitive]
        ) {
            addCandidate(rawCandidateUrl: match.group(1), defaultLabel: "Video Trình Duyệt (MP4)", defaultScore: 380)
        }

        // 5. AUTOMATIC DESKTOP FULL HD UPGRADE:
        // Mobile WebViews (`m.facebook.com`, etc.) often only load 360p SD in the DOM.
        // We invoke the Desktop/API Full HD extractor using `cleanUrl` and `discoveredFbVideoId`.
        let desktopUpgradeInfo: ExtractedMediaInfo?
        switch platform {
        case .facebook:
            let targetUrl: String
            if let vid = discoveredFbVideoId, !vid.isEmpty, !cleanUrl.lowercased().contains("/stories/") {
                targetUrl = "https://www.facebook.com/watch/?v=\(vid)"
            } else {
                targetUrl = cleanUrl
            }
            desktopUpgradeInfo = await extractFacebookDirect(
                originalInput: effectivePageUrl,
                cleanUrl: targetUrl,
                hintVideoId: discoveredFbVideoId
            )
        case .instagram:
            desktopUpgradeInfo = await extractInstagramDirect(originalInput: effectivePageUrl, cleanUrl: cleanUrl)
        case .xTwitter:
            desktopUpgradeInfo = await extractTwitterDirect(originalInput: effectivePageUrl, cleanUrl: cleanUrl)
        case .tiktok:
            desktopUpgradeInfo = await extractTikTokDirect(originalInput: effectivePageUrl, cleanUrl: cleanUrl)
        case .threads:
            desktopUpgradeInfo = await extractThreadsDirect(originalInput: effectivePageUrl, cleanUrl: cleanUrl)
        case .unknown:
            desktopUpgradeInfo = nil
        }

        if isFbStoryUrl, let upgrade = desktopUpgradeInfo, !upgrade.options.isEmpty {
            return upgrade
        }

        if let upgradeOptions = desktopUpgradeInfo?.options {
            for (idx, opt) in upgradeOptions.enumerated() {
                let upperLabel = opt.qualityLabel.uppercased()
                let score: Int
                if opt.mediaType != .video {
                    score = 50
                } else if opt.isHighestQuality || upperLabel.contains("1080") || upperLabel.contains("FULL HD") {
                    score = 1200 - idx
                } else if upperLabel.contains("720") || upperLabel.contains("HD") {
                    score = 950 - idx
                } else {
                    score = 500 - idx
                }
                addCandidate(
                    rawCandidateUrl: opt.url,
                    defaultLabel: opt.qualityLabel,
                    defaultScore: score,
                    mediaType: opt.mediaType,
                    fileExtension: opt.fileExtension,
                    pairedAudioUrl: opt.audioUrl
                )
            }
        }

        // 6. Extract Thumbnail & Cover Image
        let fallbackDisplayUrl = firstRegexMatch(pattern: #""display_url"\s*:\s*"([^"]+)""#, in: unescaped)?
            .group(1)
            .map { sanitizeCandidateUrl($0) } ?? ""
        let fallbackPosterUrl = firstRegexMatch(
            pattern: #"<video[^>]+poster\s*=\s*["']([^"']+)["']"#,
            in: rawHtml,
            options: [.caseInsensitive]
        )?.group(1).map { sanitizeCandidateUrl($0) } ?? ""

        let ogImage = firstNonEmpty(
            desktopUpgradeInfo?.thumbnailUrl ?? "",
            extractMetaContent(html: rawHtml, property: "og:image"),
            fallbackDisplayUrl,
            fallbackPosterUrl
        )

        if ogImage.lowercased().hasPrefix("http") {
            addCandidate(
                rawCandidateUrl: ogImage,
                defaultLabel: candidates.isEmpty ? "Ảnh gốc HD" : "Ảnh bìa (Cover)",
                defaultScore: 10,
                mediaType: .image,
                fileExtension: "jpg"
            )
        }

        guard !candidates.isEmpty else { return nil }

        let sortedCandidates = candidates.sorted { lhs, rhs in
            let lhsIsVideo = lhs.mediaType == .video ? 1 : 0
            let rhsIsVideo = rhs.mediaType == .video ? 1 : 0
            if lhsIsVideo != rhsIsVideo {
                return lhsIsVideo > rhsIsVideo
            }
            return lhs.score > rhs.score
        }

        let fallbackDashAudioUrl = candidates.first(where: { $0.mediaType == .audio })?.url

        var markedHighest = false
        var finalOptions: [MediaOption] = []
        for cand in sortedCandidates {
            let isHighest: Bool
            if cand.mediaType == .video && !markedHighest {
                markedHighest = true
                isHighest = true
            } else {
                isHighest = false
            }
            let resolvedAudioUrl: String?
            if let directAudio = cand.audioUrl {
                resolvedAudioUrl = directAudio
            } else if cand.mediaType == .video && isFacebookDashVideoOnlyUrl(cand.url) {
                resolvedAudioUrl = fallbackDashAudioUrl
            } else {
                resolvedAudioUrl = nil
            }
            finalOptions.append(
                MediaOption(
                    url: cand.url,
                    qualityLabel: cand.label,
                    fileExtension: cand.fileExtension,
                    mediaType: cand.mediaType,
                    isHighestQuality: isHighest,
                    isNoWatermark: true,
                    customHeaders: customHeaders,
                    audioUrl: resolvedAudioUrl
                )
            )
        }

        let upgradeTitle: String = {
            guard let t = desktopUpgradeInfo?.title,
                  !t.isEmpty,
                  t.caseInsensitiveCompare("Video Facebook") != .orderedSame else {
                return ""
            }
            return t
        }()

        let resolvedTitle = firstNonEmpty(
            upgradeTitle,
            pageTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
            extractMetaContent(html: rawHtml, property: "og:title"),
            extractMetaContent(html: rawHtml, property: "og:description"),
            extractHtmlTagTitle(rawHtml),
            "Video \(platform.displayName)"
        )

        return ExtractedMediaInfo(
            originalUrl: effectivePageUrl,
            platform: platform,
            title: resolvedTitle,
            author: desktopUpgradeInfo?.author ?? platform.displayName,
            thumbnailUrl: ogImage,
            durationSeconds: desktopUpgradeInfo?.durationSeconds ?? 0,
            options: finalOptions
        )
    }

    // MARK: - Direct Platform Router

    private func looksLikeVideoPost(url: String, platform: SocialPlatform) -> Bool {
        let lower = url.lowercased()
        switch platform {
        case .facebook:
            return lower.contains("/watch") ||
                lower.contains("/reel") ||
                lower.contains("/videos") ||
                lower.contains("fb.watch") ||
                lower.contains("/share/v") ||
                lower.contains("/share/r")
        case .instagram:
            return lower.contains("/reel") || lower.contains("/tv/") || lower.contains("/stories/")
        case .tiktok:
            return true
        case .threads, .xTwitter:
            return false
        case .unknown:
            return true
        }
    }

    private func extractDirect(
        originalInput: String,
        cleanUrl: String,
        platform: SocialPlatform
    ) async throws -> ExtractedMediaInfo? {
        switch platform {
        case .xTwitter:
            return await extractTwitterDirect(originalInput: originalInput, cleanUrl: cleanUrl)
        case .tiktok:
            return await extractTikTokDirect(originalInput: originalInput, cleanUrl: cleanUrl)
        case .facebook:
            return await extractFacebookDirect(originalInput: originalInput, cleanUrl: cleanUrl)
        case .instagram:
            return await extractInstagramDirect(originalInput: originalInput, cleanUrl: cleanUrl)
        case .threads:
            return await extractThreadsDirect(originalInput: originalInput, cleanUrl: cleanUrl)
        case .unknown:
            return nil
        }
    }

    // MARK: - X (Twitter) Direct Extractor

    private func extractTwitterDirect(originalInput: String, cleanUrl: String) async -> ExtractedMediaInfo? {
        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: .xTwitter, targetUrl: cleanUrl)
        let twitterHeaders = buildMediaHeaders(for: .xTwitter, cookie: cookie)

        let resolvedUrl: String
        if cleanUrl.lowercased().contains("t.co/") || !cleanUrl.lowercased().contains("/status/") {
            resolvedUrl = await resolveRedirectUrl(cleanUrl, userAgent: Self.desktopChromeUA)
        } else {
            resolvedUrl = cleanUrl
        }

        guard let statusIdMatch = firstRegexMatch(
            pattern: #"(?:status|statuses)/(\d+)"#,
            in: resolvedUrl,
            options: [.caseInsensitive]
        ) ?? firstRegexMatch(pattern: #"/(\d{12,25})"#, in: resolvedUrl),
              let tweetId = statusIdMatch.group(1) else {
            return nil
        }

        let endpoints = [
            "https://api.fxtwitter.com/Twitter/status/\(tweetId)",
            "https://api.vxtwitter.com/Twitter/status/\(tweetId)"
        ]

        for apiEndpoint in endpoints {
            var headers: [String: String] = [
                "User-Agent": Self.desktopChromeUA,
                "Accept": "application/json"
            ]
            if !cookie.isEmpty {
                headers["Cookie"] = cookie
            }

            guard let (responseBody, _) = await fetchText(url: apiEndpoint, headers: headers),
                  let data = responseBody.data(using: .utf8),
                  let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                continue
            }

            let rootObj = (json["tweet"] as? [String: Any]) ?? json
            let text = (rootObj["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let authorObj = rootObj["author"] as? [String: Any]
            let userName = firstNonEmpty(
                rootObj["user_name"] as? String ?? "",
                authorObj?["name"] as? String ?? ""
            )
            let screenName = firstNonEmpty(
                rootObj["user_screen_name"] as? String ?? "",
                authorObj?["screen_name"] as? String ?? ""
            )

            let authorLabel: String
            if !userName.isEmpty && !screenName.isEmpty {
                authorLabel = "\(userName) (@\(screenName))"
            } else if !screenName.isEmpty {
                authorLabel = "@\(screenName)"
            } else if !userName.isEmpty {
                authorLabel = userName
            } else {
                authorLabel = "X / Twitter"
            }

            var options: [MediaOption] = []
            var thumbnailUrl = ""
            var durationSec = 0

            // 1. Parse fxtwitter `media.all` / `media.videos` (including bitrate-sorted `variants`)
            if let mediaDict = rootObj["media"] as? [String: Any] {
                let allItems = (mediaDict["all"] as? [[String: Any]]) ??
                    (mediaDict["videos"] as? [[String: Any]]) ?? []
                var markedHighest = false
                for (i, item) in allItems.enumerated() {
                    let type = (item["type"] as? String ?? "").lowercased()
                    let mediaUrl = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    let thumb = (item["thumbnail_url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if thumbnailUrl.isEmpty && !thumb.isEmpty {
                        thumbnailUrl = thumb
                    }
                    if durationSec == 0 {
                        if let durDouble = item["duration"] as? Double {
                            durationSec = Int(durDouble)
                        } else if let durInt = item["duration"] as? Int {
                            durationSec = durInt
                        }
                    }

                    if type == "video" || type == "gif" {
                        var variantPairs: [(Int, String)] = []
                        if let variants = item["variants"] as? [[String: Any]] {
                            for v in variants {
                                let cType = (v["content_type"] as? String ?? v["type"] as? String ?? "").lowercased()
                                let vUrl = (v["url"] as? String ?? v["src"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                                if (cType.isEmpty || cType == "video/mp4" || vUrl.contains(".mp4")) && vUrl.hasPrefix("http") {
                                    let br = (v["bitrate"] as? Int) ?? (extractResolutionScore(vUrl) * 1000)
                                    variantPairs.append((br, vUrl))
                                }
                            }
                        }
                        if !mediaUrl.isEmpty && mediaUrl.hasPrefix("http") {
                            let width = item["width"] as? Int ?? 0
                            let height = item["height"] as? Int ?? 0
                            let shortSide = (width > 0 && height > 0) ? min(width, height) : max(width, height)
                            let estBitrate = max(shortSide, extractResolutionScore(mediaUrl)) * 2500
                            variantPairs.append((estBitrate, mediaUrl))
                        }

                        var seenVariantUrls = Set<String>()
                        let sortedVariants = variantPairs
                            .sorted(by: { $0.0 > $1.0 })
                            .filter { seenVariantUrls.insert($0.1).inserted }

                        for (vIdx, (bitrate, vUrl)) in sortedVariants.enumerated() {
                            let label = twitterQualityLabel(url: vUrl, bitrate: bitrate, index: vIdx, isGif: type == "gif")
                            options.append(
                                MediaOption(
                                    url: vUrl,
                                    qualityLabel: label,
                                    fileExtension: "mp4",
                                    mediaType: .video,
                                    isHighestQuality: !markedHighest,
                                    isNoWatermark: true,
                                    customHeaders: twitterHeaders
                                )
                            )
                            markedHighest = true
                        }

                        if !thumb.isEmpty {
                            options.append(
                                MediaOption(
                                    url: thumb,
                                    qualityLabel: "Ảnh bìa (Cover)",
                                    fileExtension: "jpg",
                                    mediaType: .image,
                                    isHighestQuality: false,
                                    isNoWatermark: true,
                                    customHeaders: twitterHeaders
                                )
                            )
                        }
                    } else if (type == "photo" || type == "image") && !mediaUrl.isEmpty {
                        if thumbnailUrl.isEmpty { thumbnailUrl = mediaUrl }
                        options.append(
                            MediaOption(
                                url: mediaUrl,
                                qualityLabel: allItems.count > 1 ? "Ảnh gốc HD #\(i + 1)" : "Ảnh gốc HD",
                                fileExtension: "jpg",
                                mediaType: .image,
                                isHighestQuality: !markedHighest,
                                isNoWatermark: true,
                                customHeaders: twitterHeaders
                            )
                        )
                        markedHighest = true
                    }
                }
            }

            // 2. Parse vxtwitter `media_extended` format
            if options.isEmpty, let mediaExtended = rootObj["media_extended"] as? [[String: Any]], !mediaExtended.isEmpty {
                var markedHighest = false
                for (i, item) in mediaExtended.enumerated() {
                    let type = (item["type"] as? String ?? "").lowercased()
                    let mediaUrl = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    let thumb = (item["thumbnail_url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if thumbnailUrl.isEmpty && !thumb.isEmpty {
                        thumbnailUrl = thumb
                    }
                    if let durationMillis = item["duration_millis"] as? Int, durationMillis > 0, durationSec == 0 {
                        durationSec = durationMillis / 1000
                    }

                    if type == "video" || type == "gif" {
                        if !mediaUrl.isEmpty {
                            let sizeObj = item["size"] as? [String: Any]
                            let width = sizeObj?["width"] as? Int ?? 0
                            let height = sizeObj?["height"] as? Int ?? 0
                            let shortSide = (width > 0 && height > 0) ? min(width, height) : 0
                            let dimText = (width > 0 && height > 0) ? " (\(width)x\(height))" : ""
                            let label: String
                            if type == "gif" {
                                label = "GIF / MP4 Động\(dimText)"
                            } else if shortSide >= 1080 {
                                label = "Full HD 1080p\(dimText) (MP4)"
                            } else if shortSide >= 720 {
                                label = "HD 720p\(dimText) (MP4)"
                            } else {
                                label = "Video HD Gốc\(dimText)"
                            }
                            options.append(
                                MediaOption(
                                    url: mediaUrl,
                                    qualityLabel: label,
                                    fileExtension: "mp4",
                                    mediaType: .video,
                                    isHighestQuality: !markedHighest,
                                    isNoWatermark: true,
                                    customHeaders: twitterHeaders
                                )
                            )
                            markedHighest = true
                        }
                        if !thumb.isEmpty {
                            options.append(
                                MediaOption(
                                    url: thumb,
                                    qualityLabel: "Ảnh bìa (Cover)",
                                    fileExtension: "jpg",
                                    mediaType: .image,
                                    isHighestQuality: false,
                                    isNoWatermark: true,
                                    customHeaders: twitterHeaders
                                )
                            )
                        }
                    } else if type == "image" || type == "photo" {
                        let imgUrl = mediaUrl.isEmpty ? thumb : mediaUrl
                        if !imgUrl.isEmpty {
                            if thumbnailUrl.isEmpty { thumbnailUrl = imgUrl }
                            options.append(
                                MediaOption(
                                    url: imgUrl,
                                    qualityLabel: mediaExtended.count > 1 ? "Ảnh HD #\(i + 1)" : "Ảnh gốc HD",
                                    fileExtension: "jpg",
                                    mediaType: .image,
                                    isHighestQuality: !markedHighest,
                                    isNoWatermark: true,
                                    customHeaders: twitterHeaders
                                )
                            )
                            markedHighest = true
                        }
                    }
                }
            }

            if !options.isEmpty {
                let displayTitle = text.isEmpty ? "Bài đăng X / Twitter #\(tweetId)" : text
                return ExtractedMediaInfo(
                    originalUrl: originalInput,
                    platform: .xTwitter,
                    title: displayTitle,
                    author: authorLabel,
                    thumbnailUrl: thumbnailUrl,
                    durationSeconds: durationSec,
                    options: options
                )
            }
        }

        // Fallback to Syndication API, Authenticated GraphQL, and Direct HTML with Cookie
        if let fallback = await extractTwitterSyndicationOrGraphQl(
            tweetId: tweetId,
            cleanUrl: resolvedUrl,
            cookie: cookie
        ) {
            return ExtractedMediaInfo(
                originalUrl: originalInput,
                platform: fallback.platform,
                title: fallback.title,
                author: fallback.author,
                thumbnailUrl: fallback.thumbnailUrl,
                durationSeconds: fallback.durationSeconds,
                options: fallback.options
            )
        }

        return nil
    }

    private func twitterQualityLabel(url: String, bitrate: Int, index: Int, isGif: Bool = false) -> String {
        if isGif {
            return "GIF / MP4 Động"
        }
        let resMatch = firstRegexMatch(pattern: #"/(\d{2,4})x(\d{2,4})/"#, in: url)
        let w = Int(resMatch?.group(1) ?? "") ?? 0
        let h = Int(resMatch?.group(2) ?? "") ?? 0
        let shortSide = (w > 0 && h > 0) ? min(w, h) : 0
        let dimBadge = (w > 0 && h > 0) ? " (\(w)x\(h))" : ""
        let kbps = bitrate > 1000 ? " - \(bitrate / 1000) kbps" : ""

        if shortSide >= 1080 || bitrate >= 2_000_000 {
            return "Full HD 1080p\(dimBadge) (MP4)\(kbps)"
        } else if shortSide >= 720 || bitrate >= 800_000 {
            return "HD 720p\(dimBadge) (MP4)\(kbps)"
        } else if shortSide > 0 {
            return "SD \(shortSide)p\(dimBadge) (MP4)\(kbps)"
        } else if index == 0 {
            return "Full HD 1080p (MP4)\(kbps)"
        } else {
            return "SD #\(index + 1) (MP4)\(kbps)"
        }
    }

    private func extractTwitterSyndicationOrGraphQl(
        tweetId: String,
        cleanUrl: String,
        cookie: String
    ) async -> ExtractedMediaInfo? {
        let twitterHeaders = buildMediaHeaders(for: .xTwitter, cookie: cookie)

        // 1. Try Syndication API (`cdn.syndication.twimg.com/tweet-result`)
        let syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=\(tweetId)&token=a"
        var syndHeaders: [String: String] = [
            "User-Agent": Self.desktopChromeUA,
            "Accept": "application/json"
        ]
        if !cookie.isEmpty {
            syndHeaders["Cookie"] = cookie
        }

        if let (syndicationBody, _) = await fetchText(url: syndicationUrl, headers: syndHeaders),
           let data = syndicationBody.data(using: .utf8),
           let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            let text = (json["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let userObj = json["user"] as? [String: Any]
            let userName = userObj?["name"] as? String ?? ""
            let screenName = userObj?["screen_name"] as? String ?? ""
            let authorLabel: String
            if !userName.isEmpty && !screenName.isEmpty {
                authorLabel = "\(userName) (@\(screenName))"
            } else if !screenName.isEmpty {
                authorLabel = "@\(screenName)"
            } else if !userName.isEmpty {
                authorLabel = userName
            } else {
                authorLabel = "X / Twitter"
            }

            var mp4Variants: [(Int, String)] = []
            let videoObj = json["video"] as? [String: Any]
            var thumbnailUrl = videoObj?["poster"] as? String ?? ""

            if let videoVariants = videoObj?["variants"] as? [[String: Any]] {
                for v in videoVariants {
                    let type = (v["type"] as? String ?? v["content_type"] as? String ?? "").lowercased()
                    let src = (v["src"] as? String ?? v["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if (type == "video/mp4" || src.contains(".mp4")) && src.hasPrefix("http") {
                        let bitrate = (v["bitrate"] as? Int) ?? (extractResolutionScore(src) * 1000)
                        mp4Variants.append((bitrate, src))
                    }
                }
            }

            if let mediaDetails = json["mediaDetails"] as? [[String: Any]] {
                for mediaItem in mediaDetails {
                    let mediaThumb = (mediaItem["media_url_https"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if thumbnailUrl.isEmpty && !mediaThumb.isEmpty {
                        thumbnailUrl = mediaThumb
                    }
                    let videoInfo = mediaItem["video_info"] as? [String: Any]
                    if let variants = videoInfo?["variants"] as? [[String: Any]] {
                        for v in variants {
                            let contentType = (v["content_type"] as? String ?? v["type"] as? String ?? "").lowercased()
                            let url = (v["url"] as? String ?? v["src"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                            if contentType == "video/mp4" && url.hasPrefix("http") {
                                let bitrate = (v["bitrate"] as? Int) ?? (extractResolutionScore(url) * 1000)
                                mp4Variants.append((bitrate, url))
                            }
                        }
                    }
                }
            }

            var seenUrls = Set<String>()
            let sortedVariants = mp4Variants
                .sorted(by: { $0.0 > $1.0 })
                .filter { seenUrls.insert($0.1).inserted }

            var options: [MediaOption] = []
            for (index, (bitrate, videoUrl)) in sortedVariants.enumerated() {
                let label = twitterQualityLabel(url: videoUrl, bitrate: bitrate, index: index)
                options.append(
                    MediaOption(
                        url: videoUrl,
                        qualityLabel: label,
                        fileExtension: "mp4",
                        mediaType: .video,
                        isHighestQuality: index == 0,
                        isNoWatermark: true,
                        customHeaders: twitterHeaders
                    )
                )
            }

            if thumbnailUrl.hasPrefix("http") {
                options.append(
                    MediaOption(
                        url: thumbnailUrl,
                        qualityLabel: options.isEmpty ? "Ảnh gốc HD" : "Ảnh bìa (Cover)",
                        fileExtension: "jpg",
                        mediaType: .image,
                        isHighestQuality: options.isEmpty,
                        isNoWatermark: true,
                        customHeaders: twitterHeaders
                    )
                )
            }

            if options.contains(where: { $0.mediaType == .video }) {
                return ExtractedMediaInfo(
                    originalUrl: cleanUrl,
                    platform: .xTwitter,
                    title: text.isEmpty ? "Bài đăng X / Twitter #\(tweetId)" : text,
                    author: authorLabel,
                    thumbnailUrl: thumbnailUrl,
                    durationSeconds: 0,
                    options: options
                )
            }
        }

        // 2. Authenticated X GraphQL `TweetResultByRestId` when `auth_token` and `ct0` are present
        if cookie.contains("auth_token="),
           let csrf = extractCookieValue(from: cookie, name: "ct0"),
           !csrf.isEmpty {
            let graphqlUrl =
                "https://x.com/i/api/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId?variables=%7B%22tweetId%22%3A%22\(tweetId)%22%2C%22withCommunity%22%3Afalse%2C%22includePromotedContent%22%3Afalse%2C%22withVoice%22%3Afalse%7D&features=%7B%22creator_subscriptions_tweet_preview_api_enabled%22%3Atrue%2C%22freedom_of_speech_not_reach_fetch_enabled%22%3Atrue%2C%22graphql_is_translatable_rweb_tweet_is_translatable_enabled%22%3Atrue%2C%22longform_notetweets_consumption_enabled%22%3Atrue%2C%22responsive_web_edit_tweet_api_enabled%22%3Atrue%2C%22responsive_web_enhance_cards_enabled%22%3Afalse%2C%22responsive_web_graphql_exclude_directive_enabled%22%3Atrue%2C%22responsive_web_graphql_skip_user_profile_image_extensions_enabled%22%3Afalse%2C%22responsive_web_graphql_timeline_navigation_enabled%22%3Atrue%2C%22standardized_nudges_misinfo%22%3Atrue%2C%22tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled%22%3Atrue%2C%22verified_phone_label_enabled%22%3Afalse%2C%22view_counts_everywhere_api_enabled%22%3Atrue%7D"

            let gqlHeaders: [String: String] = [
                "User-Agent": Self.desktopChromeUA,
                "Accept": "application/json",
                "Authorization": "Bearer \(Self.xPublicBearerToken)",
                "x-csrf-token": csrf,
                "x-twitter-active-user": "yes",
                "x-twitter-auth-type": "OAuth2Session",
                "Cookie": cookie,
                "Referer": "https://x.com/"
            ]

            if let (rawGraphQl, _) = await fetchText(url: graphqlUrl, headers: gqlHeaders),
               !rawGraphQl.isEmpty {
                if let parsed = parseTwitterRawText(
                    rawText: rawGraphQl,
                    tweetId: tweetId,
                    cleanUrl: cleanUrl,
                    twitterHeaders: twitterHeaders
                ) {
                    return parsed
                }
            }
        }

        // 3. Direct HTML with Cookie fallback
        if !cookie.isEmpty {
            let directHeaders: [String: String] = [
                "User-Agent": Self.desktopChromeUA,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Cookie": cookie
            ]
            if let (rawHtml, _) = await fetchText(url: cleanUrl, headers: directHeaders),
               !rawHtml.isEmpty {
                if let parsed = parseTwitterRawText(
                    rawText: rawHtml,
                    tweetId: tweetId,
                    cleanUrl: cleanUrl,
                    twitterHeaders: twitterHeaders
                ) {
                    return parsed
                }
            }
        }

        return nil
    }

    private func parseTwitterRawText(
        rawText: String,
        tweetId: String,
        cleanUrl: String,
        twitterHeaders: [String: String]
    ) -> ExtractedMediaInfo? {
        let unescaped = unescapeHtmlAndJson(rawText)
        var variantsFound: [(Int, String)] = []

        for match in regexMatches(pattern: #"\{[^{}]*"content_type"\s*:\s*"video/mp4"[^{}]*\}"#, in: unescaped) {
            let block = match.fullMatch
            if let url = firstRegexMatch(pattern: #""url"\s*:\s*"([^"]+)""#, in: block)?.group(1)?.trimmingCharacters(in: .whitespacesAndNewlines),
               url.hasPrefix("http") {
                let bitrateStr = firstRegexMatch(pattern: #""bitrate"\s*:\s*(\d+)"#, in: block)?.group(1)
                let bitrate = Int(bitrateStr ?? "") ?? (extractResolutionScore(url) * 1000)
                variantsFound.append((bitrate, url))
            }
        }

        let directPattern = #""content_type"\s*:\s*"video/mp4"\s*,\s*(?:"bitrate"\s*:\s*(\d+)\s*,\s*)?"url"\s*:\s*"([^"]+)""#
        for match in regexMatches(pattern: directPattern, in: unescaped) {
            let bitrate = Int(match.group(1) ?? "") ?? 0
            let url = (match.group(2) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if url.hasPrefix("http") {
                variantsFound.append((bitrate, url))
            }
        }

        var seenUrls = Set<String>()
        let sorted = variantsFound
            .sorted { lhs, rhs in
                let lhsBr = lhs.0 > 0 ? lhs.0 : extractResolutionScore(lhs.1) * 1000
                let rhsBr = rhs.0 > 0 ? rhs.0 : extractResolutionScore(rhs.1) * 1000
                return lhsBr > rhsBr
            }
            .filter { seenUrls.insert($0.1).inserted }

        var options: [MediaOption] = []
        for (idx, (bitrate, videoUrl)) in sorted.enumerated() {
            let label = twitterQualityLabel(url: videoUrl, bitrate: bitrate, index: idx)
            options.append(
                MediaOption(
                    url: videoUrl,
                    qualityLabel: label,
                    fileExtension: "mp4",
                    mediaType: .video,
                    isHighestQuality: idx == 0,
                    isNoWatermark: true,
                    customHeaders: twitterHeaders
                )
            )
        }

        let thumbUrl = firstRegexMatch(pattern: #""media_url_https"\s*:\s*"([^"]+)""#, in: unescaped)?.group(1) ?? ""
        if thumbUrl.hasPrefix("http") {
            options.append(
                MediaOption(
                    url: thumbUrl,
                    qualityLabel: options.isEmpty ? "Ảnh gốc HD" : "Ảnh bìa (Cover)",
                    fileExtension: "jpg",
                    mediaType: .image,
                    isHighestQuality: options.isEmpty,
                    isNoWatermark: true,
                    customHeaders: twitterHeaders
                )
            )
        }

        guard !options.isEmpty else { return nil }

        let fullText = firstRegexMatch(pattern: #""full_text"\s*:\s*"([^"]+)""#, in: unescaped)?
            .group(1)?
            .replacingOccurrences(of: "\\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let screenName: String
        if let sn = firstRegexMatch(pattern: #""screen_name"\s*:\s*"([^"]+)""#, in: unescaped)?.group(1), !sn.isEmpty {
            screenName = "@\(sn)"
        } else {
            screenName = "X / Twitter"
        }

        return ExtractedMediaInfo(
            originalUrl: cleanUrl,
            platform: .xTwitter,
            title: fullText.isEmpty ? "Bài đăng X / Twitter #\(tweetId)" : fullText,
            author: screenName,
            thumbnailUrl: thumbUrl,
            durationSeconds: 0,
            options: options
        )
    }

    // MARK: - TikTok Direct Extractor

    private func extractTikTokDirect(originalInput: String, cleanUrl: String) async -> ExtractedMediaInfo? {
        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: .tiktok, targetUrl: cleanUrl)
        let customHeaders = buildMediaHeaders(for: .tiktok, cookie: cookie)

        var headers: [String: String] = [
            "User-Agent": Self.desktopChromeUA,
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer": "https://www.tikwm.com/"
        ]
        if !cookie.isEmpty {
            headers["Cookie"] = cookie
        }

        let encodedUrl = urlEncodeFormValue(cleanUrl)
        let bodyData = "url=\(encodedUrl)&hd=1".data(using: .utf8)

        if let (responseStr, _) = await fetchText(
            url: "https://www.tikwm.com/api/",
            method: "POST",
            headers: headers,
            body: bodyData
        ),
           let jsonData = responseStr.data(using: .utf8),
           let root = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any],
           (root["code"] as? Int) == 0,
           let data = root["data"] as? [String: Any] {

            func normalizeTikwmUrl(_ raw: String?) -> String {
                let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return "" }
                if trimmed.hasPrefix("/") {
                    return "https://www.tikwm.com\(trimmed)"
                }
                return trimmed
            }

            let rawTitle = (data["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let itemId = (data["id"] as? String) ?? "media"
            let title = rawTitle.isEmpty ? "Video TikTok #\(itemId)" : rawTitle

            let authorObj = data["author"] as? [String: Any]
            let nickname = (authorObj?["nickname"] as? String) ?? ""
            let uniqueId = (authorObj?["unique_id"] as? String) ?? ""
            let author: String
            if !nickname.isEmpty && !uniqueId.isEmpty {
                author = "\(nickname) (@\(uniqueId))"
            } else if !nickname.isEmpty {
                author = nickname
            } else if !uniqueId.isEmpty {
                author = "@\(uniqueId)"
            } else {
                author = "TikTok Creator"
            }

            let coverUrl = normalizeTikwmUrl(
                firstNonEmpty(data["origin_cover"] as? String ?? "", data["cover"] as? String ?? "")
            )
            let durationSec = (data["duration"] as? Int) ?? Int(data["duration"] as? Double ?? 0)

            var options: [MediaOption] = []

            let hdPlay = normalizeTikwmUrl(data["hdplay"] as? String)
            let play = normalizeTikwmUrl(data["play"] as? String)
            let wmPlay = normalizeTikwmUrl(data["wmplay"] as? String)
            let musicInfoObj = data["music_info"] as? [String: Any]
            let musicUrl = normalizeTikwmUrl(
                firstNonEmpty(data["music"] as? String ?? "", musicInfoObj?["play"] as? String ?? "")
            )

            let imagesArray = data["images"] as? [String] ?? []
            let hasSlideshow = !imagesArray.isEmpty

            if !hasSlideshow {
                if !hdPlay.isEmpty {
                    options.append(
                        MediaOption(
                            url: hdPlay,
                            qualityLabel: "Full HD 1080p (Không Logo)",
                            fileExtension: "mp4",
                            mediaType: .video,
                            isHighestQuality: true,
                            isNoWatermark: true,
                            customHeaders: customHeaders
                        )
                    )
                }

                if !play.isEmpty && play != hdPlay {
                    options.append(
                        MediaOption(
                            url: play,
                            qualityLabel: "HD (Không Logo)",
                            fileExtension: "mp4",
                            mediaType: .video,
                            isHighestQuality: hdPlay.isEmpty,
                            isNoWatermark: true,
                            customHeaders: customHeaders
                        )
                    )
                }

                if !wmPlay.isEmpty {
                    options.append(
                        MediaOption(
                            url: wmPlay,
                            qualityLabel: "SD (Có Logo)",
                            fileExtension: "mp4",
                            mediaType: .video,
                            isHighestQuality: false,
                            isNoWatermark: false,
                            customHeaders: customHeaders
                        )
                    )
                }
            } else {
                for (i, img) in imagesArray.enumerated() {
                    let imgUrl = img.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !imgUrl.isEmpty {
                        options.append(
                            MediaOption(
                                url: imgUrl,
                                qualityLabel: "Ảnh Slide #\(i + 1)",
                                fileExtension: "jpg",
                                mediaType: .image,
                                isHighestQuality: i == 0,
                                isNoWatermark: true,
                                customHeaders: customHeaders
                            )
                        )
                    }
                }
            }

            if !musicUrl.isEmpty {
                options.append(
                    MediaOption(
                        url: musicUrl,
                        qualityLabel: "Nhạc nền gốc (MP3)",
                        fileExtension: "mp3",
                        mediaType: .audio,
                        isHighestQuality: false,
                        isNoWatermark: true,
                        customHeaders: customHeaders
                    )
                )
            }

            if !coverUrl.isEmpty {
                options.append(
                    MediaOption(
                        url: coverUrl,
                        qualityLabel: "Ảnh bìa (Cover)",
                        fileExtension: "jpg",
                        mediaType: .image,
                        isHighestQuality: false,
                        isNoWatermark: true,
                        customHeaders: customHeaders
                    )
                )
            }

            if !options.isEmpty {
                return ExtractedMediaInfo(
                    originalUrl: originalInput,
                    platform: .tiktok,
                    title: title,
                    author: author,
                    thumbnailUrl: coverUrl,
                    durationSeconds: durationSec,
                    options: options
                )
            }
        }

        // Direct TikTok HTML `playAddr`/`downloadAddr` fallback with Cookie
        var pageHeaders: [String: String] = [
            "User-Agent": Self.desktopChromeUA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer": "https://www.tiktok.com/"
        ]
        if !cookie.isEmpty {
            pageHeaders["Cookie"] = cookie
        }
        if let (rawHtml, _) = await fetchText(url: cleanUrl, headers: pageHeaders), !rawHtml.isEmpty {
            let unescaped = unescapeHtmlAndJson(rawHtml)
            var options: [MediaOption] = []
            var seen = Set<String>()
            var markedHighest = false

            for match in regexMatches(pattern: #""(?:playAddr|downloadAddr)"\s*:\s*"([^"]+)""#, in: unescaped) {
                if let rawUrl = match.group(1) {
                    let cleaned = sanitizeCandidateUrl( decodeJsonUnicodeString(rawUrl) )
                    if cleaned.lowercased().hasPrefix("http") && seen.insert(cleaned).inserted {
                        options.append(
                            MediaOption(
                                url: cleaned,
                                qualityLabel: !markedHighest ? "Full HD 1080p (Không Logo)" : "HD (Không Logo)",
                                fileExtension: "mp4",
                                mediaType: .video,
                                isHighestQuality: !markedHighest,
                                isNoWatermark: true,
                                customHeaders: customHeaders
                            )
                        )
                        markedHighest = true
                    }
                }
            }

            if !options.isEmpty {
                let title = firstNonEmpty(
                    extractMetaContent(html: rawHtml, property: "og:title"),
                    extractMetaContent(html: rawHtml, property: "og:description"),
                    extractHtmlTagTitle(rawHtml),
                    "Video TikTok"
                )
                let thumb = extractMetaContent(html: rawHtml, property: "og:image")
                return ExtractedMediaInfo(
                    originalUrl: originalInput,
                    platform: .tiktok,
                    title: title,
                    author: "TikTok Creator",
                    thumbnailUrl: thumb,
                    durationSeconds: 0,
                    options: options
                )
            }
        }

        return nil
    }

    // MARK: - Facebook Direct Extractor (Comet Relay + progressive_urls + DASH 1080p + efg + 2-Stage Upgrade)

    private struct FacebookEfgMeta {
        let videoId: String?
        let qualityLabel: String?
        let resolutionScore: Int
        let isAudioOnly: Bool
    }

    private struct ScoredStreamCandidate {
        let url: String
        let label: String
        let score: Int
        let mediaType: MediaType
        let fileExtension: String
        let audioUrl: String?
        let videoId: String?

        init(
            url: String,
            label: String,
            score: Int,
            mediaType: MediaType,
            fileExtension: String,
            audioUrl: String?,
            videoId: String? = nil
        ) {
            self.url = url
            self.label = label
            self.score = score
            self.mediaType = mediaType
            self.fileExtension = fileExtension
            self.audioUrl = audioUrl
            self.videoId = videoId
        }
    }

    private func inspectFacebookStreamUrl(_ rawUrl: String) -> FacebookEfgMeta {
        let lowerUrl = rawUrl.lowercased()
        let hasHdTagParam = lowerUrl.contains("&tag=hd") || lowerUrl.contains("?tag=hd")
        let hasSdTagParam = lowerUrl.contains("&tag=sd") || lowerUrl.contains("?tag=sd")

        guard let efgMatch = firstRegexMatch(pattern: #"[?&]efg=([^&"'\s]+)"#, in: rawUrl)?.group(1) else {
            if hasHdTagParam {
                return FacebookEfgMeta(videoId: nil, qualityLabel: "Full HD 720p/1080p (MP4)", resolutionScore: 900, isAudioOnly: false)
            } else if hasSdTagParam {
                return FacebookEfgMeta(videoId: nil, qualityLabel: "SD 360p (MP4)", resolutionScore: 360, isAudioOnly: false)
            } else {
                return FacebookEfgMeta(videoId: nil, qualityLabel: nil, resolutionScore: 0, isAudioOnly: false)
            }
        }

        let urlDecoded = efgMatch.removingPercentEncoding ?? efgMatch
        guard let decodedData = decodeBase64Flexible(urlDecoded),
              let json = (try? JSONSerialization.jsonObject(with: decodedData)) as? [String: Any] else {
            return FacebookEfgMeta(videoId: nil, qualityLabel: nil, resolutionScore: 0, isAudioOnly: false)
        }

        // IMPORTANT: Only use `video_id` (never `xpv_asset_id`, which is an internal storage ID that 404s on /watch/?v=)
        let rawVid: String = {
            if let s = json["video_id"] as? String {
                return s.trimmingCharacters(in: .whitespacesAndNewlines)
            } else if let n = json["video_id"] as? NSNumber {
                return n.stringValue
            }
            return ""
        }()
        let videoId: String? = (!rawVid.isEmpty && rawVid != "0" && rawVid.allSatisfy({ $0.isNumber })) ? rawVid : nil

        let vencodeTag = firstNonEmpty(
            json["vencode_tag"] as? String ?? "",
            json["venc_tags"] as? String ?? "",
            json["encode_tag"] as? String ?? "",
            json["quality_label"] as? String ?? ""
        ).lowercased()

        let isAudioOnly = (vencodeTag.contains("audio") ||
            vencodeTag.contains("heaac") ||
            vencodeTag.contains("opus") ||
            vencodeTag.contains("mp4a")) && !vencodeTag.contains("progressive")

        if isAudioOnly {
            return FacebookEfgMeta(videoId: videoId, qualityLabel: "Âm thanh gốc (M4A)", resolutionScore: 100, isAudioOnly: true)
        }

        let isExplicitHd = vencodeTag.hasSuffix(".hd") ||
            vencodeTag.contains("_hd") ||
            vencodeTag.contains("sve_hd") ||
            hasHdTagParam
        let isExplicitSd = vencodeTag.hasSuffix(".sd") ||
            vencodeTag.contains("_sd") ||
            vencodeTag.contains("sve_sd") ||
            hasSdTagParam

        let resTokenStr = firstRegexMatch(
            pattern: #"(?:^|[._-])(2160|1920|1440|1280|1080|720|540|480|360|240)p?(?:$|[._-])"#,
            in: vencodeTag
        )?.group(1)
        let resToken = Int(resTokenStr ?? "")

        let crfTierStr = firstRegexMatch(
            pattern: #"_q(90|80|70|60|50|40|30|20)(?:$|[._-])"#,
            in: vencodeTag
        )?.group(1)
        let crfTier = Int(crfTierStr ?? "")

        let labelAndScore: (String?, Int)
        if resToken == 2160 {
            labelAndScore = ("4K Ultra HD 2160p (MP4)", 2160)
        } else if resToken == 1440 {
            labelAndScore = ("2K QHD 1440p (MP4)", 1440)
        } else if resToken == 1080 || resToken == 1920 || crfTier == 90 {
            labelAndScore = ("Full HD 1080p (MP4)", 1080)
        } else if resToken == 720 || resToken == 1280 || crfTier == 80 {
            labelAndScore = ("HD 720p (MP4)", 720)
        } else if crfTier == 70 {
            labelAndScore = ("HD 640p (MP4)", 640)
        } else if isExplicitHd {
            labelAndScore = ("HD 720p / 1080p (MP4)", 880)
        } else if resToken == 540 || crfTier == 60 {
            labelAndScore = ("qHD 540p (MP4)", 540)
        } else if resToken == 480 || crfTier == 50 {
            labelAndScore = ("SD 480p (MP4)", 480)
        } else if resToken == 360 || crfTier == 40 || isExplicitSd {
            labelAndScore = ("SD 360p (MP4)", 360)
        } else if resToken == 240 || crfTier == 30 || crfTier == 20 {
            labelAndScore = ("SD 240p (MP4)", 240)
        } else if vencodeTag.contains("hd") || vencodeTag.contains("high") {
            labelAndScore = ("HD 720p / 1080p (MP4)", 880)
        } else if vencodeTag.contains("sd") {
            labelAndScore = ("SD 360p (MP4)", 360)
        } else {
            labelAndScore = (nil, 0)
        }

        return FacebookEfgMeta(
            videoId: videoId,
            qualityLabel: labelAndScore.0,
            resolutionScore: labelAndScore.1,
            isAudioOnly: false
        )
    }

    private func isFacebookDashVideoOnlyUrl(_ rawUrl: String) -> Bool {
        guard let efgMatch = firstRegexMatch(pattern: #"[?&]efg=([^&"'\s]+)"#, in: rawUrl)?.group(1) else {
            return false
        }
        let urlDecoded = efgMatch.removingPercentEncoding ?? efgMatch
        guard let decodedData = decodeBase64Flexible(urlDecoded),
              let decodedJsonStr = String(data: decodedData, encoding: .utf8)?.lowercased() else {
            return false
        }
        if decodedJsonStr.contains("progressive") { return false }
        if decodedJsonStr.contains("audio") || decodedJsonStr.contains("heaac") { return false }
        return decodedJsonStr.contains("\"dash_") ||
            decodedJsonStr.contains("dash_r2") ||
            decodedJsonStr.contains("dash_vp9") ||
            decodedJsonStr.contains("dash_h264")
    }

    private func extractFacebookVideoIdFromText(_ text: String) -> String? {
        let patterns = [
            #"/videos/(?:pcb\.\d+/)?(\d{8,22})"#,
            #"/reel/(\d{8,22})"#,
            #"[?&]v=(\d{8,22})"#,
            #"story_fbid=(\d{8,22})"#,
            #""video_id"\s*:\s*"?(\d{8,22})"?"#,
            #""videoId"\s*:\s*"?(\d{8,22})"?"#
        ]
        for pattern in patterns {
            if let id = firstRegexMatch(pattern: pattern, in: text)?.group(1), !id.isEmpty {
                return id
            }
        }
        return nil
    }

    private func extractFacebookStoryTokens(_ url: String) -> [String] {
        guard let rawToken = firstRegexMatch(
            pattern: #"/stories/[^/?#]+/([^/?#]+)"#,
            in: url,
            options: [.caseInsensitive]
        )?.group(1)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !rawToken.isEmpty else {
            return []
        }

        let urlDecoded = rawToken.removingPercentEncoding ?? rawToken
        var tokens: [String] = [urlDecoded]
        if rawToken != urlDecoded {
            tokens.append(rawToken)
        }

        if let data = decodeBase64Flexible(urlDecoded),
           let decodedStr = String(data: data, encoding: .utf8) {
            for m in regexMatches(pattern: #"(\d{8,22})"#, in: decodedStr) {
                if let numId = m.group(1), !numId.isEmpty, !tokens.contains(numId) {
                    tokens.append(numId)
                }
            }
        }
        return tokens
    }

    private func normalizeFacebookStoryCardKey(_ rawToken: String) -> String {
        let trimmed = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let urlDecoded = (trimmed.removingPercentEncoding ?? trimmed)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if (8...22).contains(urlDecoded.count) && urlDecoded.allSatisfy({ $0.isNumber }) {
            return "ISC_\(urlDecoded)"
        }
        if let data = decodeBase64Flexible(urlDecoded),
           let decodedStr = String(data: data, encoding: .utf8),
           let numId = firstRegexMatch(pattern: #"(\d{8,22})"#, in: decodedStr)?.group(1),
           !numId.isEmpty {
            return "ISC_\(numId)"
        }
        return urlDecoded.trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    private func extractFacebookPhotoStoryUrlFromSlice(_ slice: String) -> String? {
        let patterns = [
            #""viewer_image"\s*:\s*\{[^{}]*"uri"\s*:\s*"(https?:[^"]+)""#,
            #""photo_image"\s*:\s*\{[^{}]*"uri"\s*:\s*"(https?:[^"]+)""#,
            #""__typename"\s*:\s*"Photo"[\s\S]{0,1200}?"image"\s*:\s*\{[^{}]*"uri"\s*:\s*"(https?:[^"]+)""#
        ]
        for pattern in patterns {
            for m in regexMatches(pattern: pattern, in: slice) {
                guard let raw = m.group(1) else { continue }
                let cleaned = sanitizeCandidateUrl(raw)
                let lower = cleaned.lowercased()
                if lower.hasPrefix("http") &&
                   !lower.contains("p50x50") &&
                   !lower.contains("s50x50") &&
                   !lower.contains("p100x100") &&
                   !lower.contains("s100x100") {
                    return cleaned
                }
            }
        }
        return nil
    }

    private func unwrapFacebookLoginRedirect(_ url: String) -> String {
        var current = url.trimmingCharacters(in: .whitespacesAndNewlines)
        for _ in 0..<3 {
            let lower = current.lowercased()
            if lower.contains("/login") && lower.contains("next=") {
                if let nextParam = firstRegexMatch(pattern: #"[?&]next=([^&#"'\s]+)"#, in: current)?.group(1),
                   !nextParam.isEmpty,
                   let decoded = nextParam.removingPercentEncoding,
                   decoded.lowercased().hasPrefix("http") {
                    current = decoded
                    continue
                }
            }
            if lower.contains("l.facebook.com/l.php") && lower.contains("u=") {
                if let uParam = firstRegexMatch(pattern: #"[?&]u=([^&#"'\s]+)"#, in: current)?.group(1),
                   !uParam.isEmpty,
                   let decoded = uParam.removingPercentEncoding,
                   decoded.lowercased().hasPrefix("http") {
                    current = decoded
                    continue
                }
            }
            break
        }
        return current
    }

    private func toDesktopFacebookUrl(_ url: String) -> String {
        let unwrapped = unwrapFacebookLoginRedirect(url)
        return unwrapped
            .replacingOccurrences(of: "://m.facebook.com", with: "://www.facebook.com")
            .replacingOccurrences(of: "://mbasic.facebook.com", with: "://www.facebook.com")
            .replacingOccurrences(of: "://touch.facebook.com", with: "://www.facebook.com")
            .replacingOccurrences(of: "://web.facebook.com", with: "://www.facebook.com")
            .replacingOccurrences(of: "?_rdc=1&_rdr", with: "")
            .replacingOccurrences(of: "&_rdc=1&_rdr", with: "")
    }

    private func buildDesktopFacebookCookie(_ rawCookie: String) -> String {
        let trimmed = rawCookie.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let filtered = trimmed
            .components(separatedBy: ";")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { part in
                let lower = part.lowercased()
                return !part.isEmpty &&
                    !lower.hasPrefix("m_pixel_ratio=") &&
                    !lower.hasPrefix("wd=") &&
                    !lower.hasPrefix("dpr=")
            }
        return (filtered + ["wd=1920x1080", "dpr=2"]).joined(separator: "; ")
    }

    private func extractFacebookStreamsFromSlice(
        region: String,
        isTargetSlice: Bool,
        register: (_ url: String?, _ label: String, _ score: Int, _ isTarget: Bool, _ audioUrl: String?) -> Void
    ) {
        let nsRegion = region as NSString
        let regionLength = nsRegion.length

        // 1. Parse `progressive_urls` (handling nested `"metadata":{"quality":"HD"}` and `"SD"`)
        for m in regexMatches(pattern: #""progressive_url"\s*:\s*"(https?:[^"]+)""#, in: region) {
            guard let url = m.group(1) else { continue }
            let afterStart = min(m.range.location + m.range.length, regionLength)
            let afterLen = min(220, regionLength - afterStart)
            let rawAfterSlice = afterLen > 0
                ? nsRegion.substring(with: NSRange(location: afterStart, length: afterLen))
                : ""
            let afterSlice = rawAfterSlice.components(separatedBy: "\"progressive_url\"").first ?? rawAfterSlice

            let beforeStart = max(0, m.range.location - 140)
            let beforeLen = max(0, m.range.location - beforeStart)
            let rawBeforeSlice = beforeLen > 0
                ? nsRegion.substring(with: NSRange(location: beforeStart, length: beforeLen))
                : ""
            let beforeSlice = rawBeforeSlice.components(separatedBy: "\"progressive_url\"").last ?? rawBeforeSlice

            let isHdMeta = firstRegexMatch(
                pattern: #""quality"\s*:\s*"HD""#,
                in: afterSlice,
                options: [.caseInsensitive]
            ) != nil || firstRegexMatch(
                pattern: #""quality"\s*:\s*"HD""#,
                in: beforeSlice,
                options: [.caseInsensitive]
            ) != nil

            let isSdMeta = firstRegexMatch(
                pattern: #""quality"\s*:\s*"SD""#,
                in: afterSlice,
                options: [.caseInsensitive]
            ) != nil

            if isHdMeta {
                register(url, "HD 720p / 1080p Gốc (MP4)", 1050, isTargetSlice, nil)
            } else if isSdMeta {
                register(url, "SD 360p (MP4)", 380, isTargetSlice, nil)
            } else {
                register(url, "HD Video (MP4)", 740, isTargetSlice, nil)
            }
        }

        // 2. Parse Classic / Legacy HD & SD fields
        let classicHdPatterns = [
            #""browser_native_hd_url"\s*:\s*"(https?:[^"]+)""#,
            #""playable_url_quality_hd"\s*:\s*"(https?:[^"]+)""#,
            #""hd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)""#,
            #""hd_src"\s*:\s*"(https?:[^"]+)""#
        ]
        for pattern in classicHdPatterns {
            for m in regexMatches(pattern: pattern, in: region) {
                register(m.group(1), "HD 720p / 1080p Gốc (MP4)", 1040, isTargetSlice, nil)
            }
        }

        let classicSdPatterns = [
            #""browser_native_sd_url"\s*:\s*"(https?:[^"]+)""#,
            #""playable_url"\s*:\s*"(https?:[^"]+)""#,
            #""sd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)""#,
            #""sd_src"\s*:\s*"(https?:[^"]+)""#
        ]
        for pattern in classicSdPatterns {
            for m in regexMatches(pattern: pattern, in: region) {
                register(m.group(1), "SD 360p (MP4)", 380, isTargetSlice, nil)
            }
        }

        // 3. Parse DASH Manifest XML (`<MPD>...<Representation><BaseURL>...</BaseURL></MPD>`)
        let xmlReady = region
            .replacingOccurrences(of: #"\""#, with: "\"")
            .replacingOccurrences(of: #"\n"#, with: "\n")
            .replacingOccurrences(of: #"\t"#, with: " ")

        let mpdMatches = regexMatches(
            pattern: #"<MPD\b[\s\S]*?</MPD>"#,
            in: xmlReady,
            options: [.caseInsensitive]
        ).map { $0.fullMatch }
        let mpdBlocks = mpdMatches.isEmpty ? [xmlReady] : mpdMatches

        let repPattern = #"<Representation\b([^>]*)>[\s\S]*?<BaseURL>\s*(https?:[^<\s"']+)\s*</BaseURL>"#

        var bestDashAudioUrl: String?
        var audioByVideoId: [String: String] = [:]

        // Pre-scan Relay JSON `"base_url"` audio representations so `bestDashAudioUrl` and `audioByVideoId` are always known
        let jsonBaseMatches = regexMatches(pattern: #""base_url"\s*:\s*"(https?:[^"]+)""#, in: region)
        for jm in jsonBaseMatches {
            guard let rawBase = jm.group(1) else { continue }
            let cleanedUrl = sanitizeCandidateUrl(rawBase)
            let efg = inspectFacebookStreamUrl(cleanedUrl)
            let start = max(0, jm.range.location - 240)
            let end = min(regionLength, jm.range.location + jm.range.length + 240)
            let ctx = end > start ? nsRegion.substring(with: NSRange(location: start, length: end - start)).lowercased() : ""
            let isAudio = efg.isAudioOnly || ctx.contains("audio/mp4") || ctx.contains("mp4a")
            if isAudio {
                if bestDashAudioUrl == nil {
                    bestDashAudioUrl = cleanedUrl
                }
                if let vid = efg.videoId, !vid.isEmpty, audioByVideoId[vid] == nil {
                    audioByVideoId[vid] = cleanedUrl
                }
                register(cleanedUrl, "Âm thanh gốc (M4A)", 110, isTargetSlice, nil)
            }
        }

        for mpdBlock in mpdBlocks {
            let repMatches = regexMatches(
                pattern: repPattern,
                in: mpdBlock,
                options: [.caseInsensitive]
            )
            if repMatches.isEmpty { continue }

            // First pass per MPD: find the highest-bitrate DASH Audio track (`audio/mp4` / `mp4a`) for THIS video
            var mpdAudioUrl: String?
            var mpdAudioBandwidth: Int64 = -1

            for rm in repMatches {
                let attrs = rm.group(1) ?? ""
                let rawBaseUrl = rm.group(2) ?? ""
                let cleanedUrl = sanitizeCandidateUrl(rawBaseUrl)
                let efg = inspectFacebookStreamUrl(cleanedUrl)
                let lowerAttrs = attrs.lowercased()
                let isAudio = efg.isAudioOnly || lowerAttrs.contains("audio") || lowerAttrs.contains("mp4a")
                if isAudio {
                    let bwStr = firstRegexMatch(pattern: #"bandwidth\s*=\s*"(\d+)""#, in: attrs)?.group(1)
                    let bw = Int64(bwStr ?? "") ?? 64_000
                    if bw > mpdAudioBandwidth {
                        mpdAudioBandwidth = bw
                        mpdAudioUrl = cleanedUrl
                    }
                    if bestDashAudioUrl == nil {
                        bestDashAudioUrl = cleanedUrl
                    }
                    if let vid = efg.videoId, !vid.isEmpty, audioByVideoId[vid] == nil {
                        audioByVideoId[vid] = cleanedUrl
                    }
                    register(cleanedUrl, "Âm thanh gốc (M4A)", 110, isTargetSlice, nil)
                }
            }

            // Second pass per MPD: register DASH Video `<Representation>` tracks paired with `audioByVideoId` / `mpdAudioUrl`
            for rm in repMatches {
                let attrs = rm.group(1) ?? ""
                let rawBaseUrl = rm.group(2) ?? ""
                let cleanedUrl = sanitizeCandidateUrl(rawBaseUrl)
                let efg = inspectFacebookStreamUrl(cleanedUrl)
                let lowerAttrs = attrs.lowercased()
                let isAudio = efg.isAudioOnly || lowerAttrs.contains("audio") || lowerAttrs.contains("mp4a")
                if isAudio { continue }

                let fbQualityLabel = firstRegexMatch(
                    pattern: #"FBQualityLabel\s*=\s*"([^"]+)""#,
                    in: attrs,
                    options: [.caseInsensitive]
                )?.group(1) ?? ""
                let labelRes = Int(firstRegexMatch(pattern: #"(\d{3,4})"#, in: fbQualityLabel)?.group(1) ?? "")
                let width = Int(firstRegexMatch(pattern: #"\bwidth\s*=\s*"(\d+)""#, in: attrs)?.group(1) ?? "") ?? 0
                let height = Int(firstRegexMatch(pattern: #"\bheight\s*=\s*"(\d+)""#, in: attrs)?.group(1) ?? "") ?? 0
                let shortSide = (width > 0 && height > 0) ? min(width, height) : max(width, height)

                let isAvcOrHevc = lowerAttrs.contains("avc1") || lowerAttrs.contains("hvc1") || lowerAttrs.contains("hev1")
                let codecBonus = isAvcOrHevc ? 30 : 0

                let res = labelRes ?? (efg.resolutionScore > 0 ? efg.resolutionScore : shortSide)
                let dashLabel: String
                let dashScore: Int
                if res >= 1080 {
                    dashLabel = "Full HD 1080p (MP4)"
                    dashScore = 1150 + codecBonus
                } else if res >= 720 {
                    dashLabel = "HD 720p (MP4)"
                    dashScore = 950 + codecBonus
                } else if res >= 540 {
                    dashLabel = "qHD 540p (MP4)"
                    dashScore = 540 + codecBonus
                } else if res >= 480 {
                    dashLabel = "SD 480p (MP4)"
                    dashScore = 480 + codecBonus
                } else if res > 0 {
                    dashLabel = "SD \(res)p (MP4)"
                    dashScore = res + codecBonus
                } else if lowerAttrs.contains("fbqualityclass=\"hd\"") {
                    dashLabel = "Full HD 1080p/720p (MP4)"
                    dashScore = 980 + codecBonus
                } else {
                    dashLabel = "SD Video (MP4)"
                    dashScore = 360
                }

                let pairedAudio = (efg.videoId.flatMap { audioByVideoId[$0] }) ?? mpdAudioUrl ?? bestDashAudioUrl
                register(cleanedUrl, dashLabel, dashScore, isTargetSlice, pairedAudio)
            }
        }

        // Second pass for Relay JSON `"base_url"` video representations
        for jm in jsonBaseMatches {
            guard let rawBase = jm.group(1) else { continue }
            let cleanedUrl = sanitizeCandidateUrl(rawBase)
            let efg = inspectFacebookStreamUrl(cleanedUrl)
            let start = max(0, jm.range.location - 260)
            let end = min(regionLength, jm.range.location + jm.range.length + 260)
            let ctx = end > start ? nsRegion.substring(with: NSRange(location: start, length: end - start)) : ""
            let lowerCtx = ctx.lowercased()
            let isAudio = efg.isAudioOnly || lowerCtx.contains("audio/mp4") || lowerCtx.contains("mp4a")
            if isAudio { continue }

            let width = Int(firstRegexMatch(pattern: #""width"\s*:\s*(\d+)"#, in: ctx)?.group(1) ?? "") ?? 0
            let height = Int(firstRegexMatch(pattern: #""height"\s*:\s*(\d+)"#, in: ctx)?.group(1) ?? "") ?? 0
            let shortSide = (width > 0 && height > 0) ? min(width, height) : max(width, height)
            let isAvc1 = lowerCtx.contains("avc1")
            let codecBonus = isAvc1 ? 30 : 0
            let res = efg.resolutionScore > 0 ? efg.resolutionScore : shortSide
            if res >= 720 {
                let label = res >= 1080 ? "Full HD 1080p (MP4)" : "HD 720p (MP4)"
                let score = (res >= 1080 ? 1150 : 950) + codecBonus
                let pairedAudio = (efg.videoId.flatMap { audioByVideoId[$0] }) ?? bestDashAudioUrl
                register(cleanedUrl, label, score, isTargetSlice, pairedAudio)
            }
        }
    }

    private func extractFacebookDirect(
        originalInput: String,
        cleanUrl: String,
        hintVideoId: String? = nil
    ) async -> ExtractedMediaInfo? {
        struct StoryBucketGroup {
            let key: String
            let rawToken: String
            var videoId: String?
            var candidates: [ScoredStreamCandidate]
        }

        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: .facebook, targetUrl: cleanUrl)
        let desktopCookie = buildDesktopFacebookCookie(cookie)
        let fbHeaders = buildMediaHeaders(for: .facebook, cookie: cookie)

        let lowerClean = cleanUrl.lowercased()
        let initialResolved: String
        if lowerClean.contains("fb.watch") || lowerClean.contains("/share/") {
            initialResolved = await resolveRedirectUrl(cleanUrl, userAgent: Self.desktopChromeUA)
        } else {
            initialResolved = cleanUrl
        }

        var finalResolvedUrl = toDesktopFacebookUrl(initialResolved)
        var targetVideoId: String? = hintVideoId ?? extractFacebookVideoIdFromText(finalResolvedUrl)

        var urlStoryTokens = extractFacebookStoryTokens(cleanUrl)
            + extractFacebookStoryTokens(initialResolved)
            + extractFacebookStoryTokens(finalResolvedUrl)
        var targetStoryKeys: Set<String> = Set(urlStoryTokens.map { normalizeFacebookStoryCardKey($0) })

        let videoRedirectPattern = #"/video_redirect/\?src=([^"&'\s<>]+)"#

        var storyGroups: [StoryBucketGroup] = []
        var storyGroupIndexByKey: [String: Int] = [:]

        var candidates: [ScoredStreamCandidate] = []
        var seenUrls = Set<String>()
        var globalAudioByVideoId: [String: String] = [:]
        var ogVideoUrl = ""
        var ogImageUrl = ""
        var ogTitle = ""
        var ogDescription = ""
        var detectedAuthor = ""

        func buildCandidateFromRaw(
            rawCandidate: String?,
            defaultLabel: String,
            defaultScore: Int,
            isTargetSlice: Bool = false,
            pairedAudioUrl: String? = nil
        ) -> ScoredStreamCandidate? {
            guard let rawCandidate = rawCandidate else { return nil }
            let cleaned = sanitizeCandidateUrl(rawCandidate)
            guard cleaned.lowercased().hasPrefix("http") else { return nil }

            let efgMeta = inspectFacebookStreamUrl(cleaned)
            if efgMeta.isAudioOnly {
                if let vid = efgMeta.videoId, !vid.isEmpty, globalAudioByVideoId[vid] == nil {
                    globalAudioByVideoId[vid] = cleaned
                }
                return ScoredStreamCandidate(
                    url: cleaned,
                    label: "Âm thanh gốc (M4A)",
                    score: 100,
                    mediaType: .audio,
                    fileExtension: "m4a",
                    audioUrl: nil,
                    videoId: efgMeta.videoId
                )
            }

            let bonus = (isTargetSlice || (targetVideoId != nil && efgMeta.videoId == targetVideoId)) ? 250 : 0
            let bestScore = max(defaultScore, efgMeta.resolutionScore)
            let effectiveScore = bestScore + bonus
            let effectiveLabel: String
            if bestScore >= 1080 {
                effectiveLabel = (defaultScore >= efgMeta.resolutionScore && defaultScore >= 1080)
                    ? defaultLabel
                    : (efgMeta.qualityLabel ?? "Full HD 1080p (MP4)")
            } else if bestScore >= 720 {
                effectiveLabel = (defaultScore >= efgMeta.resolutionScore && defaultScore >= 720)
                    ? defaultLabel
                    : (efgMeta.qualityLabel ?? "HD 720p (MP4)")
            } else if defaultScore > efgMeta.resolutionScore {
                effectiveLabel = defaultLabel
            } else {
                effectiveLabel = efgMeta.qualityLabel ?? defaultLabel
            }

            let resolvedPairedAudio = pairedAudioUrl ?? (efgMeta.videoId.flatMap { globalAudioByVideoId[$0] })
            return ScoredStreamCandidate(
                url: cleaned,
                label: effectiveLabel,
                score: effectiveScore,
                mediaType: .video,
                fileExtension: "mp4",
                audioUrl: resolvedPairedAudio,
                videoId: efgMeta.videoId
            )
        }

        func mergeCandidateIntoList(_ cand: ScoredStreamCandidate, list: inout [ScoredStreamCandidate]) {
            if let existingIdx = list.firstIndex(where: { $0.url == cand.url }) {
                let existing = list[existingIdx]
                let newAudio = existing.audioUrl ?? cand.audioUrl ?? (existing.videoId.flatMap { globalAudioByVideoId[$0] })
                let newVideoId = existing.videoId ?? cand.videoId
                let newScore = max(existing.score, cand.score)
                let newLabel = cand.score > existing.score ? cand.label : existing.label
                list[existingIdx] = ScoredStreamCandidate(
                    url: existing.url,
                    label: newLabel,
                    score: newScore,
                    mediaType: existing.mediaType,
                    fileExtension: existing.fileExtension,
                    audioUrl: newAudio,
                    videoId: newVideoId
                )
            } else {
                list.append(cand)
            }
        }

        func registerCandidateUrl(
            rawCandidate: String?,
            defaultLabel: String,
            defaultScore: Int,
            isTargetSlice: Bool = false,
            pairedAudioUrl: String? = nil
        ) {
            guard let built = buildCandidateFromRaw(
                rawCandidate: rawCandidate,
                defaultLabel: defaultLabel,
                defaultScore: defaultScore,
                isTargetSlice: isTargetSlice,
                pairedAudioUrl: pairedAudioUrl
            ) else { return }

            if targetVideoId == nil,
               storyGroups.isEmpty,
               let efgVid = built.videoId,
               !efgVid.isEmpty {
                targetVideoId = efgVid
            }

            seenUrls.insert(built.url)
            mergeCandidateIntoList(built, list: &candidates)

            if let vid = built.videoId, !vid.isEmpty,
               let gIdx = storyGroups.firstIndex(where: { $0.videoId == vid }) {
                mergeCandidateIntoList(built, list: &storyGroups[gIdx].candidates)
            }
        }

        func parseFacebookHtml(_ html: String) {
            let unescaped = unescapeHtmlAndJson(html)
            let nsUnescaped = unescaped as NSString
            let totalLen = nsUnescaped.length

            urlStoryTokens = extractFacebookStoryTokens(cleanUrl)
                + extractFacebookStoryTokens(initialResolved)
                + extractFacebookStoryTokens(finalResolvedUrl)
            targetStoryKeys = Set(urlStoryTokens.map { normalizeFacebookStoryCardKey($0) })

            // 1. Multi-Story Bucket Card Boundary Isolation (`UzpfSVNDOj...` tokens)
            let tokenMatches = regexMatches(
                pattern: #"UzpfSVNDOj[A-Za-z0-9+/=_-]+"#,
                in: unescaped
            )

            for (i, m_i) in tokenMatches.enumerated() {
                let rawTok = m_i.fullMatch
                let cardKey = normalizeFacebookStoryCardKey(rawTok)
                guard !cardKey.isEmpty else { continue }

                var nextDiffLocation: Int? = nil
                if i + 1 < tokenMatches.count {
                    for j in (i + 1)..<tokenMatches.count {
                        let otherKey = normalizeFacebookStoryCardKey(tokenMatches[j].fullMatch)
                        if otherKey != cardKey {
                            nextDiffLocation = tokenMatches[j].range.location
                            break
                        }
                    }
                }

                let sliceStart = max(0, m_i.range.location - 200)
                let maxEnd = min(totalLen, m_i.range.location + m_i.range.length + 45_000)
                let sliceEnd = nextDiffLocation != nil ? min(maxEnd, nextDiffLocation!) : maxEnd
                guard sliceEnd > sliceStart else { continue }

                let cardSlice = nsUnescaped.substring(with: NSRange(location: sliceStart, length: sliceEnd - sliceStart))
                let isTargetCard = targetStoryKeys.contains(cardKey)

                var cardCandidates: [ScoredStreamCandidate] = []
                extractFacebookStreamsFromSlice(region: cardSlice, isTargetSlice: true) { u, lbl, sc, _, audUrl in
                    if let cand = buildCandidateFromRaw(
                        rawCandidate: u,
                        defaultLabel: lbl,
                        defaultScore: sc,
                        isTargetSlice: isTargetCard,
                        pairedAudioUrl: audUrl
                    ) {
                        mergeCandidateIntoList(cand, list: &cardCandidates)
                        seenUrls.insert(cand.url)
                        mergeCandidateIntoList(cand, list: &candidates)
                    }
                }

                if !cardCandidates.contains(where: { $0.mediaType == .video }) {
                    if let photoUrl = extractFacebookPhotoStoryUrlFromSlice(cardSlice) {
                        let imgCand = ScoredStreamCandidate(
                            url: photoUrl,
                            label: "Ảnh Story HD (JPG)",
                            score: 900,
                            mediaType: .image,
                            fileExtension: "jpg",
                            audioUrl: nil,
                            videoId: nil
                        )
                        mergeCandidateIntoList(imgCand, list: &cardCandidates)
                        seenUrls.insert(imgCand.url)
                        mergeCandidateIntoList(imgCand, list: &candidates)
                    }
                }

                let hasPrimaryMedia = cardCandidates.contains(where: { $0.mediaType == .video || $0.mediaType == .image })
                if hasPrimaryMedia {
                    let discoveredCardVid = cardCandidates.compactMap({ $0.videoId }).first(where: { !$0.isEmpty })
                        ?? extractFacebookVideoIdFromText(cardSlice)

                    if let existingGroupIdx = storyGroupIndexByKey[cardKey] {
                        if storyGroups[existingGroupIdx].videoId == nil, let vid = discoveredCardVid {
                            storyGroups[existingGroupIdx].videoId = vid
                        }
                        for cand in cardCandidates {
                            mergeCandidateIntoList(cand, list: &storyGroups[existingGroupIdx].candidates)
                        }
                    } else {
                        let newGroup = StoryBucketGroup(
                            key: cardKey,
                            rawToken: rawTok,
                            videoId: discoveredCardVid,
                            candidates: cardCandidates
                        )
                        storyGroupIndexByKey[cardKey] = storyGroups.count
                        storyGroups.append(newGroup)
                    }
                }
            }

            if let matchedGroup = storyGroups.first(where: {
                targetStoryKeys.contains($0.key) || (hintVideoId != nil && $0.videoId == hintVideoId)
            }) {
                targetVideoId = matchedGroup.videoId ?? targetVideoId
            }

            // 2. Standard (non-UzpfSVNDOj) pages or fallback when storyGroups is empty
            if storyGroups.isEmpty {
                var targetSlices: [String] = []
                for token in urlStoryTokens {
                    var searchRange = NSRange(location: 0, length: totalLen)
                    var count = 0
                    while count < 3 && searchRange.location < totalLen {
                        let found = nsUnescaped.range(of: token, options: [], range: searchRange)
                        if found.location == NSNotFound { break }
                        let start = max(0, found.location - 200)
                        let end = min(totalLen, found.location + 18_000)
                        let slice = nsUnescaped.substring(with: NSRange(location: start, length: max(0, end - start)))
                        targetSlices.append(slice)
                        if targetVideoId == nil {
                            targetVideoId = extractFacebookVideoIdFromText(slice)
                        }
                        let nextLoc = found.location + found.length
                        searchRange = NSRange(location: nextLoc, length: max(0, totalLen - nextLoc))
                        count += 1
                    }
                }

                if targetVideoId == nil {
                    targetVideoId = extractFacebookVideoIdFromText(unescaped)
                }

                if let vid = targetVideoId, !vid.isEmpty {
                    var searchRange = NSRange(location: 0, length: totalLen)
                    var count = 0
                    while count < 4 && searchRange.location < totalLen {
                        let found = nsUnescaped.range(of: vid, options: [], range: searchRange)
                        if found.location == NSNotFound { break }
                        let start = max(0, found.location - 4_000)
                        let end = min(totalLen, found.location + 45_000)
                        targetSlices.append(nsUnescaped.substring(with: NSRange(location: start, length: max(0, end - start))))
                        let nextLoc = found.location + found.length
                        searchRange = NSRange(location: nextLoc, length: max(0, totalLen - nextLoc))
                        count += 1
                    }
                }

                for slice in targetSlices {
                    extractFacebookStreamsFromSlice(region: slice, isTargetSlice: true) { u, lbl, sc, isT, audUrl in
                        registerCandidateUrl(
                            rawCandidate: u,
                            defaultLabel: lbl,
                            defaultScore: sc,
                            isTargetSlice: isT,
                            pairedAudioUrl: audUrl
                        )
                    }
                }

                extractFacebookStreamsFromSlice(region: unescaped, isTargetSlice: false) { u, lbl, sc, isT, audUrl in
                    registerCandidateUrl(
                        rawCandidate: u,
                        defaultLabel: lbl,
                        defaultScore: sc,
                        isTargetSlice: isT,
                        pairedAudioUrl: audUrl
                    )
                }
            }

            let redirectMatches = regexMatches(pattern: videoRedirectPattern, in: html) +
                regexMatches(pattern: videoRedirectPattern, in: unescaped)
            for m in redirectMatches {
                if let rawSrc = m.group(1), !rawSrc.isEmpty {
                    let replaced = rawSrc.replacingOccurrences(of: "&amp;", with: "&")
                    let decoded = replaced.removingPercentEncoding ?? unescapeHtmlAndJson(rawSrc)
                    registerCandidateUrl(rawCandidate: decoded, defaultLabel: "SD 360p (MP4)", defaultScore: 360)
                }
            }

            if ogVideoUrl.isEmpty {
                ogVideoUrl = firstNonEmpty(
                    extractMetaContent(html: html, property: "og:video:secure_url"),
                    extractMetaContent(html: html, property: "og:video"),
                    extractMetaContent(html: html, property: "og:video:url")
                )
            }

            if ogImageUrl.isEmpty {
                let prefThumb = firstRegexMatch(
                    pattern: #""preferred_thumbnail"\s*:\s*\{.{0,180}?"uri"\s*:\s*"(https?:[^"]+)""#,
                    in: unescaped
                )?.group(1) ?? ""
                let prevThumb = firstRegexMatch(
                    pattern: #""(?:previewImage|thumbnailImage|preview_image)"\s*:\s*\{.{0,120}?"uri"\s*:\s*"(https?:[^"]+)""#,
                    in: unescaped
                )?.group(1) ?? ""
                ogImageUrl = firstNonEmpty(
                    extractMetaContent(html: html, property: "og:image"),
                    prefThumb,
                    prevThumb
                )
            }

            if detectedAuthor.isEmpty {
                if let rawAuthor = firstRegexMatch(
                    pattern: #""(?:story_bucket_owner|owner)"\s*:\s*\{[^{}]*"name"\s*:\s*"([^"]+)""#,
                    in: unescaped
                )?.group(1) {
                    detectedAuthor = decodeJsonUnicodeString(rawAuthor)
                }
            }

            if ogTitle.isEmpty || ogTitle.caseInsensitiveCompare("Facebook") == .orderedSame {
                let metaTitle = firstNonEmpty(
                    extractMetaContent(html: html, property: "og:title"),
                    extractHtmlTagTitle(html)
                )
                if !metaTitle.isEmpty && metaTitle.caseInsensitiveCompare("Facebook") != .orderedSame {
                    ogTitle = metaTitle
                } else if let relayRaw = firstRegexMatch(
                    pattern: #""(?:savable_description|message)"\s*:\s*\{[^{}]*"text"\s*:\s*"([^"]+)""#,
                    in: unescaped
                )?.group(1) {
                    let relayText = decodeJsonUnicodeString(relayRaw)
                    if !relayText.isEmpty {
                        ogTitle = relayText
                    }
                }
            }

            if ogDescription.isEmpty {
                ogDescription = extractMetaContent(html: html, property: "og:description")
            }
        }

        // 1. Primary Desktop Comet requests (www.facebook.com & web.facebook.com)
        var initialDesktopTargets: [String] = [finalResolvedUrl]
        if finalResolvedUrl.contains("://www.facebook.com") {
            initialDesktopTargets.append(finalResolvedUrl.replacingOccurrences(of: "://www.facebook.com", with: "://web.facebook.com"))
        }
        if let vid = targetVideoId, !vid.isEmpty, !finalResolvedUrl.lowercased().contains("/stories/") {
            initialDesktopTargets.append("https://www.facebook.com/watch/?v=\(vid)")
            initialDesktopTargets.append("https://www.facebook.com/reel/\(vid)")
        }

        var seenDesktopTargets = Set<String>()
        for desktopTarget in initialDesktopTargets where seenDesktopTargets.insert(desktopTarget).inserted {
            if let (html, resolved) = await fetchFacebookDesktopPage(urlToFetch: desktopTarget, desktopCookie: desktopCookie) {
                if resolved.contains("facebook.com") && !resolved.contains("/login") {
                    finalResolvedUrl = toDesktopFacebookUrl(resolved)
                }
                if !html.isEmpty {
                    parseFacebookHtml(html)
                }
            }
            if !storyGroups.isEmpty {
                break
            }
            if candidates.contains(where: { $0.mediaType == .video && $0.score >= 720 }) {
                break
            }
        }

        // 2. Two-stage upgrade: if initial desktop request doesn't find HD (and no story bucket was parsed),
        // probe `mbasic.facebook.com` / `m.facebook.com` to discover `targetVideoId`, then query `/watch/?v=<id>` and `/reel/<id>` for 1080p Full HD
        let hasHdVideo = !storyGroups.isEmpty || candidates.contains(where: { $0.mediaType == .video && $0.score >= 720 })
        let videoIdBeforeMobile = targetVideoId
        if !hasHdVideo {
            let baseTarget = finalResolvedUrl.isEmpty ? cleanUrl : finalResolvedUrl
            let mbasicUrl = baseTarget
                .replacingOccurrences(of: "://www.facebook.com", with: "://mbasic.facebook.com")
                .replacingOccurrences(of: "://m.facebook.com", with: "://mbasic.facebook.com")
                .replacingOccurrences(of: "://web.facebook.com", with: "://mbasic.facebook.com")
            let mobileUrl = baseTarget
                .replacingOccurrences(of: "://www.facebook.com", with: "://m.facebook.com")
                .replacingOccurrences(of: "://mbasic.facebook.com", with: "://m.facebook.com")
                .replacingOccurrences(of: "://web.facebook.com", with: "://m.facebook.com")

            let fallbackTargets: [(String, String)] = [
                (mbasicUrl, Self.desktopChromeUA),
                (mobileUrl, Self.mobileSafariUA)
            ]

            var seenFallback = Set<String>()
            for (fallbackUrl, ua) in fallbackTargets where seenFallback.insert(fallbackUrl).inserted {
                var headers: [String: String] = [
                    "User-Agent": ua,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "en-US,en;q=0.9,vi;q=0.8"
                ]
                if !cookie.isEmpty {
                    headers["Cookie"] = cookie
                }
                if let (fallbackHtml, finalUrl) = await fetchText(url: fallbackUrl, headers: headers),
                   !fallbackHtml.isEmpty {
                    let resolved = unwrapFacebookLoginRedirect(finalUrl)
                    if resolved.contains("facebook.com") && !resolved.contains("/login") && finalResolvedUrl.contains("/share/") {
                        finalResolvedUrl = toDesktopFacebookUrl(resolved)
                    }
                    parseFacebookHtml(fallbackHtml)
                }
            }

            // 3. Second-stage HD Upgrade using newly discovered `targetVideoId`
            let newlyDiscoveredVid = targetVideoId
            var hdUpgradeUrls: [String] = []
            if !finalResolvedUrl.contains("/share/") {
                hdUpgradeUrls.append(finalResolvedUrl)
                hdUpgradeUrls.append(finalResolvedUrl.replacingOccurrences(of: "://www.facebook.com", with: "://web.facebook.com"))
            }
            if let newVid = newlyDiscoveredVid, !newVid.isEmpty, newVid != videoIdBeforeMobile {
                hdUpgradeUrls.append("https://www.facebook.com/watch/?v=\(newVid)")
                hdUpgradeUrls.append("https://www.facebook.com/reel/\(newVid)")
                hdUpgradeUrls.append("https://web.facebook.com/watch/?v=\(newVid)")
            }

            if storyGroups.isEmpty && !candidates.contains(where: { $0.score >= 720 }) {
                var seenUpgrade = Set<String>()
                for upgradeUrl in hdUpgradeUrls where seenUpgrade.insert(upgradeUrl).inserted {
                    if let (upgradeHtml, resolved) = await fetchFacebookDesktopPage(urlToFetch: upgradeUrl, desktopCookie: desktopCookie) {
                        if resolved.contains("facebook.com") && !resolved.contains("/login") {
                            finalResolvedUrl = toDesktopFacebookUrl(resolved)
                        }
                        if !upgradeHtml.isEmpty {
                            parseFacebookHtml(upgradeHtml)
                        }
                    }
                    if !storyGroups.isEmpty || candidates.contains(where: { $0.mediaType == .video && $0.score >= 720 }) {
                        break
                    }
                }
            }
        }

        if storyGroups.isEmpty && !candidates.contains(where: { $0.mediaType == .video }) && ogVideoUrl.lowercased().hasPrefix("http") {
            registerCandidateUrl(rawCandidate: ogVideoUrl, defaultLabel: "HD Video (MP4)", defaultScore: 720)
        }

        let fallbackDashAudioUrl = candidates.first(where: { $0.mediaType == .audio })?.url
        let resolvedAuthor = detectedAuthor.isEmpty ? "Facebook" : detectedAuthor

        var options: [MediaOption] = []
        var optionSeenUrls = Set<String>()
        let safeTitle: String

        if !storyGroups.isEmpty {
            let targetIdx: Int = storyGroups.firstIndex(where: {
                targetStoryKeys.contains($0.key) || (targetVideoId != nil && $0.videoId == targetVideoId)
            }) ?? 0

            let hasExplicitTarget = !targetStoryKeys.isEmpty || hintVideoId != nil
            let totalStories = storyGroups.count
            let targetGroup = storyGroups[targetIdx]

            let targetGroupAudioUrl = targetGroup.candidates.first(where: { $0.mediaType == .audio })?.url
                ?? (targetGroup.videoId.flatMap { globalAudioByVideoId[$0] })
                ?? fallbackDashAudioUrl

            let targetPrimaryCands = targetGroup.candidates
                .filter { $0.mediaType == .video || $0.mediaType == .image }
                .sorted { lhs, rhs in
                    let lv = lhs.mediaType == .video ? 1 : 0
                    let rv = rhs.mediaType == .video ? 1 : 0
                    return lv != rv ? lv > rv : lhs.score > rhs.score
                }

            var markedHighest = false
            for cand in targetPrimaryCands {
                guard optionSeenUrls.insert(cand.url).inserted else { continue }
                let resolvedAudio: String?
                if cand.mediaType == .video {
                    if let directAudio = cand.audioUrl {
                        resolvedAudio = directAudio
                    } else if let vid = cand.videoId, let byVid = globalAudioByVideoId[vid] {
                        resolvedAudio = byVid
                    } else if isFacebookDashVideoOnlyUrl(cand.url) {
                        resolvedAudio = targetGroupAudioUrl
                    } else {
                        resolvedAudio = nil
                    }
                } else {
                    resolvedAudio = nil
                }

                let prefix: String
                if totalStories > 1 {
                    prefix = "Story #\(targetIdx + 1) (Link này) • "
                } else {
                    prefix = "Story #1 • "
                }

                options.append(
                    MediaOption(
                        url: cand.url,
                        qualityLabel: "\(prefix)\(cand.label)",
                        fileExtension: cand.fileExtension,
                        mediaType: cand.mediaType,
                        isHighestQuality: !markedHighest,
                        isNoWatermark: true,
                        customHeaders: fbHeaders,
                        audioUrl: resolvedAudio
                    )
                )
                markedHighest = true
            }

            if totalStories > 1 {
                for idx in 0..<totalStories where idx != targetIdx {
                    let otherGroup = storyGroups[idx]
                    let otherAudioUrl = otherGroup.candidates.first(where: { $0.mediaType == .audio })?.url
                        ?? (otherGroup.videoId.flatMap { globalAudioByVideoId[$0] })
                        ?? fallbackDashAudioUrl

                    let otherPrimary = otherGroup.candidates
                        .filter { $0.mediaType == .video || $0.mediaType == .image }
                        .sorted { lhs, rhs in
                            let lv = lhs.mediaType == .video ? 1 : 0
                            let rv = rhs.mediaType == .video ? 1 : 0
                            return lv != rv ? lv > rv : lhs.score > rhs.score
                        }

                    if let bestCand = otherPrimary.first, optionSeenUrls.insert(bestCand.url).inserted {
                        let resolvedAudio: String?
                        if bestCand.mediaType == .video {
                            if let directAudio = bestCand.audioUrl {
                                resolvedAudio = directAudio
                            } else if let vid = bestCand.videoId, let byVid = globalAudioByVideoId[vid] {
                                resolvedAudio = byVid
                            } else if isFacebookDashVideoOnlyUrl(bestCand.url) {
                                resolvedAudio = otherAudioUrl
                            } else {
                                resolvedAudio = nil
                            }
                        } else {
                            resolvedAudio = nil
                        }

                        options.append(
                            MediaOption(
                                url: bestCand.url,
                                qualityLabel: "Story #\(idx + 1) • \(bestCand.label)",
                                fileExtension: bestCand.fileExtension,
                                mediaType: bestCand.mediaType,
                                isHighestQuality: false,
                                isNoWatermark: true,
                                customHeaders: fbHeaders,
                                audioUrl: resolvedAudio
                            )
                        )
                    }
                }
            }

            if let audUrl = targetGroupAudioUrl, optionSeenUrls.insert(audUrl).inserted {
                options.append(
                    MediaOption(
                        url: audUrl,
                        qualityLabel: "Âm thanh gốc (M4A)",
                        fileExtension: "m4a",
                        mediaType: .audio,
                        isHighestQuality: false,
                        isNoWatermark: true,
                        customHeaders: fbHeaders
                    )
                )
            }

            if ogImageUrl.lowercased().hasPrefix("http") && optionSeenUrls.insert(ogImageUrl).inserted {
                options.append(
                    MediaOption(
                        url: ogImageUrl,
                        qualityLabel: "Ảnh bìa (Cover)",
                        fileExtension: "jpg",
                        mediaType: .image,
                        isHighestQuality: options.isEmpty,
                        isNoWatermark: true,
                        customHeaders: fbHeaders
                    )
                )
            }

            let shortId = String((targetGroup.videoId ?? targetGroup.key).suffix(4))
            if totalStories > 1 && hasExplicitTarget {
                safeTitle = "Story #\(targetIdx + 1)/\(totalStories) của \(resolvedAuthor) (#\(shortId))"
            } else if totalStories > 1 {
                safeTitle = "Trọn bộ \(totalStories) Story của \(resolvedAuthor) (#\(shortId))"
            } else {
                safeTitle = "Story của \(resolvedAuthor) (#\(shortId))"
            }
        } else {
            // Regular Facebook video/reel or multi-video post
            let videoCands = candidates.filter { $0.mediaType == .video }
            var orderedVideoIds: [String] = []
            for vc in videoCands {
                let vidKey = vc.videoId ?? "__default__"
                if !orderedVideoIds.contains(vidKey) {
                    orderedVideoIds.append(vidKey)
                }
            }
            if let tVid = targetVideoId, let pos = orderedVideoIds.firstIndex(of: tVid), pos > 0 {
                orderedVideoIds.remove(at: pos)
                orderedVideoIds.insert(tVid, at: 0)
            }

            let hasMultipleVideos = orderedVideoIds.filter { $0 != "__default__" }.count > 1
            var markedHighestVideo = false

            if hasMultipleVideos {
                for (vIdx, vidKey) in orderedVideoIds.enumerated() {
                    let groupCands = videoCands
                        .filter { ($0.videoId ?? "__default__") == vidKey }
                        .sorted { $0.score > $1.score }
                    if vIdx == 0 {
                        for cand in groupCands where optionSeenUrls.insert(cand.url).inserted {
                            let resolvedAudio = cand.audioUrl
                                ?? (cand.videoId.flatMap { globalAudioByVideoId[$0] })
                                ?? (isFacebookDashVideoOnlyUrl(cand.url) ? fallbackDashAudioUrl : nil)
                            options.append(
                                MediaOption(
                                    url: cand.url,
                                    qualityLabel: "Video #1 • \(cand.label)",
                                    fileExtension: cand.fileExtension,
                                    mediaType: .video,
                                    isHighestQuality: !markedHighestVideo,
                                    isNoWatermark: true,
                                    customHeaders: fbHeaders,
                                    audioUrl: resolvedAudio
                                )
                            )
                            markedHighestVideo = true
                        }
                    } else if let best = groupCands.first, optionSeenUrls.insert(best.url).inserted {
                        let resolvedAudio = best.audioUrl
                            ?? (best.videoId.flatMap { globalAudioByVideoId[$0] })
                            ?? (isFacebookDashVideoOnlyUrl(best.url) ? fallbackDashAudioUrl : nil)
                        options.append(
                            MediaOption(
                                url: best.url,
                                qualityLabel: "Video #\(vIdx + 1) • \(best.label)",
                                fileExtension: best.fileExtension,
                                mediaType: .video,
                                isHighestQuality: false,
                                isNoWatermark: true,
                                customHeaders: fbHeaders,
                                audioUrl: resolvedAudio
                            )
                        )
                    }
                }
            } else {
                let sortedVideoAndAudio = candidates.sorted { lhs, rhs in
                    let lhsIsVideo = lhs.mediaType == .video ? 1 : 0
                    let rhsIsVideo = rhs.mediaType == .video ? 1 : 0
                    if lhsIsVideo != rhsIsVideo {
                        return lhsIsVideo > rhsIsVideo
                    }
                    return lhs.score > rhs.score
                }

                for cand in sortedVideoAndAudio where optionSeenUrls.insert(cand.url).inserted {
                    let isHighest: Bool
                    if cand.mediaType == .video && !markedHighestVideo {
                        markedHighestVideo = true
                        isHighest = true
                    } else {
                        isHighest = false
                    }
                    let resolvedAudioUrl: String?
                    if let directAudio = cand.audioUrl {
                        resolvedAudioUrl = directAudio
                    } else if let vid = cand.videoId, let byVid = globalAudioByVideoId[vid] {
                        resolvedAudioUrl = byVid
                    } else if cand.mediaType == .video && isFacebookDashVideoOnlyUrl(cand.url) {
                        resolvedAudioUrl = fallbackDashAudioUrl
                    } else {
                        resolvedAudioUrl = nil
                    }
                    options.append(
                        MediaOption(
                            url: cand.url,
                            qualityLabel: cand.label,
                            fileExtension: cand.fileExtension,
                            mediaType: cand.mediaType,
                            isHighestQuality: isHighest,
                            isNoWatermark: true,
                            customHeaders: fbHeaders,
                            audioUrl: resolvedAudioUrl
                        )
                    )
                }
            }

            if hasMultipleVideos, let audUrl = fallbackDashAudioUrl, optionSeenUrls.insert(audUrl).inserted {
                options.append(
                    MediaOption(
                        url: audUrl,
                        qualityLabel: "Âm thanh gốc (M4A)",
                        fileExtension: "m4a",
                        mediaType: .audio,
                        isHighestQuality: false,
                        isNoWatermark: true,
                        customHeaders: fbHeaders
                    )
                )
            }

            if ogImageUrl.lowercased().hasPrefix("http") && optionSeenUrls.insert(ogImageUrl).inserted {
                options.append(
                    MediaOption(
                        url: ogImageUrl,
                        qualityLabel: "Ảnh bìa (Cover)",
                        fileExtension: "jpg",
                        mediaType: .image,
                        isHighestQuality: options.isEmpty,
                        isNoWatermark: true,
                        customHeaders: fbHeaders
                    )
                )
            }

            let shortVid: String = {
                if let vid = targetVideoId, !vid.isEmpty {
                    return String(vid.suffix(4))
                }
                if let tok = urlStoryTokens.first, !tok.isEmpty {
                    return String(normalizeFacebookStoryCardKey(tok).suffix(4))
                }
                let h = abs(finalResolvedUrl.hashValue) % 10_000
                return String(format: "%04d", h)
            }()

            let isStory = finalResolvedUrl.lowercased().contains("/stories/")
            let defaultFallbackTitle: String
            if isStory && !detectedAuthor.isEmpty {
                defaultFallbackTitle = "Story của \(detectedAuthor) (#\(shortVid))"
            } else if isStory {
                defaultFallbackTitle = "Facebook Story (#\(shortVid))"
            } else if !detectedAuthor.isEmpty {
                defaultFallbackTitle = "Video của \(detectedAuthor) (#\(shortVid))"
            } else {
                defaultFallbackTitle = "Video Facebook (#\(shortVid))"
            }

            let validOgTitle: String = {
                let trimmed = ogTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty,
                      trimmed.caseInsensitiveCompare("Facebook") != .orderedSame,
                      !trimmed.lowercased().contains("log into facebook") else {
                    return ""
                }
                return trimmed
            }()

            let rawChosenTitle = firstNonEmpty(validOgTitle, ogDescription, defaultFallbackTitle)
            if (rawChosenTitle.hasPrefix("Story của ") ||
                rawChosenTitle.hasPrefix("Facebook Story") ||
                rawChosenTitle.hasPrefix("Video của ") ||
                rawChosenTitle == "Video Facebook") && !rawChosenTitle.contains("(#") {
                safeTitle = "\(rawChosenTitle) (#\(shortVid))"
            } else {
                safeTitle = rawChosenTitle
            }
        }

        guard !options.isEmpty else { return nil }

        return ExtractedMediaInfo(
            originalUrl: originalInput,
            platform: .facebook,
            title: safeTitle,
            author: resolvedAuthor,
            thumbnailUrl: ogImageUrl,
            durationSeconds: 0,
            options: options
        )
    }

    // MARK: - Instagram & Threads Direct Extractors

    private func instagramShortcodeToMediaId(_ shortcode: String) -> String? {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        var digits: [Int] = [0]
        for ch in shortcode {
            guard let idx = alphabet.firstIndex(of: ch) else { return nil }
            var carry = idx
            for i in 0..<digits.count {
                let prod = digits[i] * 64 + carry
                digits[i] = prod % 10
                carry = prod / 10
            }
            while carry > 0 {
                digits.append(carry % 10)
                carry /= 10
            }
        }
        let result = digits.reversed().map(String.init).joined()
        return result.count >= 10 ? result : nil
    }

    private func extractInstagramVideoVersionsFromText(_ unescapedText: String) -> [ScoredStreamCandidate] {
        var results: [ScoredStreamCandidate] = []
        var seen = Set<String>()

        let arrayMatches = regexMatches(
            pattern: #""video_versions"\s*:\s*\[([\s\S]*?)\]"#,
            in: unescapedText
        )
        for arrayMatch in arrayMatches {
            let arrayBody = arrayMatch.group(1) ?? ""
            for objMatch in regexMatches(pattern: #"\{[^{}]+\}"#, in: arrayBody) {
                let objText = objMatch.fullMatch
                guard let rawUrl = firstRegexMatch(pattern: #""url"\s*:\s*"([^"]+)""#, in: objText)?.group(1) else {
                    continue
                }
                let url = sanitizeCandidateUrl(rawUrl)
                guard url.lowercased().hasPrefix("http"), seen.insert(url).inserted else {
                    continue
                }

                let width = Int(firstRegexMatch(pattern: #""width"\s*:\s*(\d+)"#, in: objText)?.group(1) ?? "") ?? 0
                let height = Int(firstRegexMatch(pattern: #""height"\s*:\s*(\d+)"#, in: objText)?.group(1) ?? "") ?? 0
                let maxDim = max(width, height)
                let minDim = (width > 0 && height > 0) ? min(width, height) : 0
                let score: Int
                if minDim >= 1080 || maxDim >= 1920 {
                    score = 1080
                } else if minDim >= 720 || maxDim >= 1280 {
                    score = 720
                } else if minDim >= 540 {
                    score = 540
                } else if minDim >= 480 {
                    score = 480
                } else {
                    score = 800
                }

                let dimBadge = (width > 0 && height > 0) ? " (\(width)x\(height))" : ""
                let label: String
                if score >= 1080 {
                    label = "Full HD 1080p\(dimBadge) (MP4)"
                } else if score >= 720 {
                    label = "HD 720p\(dimBadge) (MP4)"
                } else {
                    label = "SD Video\(dimBadge) (MP4)"
                }
                results.append(
                    ScoredStreamCandidate(
                        url: url,
                        label: label,
                        score: score,
                        mediaType: .video,
                        fileExtension: "mp4",
                        audioUrl: nil
                    )
                )
            }
        }
        return results.sorted(by: { $0.score > $1.score })
    }

    private func extractInstagramDirect(originalInput: String, cleanUrl: String) async -> ExtractedMediaInfo? {
        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: .instagram, targetUrl: cleanUrl)
        let igHeaders = buildMediaHeaders(for: .instagram, cookie: cookie)

        let shortcode = firstRegexMatch(pattern: #"/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)"#, in: cleanUrl)?.group(1)
        let storyPk = firstRegexMatch(pattern: #"/stories/[^/]+/(\d{10,25})"#, in: cleanUrl)?.group(1)
        let mediaId = storyPk ?? shortcode.flatMap { instagramShortcodeToMediaId($0) }

        let hasSessionId = cookie.contains("sessionid=")

        // 1. If logged in and mediaId is known, query Instagram Mobile API v1 for true 1080p Full HD `video_versions` & `video_dash_manifest`
        if hasSessionId, let mediaId = mediaId, !mediaId.isEmpty {
            let apiEndpoints = [
                "https://i.instagram.com/api/v1/media/\(mediaId)/info/",
                "https://www.instagram.com/api/v1/media/\(mediaId)/info/"
            ]
            for apiEndpoint in apiEndpoints {
                let apiHeaders: [String: String] = [
                    "User-Agent": Self.instagramAndroidAppUA,
                    "Accept": "application/json",
                    "X-IG-App-ID": Self.igWebAppId,
                    "Cookie": cookie
                ]
                guard let (apiBody, _) = await fetchText(url: apiEndpoint, headers: apiHeaders),
                      !apiBody.isEmpty else {
                    continue
                }

                let unescapedApi = unescapeHtmlAndJson(apiBody)
                var versionCandidates = extractInstagramVideoVersionsFromText(unescapedApi)
                var seenUrls = Set(versionCandidates.map { $0.url })

                extractFacebookStreamsFromSlice(region: unescapedApi, isTargetSlice: true) { u, lbl, sc, _, audUrl in
                    guard let u = u else { return }
                    let cleaned = sanitizeCandidateUrl(u)
                    guard cleaned.lowercased().hasPrefix("http"), seenUrls.insert(cleaned).inserted else { return }
                    let mt: MediaType = lbl.uppercased().contains("M4A") ? .audio : .video
                    versionCandidates.append(
                        ScoredStreamCandidate(
                            url: cleaned,
                            label: lbl,
                            score: sc,
                            mediaType: mt,
                            fileExtension: mt == .audio ? "m4a" : "mp4",
                            audioUrl: audUrl
                        )
                    )
                }

                if !versionCandidates.isEmpty {
                    let thumb = firstRegexMatch(
                        pattern: #""image_versions2"\s*:\s*\{\s*"candidates"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"([^"]+)""#,
                        in: unescapedApi
                    )?.group(1).map { sanitizeCandidateUrl($0) } ?? ""
                    let username: String
                    if let uname = firstRegexMatch(pattern: #""username"\s*:\s*"([^"]+)""#, in: unescapedApi)?.group(1), !uname.isEmpty {
                        username = "@\(uname)"
                    } else {
                        username = "Instagram"
                    }
                    let rawCaption = firstRegexMatch(
                        pattern: #""caption"\s*:\s*\{[^{}]*"text"\s*:\s*"([^"]+)""#,
                        in: unescapedApi
                    )?.group(1)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    let shortId = String(mediaId.suffix(4))
                    let isIgStory = cleanUrl.lowercased().contains("/stories/")
                    let captionText: String
                    if !rawCaption.isEmpty {
                        captionText = rawCaption
                    } else if isIgStory {
                        captionText = "Story Instagram của \(username) (#\(shortId))"
                    } else {
                        captionText = "Instagram Full HD Media (#\(shortId))"
                    }

                    let sortedCands = versionCandidates.sorted { lhs, rhs in
                        let lv = lhs.mediaType == .video ? 1 : 0
                        let rv = rhs.mediaType == .video ? 1 : 0
                        return lv != rv ? lv > rv : lhs.score > rhs.score
                    }

                    var markedHighest = false
                    var options: [MediaOption] = sortedCands.map { cand in
                        let isHighest: Bool
                        if cand.mediaType == .video && !markedHighest {
                            markedHighest = true
                            isHighest = true
                        } else {
                            isHighest = false
                        }
                        return MediaOption(
                            url: cand.url,
                            qualityLabel: cand.label,
                            fileExtension: cand.fileExtension,
                            mediaType: cand.mediaType,
                            isHighestQuality: isHighest,
                            isNoWatermark: true,
                            customHeaders: igHeaders,
                            audioUrl: cand.audioUrl
                        )
                    }

                    if thumb.lowercased().hasPrefix("http") {
                        options.append(
                            MediaOption(
                                url: thumb,
                                qualityLabel: "Ảnh bìa HD (Cover)",
                                fileExtension: "jpg",
                                mediaType: .image,
                                isHighestQuality: false,
                                isNoWatermark: true,
                                customHeaders: igHeaders
                            )
                        )
                    }

                    return ExtractedMediaInfo(
                        originalUrl: originalInput,
                        platform: .instagram,
                        title: captionText.isEmpty ? "Instagram Full HD Media" : captionText,
                        author: username,
                        thumbnailUrl: thumb,
                        durationSeconds: 0,
                        options: options
                    )
                }
            }
        }

        // 2. Web / Embed / GraphQL HTML scraping
        var candidateRequests: [(String, String, Bool)] = []
        if hasSessionId {
            if let sc = shortcode, !sc.isEmpty {
                candidateRequests.append(("https://www.instagram.com/p/\(sc)/?__a=1&__d=dis", Self.desktopChromeUA, true))
            }
            candidateRequests.append((cleanUrl, Self.desktopChromeUA, true))
            candidateRequests.append((cleanUrl, Self.instagramAndroidAppUA, true))
        }
        if let sc = shortcode, !sc.isEmpty {
            candidateRequests.append(("https://www.instagram.com/p/\(sc)/embed/captioned/", Self.desktopChromeUA, hasSessionId))
            candidateRequests.append(("https://www.instagram.com/reel/\(sc)/embed/captioned/", Self.desktopChromeUA, hasSessionId))
        }
        candidateRequests.append((cleanUrl, Self.desktopChromeUA, hasSessionId))

        var seenReqKeys = Set<String>()
        var fallbackImageResult: ExtractedMediaInfo?

        for (targetUrl, userAgent, includeIgAppHeader) in candidateRequests {
            let key = "\(targetUrl)|\(userAgent)"
            guard seenReqKeys.insert(key).inserted else { continue }

            var headers: [String: String] = [
                "User-Agent": userAgent,
                "Accept": "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9"
            ]
            if !cookie.isEmpty {
                headers["Cookie"] = cookie
            }
            if includeIgAppHeader {
                headers["X-IG-App-ID"] = Self.igWebAppId
            }

            guard let (rawHtml, _) = await fetchText(url: targetUrl, headers: headers),
                  !rawHtml.isEmpty else {
                continue
            }

            let unescaped = unescapeHtmlAndJson(rawHtml)
            var scoredVideos: [ScoredStreamCandidate] = []
            var seenVideoUrls = Set<String>()

            for cand in extractInstagramVideoVersionsFromText(unescaped) {
                if seenVideoUrls.insert(cand.url).inserted {
                    scoredVideos.append(cand)
                }
            }

            // Also parse `video_dash_manifest` `<Representation><BaseURL>` with paired audio
            extractFacebookStreamsFromSlice(region: unescaped, isTargetSlice: true) { u, lbl, sc, _, audUrl in
                guard let u = u else { return }
                let cleaned = sanitizeCandidateUrl(u)
                guard cleaned.lowercased().hasPrefix("http"), seenVideoUrls.insert(cleaned).inserted else { return }
                let mt: MediaType = lbl.uppercased().contains("M4A") ? .audio : .video
                scoredVideos.append(
                    ScoredStreamCandidate(
                        url: cleaned,
                        label: lbl,
                        score: sc,
                        mediaType: mt,
                        fileExtension: mt == .audio ? "m4a" : "mp4",
                        audioUrl: audUrl
                    )
                )
            }

            for m in regexMatches(pattern: #""video_url"\s*:\s*"([^"]+)""#, in: unescaped) {
                if let rawU = m.group(1) {
                    let u = sanitizeCandidateUrl(rawU)
                    if u.lowercased().hasPrefix("http") && seenVideoUrls.insert(u).inserted {
                        let efgMeta = inspectFacebookStreamUrl(u)
                        let score = efgMeta.resolutionScore > 0 ? efgMeta.resolutionScore : 950
                        let label = efgMeta.qualityLabel ?? "Full HD 1080p (MP4)"
                        scoredVideos.append(
                            ScoredStreamCandidate(
                                url: u,
                                label: label,
                                score: score,
                                mediaType: .video,
                                fileExtension: "mp4",
                                audioUrl: nil
                            )
                        )
                    }
                }
            }

            let ogVideo = firstNonEmpty(
                extractMetaContent(html: rawHtml, property: "og:video:secure_url"),
                extractMetaContent(html: rawHtml, property: "og:video")
            )
            if ogVideo.lowercased().hasPrefix("http") && seenVideoUrls.insert(ogVideo).inserted {
                scoredVideos.append(
                    ScoredStreamCandidate(
                        url: ogVideo,
                        label: "HD Video (MP4)",
                        score: 720,
                        mediaType: .video,
                        fileExtension: "mp4",
                        audioUrl: nil
                    )
                )
            }

            let displayUrlFallback = firstRegexMatch(pattern: #""display_url"\s*:\s*"([^"]+)""#, in: unescaped)?
                .group(1).map { sanitizeCandidateUrl($0) } ?? ""
            let imageUrl = firstNonEmpty(
                extractMetaContent(html: rawHtml, property: "og:image"),
                displayUrlFallback
            )

            let username: String
            if let uname = firstRegexMatch(pattern: #""username"\s*:\s*"([^"]+)""#, in: unescaped)?.group(1), !uname.isEmpty {
                username = "@\(uname)"
            } else {
                username = "Instagram"
            }

            let rawTitle = firstNonEmpty(
                extractMetaContent(html: rawHtml, property: "og:title"),
                extractHtmlTagTitle(rawHtml)
            )
            let shortId = String((mediaId ?? shortcode ?? String(abs(cleanUrl.hashValue))).suffix(4))
            let isIgStory = cleanUrl.lowercased().contains("/stories/")
            let title: String
            if !rawTitle.isEmpty &&
               rawTitle.caseInsensitiveCompare("Instagram") != .orderedSame &&
               !rawTitle.lowercased().contains("watch this story") {
                title = isIgStory && !rawTitle.contains("(#") ? "\(rawTitle) (#\(shortId))" : rawTitle
            } else if isIgStory {
                title = "Story Instagram của \(username) (#\(shortId))"
            } else {
                title = "Instagram Media \(shortcode ?? "(#\(shortId))")".trimmingCharacters(in: .whitespacesAndNewlines)
            }

            var options: [MediaOption] = []
            let sortedVideos = scoredVideos.sorted { lhs, rhs in
                let lv = lhs.mediaType == .video ? 1 : 0
                let rv = rhs.mediaType == .video ? 1 : 0
                return lv != rv ? lv > rv : lhs.score > rhs.score
            }
            var markedHighest = false
            for cand in sortedVideos {
                let isHighest: Bool
                if cand.mediaType == .video && !markedHighest {
                    markedHighest = true
                    isHighest = true
                } else {
                    isHighest = false
                }
                options.append(
                    MediaOption(
                        url: cand.url,
                        qualityLabel: cand.label,
                        fileExtension: cand.fileExtension,
                        mediaType: cand.mediaType,
                        isHighestQuality: isHighest,
                        isNoWatermark: true,
                        customHeaders: igHeaders,
                        audioUrl: cand.audioUrl
                    )
                )
            }

            if imageUrl.lowercased().hasPrefix("http") {
                options.append(
                    MediaOption(
                        url: imageUrl,
                        qualityLabel: options.isEmpty ? "Ảnh gốc HD" : "Ảnh bìa (Cover)",
                        fileExtension: "jpg",
                        mediaType: .image,
                        isHighestQuality: options.isEmpty,
                        isNoWatermark: true,
                        customHeaders: igHeaders
                    )
                )
            }

            if options.contains(where: { $0.mediaType == .video }) {
                return ExtractedMediaInfo(
                    originalUrl: originalInput,
                    platform: .instagram,
                    title: title,
                    author: username,
                    thumbnailUrl: imageUrl,
                    durationSeconds: 0,
                    options: options
                )
            } else if !options.isEmpty && fallbackImageResult == nil {
                fallbackImageResult = ExtractedMediaInfo(
                    originalUrl: originalInput,
                    platform: .instagram,
                    title: title,
                    author: username,
                    thumbnailUrl: imageUrl,
                    durationSeconds: 0,
                    options: options
                )
            }
        }

        return fallbackImageResult
    }

    private func extractThreadsDirect(originalInput: String, cleanUrl: String) async -> ExtractedMediaInfo? {
        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: .threads, targetUrl: cleanUrl)
        let threadsHeaders = buildMediaHeaders(for: .threads, cookie: cookie)

        var headers: [String: String] = [
            "User-Agent": Self.desktopChromeUA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9"
        ]
        if !cookie.isEmpty {
            headers["Cookie"] = cookie
        }

        guard let (rawHtml, _) = await fetchText(url: cleanUrl, headers: headers),
              !rawHtml.isEmpty else {
            return nil
        }

        let unescaped = unescapeHtmlAndJson(rawHtml)
        var versionCandidates = extractInstagramVideoVersionsFromText(unescaped)
        var seenUrls = Set(versionCandidates.map { $0.url })

        extractFacebookStreamsFromSlice(region: unescaped, isTargetSlice: true) { u, lbl, sc, _, audUrl in
            guard let u = u else { return }
            let cleaned = sanitizeCandidateUrl(u)
            guard cleaned.lowercased().hasPrefix("http"), seenUrls.insert(cleaned).inserted else { return }
            let mt: MediaType = lbl.uppercased().contains("M4A") ? .audio : .video
            versionCandidates.append(
                ScoredStreamCandidate(
                    url: cleaned,
                    label: lbl,
                    score: sc,
                    mediaType: mt,
                    fileExtension: mt == .audio ? "m4a" : "mp4",
                    audioUrl: audUrl
                )
            )
        }

        let jsonVideoUrl = firstRegexMatch(pattern: #""video_url"\s*:\s*"([^"]+)""#, in: unescaped)?
            .group(1).map { sanitizeCandidateUrl($0) } ?? ""
        let fallbackVideoUrl = firstNonEmpty(
            jsonVideoUrl,
            extractMetaContent(html: rawHtml, property: "og:video:secure_url"),
            extractMetaContent(html: rawHtml, property: "og:video")
        )

        let candidateImg = firstRegexMatch(
            pattern: #""image_versions2"\s*:\s*\{\s*"candidates"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"([^"]+)""#,
            in: unescaped
        )?.group(1).map { sanitizeCandidateUrl($0) } ?? ""
        let displayImg = firstRegexMatch(pattern: #""display_url"\s*:\s*"([^"]+)""#, in: unescaped)?
            .group(1).map { sanitizeCandidateUrl($0) } ?? ""
        let imageUrl = firstNonEmpty(
            extractMetaContent(html: rawHtml, property: "og:image"),
            candidateImg,
            displayImg
        )

        let title = firstNonEmpty(
            extractMetaContent(html: rawHtml, property: "og:description"),
            extractMetaContent(html: rawHtml, property: "og:title"),
            "Bài đăng Threads"
        )

        let author = firstRegexMatch(pattern: #"@([A-Za-z0-9_.]+)"#, in: cleanUrl)?.fullMatch ?? "Threads"

        var options: [MediaOption] = []
        if !versionCandidates.isEmpty {
            let sorted = versionCandidates.sorted { lhs, rhs in
                let lv = lhs.mediaType == .video ? 1 : 0
                let rv = rhs.mediaType == .video ? 1 : 0
                return lv != rv ? lv > rv : lhs.score > rhs.score
            }
            var markedHighest = false
            for cand in sorted {
                let isHighest: Bool
                if cand.mediaType == .video && !markedHighest {
                    markedHighest = true
                    isHighest = true
                } else {
                    isHighest = false
                }
                options.append(
                    MediaOption(
                        url: cand.url,
                        qualityLabel: cand.label,
                        fileExtension: cand.fileExtension,
                        mediaType: cand.mediaType,
                        isHighestQuality: isHighest,
                        isNoWatermark: true,
                        customHeaders: threadsHeaders,
                        audioUrl: cand.audioUrl
                    )
                )
            }
        } else if fallbackVideoUrl.lowercased().hasPrefix("http") {
            options.append(
                MediaOption(
                    url: fallbackVideoUrl,
                    qualityLabel: "Full HD Video (MP4)",
                    fileExtension: "mp4",
                    mediaType: .video,
                    isHighestQuality: true,
                    isNoWatermark: true,
                    customHeaders: threadsHeaders
                )
            )
        }

        if imageUrl.lowercased().hasPrefix("http") {
            options.append(
                MediaOption(
                    url: imageUrl,
                    qualityLabel: options.isEmpty ? "Ảnh gốc HD" : "Ảnh bìa (Cover)",
                    fileExtension: "jpg",
                    mediaType: .image,
                    isHighestQuality: options.isEmpty,
                    isNoWatermark: true,
                    customHeaders: threadsHeaders
                )
            )
        }

        guard !options.isEmpty else { return nil }

        return ExtractedMediaInfo(
            originalUrl: originalInput,
            platform: .threads,
            title: title,
            author: author,
            thumbnailUrl: imageUrl,
            durationSeconds: 0,
            options: options
        )
    }

    // MARK: - Cobalt API Fallback (v10 & v7)

    private func extractViaCobalt(
        cleanUrl: String,
        detectedPlatform: SocialPlatform,
        originalInput: String
    ) async throws -> ExtractedMediaInfo {
        let customBase = AuthCookieStore.shared.getCustomCobaltUrl()
        var instances: [String] = []
        if !customBase.isEmpty {
            instances.append(customBase)
        }
        instances.append(contentsOf: [
            "https://api.cobalt.tools",
            "https://cobalt-api.kwiatekmiki.com",
            "https://cobalt.api.timelessnesses.me"
        ])

        let platform = detectedPlatform != .unknown ? detectedPlatform : UrlDetector.detectPlatform(cleanUrl)
        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: platform, targetUrl: cleanUrl)
        let defaultHeaders = buildMediaHeaders(for: platform, cookie: cookie)

        for rawInstance in instances {
            let base = rawInstance.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
            guard base.lowercased().hasPrefix("http") else { continue }

            // 1. Try Cobalt v10 (`POST /`)
            let v10Payload: [String: Any] = [
                "url": cleanUrl,
                "videoQuality": "1080",
                "filenameStyle": "basic",
                "downloadMode": "auto"
            ]
            if let v10Data = try? JSONSerialization.data(withJSONObject: v10Payload),
               let (v10Resp, _) = await fetchText(
                   url: "\(base)/",
                   method: "POST",
                   headers: [
                       "Accept": "application/json",
                       "Content-Type": "application/json",
                       "User-Agent": Self.desktopChromeUA
                   ],
                   body: v10Data
               ),
               let info = parseCobaltJsonResponse(
                   v10Resp,
                   originalInput: originalInput,
                   platform: platform,
                   defaultHeaders: defaultHeaders
               ) {
                return info
            }

            // 2. Try Cobalt v7 (`POST /api/json`)
            let v7Payload: [String: Any] = [
                "url": cleanUrl,
                "vQuality": "1080",
                "isNoTTWatermark": true
            ]
            if let v7Data = try? JSONSerialization.data(withJSONObject: v7Payload),
               let (v7Resp, _) = await fetchText(
                   url: "\(base)/api/json",
                   method: "POST",
                   headers: [
                       "Accept": "application/json",
                       "Content-Type": "application/json",
                       "User-Agent": Self.desktopChromeUA
                   ],
                   body: v7Data
               ),
               let info = parseCobaltJsonResponse(
                   v7Resp,
                   originalInput: originalInput,
                   platform: platform,
                   defaultHeaders: defaultHeaders
               ) {
                return info
            }
        }

        throw ExtractorError.extractionFailed("Cobalt API không phản hồi dữ liệu phương tiện hợp lệ.")
    }

    private func parseCobaltJsonResponse(
        _ responseStr: String,
        originalInput: String,
        platform: SocialPlatform,
        defaultHeaders: [String: String]
    ) -> ExtractedMediaInfo? {
        guard let data = responseStr.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }

        let status = (json["status"] as? String ?? "").lowercased()
        if status == "error" || status == "rate-limit" {
            return nil
        }

        var options: [MediaOption] = []
        var thumbnailUrl = ""
        let filename = (json["filename"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        if let mainUrl = (json["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           mainUrl.lowercased().hasPrefix("http") {
            let isAudio = mainUrl.lowercased().contains(".mp3") || mainUrl.lowercased().contains(".m4a")
            options.append(
                MediaOption(
                    url: mainUrl,
                    qualityLabel: isAudio ? "Âm thanh gốc (MP3)" : "Full HD 1080p (MP4)",
                    fileExtension: isAudio ? "mp3" : "mp4",
                    mediaType: isAudio ? .audio : .video,
                    isHighestQuality: true,
                    isNoWatermark: true,
                    customHeaders: defaultHeaders
                )
            )
        }

        if let picker = json["picker"] as? [[String: Any]] {
            var markedHighest = !options.isEmpty
            for (idx, item) in picker.enumerated() {
                guard let itemUrl = (item["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                      itemUrl.lowercased().hasPrefix("http") else {
                    continue
                }
                let type = (item["type"] as? String ?? "video").lowercased()
                let thumb = (item["thumb"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if thumbnailUrl.isEmpty && !thumb.isEmpty {
                    thumbnailUrl = thumb
                }
                let isImage = type == "photo" || type == "image"
                options.append(
                    MediaOption(
                        url: itemUrl,
                        qualityLabel: isImage ? "Ảnh HD #\(idx + 1)" : "Full HD Video #\(idx + 1) (MP4)",
                        fileExtension: isImage ? "jpg" : "mp4",
                        mediaType: isImage ? .image : .video,
                        isHighestQuality: !markedHighest,
                        isNoWatermark: true,
                        customHeaders: defaultHeaders
                    )
                )
                markedHighest = true
            }
        }

        if let audioUrl = (json["audio"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           audioUrl.lowercased().hasPrefix("http") {
            options.append(
                MediaOption(
                    url: audioUrl,
                    qualityLabel: "Nhạc nền gốc (MP3)",
                    fileExtension: "mp3",
                    mediaType: .audio,
                    isHighestQuality: false,
                    isNoWatermark: true,
                    customHeaders: defaultHeaders
                )
            )
        }

        guard !options.isEmpty else { return nil }

        let title = filename.isEmpty ? "Video \(platform.displayName)" : filename
        return ExtractedMediaInfo(
            originalUrl: originalInput,
            platform: platform,
            title: title,
            author: platform.displayName,
            thumbnailUrl: thumbnailUrl,
            durationSeconds: 0,
            options: options
        )
    }

    // MARK: - Universal RapidAPI Fallback

    private func extractViaRapidApi(
        cleanUrl: String,
        detectedPlatform: SocialPlatform,
        originalInput: String
    ) async throws -> ExtractedMediaInfo {
        let encodedUrl = urlEncodeFormValue(cleanUrl)
        let bodyData = "url=\(encodedUrl)".data(using: .utf8)

        let headers: [String: String] = [
            "x-rapidapi-host": Self.rapidApiHost,
            "x-rapidapi-key": Self.defaultRapidApiKey,
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
            "User-Agent": Self.desktopChromeUA
        ]

        guard let (responseStr, _) = await fetchText(
            url: Self.rapidApiEndpoint,
            method: "POST",
            headers: headers,
            body: bodyData
        ),
              let data = responseStr.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw ExtractorError.extractionFailed("Không thể kết nối máy chủ dự phòng.")
        }

        if (json["error"] as? Bool) == true {
            let msg = (json["message"] as? String) ?? "Không thể lấy dữ liệu từ liên kết này."
            throw ExtractorError.extractionFailed(msg)
        }

        let sourceUrl = firstNonEmpty(json["url"] as? String ?? "", cleanUrl)
        let platform = detectedPlatform != .unknown ? detectedPlatform : UrlDetector.detectPlatform(sourceUrl)

        let title = firstNonEmpty(
            (json["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
            "Video \(platform.displayName)"
        )
        let author = firstNonEmpty(
            (json["author"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
            (json["source"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
            platform.displayName
        )
        let thumbnail = (json["thumbnail"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let durationSec = parseDurationSeconds(json["duration"])

        let mediasArray = (json["medias"] as? [[String: Any]]) ?? []
        var highestVideoIndex = -1
        var highestResolutionScore = -1

        for (i, item) in mediasArray.enumerated() {
            let typeStr = (item["type"] as? String ?? "").lowercased()
            let extStr = (item["extension"] as? String ?? "").lowercased()
            if typeStr == "video" || extStr == "mp4" || extStr == "webm" {
                let qualityStr = item["quality"] as? String ?? ""
                let score = extractResolutionScore(qualityStr)
                if score > highestResolutionScore {
                    highestResolutionScore = score
                    highestVideoIndex = i
                }
            }
        }

        let cookie = AuthCookieStore.shared.getEffectiveCookie(for: platform, targetUrl: cleanUrl)
        let defaultHeaders = buildMediaHeaders(for: platform, cookie: cookie)

        var options: [MediaOption] = []
        for (i, item) in mediasArray.enumerated() {
            let mediaUrl = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if mediaUrl.isEmpty { continue }

            let typeRaw = (item["type"] as? String ?? "video").lowercased()
            let rawQuality = (item["quality"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let extRaw = (item["extension"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

            let mediaType: MediaType
            if typeRaw.contains("audio") || extRaw == "mp3" || extRaw == "m4a" {
                mediaType = .audio
            } else if typeRaw.contains("image") || typeRaw.contains("photo") || ["jpg", "jpeg", "png", "webp"].contains(extRaw) {
                mediaType = .image
            } else {
                mediaType = .video
            }

            let fileExt: String
            if !extRaw.isEmpty {
                fileExt = extRaw
            } else if mediaType == .audio {
                fileExt = "mp3"
            } else if mediaType == .image {
                fileExt = "jpg"
            } else {
                fileExt = "mp4"
            }

            let lowerQual = rawQuality.lowercased()
            let isNoWatermark = !lowerQual.contains("watermark") ||
                lowerQual.contains("no watermark") ||
                lowerQual.contains("hd_no_watermark")

            let qualityLabel = formatQualityLabel(
                rawQuality: rawQuality,
                mediaType: mediaType,
                fileExtension: fileExt,
                index: i + 1
            )
            let isHighest = (i == highestVideoIndex) || (highestVideoIndex == -1 && i == 0)

            options.append(
                MediaOption(
                    url: mediaUrl,
                    qualityLabel: qualityLabel,
                    fileExtension: fileExt,
                    mediaType: mediaType,
                    isHighestQuality: isHighest,
                    isNoWatermark: isNoWatermark,
                    customHeaders: defaultHeaders
                )
            )
        }

        if thumbnail.lowercased().hasPrefix("http") {
            options.append(
                MediaOption(
                    url: thumbnail,
                    qualityLabel: "Ảnh bìa (Cover)",
                    fileExtension: "jpg",
                    mediaType: .image,
                    isHighestQuality: options.isEmpty,
                    isNoWatermark: true,
                    customHeaders: defaultHeaders
                )
            )
        }

        return ExtractedMediaInfo(
            originalUrl: originalInput,
            platform: platform,
            title: title,
            author: author,
            thumbnailUrl: thumbnail,
            durationSeconds: durationSec,
            options: options
        )
    }

    // MARK: - Networking & String / Regex Helpers

    private func buildMediaHeaders(
        for platform: SocialPlatform,
        cookie: String,
        userAgent: String = MultiPlatformExtractor.desktopChromeUA
    ) -> [String: String] {
        var headers: [String: String] = [
            "User-Agent": userAgent,
            "Referer": defaultReferer(for: platform),
            "Origin": defaultOrigin(for: platform)
        ]
        if !cookie.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            headers["Cookie"] = cookie
        }
        return headers
    }

    private func defaultReferer(for platform: SocialPlatform) -> String {
        switch platform {
        case .facebook: return "https://www.facebook.com/"
        case .instagram: return "https://www.instagram.com/"
        case .tiktok: return "https://www.tiktok.com/"
        case .xTwitter: return "https://x.com/"
        case .threads: return "https://www.threads.net/"
        case .unknown: return "https://www.google.com/"
        }
    }

    private func defaultOrigin(for platform: SocialPlatform) -> String {
        switch platform {
        case .facebook: return "https://www.facebook.com"
        case .instagram: return "https://www.instagram.com"
        case .tiktok: return "https://www.tiktok.com"
        case .xTwitter: return "https://x.com"
        case .threads: return "https://www.threads.net"
        case .unknown: return "https://www.google.com"
        }
    }

    private func fetchFacebookDesktopPage(
        urlToFetch: String,
        desktopCookie: String
    ) async -> (String, String)? {
        var headers: [String: String] = [
            "User-Agent": Self.desktopChromeUA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9,vi;q=0.8",
            "Sec-Ch-Ua": "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile": "?0",
            "Sec-Ch-Ua-Platform": "\"Windows\"",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
            "Sec-Fetch-User": "?1",
            "Upgrade-Insecure-Requests": "1"
        ]
        if !desktopCookie.isEmpty {
            headers["Cookie"] = desktopCookie
        }
        guard let (body, finalUrl) = await fetchText(url: urlToFetch, headers: headers) else {
            return nil
        }
        let resolved = unwrapFacebookLoginRedirect(finalUrl)
        return (body, resolved)
    }

    private func fetchText(
        url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async -> (String, String)? {
        guard let requestUrl = URL(string: url) else { return nil }
        var request = URLRequest(url: requestUrl)
        request.httpMethod = method
        request.httpBody = body
        for (k, v) in headers {
            request.setValue(v, forHTTPHeaderField: k)
        }
        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResp = response as? HTTPURLResponse,
                  (200...299).contains(httpResp.statusCode) else {
                return nil
            }
            let finalUrl = httpResp.url?.absoluteString ?? url
            let str = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
            return (str, finalUrl)
        } catch {
            return nil
        }
    }

    private func extractResolvedFacebookUrlFromHtml(_ html: String) -> String? {
        guard !html.isEmpty else { return nil }
        let unescaped = unescapeHtmlAndJson(html)
        if let storyUrl = firstRegexMatch(
            pattern: #"https://(?:www\.|m\.|web\.)?facebook\.com/stories/[^/?#"'\s<>]+/UzpfSVNDOj[A-Za-z0-9+/=%_-]+"#,
            in: unescaped,
            options: [.caseInsensitive]
        )?.fullMatch {
            return sanitizeCandidateUrl(storyUrl)
        }
        let ogUrl = extractMetaContent(html: html, property: "og:url")
        if ogUrl.lowercased().hasPrefix("http") &&
           !ogUrl.lowercased().contains("/share/") &&
           !ogUrl.lowercased().contains("fb.watch") {
            return unwrapFacebookLoginRedirect(ogUrl)
        }
        if let canonical = firstRegexMatch(
            pattern: #"<link[^>]+rel\s*=\s*["']canonical["'][^>]+href\s*=\s*["']([^"']+)["']"#,
            in: html,
            options: [.caseInsensitive]
        )?.group(1),
           canonical.lowercased().hasPrefix("http"),
           !canonical.lowercased().contains("/share/"),
           !canonical.lowercased().contains("fb.watch") {
            return unwrapFacebookLoginRedirect(unescapeHtmlAndJson(canonical))
        }
        return nil
    }

    private func resolveRedirectUrl(_ url: String, userAgent: String) async -> String {
        guard let requestUrl = URL(string: url) else { return url }
        let platform = UrlDetector.detectPlatform(url)
        let rawCookie = AuthCookieStore.shared.getEffectiveCookie(for: platform, targetUrl: url)
        let effectiveCookie = platform == .facebook ? buildDesktopFacebookCookie(rawCookie) : rawCookie

        func makeRedirectRequest(includeCookie: Bool) -> URLRequest {
            var req = URLRequest(url: requestUrl)
            req.httpMethod = "GET"
            req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            req.setValue(
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                forHTTPHeaderField: "Accept"
            )
            req.setValue("en-US,en;q=0.9,vi;q=0.8", forHTTPHeaderField: "Accept-Language")
            req.setValue("document", forHTTPHeaderField: "Sec-Fetch-Dest")
            req.setValue("navigate", forHTTPHeaderField: "Sec-Fetch-Mode")
            req.setValue("none", forHTTPHeaderField: "Sec-Fetch-Site")
            req.setValue("1", forHTTPHeaderField: "Upgrade-Insecure-Requests")
            if includeCookie && !effectiveCookie.isEmpty {
                req.setValue(effectiveCookie, forHTTPHeaderField: "Cookie")
            }
            return req
        }

        var resolvedUrl = url
        do {
            let (data, response) = try await session.data(for: makeRedirectRequest(includeCookie: true))
            if let finalUrl = response.url?.absoluteString {
                resolvedUrl = unwrapFacebookLoginRedirect(finalUrl)
            }
            let lowerResolved = resolvedUrl.lowercased()
            if lowerResolved.contains("/share/") || lowerResolved.contains("fb.watch") {
                let html = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
                if let extracted = extractResolvedFacebookUrlFromHtml(html) {
                    resolvedUrl = extracted
                }
            }
        } catch {
            resolvedUrl = url
        }

        // Crucial Fallback: Logged-in Facebook Comet SPA sometimes serves `/share/` with HTTP 200 without redirecting
        // the URL to `/stories/<bucket>/<UzpfSVNDOj...>`. Probing WITHOUT Cookie triggers an immediate 302 redirect
        // to the canonical `/stories/<bucket>/<UzpfSVNDOj...>` (or `/login/?next=...` which unwraps to it).
        let stillUnresolved = resolvedUrl.lowercased().contains("/share/") || resolvedUrl.lowercased().contains("fb.watch")
        if stillUnresolved && !effectiveCookie.isEmpty {
            do {
                let (noCookieData, noCookieResp) = try await session.data(for: makeRedirectRequest(includeCookie: false))
                if let secondFinal = noCookieResp.url?.absoluteString {
                    let unwrappedSecond = unwrapFacebookLoginRedirect(secondFinal)
                    let lowerSecond = unwrappedSecond.lowercased()
                    if !lowerSecond.contains("/share/") &&
                       !lowerSecond.contains("fb.watch") &&
                       !lowerSecond.contains("/login") {
                        return unwrappedSecond
                    }
                }
                let secondHtml = String(data: noCookieData, encoding: .utf8) ?? String(decoding: noCookieData, as: UTF8.self)
                if let extractedSecond = extractResolvedFacebookUrlFromHtml(secondHtml) {
                    return extractedSecond
                }
            } catch {
                // Keep resolvedUrl
            }
        }

        return resolvedUrl
    }

    private func extractCookieValue(from cookie: String, name: String) -> String? {
        for pair in cookie.components(separatedBy: ";") {
            let trimmed = pair.trimmingCharacters(in: .whitespacesAndNewlines)
            if let eqIdx = trimmed.firstIndex(of: "=") {
                let k = String(trimmed[..<eqIdx]).trimmingCharacters(in: .whitespacesAndNewlines)
                let v = String(trimmed[trimmed.index(after: eqIdx)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                if k == name {
                    return v
                }
            }
        }
        return nil
    }

    private func decodeBase64Flexible(_ raw: String) -> Data? {
        var base64 = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 {
            base64.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: base64, options: [.ignoreUnknownCharacters])
    }

    private func urlEncodeFormValue(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func unescapeHtmlAndJson(_ raw: String) -> String {
        return raw
            .replacingOccurrences(of: #"\/"#, with: "/")
            .replacingOccurrences(of: #"\u0025"#, with: "%")
            .replacingOccurrences(of: #"\u0026"#, with: "&")
            .replacingOccurrences(of: #"\u003C"#, with: "<")
            .replacingOccurrences(of: #"\u003E"#, with: ">")
            .replacingOccurrences(of: #"\u003D"#, with: "=")
            .replacingOccurrences(of: #"\u002F"#, with: "/")
            .replacingOccurrences(of: #"\u003c"#, with: "<")
            .replacingOccurrences(of: #"\u003e"#, with: ">")
            .replacingOccurrences(of: #"\u003d"#, with: "=")
            .replacingOccurrences(of: #"\u002f"#, with: "/")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#x3d;", with: "=")
            .replacingOccurrences(of: "&#x3D;", with: "=")
            .replacingOccurrences(of: "&#x26;", with: "&")
    }

    private func decodeJsonUnicodeString(_ raw: String) -> String {
        let unescaped = unescapeHtmlAndJson(raw)
        guard let regex = try? NSRegularExpression(pattern: #"\\u([0-9a-fA-F]{4})"#, options: []) else {
            return unescaped
        }
        let nsString = unescaped as NSString
        let matches = regex.matches(in: unescaped, options: [], range: NSRange(location: 0, length: nsString.length))
        guard !matches.isEmpty else { return unescaped }

        var result = unescaped
        for match in matches.reversed() {
            if match.numberOfRanges > 1 {
                let hexRange = match.range(at: 1)
                let hex = nsString.substring(with: hexRange)
                if let code = UInt32(hex, radix: 16), let scalar = UnicodeScalar(code) {
                    if let fullSwiftRange = Range(match.range, in: result) {
                        result.replaceSubrange(fullSwiftRange, with: String(Character(scalar)))
                    }
                }
            }
        }
        return result
    }

    private func sanitizeCandidateUrl(_ rawUrl: String) -> String {
        let trimmed = rawUrl
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        var cleaned = unescapeHtmlAndJson(trimmed)
        let lower = cleaned.lowercased()
        if lower.hasPrefix("http%3a%2f%2f") || lower.hasPrefix("https%3a%2f%2f") {
            if let decoded = cleaned.removingPercentEncoding {
                cleaned = decoded
            }
        }
        return cleaned
    }

    private func extractMetaContent(html: String, property: String) -> String {
        let escapedProp = NSRegularExpression.escapedPattern(for: property)
        let patterns = [
            #"<meta[^>]+(?:property|name)\s*=\s*["']\#(escapedProp)["'][^>]+content\s*=\s*["']([^"']+)["']"#,
            #"<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']\#(escapedProp)["']"#
        ]
        for pattern in patterns {
            if let match = firstRegexMatch(pattern: pattern, in: html, options: [.caseInsensitive]),
               let val = match.group(1), !val.isEmpty {
                return unescapeHtmlAndJson(val).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return ""
    }

    private func extractHtmlTagTitle(_ html: String) -> String {
        guard let rawTitle = firstRegexMatch(
            pattern: #"<title[^>]*>([\s\S]*?)</title>"#,
            in: html,
            options: [.caseInsensitive]
        )?.group(1) else {
            return ""
        }
        let unescaped = unescapeHtmlAndJson(rawTitle)
        return unescaped
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func extractResolutionScore(_ quality: String) -> Int {
        let lower = quality.lowercased()
        if let numStr = firstRegexMatch(pattern: #"(\d{3,4})"#, in: lower)?.group(1),
           let num = Int(numStr) {
            return num
        }
        if lower.contains("4k") || lower.contains("2160") { return 2160 }
        if lower.contains("2k") || lower.contains("1440") { return 1440 }
        if lower.contains("full") || lower.contains("fhd") || lower.contains("1080") { return 1080 }
        if lower.contains("hd_no_watermark") { return 1050 }
        if lower.contains("hd") || lower.contains("720") { return 720 }
        if lower.contains("no_watermark") { return 600 }
        if lower.contains("sd") || lower.contains("480") { return 480 }
        return 360
    }

    private func formatQualityLabel(
        rawQuality: String,
        mediaType: MediaType,
        fileExtension: String,
        index: Int
    ) -> String {
        let upperExt = fileExtension.uppercased()
        if mediaType == .audio {
            return !rawQuality.isEmpty ? "Âm thanh \(rawQuality) (\(upperExt))" : "Nhạc nền gốc (\(upperExt))"
        }
        if mediaType == .image {
            return !rawQuality.isEmpty ? "Ảnh \(rawQuality) (\(upperExt))" : "Ảnh gốc HD #\(index)"
        }
        let lower = rawQuality.lowercased()
        switch lower {
        case "hd_no_watermark":
            return "Full HD 1080p (Không Logo)"
        case "no_watermark":
            return "HD (Không Logo)"
        case "watermark":
            return "SD (Có Logo)"
        default:
            if lower.contains("hd") && !lower.contains("sd") {
                return "\(rawQuality.uppercased()) (\(upperExt))"
            } else if !rawQuality.isEmpty {
                return "\(rawQuality) (\(upperExt))"
            } else {
                return "Video HD #\(index) (\(upperExt))"
            }
        }
    }

    private func parseDurationSeconds(_ rawDuration: Any?) -> Int {
        guard let rawDuration = rawDuration else { return 0 }
        if let intVal = rawDuration as? Int { return intVal }
        if let doubleVal = rawDuration as? Double { return Int(doubleVal) }
        if let numVal = rawDuration as? NSNumber { return numVal.intValue }
        let str = String(describing: rawDuration).trimmingCharacters(in: .whitespacesAndNewlines)
        if str.isEmpty { return 0 }
        if let d = Double(str) { return Int(d) }
        let parts = str.components(separatedBy: ":").compactMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
        switch parts.count {
        case 3: return parts[0] * 3600 + parts[1] * 60 + parts[2]
        case 2: return parts[0] * 60 + parts[1]
        case 1: return parts[0]
        default: return 0
        }
    }

    private func firstNonEmpty(_ values: String...) -> String {
        for v in values {
            let trimmed = v.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                return trimmed
            }
        }
        return ""
    }

    // MARK: - Regex Engine

    private struct RegexMatchResult {
        let fullMatch: String
        let groups: [String?]
        let range: NSRange

        func group(_ index: Int) -> String? {
            guard index >= 0 && index < groups.count else { return nil }
            return groups[index]
        }
    }

    private func regexMatches(
        pattern: String,
        in text: String,
        options: NSRegularExpression.Options = []
    ) -> [RegexMatchResult] {
        guard !text.isEmpty,
              let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
            return []
        }
        let nsText = text as NSString
        let fullRange = NSRange(location: 0, length: nsText.length)
        let matches = regex.matches(in: text, options: [], range: fullRange)
        return matches.map { m in
            var groups: [String?] = []
            for i in 0..<m.numberOfRanges {
                let r = m.range(at: i)
                if r.location != NSNotFound && r.location + r.length <= nsText.length {
                    groups.append(nsText.substring(with: r))
                } else {
                    groups.append(nil)
                }
            }
            let fullText = (m.range.location != NSNotFound && m.range.location + m.range.length <= nsText.length)
                ? nsText.substring(with: m.range)
                : ""
            return RegexMatchResult(
                fullMatch: fullText,
                groups: groups,
                range: m.range
            )
        }
    }

    private func firstRegexMatch(
        pattern: String,
        in text: String,
        options: NSRegularExpression.Options = []
    ) -> RegexMatchResult? {
        guard !text.isEmpty,
              let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
            return nil
        }
        let nsText = text as NSString
        let fullRange = NSRange(location: 0, length: nsText.length)
        guard let m = regex.firstMatch(in: text, options: [], range: fullRange) else {
            return nil
        }
        var groups: [String?] = []
        for i in 0..<m.numberOfRanges {
            let r = m.range(at: i)
            if r.location != NSNotFound && r.location + r.length <= nsText.length {
                groups.append(nsText.substring(with: r))
            } else {
                groups.append(nil)
            }
        }
        let fullText = (m.range.location != NSNotFound && m.range.location + m.range.length <= nsText.length)
            ? nsText.substring(with: m.range)
            : ""
        return RegexMatchResult(
            fullMatch: fullText,
            groups: groups,
            range: m.range
        )
    }
}
