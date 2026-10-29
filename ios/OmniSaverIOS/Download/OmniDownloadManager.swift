import Foundation
import Photos

@MainActor
final class OmniDownloadManager: ObservableObject {
    static let shared = OmniDownloadManager()

    private static let defaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private static let historyStorageKey = "omnisaver_downloaded_items_v1"

    @Published var isDownloading: Bool = false
    @Published var progress: Double = 0.0
    @Published var statusMessage: String = ""
    @Published var downloadedItems: [DownloadedItem] = []

    private init() {
        ensureDownloadsDirectoryExists()
        loadHistory()
    }

    // MARK: - Directory & History Management

    var downloadsDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent("OmniSaver", isDirectory: true)
    }

    private func ensureDownloadsDirectoryExists() {
        try? FileManager.default.createDirectory(at: downloadsDirectory, withIntermediateDirectories: true)
    }

    func loadHistory() {
        ensureDownloadsDirectoryExists()
        let fm = FileManager.default
        var loaded: [DownloadedItem] = []

        if let data = UserDefaults.standard.data(forKey: Self.historyStorageKey),
           let decoded = try? JSONDecoder().decode([DownloadedItem].self, from: data) {
            loaded = decoded.filter { fm.fileExists(atPath: $0.fileURL.path) }
        }

        // Also index any existing media files in Documents/OmniSaver not yet in history
        let knownNames = Set(loaded.map { $0.fileName })
        if let fileURLs = try? fm.contentsOfDirectory(
            at: downloadsDirectory,
            includingPropertiesForKeys: [.fileSizeKey, .creationDateKey],
            options: [.skipsHiddenFiles]
        ) {
            for url in fileURLs {
                let name = url.lastPathComponent
                guard !knownNames.contains(name) else { continue }
                let ext = url.pathExtension.lowercased()
                guard ["mp4", "m4a", "mp3", "jpg", "jpeg", "png", "webp"].contains(ext) else { continue }

                let attrs = try? fm.attributesOfItem(atPath: url.path)
                let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
                let created = (attrs?[.creationDate] as? Date) ?? Date()
                let mediaType: MediaType = (ext == "m4a" || ext == "mp3") ? .audio : (["jpg", "jpeg", "png", "webp"].contains(ext) ? .image : .video)

                let recovered = DownloadedItem(
                    id: UUID().uuidString,
                    title: url.deletingPathExtension().lastPathComponent,
                    platformName: "OmniSaver",
                    qualityLabel: mediaType == .audio ? "Audio" : "HD",
                    fileName: name,
                    localRelativePath: "OmniSaver/\(name)",
                    fileSizeBytes: size,
                    createdAt: created,
                    savedToPhotos: false,
                    mediaType: mediaType
                )
                loaded.append(recovered)
            }
        }

        loaded.sort { $0.createdAt > $1.createdAt }
        downloadedItems = loaded
        persistHistory()
    }

    func deleteItem(_ item: DownloadedItem) {
        try? FileManager.default.removeItem(at: item.fileURL)
        downloadedItems.removeAll { $0.id == item.id }
        persistHistory()
    }

    private func persistHistory() {
        if let encoded = try? JSONEncoder().encode(downloadedItems) {
            UserDefaults.standard.set(encoded, forKey: Self.historyStorageKey)
        }
    }

    // MARK: - Download & ISO-BMFF Muxing Pipeline

    func startDownload(mediaInfo: ExtractedMediaInfo, option: MediaOption) async throws -> DownloadedItem {
        ensureDownloadsDirectoryExists()
        isDownloading = true
        progress = 0.02
        statusMessage = "Đang khởi tạo kết nối tải xuống..."

        defer {
            isDownloading = false
        }

        let safeFileName = buildSafeFileName(mediaInfo: mediaInfo, option: option)
        let finalFile = downloadsDirectory.appendingPathComponent(safeFileName)
        let fm = FileManager.default
        try? fm.removeItem(at: finalFile)

        let tempDir = fm.temporaryDirectory.appendingPathComponent("omnisaver_dash_temp", isDirectory: true)
        try? fm.createDirectory(at: tempDir, withIntermediateDirectories: true)

        let pairedAudioUrl = option.audioUrl?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let requiresDashMux = option.mediaType == .video && !pairedAudioUrl.isEmpty

        if requiresDashMux {
            let uniqueToken = UUID().uuidString.prefix(8)
            let tempVideo = tempDir.appendingPathComponent("dash_video_\(uniqueToken).mp4")
            let tempAudio = tempDir.appendingPathComponent("dash_audio_\(uniqueToken).m4a")
            defer {
                try? fm.removeItem(at: tempVideo)
                try? fm.removeItem(at: tempAudio)
            }

            // 1. Download Video stream (0% -> 72%)
            statusMessage = "Đang tải luồng Video \(option.qualityLabel)..."
            let videoRequest = try buildConfiguredRequest(
                urlString: option.url,
                platform: mediaInfo.platform,
                customHeaders: option.customHeaders
            )
            try await downloadFileWithProgress(request: videoRequest, destination: tempVideo) { [weak self] frac in
                Task { @MainActor in
                    guard let self = self else { return }
                    self.progress = min(0.72, max(0.02, frac * 0.72))
                }
            }

            // 2. Download Audio stream (72% -> 90%)
            statusMessage = "Đang tải luồng âm thanh gốc M4A..."
            progress = 0.73
            var audioDownloadedOk = false
            if let audioRequest = try? buildConfiguredRequest(
                urlString: pairedAudioUrl,
                platform: mediaInfo.platform,
                customHeaders: option.customHeaders
            ) {
                do {
                    try await downloadFileWithProgress(request: audioRequest, destination: tempAudio) { [weak self] frac in
                        Task { @MainActor in
                            guard let self = self else { return }
                            self.progress = min(0.90, max(0.72, 0.72 + frac * 0.18))
                        }
                    }
                    audioDownloadedOk = fm.fileExists(atPath: tempAudio.path) && fileSize(at: tempAudio) > 512
                } catch {
                    audioDownloadedOk = false
                }
            }

            // 3. Mux Video + Audio via IsoBmffMp4Muxer (90% -> 97%)
            progress = 0.91
            statusMessage = "Đang ghép kênh Video Full HD + Âm thanh gốc..."
            var muxedOk = false
            if audioDownloadedOk {
                muxedOk = await IsoBmffMp4Muxer.muxVideoAndAudio(
                    videoFile: tempVideo,
                    audioFile: tempAudio,
                    outputFile: finalFile
                )
            }

            if !muxedOk || !fm.fileExists(atPath: finalFile.path) || fileSize(at: finalFile) < 1024 {
                try? fm.removeItem(at: finalFile)
                try fm.copyItem(at: tempVideo, to: finalFile)
            }
            progress = 0.97
        } else {
            // Single stream download (0% -> 95%)
            let uniqueToken = UUID().uuidString.prefix(8)
            let tempSingle = tempDir.appendingPathComponent("single_\(uniqueToken).\(option.fileExtension)")
            defer {
                try? fm.removeItem(at: tempSingle)
            }

            statusMessage = "Đang tải \(option.qualityLabel)..."
            let request = try buildConfiguredRequest(
                urlString: option.url,
                platform: mediaInfo.platform,
                customHeaders: option.customHeaders
            )
            try await downloadFileWithProgress(request: request, destination: tempSingle) { [weak self] frac in
                Task { @MainActor in
                    guard let self = self else { return }
                    self.progress = min(0.95, max(0.02, frac * 0.95))
                }
            }

            try? fm.removeItem(at: finalFile)
            try fm.moveItem(at: tempSingle, to: finalFile)
            progress = 0.97
        }

        let finalSize = fileSize(at: finalFile)
        guard fm.fileExists(atPath: finalFile.path), finalSize > 512 else {
            throw NSError(
                domain: "OmniDownloadManager",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Tệp tải về bị rỗng hoặc không hợp lệ."]
            )
        }

        // 4. Save to iOS Photos Library if Video or Image (97% -> 100%)
        statusMessage = "Đang lưu vào Thư viện Ảnh..."
        let savedToPhotos = await saveToPhotoLibraryIfApplicable(fileURL: finalFile, mediaType: option.mediaType)

        let item = DownloadedItem(
            id: UUID().uuidString,
            title: mediaInfo.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "\(mediaInfo.platform.displayName) Video"
                : mediaInfo.title,
            platformName: mediaInfo.platform.displayName,
            qualityLabel: option.qualityLabel,
            fileName: safeFileName,
            localRelativePath: "OmniSaver/\(safeFileName)",
            fileSizeBytes: finalSize,
            createdAt: Date(),
            savedToPhotos: savedToPhotos,
            mediaType: option.mediaType
        )

        downloadedItems.insert(item, at: 0)
        persistHistory()

        progress = 1.0
        statusMessage = savedToPhotos
            ? "Đã tải xong & lưu vào Thư viện Ảnh!"
            : "Đã tải xong vào thư mục OmniSaver!"

        return item
    }

    // MARK: - Helpers

    private func buildSafeFileName(mediaInfo: ExtractedMediaInfo, option: MediaOption) -> String {
        let rawTitle = mediaInfo.title
            .replacingOccurrences(of: #"[^A-Za-z0-9_]+"#, with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        let clippedTitle = String(rawTitle.prefix(32)).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        let safeTitle = clippedTitle.isEmpty ? "media" : clippedTitle

        let rawQuality = option.qualityLabel
            .replacingOccurrences(of: #"[^A-Za-z0-9_]+"#, with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        let safeQuality = rawQuality.isEmpty ? option.mediaType.rawValue.lowercased() : String(rawQuality.prefix(20))

        let cleanExt = option.fileExtension
            .trimmingCharacters(in: CharacterSet(charactersIn: ". "))
            .lowercased()
        let finalExt: String
        if !cleanExt.isEmpty {
            finalExt = cleanExt
        } else {
            switch option.mediaType {
            case .video: finalExt = "mp4"
            case .audio: finalExt = "m4a"
            case .image: finalExt = "jpg"
            }
        }

        let timestamp = Int(Date().timeIntervalSince1970) % 100_000
        let platformPrefix = mediaInfo.platform.shortName
            .replacingOccurrences(of: #"[^A-Za-z0-9]+"#, with: "", options: .regularExpression)
            .lowercased()

        return "\(platformPrefix)_\(safeTitle)_\(safeQuality)_\(timestamp).\(finalExt)"
    }

    private func buildConfiguredRequest(
        urlString: String,
        platform: SocialPlatform,
        customHeaders: [String: String]
    ) throws -> URLRequest {
        guard let url = URL(string: urlString) else {
            throw NSError(
                domain: "OmniDownloadManager",
                code: -3,
                userInfo: [NSLocalizedDescriptionKey: "Đường dẫn tải xuống không hợp lệ: \(urlString)"]
            )
        }

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 90)
        request.httpMethod = "GET"
        request.setValue(Self.defaultUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.setValue("en-US,en;q=0.9,vi;q=0.8", forHTTPHeaderField: "Accept-Language")

        let defaultReferer = defaultReferer(for: platform)
        if !defaultReferer.isEmpty {
            request.setValue(defaultReferer, forHTTPHeaderField: "Referer")
        }

        let storedCookie = AuthCookieStore.shared.getEffectiveCookie(for: platform, targetUrl: urlString)
        if !storedCookie.isEmpty {
            request.setValue(storedCookie, forHTTPHeaderField: "Cookie")
        }

        for (headerKey, headerVal) in customHeaders where !headerVal.isEmpty {
            request.setValue(headerVal, forHTTPHeaderField: headerKey)
        }

        return request
    }

    private func defaultReferer(for platform: SocialPlatform) -> String {
        switch platform {
        case .facebook: return "https://www.facebook.com/"
        case .instagram: return "https://www.instagram.com/"
        case .tiktok: return "https://www.tiktok.com/"
        case .xTwitter: return "https://x.com/"
        case .threads: return "https://www.threads.net/"
        case .unknown: return ""
        }
    }

    private func downloadFileWithProgress(
        request: URLRequest,
        destination: URL,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let delegate = StreamDownloadDelegate(
                destinationURL: destination,
                onProgress: onProgress,
                continuation: continuation
            )
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 35
            config.timeoutIntervalForResource = 300
            config.waitsForConnectivity = false
            let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
            let task = session.downloadTask(with: request)
            task.resume()
        }
    }

    private func saveToPhotoLibraryIfApplicable(fileURL: URL, mediaType: MediaType) async -> Bool {
        guard mediaType == .video || mediaType == .image else {
            return false
        }

        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            return false
        }

        do {
            try await PHPhotoLibrary.shared().performChanges {
                switch mediaType {
                case .video:
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
                case .image:
                    PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
                case .audio:
                    break
                }
            }
            return true
        } catch {
            return false
        }
    }

    private func fileSize(at url: URL) -> Int64 {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let num = attrs[.size] as? NSNumber else {
            return 0
        }
        return num.int64Value
    }
}

// MARK: - Native CFNetwork Stream Download Delegate

private final class StreamDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destinationURL: URL
    private let onProgress: @Sendable (Double) -> Void
    private var continuation: CheckedContinuation<Void, Error>?
    private var didMoveFile = false
    private var moveError: Error?
    private var lastReportTimestamp: TimeInterval = 0

    init(
        destinationURL: URL,
        onProgress: @escaping @Sendable (Double) -> Void,
        continuation: CheckedContinuation<Void, Error>
    ) {
        self.destinationURL = destinationURL
        self.onProgress = onProgress
        self.continuation = continuation
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let now = Date().timeIntervalSinceReferenceDate
        if now - lastReportTimestamp >= 0.12 || totalBytesWritten >= totalBytesExpectedToWrite {
            lastReportTimestamp = now
            let fraction = min(1.0, max(0.0, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)))
            onProgress(fraction)
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        if let httpResponse = downloadTask.response as? HTTPURLResponse,
           !(200...299).contains(httpResponse.statusCode) {
            moveError = NSError(
                domain: "OmniDownloadManager",
                code: httpResponse.statusCode,
                userInfo: [
                    NSLocalizedDescriptionKey: "Máy chủ trả về mã lỗi HTTP \(httpResponse.statusCode) khi tải phương tiện."
                ]
            )
            return
        }

        do {
            let fm = FileManager.default
            try? fm.removeItem(at: destinationURL)
            try fm.createDirectory(at: destinationURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fm.moveItem(at: location, to: destinationURL)
            didMoveFile = true
        } catch {
            moveError = error
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        defer {
            session.finishTasksAndInvalidate()
        }
        guard let cont = continuation else { return }
        continuation = nil

        if let error = error {
            cont.resume(throwing: error)
            return
        }
        if let moveError = moveError {
            cont.resume(throwing: moveError)
            return
        }
        if !didMoveFile {
            cont.resume(
                throwing: NSError(
                    domain: "OmniDownloadManager",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Không thể lưu tệp tạm thời sau khi tải."]
                )
            )
            return
        }
        cont.resume(returning: ())
    }
}
