import Foundation
import AVFoundation

/// Pure Swift ISO-BMFF Fragmented MP4 (fMP4 / CMAF) De-fragmenting & Track Muxer
/// with AVFoundation Passthrough fallback.
///
/// Combines a DASH Video fMP4 (`av01` AV1, `vp09` VP9, `avc1` H.264, `hvc1` HEVC)
/// and a DASH Audio fMP4 (`mp4a.40.5` HE-AAC, `mp4a.40.2` AAC-LC) directly into a
/// standard non-fragmented `.mp4` (`ftyp` + `moov` + `mdat`) with complete
/// `stts`, `ctts`, `stss`, `stsc`, `stsz`, `stco` sample tables.
enum IsoBmffMp4Muxer {

    private struct Fmp4ChunkRun {
        let sourceOffset: UInt64
        let byteLength: Int
        let sampleCount: Int
    }

    private final class ParsedFmp4Track {
        var ftypBox: [UInt8]?
        var mvhdBox: [UInt8]?
        var tkhdBox: [UInt8]?
        var mdhdBox: [UInt8]?
        var hdlrBox: [UInt8]?
        var mediaHeaderBox: [UInt8]?
        var dinfBox: [UInt8]?
        var stsdBox: [UInt8]?
        var mvhdTimescale: UInt64 = 0
        var mdhdTimescale: UInt64 = 0
        var defaultSampleDescIndex: UInt64 = 1
        var defaultSampleDuration: UInt64 = 0
        var defaultSampleSize: UInt64 = 0
        var defaultSampleFlags: UInt64 = 0
        var sampleDurations: [UInt64] = []
        var sampleSizes: [UInt64] = []
        var sampleFlags: [UInt64] = []
        var sampleCtsOffsets: [Int32] = []
        var chunks: [Fmp4ChunkRun] = []
        var totalDuration: UInt64 = 0
        var totalDataBytes: UInt64 = 0
    }

    // MARK: - Public Entry Point

    /// Muxes a separate video stream and audio stream into a single playable `.mp4` file.
    /// First attempts the codec-agnostic ISO-BMFF fMP4 binary muxer (supporting AV1, VP9, HEVC, H.264 + HE-AAC),
    /// then falls back to `AVFoundation` passthrough composition if the streams are not fragmented MP4.
    static func muxVideoAndAudio(videoFile: URL, audioFile: URL, outputFile: URL) async -> Bool {
        let fm = FileManager.default
        guard fm.fileExists(atPath: videoFile.path),
              fm.fileExists(atPath: audioFile.path),
              fileSize(at: audioFile) > 512 else {
            return false
        }

        let videoInputSize = fileSize(at: videoFile)

        // Primary path: Codec-agnostic binary ISO-BMFF Fragmented MP4 de-fragmenting muxer
        let isoSuccess = await Task.detached(priority: .userInitiated) {
            muxIsoBmffFragmentedMp4(videoFile: videoFile, audioFile: audioFile, outputFile: outputFile)
        }.value

        if isoSuccess, fm.fileExists(atPath: outputFile.path), fileSize(at: outputFile) > videoInputSize {
            return true
        }

        // Secondary fallback path: AVFoundation passthrough export
        try? fm.removeItem(at: outputFile)
        let avSuccess = await muxWithAVFoundation(videoFile: videoFile, audioFile: audioFile, outputFile: outputFile)
        if avSuccess, fm.fileExists(atPath: outputFile.path), fileSize(at: outputFile) > 1024 {
            return true
        }

        return false
    }

    // MARK: - Primary Path: Binary ISO-BMFF Fragmented MP4 Muxer

    static func muxIsoBmffFragmentedMp4(videoFile: URL, audioFile: URL, outputFile: URL) -> Bool {
        let v = parseFmp4File(videoFile)
        let a = parseFmp4File(audioFile)

        guard let ftyp = v.ftypBox,
              let vMvhd = v.mvhdBox,
              v.tkhdBox != nil, v.mdhdBox != nil, v.hdlrBox != nil,
              v.mediaHeaderBox != nil, v.dinfBox != nil, v.stsdBox != nil,
              a.tkhdBox != nil, a.mdhdBox != nil, a.hdlrBox != nil,
              a.mediaHeaderBox != nil, a.dinfBox != nil, a.stsdBox != nil,
              !v.chunks.isEmpty, !a.chunks.isEmpty else {
            return false
        }

        let mvhdScale: UInt64
        if v.mvhdTimescale > 0 {
            mvhdScale = v.mvhdTimescale
        } else if v.mdhdTimescale > 0 {
            mvhdScale = v.mdhdTimescale
        } else {
            mvhdScale = 1000
        }

        let mvhdDur: UInt64
        if v.mdhdTimescale > 0 {
            mvhdDur = (v.totalDuration * mvhdScale) / v.mdhdTimescale
        } else {
            mvhdDur = v.totalDuration
        }

        var mvhd = vMvhd
        guard mvhd.count >= 12 else { return false }
        let mvhdVer = Int(mvhd[8])
        if mvhdVer == 1 && mvhd.count >= 40 {
            writeU64(&mvhd, 32, mvhdDur)
        } else if mvhd.count >= 28 {
            writeU32(&mvhd, 24, mvhdDur)
        }
        writeU32(&mvhd, mvhd.count - 4, 3) // next_track_ID = 3

        let dummyVOffsets = [UInt64](repeating: 0, count: v.chunks.count)
        let dummyAOffsets = [UInt64](repeating: 0, count: a.chunks.count)
        let dummyMoov = makeMp4Box(
            "moov",
            combineMp4Boxes([
                mvhd,
                buildStandardTrakBox(v, trackId: 1, mvhdTimescale: mvhdScale, chunkOffsets: dummyVOffsets, isVideo: true),
                buildStandardTrakBox(a, trackId: 2, mvhdTimescale: mvhdScale, chunkOffsets: dummyAOffsets, isVideo: false)
            ])
        )

        let mdatDataStart = UInt64(ftyp.count + dummyMoov.count + 8)
        var realVOffsets = [UInt64](repeating: 0, count: v.chunks.count)
        var curOffset = mdatDataStart
        for i in v.chunks.indices {
            realVOffsets[i] = curOffset
            curOffset += UInt64(v.chunks[i].byteLength)
        }
        var realAOffsets = [UInt64](repeating: 0, count: a.chunks.count)
        for i in a.chunks.indices {
            realAOffsets[i] = curOffset
            curOffset += UInt64(a.chunks[i].byteLength)
        }

        let finalMoov = makeMp4Box(
            "moov",
            combineMp4Boxes([
                mvhd,
                buildStandardTrakBox(v, trackId: 1, mvhdTimescale: mvhdScale, chunkOffsets: realVOffsets, isVideo: true),
                buildStandardTrakBox(a, trackId: 2, mvhdTimescale: mvhdScale, chunkOffsets: realAOffsets, isVideo: false)
            ])
        )

        let fm = FileManager.default
        try? fm.removeItem(at: outputFile)
        let parentDir = outputFile.deletingLastPathComponent()
        try? fm.createDirectory(at: parentDir, withIntermediateDirectories: true)
        guard fm.createFile(atPath: outputFile.path, contents: nil),
              let outHandle = try? FileHandle(forWritingTo: outputFile),
              let vHandle = try? FileHandle(forReadingFrom: videoFile),
              let aHandle = try? FileHandle(forReadingFrom: audioFile) else {
            return false
        }

        defer {
            try? outHandle.close()
            try? vHandle.close()
            try? aHandle.close()
        }

        do {
            try outHandle.write(contentsOf: Data(ftyp))
            try outHandle.write(contentsOf: Data(finalMoov))

            var mdatHeader = [UInt8](repeating: 0, count: 8)
            let mdatTotalSize = 8 + v.totalDataBytes + a.totalDataBytes
            writeU32(&mdatHeader, 0, mdatTotalSize)
            mdatHeader[4] = UInt8(ascii: "m")
            mdatHeader[5] = UInt8(ascii: "d")
            mdatHeader[6] = UInt8(ascii: "a")
            mdatHeader[7] = UInt8(ascii: "t")
            try outHandle.write(contentsOf: Data(mdatHeader))

            let copyChunkSize = 64 * 1024
            for ch in v.chunks {
                try vHandle.seek(toOffset: ch.sourceOffset)
                var remaining = ch.byteLength
                while remaining > 0 {
                    let toRead = min(copyChunkSize, remaining)
                    guard let data = try vHandle.read(upToCount: toRead), !data.isEmpty else { break }
                    try outHandle.write(contentsOf: data)
                    remaining -= data.count
                }
            }

            for ch in a.chunks {
                try aHandle.seek(toOffset: ch.sourceOffset)
                var remaining = ch.byteLength
                while remaining > 0 {
                    let toRead = min(copyChunkSize, remaining)
                    guard let data = try aHandle.read(upToCount: toRead), !data.isEmpty else { break }
                    try outHandle.write(contentsOf: data)
                    remaining -= data.count
                }
            }

            try outHandle.synchronize()
        } catch {
            return false
        }

        let outBytes = fileSize(at: outputFile)
        let inVideoBytes = fileSize(at: videoFile)
        return outBytes > inVideoBytes + 1024
    }

    // MARK: - Secondary Path: AVFoundation Passthrough Muxer

    static func muxWithAVFoundation(videoFile: URL, audioFile: URL, outputFile: URL) async -> Bool {
        let videoAsset = AVURLAsset(url: videoFile)
        let audioAsset = AVURLAsset(url: audioFile)
        let composition = AVMutableComposition()

        do {
            let videoTracks = try await videoAsset.loadTracks(withMediaType: .video)
            let audioTracks = try await audioAsset.loadTracks(withMediaType: .audio)
            guard let sourceVideoTrack = videoTracks.first,
                  let sourceAudioTrack = audioTracks.first else {
                return false
            }

            let videoDuration = try await videoAsset.load(.duration)
            let audioDuration = try await audioAsset.load(.duration)
            guard videoDuration.seconds > 0 else { return false }

            guard let compVideoTrack = composition.addMutableTrack(
                withMediaType: .video,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ),
            let compAudioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ) else {
                return false
            }

            let videoRange = CMTimeRange(start: .zero, duration: videoDuration)
            try compVideoTrack.insertTimeRange(videoRange, of: sourceVideoTrack, at: .zero)
            if let preferredTransform = try? await sourceVideoTrack.load(.preferredTransform) {
                compVideoTrack.preferredTransform = preferredTransform
            }

            let effectiveAudioDuration = CMTimeMinimum(videoDuration, audioDuration)
            let audioRange = CMTimeRange(
                start: .zero,
                duration: effectiveAudioDuration.seconds > 0 ? effectiveAudioDuration : videoDuration
            )
            try compAudioTrack.insertTimeRange(audioRange, of: sourceAudioTrack, at: .zero)

            try? FileManager.default.removeItem(at: outputFile)
            guard let exportSession = AVAssetExportSession(
                asset: composition,
                presetName: AVAssetExportPresetPassthrough
            ) else {
                return false
            }

            exportSession.outputURL = outputFile
            exportSession.outputFileType = .mp4
            exportSession.shouldOptimizeForNetworkUse = true

            await exportSession.export()
            return exportSession.status == .completed
        } catch {
            return false
        }
    }

    // MARK: - ISO-BMFF Box Parsing

    private static func parseFmp4File(_ fileURL: URL) -> ParsedFmp4Track {
        let t = ParsedFmp4Track()
        let fileLen = fileSize(at: fileURL)
        guard fileLen >= 16,
              let handle = try? FileHandle(forReadingFrom: fileURL) else {
            return t
        }
        defer { try? handle.close() }

        var pos: UInt64 = 0
        while pos + 8 <= fileLen {
            do {
                try handle.seek(toOffset: pos)
                guard let hdrData = try handle.read(upToCount: 8), hdrData.count == 8 else { break }
                let hdr = [UInt8](hdrData)
                var sz = readU32(hdr, 0)
                let type = String(bytes: hdr[4..<8], encoding: .ascii) ?? ""

                if sz == 1 {
                    guard let extData = try handle.read(upToCount: 8), extData.count == 8 else { break }
                    sz = readU64([UInt8](extData), 0)
                }
                if sz < 8 || pos + sz > fileLen { break }

                switch type {
                case "ftyp":
                    if sz <= 4096 {
                        try handle.seek(toOffset: pos)
                        if let boxData = try handle.read(upToCount: Int(sz)), boxData.count == Int(sz) {
                            t.ftypBox = [UInt8](boxData)
                        }
                    }
                case "moov":
                    if sz <= 4 * 1024 * 1024 {
                        try handle.seek(toOffset: pos)
                        if let boxData = try handle.read(upToCount: Int(sz)), boxData.count == Int(sz) {
                            let bytes = [UInt8](boxData)
                            parseMoovBox(bytes, offset: 8, end: bytes.count, t: t)
                        }
                    }
                case "moof":
                    if sz <= 2 * 1024 * 1024 {
                        try handle.seek(toOffset: pos)
                        if let boxData = try handle.read(upToCount: Int(sz)), boxData.count == Int(sz) {
                            let bytes = [UInt8](boxData)
                            parseMoofBox(bytes, moofFileOffset: pos, moofSize: bytes.count, t: t)
                        }
                    }
                default:
                    break
                }

                pos += sz
            } catch {
                break
            }
        }
        return t
    }

    private static func parseMoovBox(_ moov: [UInt8], offset: Int, end: Int, t: ParsedFmp4Track) {
        var pos = offset
        while pos + 8 <= end {
            let sz = Int(readU32(moov, pos))
            let type = String(bytes: moov[(pos + 4)..<(pos + 8)], encoding: .ascii) ?? ""
            if sz < 8 || pos + sz > end { break }
            let boxEnd = pos + sz

            switch type {
            case "mvhd":
                t.mvhdBox = Array(moov[pos..<boxEnd])
                if pos + 9 <= boxEnd {
                    let ver = Int(moov[pos + 8])
                    if ver == 1 && sz >= 32 {
                        t.mvhdTimescale = readU32(moov, pos + 28)
                    } else if sz >= 24 {
                        t.mvhdTimescale = readU32(moov, pos + 20)
                    }
                }
            case "tkhd":
                t.tkhdBox = Array(moov[pos..<boxEnd])
            case "mdhd":
                t.mdhdBox = Array(moov[pos..<boxEnd])
                if pos + 9 <= boxEnd {
                    let ver = Int(moov[pos + 8])
                    if ver == 1 && sz >= 32 {
                        t.mdhdTimescale = readU32(moov, pos + 28)
                    } else if sz >= 24 {
                        t.mdhdTimescale = readU32(moov, pos + 20)
                    }
                }
            case "hdlr":
                if t.hdlrBox == nil {
                    t.hdlrBox = Array(moov[pos..<boxEnd])
                }
            case "vmhd", "smhd":
                t.mediaHeaderBox = Array(moov[pos..<boxEnd])
            case "dinf":
                t.dinfBox = Array(moov[pos..<boxEnd])
            case "stsd":
                t.stsdBox = Array(moov[pos..<boxEnd])
            case "trex":
                if sz >= 32 {
                    t.defaultSampleDescIndex = readU32(moov, pos + 16)
                    t.defaultSampleDuration = readU32(moov, pos + 20)
                    t.defaultSampleSize = readU32(moov, pos + 24)
                    t.defaultSampleFlags = readU32(moov, pos + 28)
                }
            case "trak", "mdia", "minf", "stbl", "mvex":
                parseMoovBox(moov, offset: pos + 8, end: boxEnd, t: t)
            default:
                break
            }

            pos = boxEnd
        }
    }

    private static func parseMoofBox(_ moof: [UInt8], moofFileOffset: UInt64, moofSize: Int, t: ParsedFmp4Track) {
        var pos = 8
        while pos + 8 <= moofSize {
            let sz = Int(readU32(moof, pos))
            let type = String(bytes: moof[(pos + 4)..<(pos + 8)], encoding: .ascii) ?? ""
            if sz < 8 || pos + sz > moofSize { break }

            if type == "traf" {
                var c = pos + 8
                let trafEnd = pos + sz
                var baseDataOffset = moofFileOffset
                var defDur = t.defaultSampleDuration
                var defSize = t.defaultSampleSize
                var defFlags = t.defaultSampleFlags

                while c + 8 <= trafEnd {
                    let sSz = Int(readU32(moof, c))
                    let sType = String(bytes: moof[(c + 4)..<(c + 8)], encoding: .ascii) ?? ""
                    if sSz < 8 || c + sSz > trafEnd { break }
                    let sEnd = c + sSz
                    guard c + 12 <= sEnd else { break }

                    let flags = (Int(moof[c + 9]) << 16) |
                                (Int(moof[c + 10]) << 8) |
                                Int(moof[c + 11])

                    if sType == "tfhd" {
                        var p = c + 16
                        if (flags & 0x01) != 0 && p + 8 <= sEnd {
                            baseDataOffset = readU64(moof, p)
                            p += 8
                        }
                        if (flags & 0x02) != 0 && p + 4 <= sEnd { p += 4 }
                        if (flags & 0x08) != 0 && p + 4 <= sEnd {
                            defDur = readU32(moof, p)
                            p += 4
                        }
                        if (flags & 0x10) != 0 && p + 4 <= sEnd {
                            defSize = readU32(moof, p)
                            p += 4
                        }
                        if (flags & 0x20) != 0 && p + 4 <= sEnd {
                            defFlags = readU32(moof, p)
                            p += 4
                        }
                    } else if sType == "trun" && c + 16 <= sEnd {
                        let sc = Int(readU32(moof, c + 12))
                        var p = c + 16
                        var dataOff = Int32(moofSize + 8)
                        if (flags & 0x01) != 0 && p + 4 <= sEnd {
                            dataOff = readI32(moof, p)
                            p += 4
                        }
                        var firstFlags = defFlags
                        if (flags & 0x04) != 0 && p + 4 <= sEnd {
                            firstFlags = readU32(moof, p)
                            p += 4
                        }

                        var chunkBytes = 0
                        for i in 0..<sc {
                            let dur: UInt64
                            if (flags & 0x100) != 0 && p + 4 <= sEnd {
                                dur = readU32(moof, p)
                                p += 4
                            } else {
                                dur = defDur
                            }

                            let smpSz: UInt64
                            if (flags & 0x200) != 0 && p + 4 <= sEnd {
                                smpSz = readU32(moof, p)
                                p += 4
                            } else {
                                smpSz = defSize
                            }

                            let smpFl: UInt64
                            if (flags & 0x400) != 0 && p + 4 <= sEnd {
                                smpFl = readU32(moof, p)
                                p += 4
                            } else if i == 0 {
                                smpFl = firstFlags
                            } else {
                                smpFl = defFlags
                            }

                            let cts: Int32
                            if (flags & 0x800) != 0 && p + 4 <= sEnd {
                                cts = readI32(moof, p)
                                p += 4
                            } else {
                                cts = 0
                            }

                            t.sampleDurations.append(dur)
                            t.sampleSizes.append(smpSz)
                            t.sampleFlags.append(smpFl)
                            t.sampleCtsOffsets.append(cts)
                            t.totalDuration += dur
                            chunkBytes += Int(smpSz)
                        }

                        let signedChunkOffset = Int64(bitPattern: baseDataOffset) + Int64(dataOff)
                        if sc > 0 && chunkBytes > 0 && signedChunkOffset >= 0 {
                            t.chunks.append(
                                Fmp4ChunkRun(
                                    sourceOffset: UInt64(signedChunkOffset),
                                    byteLength: chunkBytes,
                                    sampleCount: sc
                                )
                            )
                            t.totalDataBytes += UInt64(chunkBytes)
                        }
                    }

                    c = sEnd
                }
            }

            pos += sz
        }
    }

    // MARK: - Standard MP4 Sample Table Builders

    private static func buildStandardTrakBox(
        _ t: ParsedFmp4Track,
        trackId: UInt64,
        mvhdTimescale: UInt64,
        chunkOffsets: [UInt64],
        isVideo: Bool
    ) -> [UInt8] {
        var tkhd = t.tkhdBox ?? []
        if tkhd.count >= 12 {
            let tkhdVer = Int(tkhd[8])
            tkhd[9] = 0
            tkhd[10] = 0
            tkhd[11] = 3 // track_enabled | track_in_movie
            let scaledDur = t.mdhdTimescale > 0 ? (t.totalDuration * mvhdTimescale) / t.mdhdTimescale : t.totalDuration
            if tkhdVer == 1 && tkhd.count >= 44 {
                writeU32(&tkhd, 28, trackId)
                writeU64(&tkhd, 36, scaledDur)
            } else if tkhd.count >= 32 {
                writeU32(&tkhd, 20, trackId)
                writeU32(&tkhd, 28, scaledDur)
            }
        }

        var mdhd = t.mdhdBox ?? []
        if mdhd.count >= 12 {
            let mdhdVer = Int(mdhd[8])
            if mdhdVer == 1 && mdhd.count >= 40 {
                writeU64(&mdhd, 32, t.totalDuration)
            } else if mdhd.count >= 28 {
                writeU32(&mdhd, 24, t.totalDuration)
            }
        }

        let stts = buildSttsBox(t.sampleDurations)
        let ctts = buildCttsBox(t.sampleCtsOffsets)
        let stss = isVideo ? buildStssBox(t.sampleFlags) : []
        let stsc = buildStscBox(t.chunks)
        let stsz = buildStszBox(t.sampleSizes)
        let stco = buildStcoBox(chunkOffsets)

        let stbl = makeMp4Box(
            "stbl",
            combineMp4Boxes([t.stsdBox ?? [], stts, ctts, stss, stsc, stsz, stco])
        )
        let minf = makeMp4Box(
            "minf",
            combineMp4Boxes([t.mediaHeaderBox ?? [], t.dinfBox ?? [], stbl])
        )
        let mdia = makeMp4Box(
            "mdia",
            combineMp4Boxes([mdhd, t.hdlrBox ?? [], minf])
        )
        return makeMp4Box("trak", combineMp4Boxes([tkhd, mdia]))
    }

    private static func buildSttsBox(_ durations: [UInt64]) -> [UInt8] {
        var counts: [UInt64] = []
        var deltas: [UInt64] = []
        for d in durations {
            if let last = deltas.last, last == d {
                counts[counts.count - 1] += 1
            } else {
                counts.append(1)
                deltas.append(d)
            }
        }
        var out: [UInt8] = []
        out.reserveCapacity(8 + counts.count * 8)
        appendU32(&out, 0)
        appendU32(&out, UInt64(counts.count))
        for i in counts.indices {
            appendU32(&out, counts[i])
            appendU32(&out, deltas[i])
        }
        return makeMp4Box("stts", out)
    }

    private static func buildCttsBox(_ offsets: [Int32]) -> [UInt8] {
        var hasNonZero = false
        var hasNegative = false
        for o in offsets {
            if o != 0 { hasNonZero = true }
            if o < 0 { hasNegative = true }
        }
        if !hasNonZero { return [] }

        var counts: [UInt64] = []
        var vals: [Int32] = []
        for o in offsets {
            if let last = vals.last, last == o {
                counts[counts.count - 1] += 1
            } else {
                counts.append(1)
                vals.append(o)
            }
        }
        var out: [UInt8] = []
        out.reserveCapacity(8 + counts.count * 8)
        appendU32(&out, hasNegative ? 0x0100_0000 : 0)
        appendU32(&out, UInt64(counts.count))
        for i in counts.indices {
            appendU32(&out, counts[i])
            appendU32(&out, UInt64(UInt32(bitPattern: vals[i])))
        }
        return makeMp4Box("ctts", out)
    }

    private static func buildStssBox(_ flags: [UInt64]) -> [UInt8] {
        var syncs: [UInt64] = []
        for i in flags.indices {
            if (flags[i] & 0x0001_0000) == 0 {
                syncs.append(UInt64(i + 1))
            }
        }
        if syncs.isEmpty || syncs.count == flags.count { return [] }
        var out: [UInt8] = []
        out.reserveCapacity(8 + syncs.count * 4)
        appendU32(&out, 0)
        appendU32(&out, UInt64(syncs.count))
        for s in syncs {
            appendU32(&out, s)
        }
        return makeMp4Box("stss", out)
    }

    private static func buildStscBox(_ chunks: [Fmp4ChunkRun]) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(8 + chunks.count * 12)
        appendU32(&out, 0)
        appendU32(&out, UInt64(chunks.count))
        for i in chunks.indices {
            appendU32(&out, UInt64(i + 1))
            appendU32(&out, UInt64(chunks[i].sampleCount))
            appendU32(&out, 1)
        }
        return makeMp4Box("stsc", out)
    }

    private static func buildStszBox(_ sizes: [UInt64]) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(12 + sizes.count * 4)
        appendU32(&out, 0)
        appendU32(&out, 0)
        appendU32(&out, UInt64(sizes.count))
        for s in sizes {
            appendU32(&out, s)
        }
        return makeMp4Box("stsz", out)
    }

    private static func buildStcoBox(_ chunkOffsets: [UInt64]) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(8 + chunkOffsets.count * 4)
        appendU32(&out, 0)
        appendU32(&out, UInt64(chunkOffsets.count))
        for o in chunkOffsets {
            appendU32(&out, o)
        }
        return makeMp4Box("stco", out)
    }

    // MARK: - Binary Utilities

    private static func makeMp4Box(_ type: String, _ payload: [UInt8]) -> [UInt8] {
        var res = [UInt8](repeating: 0, count: 8 + payload.count)
        writeU32(&res, 0, UInt64(res.count))
        let typeBytes = Array(type.utf8.prefix(4))
        for i in 0..<min(4, typeBytes.count) {
            res[4 + i] = typeBytes[i]
        }
        if !payload.isEmpty {
            res.replaceSubrange(8..<(8 + payload.count), with: payload)
        }
        return res
    }

    private static func combineMp4Boxes(_ boxes: [[UInt8]]) -> [UInt8] {
        let total = boxes.reduce(0) { $0 + $1.count }
        var res: [UInt8] = []
        res.reserveCapacity(total)
        for b in boxes where !b.isEmpty {
            res.append(contentsOf: b)
        }
        return res
    }

    private static func readU32(_ b: [UInt8], _ o: Int) -> UInt64 {
        guard o >= 0, o + 4 <= b.count else { return 0 }
        return (UInt64(b[o]) << 24) |
               (UInt64(b[o + 1]) << 16) |
               (UInt64(b[o + 2]) << 8) |
               UInt64(b[o + 3])
    }

    private static func readI32(_ b: [UInt8], _ o: Int) -> Int32 {
        let u = UInt32(truncatingIfNeeded: readU32(b, o))
        return Int32(bitPattern: u)
    }

    private static func readU64(_ b: [UInt8], _ o: Int) -> UInt64 {
        guard o >= 0, o + 8 <= b.count else { return 0 }
        return (readU32(b, o) << 32) | (readU32(b, o + 4) & 0xFFFF_FFFF)
    }

    private static func writeU32(_ b: inout [UInt8], _ o: Int, _ v: UInt64) {
        guard o >= 0, o + 4 <= b.count else { return }
        b[o]     = UInt8((v >> 24) & 0xFF)
        b[o + 1] = UInt8((v >> 16) & 0xFF)
        b[o + 2] = UInt8((v >> 8) & 0xFF)
        b[o + 3] = UInt8(v & 0xFF)
    }

    private static func writeU64(_ b: inout [UInt8], _ o: Int, _ v: UInt64) {
        guard o >= 0, o + 8 <= b.count else { return }
        writeU32(&b, o, v >> 32)
        writeU32(&b, o + 4, v & 0xFFFF_FFFF)
    }

    private static func appendU32(_ out: inout [UInt8], _ v: UInt64) {
        out.append(UInt8((v >> 24) & 0xFF))
        out.append(UInt8((v >> 16) & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8(v & 0xFF))
    }

    private static func fileSize(at url: URL) -> UInt64 {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? NSNumber else {
            return 0
        }
        return size.uint64Value
    }
}
