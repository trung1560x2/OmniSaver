package com.omnisaver.videodownloader.util

import android.net.Uri
import android.util.Patterns
import com.omnisaver.videodownloader.model.SocialPlatform
import java.util.Locale

object UrlDetector {

    private val URL_REGEX = Regex("""(https?://[^\s"'<>]+)""", RegexOption.IGNORE_CASE)

    private val TRACKING_PARAMS = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "fbclid",
        "igshid",
        "igsh",
        "s",
        "t",
        "_r",
        "_t",
        "is_from_webapp",
        "sender_device",
        "share_app_id"
    )

    private val PRESERVED_PARAMS = setOf(
        "v",
        "id",
        "story_fbid",
        "fbid"
    )

    /**
     * Extracts the first HTTP/HTTPS URL from arbitrary shared text (such as share sheets
     * from TikTok, Facebook, Threads, Instagram, or X that include captions around the link).
     */
    fun extractFirstUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val match = URL_REGEX.find(rawText) ?: return null
        val candidate = match.groupValues[1]
            .trimEnd('.', ',', ')', ']', ';', '!', '\'', '"')
        return candidate.takeIf { it.isNotBlank() }
    }

    /**
     * Case-insensitive domain/host detection for the 5 supported social platforms.
     */
    fun detectPlatform(url: String): SocialPlatform {
        if (url.isBlank()) return SocialPlatform.UNKNOWN
        val extracted = extractFirstUrl(url) ?: url.trim()
        val withScheme = if (
            extracted.startsWith("http://", ignoreCase = true) ||
            extracted.startsWith("https://", ignoreCase = true)
        ) {
            extracted
        } else {
            "https://$extracted"
        }

        val host = try {
            Uri.parse(withScheme).host?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val lowerUrl = extracted.lowercase(Locale.ROOT)

        fun matches(vararg domains: String): Boolean {
            return if (host.isNotEmpty()) {
                domains.any { domain -> host == domain || host.endsWith(".$domain") }
            } else {
                domains.any { domain -> lowerUrl.contains(domain) }
            }
        }

        return when {
            matches("facebook.com", "fb.watch", "fb.gg", "fb.com") -> SocialPlatform.FACEBOOK
            matches("x.com", "twitter.com", "t.co", "vxtwitter.com", "fxtwitter.com") -> SocialPlatform.X_TWITTER
            matches("instagram.com", "instagr.am", "ddinstagram.com") -> SocialPlatform.INSTAGRAM
            matches("tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "douyin.com") -> SocialPlatform.TIKTOK
            matches("threads.net", "threads.com") -> SocialPlatform.THREADS
            else -> SocialPlatform.UNKNOWN
        }
    }

    /**
     * Returns true if the input contains a valid HTTP/HTTPS URL belonging to one of the supported platforms.
     */
    fun isSupportedUrl(rawTextOrUrl: String?): Boolean {
        val url = extractFirstUrl(rawTextOrUrl) ?: return false
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        } ?: return false

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank() && !Patterns.WEB_URL.matcher(url).matches()) return false

        return detectPlatform(url) != SocialPlatform.UNKNOWN
    }

    /**
     * Cleans tracking query parameters while preserving essential video identifiers
     * such as `v`, `id`, `story_fbid`, and `fbid`.
     */
    fun cleanUrl(rawUrl: String): String {
        val extracted = extractFirstUrl(rawUrl) ?: rawUrl.trim()
        if (extracted.isEmpty()) return ""

        return try {
            val uri = Uri.parse(extracted)
            if (uri.isOpaque || uri.scheme == null) {
                return extracted
            }
            val queryNames = uri.queryParameterNames
            if (queryNames.isNullOrEmpty()) {
                return extracted
            }

            val builder = uri.buildUpon().clearQuery()
            for (paramName in queryNames) {
                val lowerParam = paramName.lowercase(Locale.ROOT)
                val shouldKeep = lowerParam in PRESERVED_PARAMS || lowerParam !in TRACKING_PARAMS
                if (shouldKeep) {
                    val values = uri.getQueryParameters(paramName)
                    for (value in values) {
                        builder.appendQueryParameter(paramName, value)
                    }
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            extracted
        }
    }

    /**
     * Builds a safe, sanitized filename for saving media via MediaStore / DownloadManager.
     */
    fun buildSafeFileName(
        platform: SocialPlatform,
        titleOrUrl: String,
        qualityLabel: String,
        extension: String
    ): String {
        val strippedSource = titleOrUrl
            .replace(Regex("^https?://(www\\.)?", RegexOption.IGNORE_CASE), "")
            .trim()

        val cleanBase = strippedSource
            .replace(Regex("[^a-zA-Z0-9_]+"), "_")
            .trim('_')
            .take(36)
            .trimEnd('_')
            .ifEmpty { System.currentTimeMillis().toString() }

        val cleanQuality = qualityLabel
            .replace(Regex("[^a-zA-Z0-9_]+"), "_")
            .trim('_')
            .take(24)
            .trimEnd('_')
            .ifEmpty { "HD" }

        val cleanExt = extension
            .trim()
            .trimStart('.')
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
            .ifEmpty { "mp4" }

        return "${platform.folderName}_${cleanBase}_${cleanQuality}.$cleanExt"
    }
}
