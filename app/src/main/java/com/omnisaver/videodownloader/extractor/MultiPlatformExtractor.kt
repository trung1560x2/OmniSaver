package com.omnisaver.videodownloader.extractor

import android.content.Context
import android.util.Base64
import com.omnisaver.videodownloader.model.ExtractedMediaInfo
import com.omnisaver.videodownloader.model.MediaOption
import com.omnisaver.videodownloader.model.MediaType
import com.omnisaver.videodownloader.model.SocialPlatform
import com.omnisaver.videodownloader.util.AuthCookieStore
import com.omnisaver.videodownloader.util.UrlDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MultiPlatformExtractor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "omni_prefs"
        private const val KEY_CUSTOM_RAPID_API = "custom_rapid_api_key"
        private const val DEFAULT_RAPID_API_KEY =
            "ca5c6d6fa3mshfcd2b0a0feac6b7p140e57jsn72684628152a"
        private const val RAPID_API_HOST = "auto-download-all-in-one.p.rapidapi.com"
        private const val RAPID_API_ENDPOINT =
            "https://auto-download-all-in-one.p.rapidapi.com/v1/social/autolink"

        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_SAFARI_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
        private const val INSTAGRAM_ANDROID_APP_UA =
            "Instagram 312.0.0.34.111 Android (34/14; 480dpi; 1080x2400; samsung; SM-S918B; dm3q; qcom; en_US; 559996041)"

        private const val X_PUBLIC_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        private const val IG_WEB_APP_ID = "936619743392459"
    }

    private val authCookieStore = AuthCookieStore(context)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCustomApiKey(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_RAPID_API, "")?.trim().orEmpty()
    }

    fun setCustomApiKey(key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_RAPID_API, key.trim()).apply()
    }

    private fun getEffectiveApiKey(): String {
        val custom = getCustomApiKey()
        return if (custom.isNotBlank()) custom else DEFAULT_RAPID_API_KEY
    }

    private fun buildMediaHeaders(
        platform: SocialPlatform,
        cookie: String,
        userAgent: String = DESKTOP_CHROME_UA
    ): Map<String, String> {
        return buildMap {
            put("User-Agent", userAgent)
            put("Referer", platform.referer)
            put("Origin", platform.origin)
            if (cookie.isNotBlank()) {
                put("Cookie", cookie)
            }
        }
    }

    suspend fun extract(rawInput: String): Result<ExtractedMediaInfo> = withContext(Dispatchers.IO) {
        val extractedUrl = UrlDetector.extractFirstUrl(rawInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Không tìm thấy đường dẫn URL hợp lệ trong văn bản.")
            )

        val cleanUrl = UrlDetector.cleanUrl(extractedUrl)
        val initialPlatform = UrlDetector.detectPlatform(cleanUrl)

        val directAttempt = runCatching {
            extractDirect(rawInput, cleanUrl, initialPlatform)
        }
        val directInfo = directAttempt.getOrNull()

        val hasPrimaryMedia = directInfo != null && (
            directInfo.options.any { it.mediaType == MediaType.VIDEO || it.mediaType == MediaType.AUDIO } ||
                (!looksLikeVideoPost(cleanUrl, directInfo.platform) && directInfo.options.isNotEmpty())
            )

        if (directInfo != null && hasPrimaryMedia) {
            return@withContext Result.success(directInfo)
        }

        val rapidAttempt = runCatching {
            extractViaRapidApi(cleanUrl, initialPlatform, rawInput)
        }
        val rapidInfo = rapidAttempt.getOrNull()
        if (rapidInfo != null && rapidInfo.options.isNotEmpty()) {
            return@withContext Result.success(rapidInfo)
        }

        if (directInfo != null && directInfo.options.isNotEmpty()) {
            return@withContext Result.success(directInfo)
        }

        val errorMsg = rapidAttempt.exceptionOrNull()?.localizedMessage
            ?: directAttempt.exceptionOrNull()?.localizedMessage
            ?: "Không thể bóc tách video/ảnh từ liên kết này. Nếu đây là video Riêng tư / Nhóm kín / 18+, hãy đăng nhập tài khoản trong mục Trình duyệt & Cookie."

        Result.failure(IllegalStateException(errorMsg))
    }

    private fun looksLikeVideoPost(url: String, platform: SocialPlatform): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return when (platform) {
            SocialPlatform.FACEBOOK -> lower.contains("/watch") || lower.contains("/reel") ||
                lower.contains("/videos") || lower.contains("fb.watch") || lower.contains("/share/v") ||
                lower.contains("/share/r")
            SocialPlatform.INSTAGRAM -> lower.contains("/reel") || lower.contains("/tv/") || lower.contains("/stories/")
            SocialPlatform.TIKTOK -> true
            SocialPlatform.THREADS -> false
            SocialPlatform.X_TWITTER -> false
            SocialPlatform.UNKNOWN -> true
        }
    }

    private fun extractDirect(
        originalInput: String,
        cleanUrl: String,
        platform: SocialPlatform
    ): ExtractedMediaInfo? {
        return when (platform) {
            SocialPlatform.X_TWITTER -> extractTwitterDirect(originalInput, cleanUrl)
            SocialPlatform.TIKTOK -> extractTikTokDirect(originalInput, cleanUrl)
            SocialPlatform.FACEBOOK -> extractFacebookDirect(originalInput, cleanUrl)
            SocialPlatform.INSTAGRAM -> extractInstagramDirect(originalInput, cleanUrl)
            SocialPlatform.THREADS -> extractThreadsDirect(originalInput, cleanUrl)
            SocialPlatform.UNKNOWN -> null
        }
    }

    /**
     * X / Twitter extractor using vxtwitter API, fxtwitter API, Syndication API, and Authenticated GraphQL fallback.
     */
    private fun extractTwitterDirect(originalInput: String, cleanUrl: String): ExtractedMediaInfo? {
        val cookie = authCookieStore.getEffectiveCookie(SocialPlatform.X_TWITTER, cleanUrl)
        val twitterHeaders = buildMediaHeaders(SocialPlatform.X_TWITTER, cookie)

        val resolvedUrl = if (cleanUrl.contains("t.co/", ignoreCase = true) ||
            !cleanUrl.contains("/status/", ignoreCase = true)
        ) {
            resolveRedirectUrl(cleanUrl, DESKTOP_CHROME_UA)
        } else {
            cleanUrl
        }

        val statusIdMatch = Regex("""(?:status|statuses)/(\d+)""", RegexOption.IGNORE_CASE)
            .find(resolvedUrl)
            ?: Regex("""/(\d{12,25})""").find(resolvedUrl)
            ?: return null

        val tweetId = statusIdMatch.groupValues[1]

        val endpoints = listOf(
            "https://api.vxtwitter.com/Twitter/status/$tweetId",
            "https://api.fxtwitter.com/Twitter/status/$tweetId"
        )

        for (apiEndpoint in endpoints) {
            val requestBuilder = Request.Builder()
                .url(apiEndpoint)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "application/json")
                .get()
            if (cookie.isNotBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            val responseBody = runCatching {
                httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull() ?: continue

            val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: continue
            val rootObj = json.optJSONObject("tweet") ?: json

            val text = rootObj.optString("text", "").trim()
            val userName = rootObj.optString("user_name", "")
                .ifBlank { rootObj.optJSONObject("author")?.optString("name", "").orEmpty() }
            val screenName = rootObj.optString("user_screen_name", "")
                .ifBlank { rootObj.optJSONObject("author")?.optString("screen_name", "").orEmpty() }

            val authorLabel = when {
                userName.isNotBlank() && screenName.isNotBlank() -> "$userName (@$screenName)"
                screenName.isNotBlank() -> "@$screenName"
                userName.isNotBlank() -> userName
                else -> "X / Twitter"
            }

            val options = mutableListOf<MediaOption>()
            var thumbnailUrl = ""
            var durationSec = 0L

            val mediaExtended = rootObj.optJSONArray("media_extended")
            if (mediaExtended != null && mediaExtended.length() > 0) {
                var markedHighest = false
                for (i in 0 until mediaExtended.length()) {
                    val item = mediaExtended.optJSONObject(i) ?: continue
                    val type = item.optString("type", "").lowercase(Locale.ROOT)
                    val mediaUrl = item.optString("url", "").trim()
                    val thumb = item.optString("thumbnail_url", "").trim()
                    if (thumbnailUrl.isBlank() && thumb.isNotBlank()) {
                        thumbnailUrl = thumb
                    }
                    val durationMillis = item.optLong("duration_millis", 0L)
                    if (durationMillis > 0L && durationSec == 0L) {
                        durationSec = durationMillis / 1000L
                    }

                    if (type == "video" || type == "gif") {
                        if (mediaUrl.isNotBlank()) {
                            val width = item.optJSONObject("size")?.optInt("width", 0) ?: 0
                            val height = item.optJSONObject("size")?.optInt("height", 0) ?: 0
                            val dimText = if (width > 0 && height > 0) " (${width}x${height})" else ""
                            val label = if (type == "gif") {
                                "GIF / MP4 Động$dimText"
                            } else {
                                "Video HD Gốc$dimText"
                            }
                            options.add(
                                MediaOption(
                                    url = mediaUrl,
                                    qualityLabel = label,
                                    extension = "mp4",
                                    mediaType = MediaType.VIDEO,
                                    isHighestQuality = !markedHighest,
                                    isNoWatermark = true,
                                    customHeaders = twitterHeaders
                                )
                            )
                            markedHighest = true
                        }
                        if (thumb.isNotBlank()) {
                            options.add(
                                MediaOption(
                                    url = thumb,
                                    qualityLabel = "Ảnh bìa (Cover)",
                                    extension = "jpg",
                                    mediaType = MediaType.IMAGE,
                                    isHighestQuality = false,
                                    isNoWatermark = true,
                                    customHeaders = twitterHeaders
                                )
                            )
                        }
                    } else if (type == "image" || type == "photo") {
                        val imgUrl = mediaUrl.ifBlank { thumb }
                        if (imgUrl.isNotBlank()) {
                            if (thumbnailUrl.isBlank()) thumbnailUrl = imgUrl
                            options.add(
                                MediaOption(
                                    url = imgUrl,
                                    qualityLabel = if (mediaExtended.length() > 1) {
                                        "Ảnh HD #${i + 1}"
                                    } else {
                                        "Ảnh gốc HD"
                                    },
                                    extension = "jpg",
                                    mediaType = MediaType.IMAGE,
                                    isHighestQuality = !markedHighest,
                                    isNoWatermark = true,
                                    customHeaders = twitterHeaders
                                )
                            )
                            markedHighest = true
                        }
                    }
                }
            }

            // Also support fxtwitter nested `media.all` format if `media_extended` was absent
            if (options.isEmpty()) {
                val fxMediaAll = rootObj.optJSONObject("media")?.optJSONArray("all")
                if (fxMediaAll != null && fxMediaAll.length() > 0) {
                    var markedHighest = false
                    for (i in 0 until fxMediaAll.length()) {
                        val item = fxMediaAll.optJSONObject(i) ?: continue
                        val type = item.optString("type", "").lowercase(Locale.ROOT)
                        val mediaUrl = item.optString("url", "").trim()
                        val thumb = item.optString("thumbnail_url", "").trim()
                        if (thumbnailUrl.isBlank() && thumb.isNotBlank()) thumbnailUrl = thumb
                        if (durationSec == 0L) durationSec = item.optDouble("duration", 0.0).toLong()

                        if ((type == "video" || type == "gif") && mediaUrl.isNotBlank()) {
                            options.add(
                                MediaOption(
                                    url = mediaUrl,
                                    qualityLabel = "Video HD Gốc",
                                    extension = "mp4",
                                    mediaType = MediaType.VIDEO,
                                    isHighestQuality = !markedHighest,
                                    isNoWatermark = true,
                                    customHeaders = twitterHeaders
                                )
                            )
                            markedHighest = true
                            if (thumb.isNotBlank()) {
                                options.add(
                                    MediaOption(
                                        url = thumb,
                                        qualityLabel = "Ảnh bìa (Cover)",
                                        extension = "jpg",
                                        mediaType = MediaType.IMAGE,
                                        isHighestQuality = false,
                                        customHeaders = twitterHeaders
                                    )
                                )
                            }
                        } else if ((type == "photo" || type == "image") && mediaUrl.isNotBlank()) {
                            if (thumbnailUrl.isBlank()) thumbnailUrl = mediaUrl
                            options.add(
                                MediaOption(
                                    url = mediaUrl,
                                    qualityLabel = "Ảnh gốc HD #${i + 1}",
                                    extension = "jpg",
                                    mediaType = MediaType.IMAGE,
                                    isHighestQuality = !markedHighest,
                                    customHeaders = twitterHeaders
                                )
                            )
                            markedHighest = true
                        }
                    }
                }
            }

            if (options.isNotEmpty()) {
                val displayTitle = text.ifBlank { "Bài đăng X / Twitter #$tweetId" }
                return ExtractedMediaInfo(
                    originalUrl = originalInput,
                    cleanUrl = resolvedUrl,
                    platform = SocialPlatform.X_TWITTER,
                    title = displayTitle,
                    author = authorLabel,
                    thumbnailUrl = thumbnailUrl,
                    durationSeconds = durationSec,
                    options = options
                )
            }
        }

        // Fallback to Twitter Syndication API and Authenticated GraphQL (Protected / 18+ NSFW tweets)
        return extractTwitterSyndicationOrGraphQl(tweetId, resolvedUrl, cookie)?.copy(
            originalUrl = originalInput
        )
    }

    /**
     * Extracts Protected & 18+ (NSFW) X/Twitter videos via Syndication API and Authenticated GraphQL TweetResultByRestId.
     */
    private fun extractTwitterSyndicationOrGraphQl(
        tweetId: String,
        cleanUrl: String,
        cookie: String
    ): ExtractedMediaInfo? {
        val twitterHeaders = buildMediaHeaders(SocialPlatform.X_TWITTER, cookie)

        // 1. Try Syndication API (`cdn.syndication.twimg.com/tweet-result`)
        val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=a"
        val syndicationReq = Request.Builder()
            .url(syndicationUrl)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("Accept", "application/json")
            .apply {
                if (cookie.isNotBlank()) {
                    header("Cookie", cookie)
                }
            }
            .get()
            .build()

        val syndicationBody = runCatching {
            httpClient.newCall(syndicationReq).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()

        if (!syndicationBody.isNullOrBlank()) {
            val json = runCatching { JSONObject(syndicationBody) }.getOrNull()
            if (json != null) {
                val text = json.optString("text", "").trim()
                val userObj = json.optJSONObject("user")
                val userName = userObj?.optString("name", "").orEmpty()
                val screenName = userObj?.optString("screen_name", "").orEmpty()
                val authorLabel = when {
                    userName.isNotBlank() && screenName.isNotBlank() -> "$userName (@$screenName)"
                    screenName.isNotBlank() -> "@$screenName"
                    userName.isNotBlank() -> userName
                    else -> "X / Twitter"
                }

                val mp4Variants = mutableListOf<Pair<Int, String>>()
                var thumbnailUrl = json.optJSONObject("video")?.optString("poster", "").orEmpty()

                val videoVariants = json.optJSONObject("video")?.optJSONArray("variants")
                if (videoVariants != null) {
                    for (i in 0 until videoVariants.length()) {
                        val v = videoVariants.optJSONObject(i) ?: continue
                        val type = v.optString("type", v.optString("content_type", "")).lowercase(Locale.ROOT)
                        val src = v.optString("src", v.optString("url", "")).trim()
                        if ((type == "video/mp4" || src.contains(".mp4")) && src.startsWith("http")) {
                            val bitrate = v.optInt("bitrate", extractResolutionScore(src) * 1000)
                            mp4Variants.add(bitrate to src)
                        }
                    }
                }

                val mediaDetails = json.optJSONArray("mediaDetails")
                if (mediaDetails != null) {
                    for (i in 0 until mediaDetails.length()) {
                        val mediaItem = mediaDetails.optJSONObject(i) ?: continue
                        val mediaThumb = mediaItem.optString("media_url_https", "").trim()
                        if (thumbnailUrl.isBlank() && mediaThumb.isNotBlank()) {
                            thumbnailUrl = mediaThumb
                        }
                        val variants = mediaItem.optJSONObject("video_info")?.optJSONArray("variants")
                        if (variants != null) {
                            for (j in 0 until variants.length()) {
                                val v = variants.optJSONObject(j) ?: continue
                                val contentType = v.optString("content_type", v.optString("type", "")).lowercase(Locale.ROOT)
                                val url = v.optString("url", v.optString("src", "")).trim()
                                if (contentType == "video/mp4" && url.startsWith("http")) {
                                    val bitrate = v.optInt("bitrate", extractResolutionScore(url) * 1000)
                                    mp4Variants.add(bitrate to url)
                                }
                            }
                        }
                    }
                }

                val sortedVariants = mp4Variants
                    .sortedByDescending { it.first }
                    .distinctBy { it.second }

                val options = mutableListOf<MediaOption>()
                sortedVariants.forEachIndexed { index, (bitrate, videoUrl) ->
                    val resMatch = Regex("""/(\d{2,4}x\d{2,4})/""").find(videoUrl)?.groupValues?.getOrNull(1)
                    val kbps = if (bitrate > 1000) " - ${bitrate / 1000} kbps" else ""
                    val label = when {
                        resMatch != null -> "Video $resMatch (MP4)$kbps"
                        index == 0 -> "Video HD Gốc (MP4)$kbps"
                        else -> "Video SD #${index + 1} (MP4)$kbps"
                    }
                    options.add(
                        MediaOption(
                            url = videoUrl,
                            qualityLabel = label,
                            extension = "mp4",
                            mediaType = MediaType.VIDEO,
                            isHighestQuality = (index == 0),
                            isNoWatermark = true,
                            customHeaders = twitterHeaders
                        )
                    )
                }

                if (thumbnailUrl.startsWith("http")) {
                    options.add(
                        MediaOption(
                            url = thumbnailUrl,
                            qualityLabel = if (options.isEmpty()) "Ảnh gốc HD" else "Ảnh bìa (Cover)",
                            extension = "jpg",
                            mediaType = MediaType.IMAGE,
                            isHighestQuality = options.isEmpty(),
                            isNoWatermark = true,
                            customHeaders = twitterHeaders
                        )
                    )
                }

                if (options.any { it.mediaType == MediaType.VIDEO }) {
                    return ExtractedMediaInfo(
                        originalUrl = cleanUrl,
                        cleanUrl = cleanUrl,
                        platform = SocialPlatform.X_TWITTER,
                        title = text.ifBlank { "Bài đăng X / Twitter #$tweetId" },
                        author = authorLabel,
                        thumbnailUrl = thumbnailUrl,
                        durationSeconds = 0L,
                        options = options
                    )
                }
            }
        }

        // 2. Authenticated X GraphQL `TweetResultByRestId` when `auth_token` and `ct0` are present
        val csrf = authCookieStore.extractCookieValue(cookie, "ct0")
        if (cookie.contains("auth_token=") && !csrf.isNullOrBlank()) {
            val graphqlUrl =
                "https://x.com/i/api/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId?variables=%7B%22tweetId%22%3A%22${tweetId}%22%2C%22withCommunity%22%3Afalse%2C%22includePromotedContent%22%3Afalse%2C%22withVoice%22%3Afalse%7D&features=%7B%22creator_subscriptions_tweet_preview_api_enabled%22%3Atrue%2C%22freedom_of_speech_not_reach_fetch_enabled%22%3Atrue%2C%22graphql_is_translatable_rweb_tweet_is_translatable_enabled%22%3Atrue%2C%22longform_notetweets_consumption_enabled%22%3Atrue%2C%22responsive_web_edit_tweet_api_enabled%22%3Atrue%2C%22responsive_web_enhance_cards_enabled%22%3Afalse%2C%22responsive_web_graphql_exclude_directive_enabled%22%3Atrue%2C%22responsive_web_graphql_skip_user_profile_image_extensions_enabled%22%3Afalse%2C%22responsive_web_graphql_timeline_navigation_enabled%22%3Atrue%2C%22standardized_nudges_misinfo%22%3Atrue%2C%22tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled%22%3Atrue%2C%22verified_phone_label_enabled%22%3Afalse%2C%22view_counts_everywhere_api_enabled%22%3Atrue%7D"

            val graphqlReq = Request.Builder()
                .url(graphqlUrl)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $X_PUBLIC_BEARER_TOKEN")
                .header("x-csrf-token", csrf)
                .header("x-twitter-active-user", "yes")
                .header("x-twitter-auth-type", "OAuth2Session")
                .header("Cookie", cookie)
                .header("Referer", "https://x.com/")
                .get()
                .build()

            val rawGraphQl = runCatching {
                httpClient.newCall(graphqlReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()

            if (!rawGraphQl.isNullOrBlank()) {
                val unescaped = unescapeHtmlAndJson(rawGraphQl)
                val variantsFound = mutableListOf<Pair<Int, String>>()

                // Match variant JSON objects containing content_type: video/mp4 in any key order
                val variantObjRegex = Regex("""\{[^{}]*"content_type"\s*:\s*"video/mp4"[^{}]*\}""")
                for (match in variantObjRegex.findAll(unescaped)) {
                    val block = match.value
                    val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.getOrNull(1)?.trim()
                    if (!url.isNullOrBlank() && url.startsWith("http")) {
                        val bitrate = Regex(""""bitrate"\s*:\s*(\d+)""")
                            .find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: (extractResolutionScore(url) * 1000)
                        variantsFound.add(bitrate to url)
                    }
                }

                // Also match direct `"content_type":"video/mp4","url":"([^"]+)"` pattern
                val directVariantRegex = Regex(
                    """"content_type"\s*:\s*"video/mp4"\s*,\s*(?:"bitrate"\s*:\s*(\d+)\s*,\s*)?"url"\s*:\s*"([^"]+)""""
                )
                for (match in directVariantRegex.findAll(unescaped)) {
                    val bitrate = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                    val url = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (url.startsWith("http")) {
                        variantsFound.add(bitrate to url)
                    }
                }

                val sorted = variantsFound
                    .sortedByDescending { (bitrate, url) ->
                        if (bitrate > 0) bitrate else extractResolutionScore(url) * 1000
                    }
                    .distinctBy { it.second }

                val options = mutableListOf<MediaOption>()
                sorted.forEachIndexed { idx, (bitrate, videoUrl) ->
                    val resMatch = Regex("""/(\d{2,4}x\d{2,4})/""").find(videoUrl)?.groupValues?.getOrNull(1)
                    val kbps = if (bitrate > 1000) " (${bitrate / 1000} kbps)" else ""
                    val label = when {
                        resMatch != null -> "Video $resMatch (MP4)$kbps"
                        idx == 0 -> "Video HD Gốc (MP4)$kbps"
                        else -> "Video #${idx + 1} (MP4)$kbps"
                    }
                    options.add(
                        MediaOption(
                            url = videoUrl,
                            qualityLabel = label,
                            extension = "mp4",
                            mediaType = MediaType.VIDEO,
                            isHighestQuality = (idx == 0),
                            isNoWatermark = true,
                            customHeaders = twitterHeaders
                        )
                    )
                }

                val thumbUrl = Regex(""""media_url_https"\s*:\s*"([^"]+)"""")
                    .find(unescaped)?.groupValues?.getOrNull(1).orEmpty()
                if (thumbUrl.startsWith("http")) {
                    options.add(
                        MediaOption(
                            url = thumbUrl,
                            qualityLabel = if (options.isEmpty()) "Ảnh gốc HD" else "Ảnh bìa (Cover)",
                            extension = "jpg",
                            mediaType = MediaType.IMAGE,
                            isHighestQuality = options.isEmpty(),
                            isNoWatermark = true,
                            customHeaders = twitterHeaders
                        )
                    )
                }

                if (options.isNotEmpty()) {
                    val fullText = Regex(""""full_text"\s*:\s*"([^"]+)"""")
                        .find(unescaped)?.groupValues?.getOrNull(1)
                        ?.replace("\\n", " ")
                        ?.trim()
                        .orEmpty()
                    val screenName = Regex(""""screen_name"\s*:\s*"([^"]+)"""")
                        .find(unescaped)?.groupValues?.getOrNull(1)
                        ?.let { "@$it" }
                        ?: "X / Twitter"

                    return ExtractedMediaInfo(
                        originalUrl = cleanUrl,
                        cleanUrl = cleanUrl,
                        platform = SocialPlatform.X_TWITTER,
                        title = fullText.ifBlank { "Bài đăng X / Twitter #$tweetId" },
                        author = screenName,
                        thumbnailUrl = thumbUrl,
                        durationSeconds = 0L,
                        options = options
                    )
                }
            }
        }

        return null
    }

    /**
     * TikTok extractor using TikWM API (`POST https://www.tikwm.com/api/`) and authenticated direct page fallback.
     */
    private fun extractTikTokDirect(originalInput: String, cleanUrl: String): ExtractedMediaInfo? {
        val cookie = authCookieStore.getEffectiveCookie(SocialPlatform.TIKTOK, cleanUrl)
        val customHeaders = buildMediaHeaders(SocialPlatform.TIKTOK, cookie)

        val formBody = FormBody.Builder()
            .add("url", cleanUrl)
            .add("hd", "1")
            .build()

        val requestBuilder = Request.Builder()
            .url("https://www.tikwm.com/api/")
            .post(formBody)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("Accept", "application/json")
            .header("Referer", "https://www.tikwm.com/")
        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)
        }

        val responseStr = runCatching {
            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()

        if (!responseStr.isNullOrBlank()) {
            val root = runCatching { JSONObject(responseStr) }.getOrNull()
            val data = if (root?.optInt("code", -1) == 0) root.optJSONObject("data") else null
            if (data != null) {
                fun normalizeTikwmUrl(raw: String): String {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) return ""
                    return if (trimmed.startsWith("/")) {
                        "https://www.tikwm.com$trimmed"
                    } else {
                        trimmed
                    }
                }

                val title = data.optString("title", "").trim()
                    .ifBlank { "Video TikTok #${data.optString("id", "media")}" }

                val authorObj = data.optJSONObject("author")
                val nickname = authorObj?.optString("nickname", "").orEmpty()
                val uniqueId = authorObj?.optString("unique_id", "").orEmpty()
                val author = when {
                    nickname.isNotBlank() && uniqueId.isNotBlank() -> "$nickname (@$uniqueId)"
                    nickname.isNotBlank() -> nickname
                    uniqueId.isNotBlank() -> "@$uniqueId"
                    else -> "TikTok Creator"
                }

                val coverUrl = normalizeTikwmUrl(
                    data.optString("origin_cover", "").ifBlank { data.optString("cover", "") }
                )
                val durationSec = data.optLong("duration", 0L)

                val options = mutableListOf<MediaOption>()

                val hdPlay = normalizeTikwmUrl(data.optString("hdplay", ""))
                val play = normalizeTikwmUrl(data.optString("play", ""))
                val wmPlay = normalizeTikwmUrl(data.optString("wmplay", ""))
                val musicUrl = normalizeTikwmUrl(
                    data.optString("music", "").ifBlank {
                        data.optJSONObject("music_info")?.optString("play", "").orEmpty()
                    }
                )

                val imagesArray = data.optJSONArray("images")
                val hasSlideshow = imagesArray != null && imagesArray.length() > 0

                if (!hasSlideshow) {
                    if (hdPlay.isNotBlank()) {
                        options.add(
                            MediaOption(
                                url = hdPlay,
                                qualityLabel = "Full HD (Không Logo)",
                                extension = "mp4",
                                mediaType = MediaType.VIDEO,
                                sizeText = formatBytes(data.optLong("hd_size", 0L)),
                                isHighestQuality = true,
                                isNoWatermark = true,
                                customHeaders = customHeaders
                            )
                        )
                    }

                    if (play.isNotBlank() && play != hdPlay) {
                        options.add(
                            MediaOption(
                                url = play,
                                qualityLabel = "HD (Không Logo)",
                                extension = "mp4",
                                mediaType = MediaType.VIDEO,
                                sizeText = formatBytes(data.optLong("size", 0L)),
                                isHighestQuality = hdPlay.isBlank(),
                                isNoWatermark = true,
                                customHeaders = customHeaders
                            )
                        )
                    }

                    if (wmPlay.isNotBlank()) {
                        options.add(
                            MediaOption(
                                url = wmPlay,
                                qualityLabel = "SD (Có Logo)",
                                extension = "mp4",
                                mediaType = MediaType.VIDEO,
                                sizeText = formatBytes(data.optLong("wm_size", 0L)),
                                isHighestQuality = false,
                                isNoWatermark = false,
                                customHeaders = customHeaders
                            )
                        )
                    }
                } else if (imagesArray != null) {
                    for (i in 0 until imagesArray.length()) {
                        val imgUrl = imagesArray.optString(i, "").trim()
                        if (imgUrl.isNotBlank()) {
                            options.add(
                                MediaOption(
                                    url = imgUrl,
                                    qualityLabel = "Ảnh Slide #${i + 1}",
                                    extension = "jpg",
                                    mediaType = MediaType.IMAGE,
                                    isHighestQuality = (i == 0),
                                    isNoWatermark = true,
                                    customHeaders = customHeaders
                                )
                            )
                        }
                    }
                }

                if (musicUrl.isNotBlank()) {
                    options.add(
                        MediaOption(
                            url = musicUrl,
                            qualityLabel = "Nhạc nền gốc (MP3)",
                            extension = "mp3",
                            mediaType = MediaType.AUDIO,
                            isHighestQuality = false,
                            isNoWatermark = true,
                            customHeaders = customHeaders
                        )
                    )
                }

                if (coverUrl.isNotBlank()) {
                    options.add(
                        MediaOption(
                            url = coverUrl,
                            qualityLabel = "Ảnh bìa (Cover)",
                            extension = "jpg",
                            mediaType = MediaType.IMAGE,
                            isHighestQuality = false,
                            isNoWatermark = true,
                            customHeaders = customHeaders
                        )
                    )
                }

                if (options.isNotEmpty()) {
                    return ExtractedMediaInfo(
                        originalUrl = originalInput,
                        cleanUrl = cleanUrl,
                        platform = SocialPlatform.TIKTOK,
                        title = title,
                        author = author,
                        thumbnailUrl = coverUrl,
                        durationSeconds = durationSec,
                        options = options
                    )
                }
            }
        }

        // Direct authenticated HTML fallback for private/friends-only TikTok videos
        if (cookie.isNotBlank()) {
            val directReq = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Cookie", cookie)
                .get()
                .build()
            val rawHtml = runCatching {
                httpClient.newCall(directReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
            if (!rawHtml.isNullOrBlank()) {
                return parseHtmlAndSniffedUrls(rawHtml, cleanUrl, null, emptyList())?.copy(
                    originalUrl = originalInput
                )
            }
        }

        return null
    }

    private data class FacebookEfgMeta(
        val videoId: String?,
        val qualityLabel: String?,
        val resolutionScore: Int,
        val isAudioOnly: Boolean
    )

    private data class ScoredStreamCandidate(
        val url: String,
        val label: String,
        val score: Int,
        val mediaType: MediaType = MediaType.VIDEO,
        val extension: String = "mp4",
        val audioUrl: String? = null,
        val videoId: String? = null
    )

    private data class StoryBucketGroup(
        val key: String,
        val rawToken: String,
        var videoId: String? = null,
        val candidates: MutableList<ScoredStreamCandidate> = mutableListOf()
    )

    /**
     * Decodes Facebook/Instagram `efg=` Base64 query parameter to identify the exact video_id,
     * resolution (1080p, 720p, 480p, 360p), and whether a DASH stream is audio-only.
     */
    private fun inspectFacebookStreamUrl(rawUrl: String): FacebookEfgMeta {
        val lowerUrl = rawUrl.lowercase(Locale.ROOT)
        val hasHdTagParam = lowerUrl.contains("&tag=hd") || lowerUrl.contains("?tag=hd")
        val hasSdTagParam = lowerUrl.contains("&tag=sd") || lowerUrl.contains("?tag=sd")

        val efgMatch = Regex("""[?&]efg=([^&"'\s]+)""").find(rawUrl)?.groupValues?.getOrNull(1)
        if (efgMatch == null) {
            return when {
                hasHdTagParam -> FacebookEfgMeta(null, "Full HD 720p/1080p (MP4)", 900, false)
                hasSdTagParam -> FacebookEfgMeta(null, "SD 360p (MP4)", 360, false)
                else -> FacebookEfgMeta(null, null, 0, false)
            }
        }

        val urlDecoded = runCatching {
            URLDecoder.decode(efgMatch, "UTF-8")
        }.getOrDefault(efgMatch)

        val decodedJsonStr = runCatching {
            val bytes = try {
                Base64.decode(urlDecoded, Base64.URL_SAFE or Base64.NO_WRAP)
            } catch (_: Exception) {
                Base64.decode(urlDecoded, Base64.DEFAULT)
            }
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return FacebookEfgMeta(null, null, 0, false)

        val json = runCatching { JSONObject(decodedJsonStr) }.getOrNull()
            ?: return FacebookEfgMeta(null, null, 0, false)

        // IMPORTANT: Only use `video_id` (never `xpv_asset_id`, which is an internal storage ID that 404s on /watch/?v=)
        val videoId = json.optString("video_id", "")
            .trim()
            .takeIf { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }

        val vencodeTag = json.optString("vencode_tag", "")
            .ifBlank { json.optString("venc_tags", "") }
            .ifBlank { json.optString("encode_tag", "") }
            .lowercase(Locale.ROOT)
        val isAudioOnly = (vencodeTag.contains("audio") || vencodeTag.contains("heaac") || vencodeTag.contains("opus") || vencodeTag.contains("mp4a")) &&
            !vencodeTag.contains("progressive")

        if (isAudioOnly) {
            return FacebookEfgMeta(videoId, "Âm thanh gốc (M4A)", 100, true)
        }

        val isExplicitHd = vencodeTag.endsWith(".hd") ||
            vencodeTag.contains("_hd") ||
            vencodeTag.contains("sve_hd") ||
            hasHdTagParam
        val isExplicitSd = vencodeTag.endsWith(".sd") ||
            vencodeTag.contains("_sd") ||
            vencodeTag.contains("sve_sd") ||
            hasSdTagParam

        val resToken = Regex("""(?:^|[._-])(2160|1920|1440|1280|1080|720|540|480|360|240)p?(?:$|[._-])""")
            .find(vencodeTag)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val crfTier = Regex("""_q(90|80|70|60|50|40|30|20)(?:$|[._-])""")
            .find(vencodeTag)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val (label, score) = when {
            resToken == 2160 -> "4K Ultra HD 2160p (MP4)" to 2160
            resToken == 1440 -> "2K QHD 1440p (MP4)" to 1440
            resToken == 1080 || resToken == 1920 || crfTier == 90 -> "Full HD 1080p (MP4)" to 1080
            resToken == 720 || resToken == 1280 || crfTier == 80 -> "HD 720p (MP4)" to 720
            crfTier == 70 -> "HD 640p (MP4)" to 640
            isExplicitHd -> "HD 720p / 1080p (MP4)" to 880
            resToken == 540 || crfTier == 60 -> "qHD 540p (MP4)" to 540
            resToken == 480 || crfTier == 50 -> "SD 480p (MP4)" to 480
            resToken == 360 || crfTier == 40 || isExplicitSd -> "SD 360p (MP4)" to 360
            resToken == 240 || crfTier == 30 || crfTier == 20 -> "SD 240p (MP4)" to 240
            vencodeTag.contains("hd") || vencodeTag.contains("high") -> "HD 720p / 1080p (MP4)" to 880
            vencodeTag.contains("sd") -> "SD 360p (MP4)" to 360
            else -> null to 0
        }

        return FacebookEfgMeta(videoId, label, score, false)
    }

    private fun isFacebookDashVideoOnlyUrl(rawUrl: String): Boolean {
        val efgMatch = Regex("""[?&]efg=([^&"'\s]+)""").find(rawUrl)?.groupValues?.getOrNull(1) ?: return false
        val urlDecoded = runCatching { URLDecoder.decode(efgMatch, "UTF-8") }.getOrDefault(efgMatch)
        val decodedJsonStr = runCatching {
            val bytes = try {
                Base64.decode(urlDecoded, Base64.URL_SAFE or Base64.NO_WRAP)
            } catch (_: Exception) {
                Base64.decode(urlDecoded, Base64.DEFAULT)
            }
            String(bytes, Charsets.UTF_8).lowercase(Locale.ROOT)
        }.getOrNull() ?: return false

        if (decodedJsonStr.contains("progressive")) return false
        if (decodedJsonStr.contains("audio") || decodedJsonStr.contains("heaac")) return false
        return decodedJsonStr.contains("\"dash_") || decodedJsonStr.contains("dash_r2") || decodedJsonStr.contains("dash_vp9") || decodedJsonStr.contains("dash_h264")
    }

    private fun extractFacebookVideoIdFromText(text: String): String? {
        val patterns = listOf(
            Regex("""/videos/(?:pcb\.\d+/)?(\d{8,22})"""),
            Regex("""/reel/(\d{8,22})"""),
            Regex("""[?&]v=(\d{8,22})"""),
            Regex("""story_fbid=(\d{8,22})"""),
            Regex(""""video_id"\s*:\s*"?(\d{8,22})"?"""),
            Regex(""""videoId"\s*:\s*"?(\d{8,22})"?""")
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)
        }
    }

    /**
     * Extracts Facebook Story card tokens from `/stories/<bucket_id>/<story_card_token>/`
     * (e.g. `UzpfSVNDOjE3NTgxODg2ODE5MDcyNTA=` -> Base64 `S:_ISC:1758188681907250` -> `1758188681907250`).
     */
    private fun extractFacebookStoryTokens(url: String): List<String> {
        val match = Regex("""/stories/[^/?#]+/([^/?#]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return emptyList()
        val rawToken = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (rawToken.isBlank()) return emptyList()
        val urlDecoded = runCatching { URLDecoder.decode(rawToken, "UTF-8") }.getOrDefault(rawToken)
        val tokens = mutableListOf(urlDecoded)
        if (rawToken != urlDecoded) tokens.add(rawToken)

        runCatching {
            val bytes = try {
                Base64.decode(urlDecoded, Base64.DEFAULT)
            } catch (_: Exception) {
                Base64.decode(urlDecoded, Base64.URL_SAFE)
            }
            val decodedStr = String(bytes, Charsets.UTF_8)
            Regex("""(\d{8,22})""").findAll(decodedStr).forEach { m ->
                val numId = m.groupValues.getOrNull(1)
                if (!numId.isNullOrBlank()) tokens.add(numId)
            }
        }
        return tokens.distinct()
    }

    private fun unwrapFacebookLoginRedirect(url: String): String {
        var current = url
        repeat(3) {
            val lower = current.lowercase(Locale.ROOT)
            val paramRegex = when {
                lower.contains("/login") && lower.contains("next=") -> Regex("""[?&]next=([^&#]+)""", RegexOption.IGNORE_CASE)
                lower.contains("l.facebook.com/l.php") && lower.contains("u=") -> Regex("""[?&]u=([^&#]+)""", RegexOption.IGNORE_CASE)
                else -> null
            } ?: return current

            val encodedTarget = paramRegex.find(current)?.groupValues?.getOrNull(1)
            if (encodedTarget.isNullOrBlank()) return current
            val decoded = runCatching { URLDecoder.decode(encodedTarget, "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.startsWith("http", ignoreCase = true)) {
                current = decoded
            } else {
                return current
            }
        }
        return current
    }

    private fun normalizeFacebookStoryCardKey(rawToken: String): String {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) return ""
        val urlDecoded = runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        if (urlDecoded.all(Char::isDigit) && urlDecoded.length >= 8) {
            return "ISC_$urlDecoded"
        }
        val decodedNum = runCatching {
            val cleanB64 = urlDecoded.trimEnd('=')
            val padded = cleanB64 + "=".repeat((4 - cleanB64.length % 4) % 4)
            val bytes = try {
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: Exception) {
                Base64.decode(padded, Base64.URL_SAFE)
            }
            val decodedStr = String(bytes, Charsets.UTF_8)
            Regex("""(\d{8,22})""").find(decodedStr)?.groupValues?.getOrNull(1)
        }.getOrNull()
        return if (!decodedNum.isNullOrBlank()) {
            "ISC_$decodedNum"
        } else {
            urlDecoded.trimEnd('=')
        }
    }

    private fun extractFacebookPhotoStoryUrlFromSlice(slice: String): String? {
        val photoPatterns = listOf(
            Regex(""""viewer_image"\s*:\s*\{[^{}]*"uri"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""photo_image"\s*:\s*\{[^{}]*"uri"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""__typename"\s*:\s*"Photo"[\s\S]{0,1200}?"image"\s*:\s*\{[^{}]*"uri"\s*:\s*"(https?:[^"]+)"""")
        )
        for (pattern in photoPatterns) {
            for (m in pattern.findAll(slice)) {
                val rawUri = m.groupValues.getOrNull(1) ?: continue
                val cleaned = sanitizeCandidateUrl(rawUri)
                val lower = cleaned.lowercase(Locale.ROOT)
                if (cleaned.startsWith("http", ignoreCase = true) &&
                    !lower.contains("p50x50") &&
                    !lower.contains("s50x50") &&
                    !lower.contains("p100x100") &&
                    !lower.contains("s100x100")
                ) {
                    return cleaned
                }
            }
        }
        return null
    }

    private fun toDesktopFacebookUrl(url: String): String {
        val unwrapped = unwrapFacebookLoginRedirect(url)
        return unwrapped
            .replace("://m.facebook.com", "://www.facebook.com")
            .replace("://mbasic.facebook.com", "://www.facebook.com")
            .replace("://touch.facebook.com", "://www.facebook.com")
            .replace("://web.facebook.com", "://www.facebook.com")
            .replace("?_rdc=1&_rdr", "")
            .replace("&_rdc=1&_rdr", "")
    }

    private fun buildDesktopFacebookCookie(rawCookie: String): String {
        if (rawCookie.isBlank()) return ""
        val filtered = rawCookie.split(";")
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("m_pixel_ratio=", ignoreCase = true) &&
                    !it.startsWith("wd=", ignoreCase = true) &&
                    !it.startsWith("dpr=", ignoreCase = true)
            }
        return (filtered + listOf("wd=1920x1080", "dpr=2")).joinToString("; ")
    }

    private fun extractFacebookStreamsFromSlice(
        region: String,
        isTargetSlice: Boolean,
        register: (url: String?, label: String, score: Int, isTarget: Boolean, audioUrl: String?) -> Unit
    ) {
        // 1. Parse `progressive_urls` (handles nested `"metadata":{"quality":"HD"}` without breaking on `{` or `}`)
        val progRegex = Regex(""""progressive_url"\s*:\s*"(https?:[^"]+)"""")
        progRegex.findAll(region).forEach { m ->
            val url = m.groupValues.getOrNull(1) ?: return@forEach
            val afterStart = m.range.last + 1
            val afterEnd = (afterStart + 220).coerceAtMost(region.length)
            val afterSlice = region.substring(afterStart, afterEnd)
                .substringBefore("\"progressive_url\"")
            val beforeStart = (m.range.first - 140).coerceAtLeast(0)
            val beforeSlice = region.substring(beforeStart, m.range.first)
                .substringAfterLast("\"progressive_url\"")

            val isHdMeta = Regex(""""quality"\s*:\s*"HD"""", RegexOption.IGNORE_CASE)
                .containsMatchIn(afterSlice) ||
                Regex(""""quality"\s*:\s*"HD"""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(beforeSlice)
            val isSdMeta = Regex(""""quality"\s*:\s*"SD"""", RegexOption.IGNORE_CASE)
                .containsMatchIn(afterSlice)

            when {
                isHdMeta -> register(url, "HD 720p / 1080p Gốc (MP4)", 1050, isTargetSlice, null)
                isSdMeta -> register(url, "SD 360p (MP4)", 380, isTargetSlice, null)
                else -> register(url, "HD Video (MP4)", 740, isTargetSlice, null)
            }
        }

        // 2. Parse Classic / Legacy HD & SD fields
        val classicHdPatterns = listOf(
            Regex(""""browser_native_hd_url"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""playable_url_quality_hd"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""hd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""hd_src"\s*:\s*"(https?:[^"]+)"""")
        )
        for (regex in classicHdPatterns) {
            regex.findAll(region).forEach { m ->
                register(m.groupValues.getOrNull(1), "HD 720p / 1080p Gốc (MP4)", 1040, isTargetSlice, null)
            }
        }

        val classicSdPatterns = listOf(
            Regex(""""browser_native_sd_url"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""playable_url"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""sd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)""""),
            Regex(""""sd_src"\s*:\s*"(https?:[^"]+)"""")
        )
        for (regex in classicSdPatterns) {
            regex.findAll(region).forEach { m ->
                register(m.groupValues.getOrNull(1), "SD 360p (MP4)", 380, isTargetSlice, null)
            }
        }

        // 3. Parse DASH Manifest XML (`manifest_xml` / `dash_manifest_xml_string` `<Representation ...><BaseURL>...</BaseURL>`)
        val xmlReady = region
            .replace("""\"""", "\"")
            .replace("""\n""", "\n")
            .replace("""\t""", " ")

        val mpdRegex = Regex("""<MPD\b[\s\S]*?</MPD>""", RegexOption.IGNORE_CASE)
        val mpdBlocks = mpdRegex.findAll(xmlReady).map { it.value }.toList()
            .ifEmpty { listOf(xmlReady) }

        val repRegex = Regex(
            """<Representation\b([^>]*)>[\s\S]*?<BaseURL>\s*(https?:[^<\s"']+)\s*</BaseURL>""",
            RegexOption.IGNORE_CASE
        )

        var bestDashAudioUrl: String? = null
        val audioByVideoId = LinkedHashMap<String, String>()

        // Pre-scan Relay JSON `"base_url"` audio representations so `bestDashAudioUrl` and `audioByVideoId` are populated
        val jsonBaseUrlRegex = Regex(""""base_url"\s*:\s*"(https?:[^"]+)"""")
        val jsonBaseMatches = jsonBaseUrlRegex.findAll(region).toList()
        for (jm in jsonBaseMatches) {
            val cleanedUrl = sanitizeCandidateUrl(jm.groupValues[1])
            val efg = inspectFacebookStreamUrl(cleanedUrl)
            val start = (jm.range.first - 240).coerceAtLeast(0)
            val end = (jm.range.last + 240).coerceAtMost(region.length)
            val ctx = region.substring(start, end)
            val isAudio = efg.isAudioOnly ||
                ctx.contains("audio/mp4", ignoreCase = true) ||
                ctx.contains("mp4a", ignoreCase = true)
            if (isAudio) {
                if (bestDashAudioUrl == null) {
                    bestDashAudioUrl = cleanedUrl
                }
                if (!efg.videoId.isNullOrBlank() && !audioByVideoId.containsKey(efg.videoId)) {
                    audioByVideoId[efg.videoId] = cleanedUrl
                }
                register(cleanedUrl, "Âm thanh gốc (M4A)", 110, isTargetSlice, null)
            }
        }

        for (mpdBlock in mpdBlocks) {
            val repMatches = repRegex.findAll(mpdBlock).toList()
            if (repMatches.isEmpty()) continue

            // First pass per MPD: find the highest-bitrate DASH Audio track (`audio/mp4` / `mp4a`) for THIS MPD block
            var mpdAudioUrl: String? = null
            var mpdAudioBandwidth = -1L

            for (rm in repMatches) {
                val attrs = rm.groupValues[1]
                val rawBaseUrl = rm.groupValues[2]
                val cleanedUrl = sanitizeCandidateUrl(rawBaseUrl)
                val efg = inspectFacebookStreamUrl(cleanedUrl)
                val isAudio = efg.isAudioOnly ||
                    attrs.contains("audio", ignoreCase = true) ||
                    attrs.contains("mp4a", ignoreCase = true)
                if (isAudio) {
                    val bw = Regex("""bandwidth\s*=\s*"(\d+)"""").find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 64000L
                    if (bw > mpdAudioBandwidth) {
                        mpdAudioBandwidth = bw
                        mpdAudioUrl = cleanedUrl
                    }
                    if (!efg.videoId.isNullOrBlank()) {
                        audioByVideoId[efg.videoId] = cleanedUrl
                    }
                    if (bestDashAudioUrl == null) {
                        bestDashAudioUrl = cleanedUrl
                    }
                    register(cleanedUrl, "Âm thanh gốc (M4A)", 110, isTargetSlice, null)
                }
            }

            // Second pass per MPD: register DASH Video `<Representation>` tracks paired with their exact `videoId` audio or `mpdAudioUrl`
            for (rm in repMatches) {
                val attrs = rm.groupValues[1]
                val rawBaseUrl = rm.groupValues[2]
                val cleanedUrl = sanitizeCandidateUrl(rawBaseUrl)
                val efg = inspectFacebookStreamUrl(cleanedUrl)
                val isAudio = efg.isAudioOnly ||
                    attrs.contains("audio", ignoreCase = true) ||
                    attrs.contains("mp4a", ignoreCase = true)
                if (isAudio) continue

                val fbQualityLabel = Regex("""FBQualityLabel\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                val labelRes = Regex("""(\d{3,4})""").find(fbQualityLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val width = Regex("""\bwidth\s*=\s*"(\d+)"""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val height = Regex("""\bheight\s*=\s*"(\d+)"""").find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val shortSide = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)

                val isAvcOrHevc = attrs.contains("avc1", ignoreCase = true) ||
                    attrs.contains("hvc1", ignoreCase = true) ||
                    attrs.contains("hev1", ignoreCase = true)
                val codecBonus = if (isAvcOrHevc) 30 else 0

                val res = labelRes ?: efg.resolutionScore.takeIf { it > 0 } ?: shortSide
                val (dashLabel, dashScore) = when {
                    res >= 1080 -> "Full HD 1080p (MP4)" to (1150 + codecBonus)
                    res >= 720 -> "HD 720p (MP4)" to (950 + codecBonus)
                    res >= 540 -> "qHD 540p (MP4)" to (540 + codecBonus)
                    res >= 480 -> "SD 480p (MP4)" to (480 + codecBonus)
                    res > 0 -> "SD ${res}p (MP4)" to (res + codecBonus)
                    attrs.contains("FBQualityClass=\"hd\"", ignoreCase = true) -> "Full HD 1080p/720p (MP4)" to (980 + codecBonus)
                    else -> "SD Video (MP4)" to 360
                }
                val exactAudio = efg.videoId?.let { audioByVideoId[it] } ?: mpdAudioUrl ?: bestDashAudioUrl
                register(cleanedUrl, dashLabel, dashScore, isTargetSlice, exactAudio)
            }
        }

        // Second pass for Relay JSON `"base_url"` video representations
        for (jm in jsonBaseMatches) {
            val cleanedUrl = sanitizeCandidateUrl(jm.groupValues[1])
            val efg = inspectFacebookStreamUrl(cleanedUrl)
            val start = (jm.range.first - 260).coerceAtLeast(0)
            val end = (jm.range.last + 260).coerceAtMost(region.length)
            val ctx = region.substring(start, end)
            val isAudio = efg.isAudioOnly ||
                ctx.contains("audio/mp4", ignoreCase = true) ||
                ctx.contains("mp4a", ignoreCase = true)
            if (isAudio) continue

            val width = Regex(""""width"\s*:\s*(\d+)""").find(ctx)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val height = Regex(""""height"\s*:\s*(\d+)""").find(ctx)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val shortSide = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)
            val isAvc1 = ctx.contains("avc1", ignoreCase = true)
            val codecBonus = if (isAvc1) 30 else 0
            val res = efg.resolutionScore.takeIf { it > 0 } ?: shortSide
            if (res >= 720) {
                val label = if (res >= 1080) "Full HD 1080p (MP4)" else "HD 720p (MP4)"
                val score = (if (res >= 1080) 1150 else 950) + codecBonus
                val exactAudio = efg.videoId?.let { audioByVideoId[it] } ?: bestDashAudioUrl
                register(cleanedUrl, label, score, isTargetSlice, exactAudio)
            }
        }
    }

    private fun extractFacebookDirect(
        originalInput: String,
        cleanUrl: String,
        hintVideoId: String? = null
    ): ExtractedMediaInfo? {
        val cookie = authCookieStore.getEffectiveCookie(SocialPlatform.FACEBOOK, cleanUrl)
        val desktopCookie = buildDesktopFacebookCookie(cookie)
        val fbHeaders = buildMediaHeaders(SocialPlatform.FACEBOOK, cookie)

        // Resolve shortlinks (fb.watch, /share/, /share/v/, /share/r/) with full navigation headers
        val initialResolved = if (
            cleanUrl.contains("fb.watch", ignoreCase = true) ||
            cleanUrl.contains("/share/", ignoreCase = true)
        ) {
            resolveRedirectUrl(cleanUrl, DESKTOP_CHROME_UA)
        } else {
            cleanUrl
        }

        var finalResolvedUrl = toDesktopFacebookUrl(initialResolved)
        var targetVideoId: String? = hintVideoId ?: extractFacebookVideoIdFromText(finalResolvedUrl)

        val videoRedirectRegex = Regex("""/video_redirect/\?src=([^"&'\s<>]+)""")
        val uzpfTokenRegex = Regex("""UzpfSVNDOj[A-Za-z0-9+/=_-]+""")

        val candidates = mutableListOf<ScoredStreamCandidate>()
        val seenUrls = mutableSetOf<String>()
        val storyGroups = mutableListOf<StoryBucketGroup>()
        val storyGroupByKey = LinkedHashMap<String, StoryBucketGroup>()

        var ogVideoUrl = ""
        var ogImageUrl = ""
        var ogTitle = ""
        var ogDescription = ""
        var detectedAuthor = ""

        fun buildScoredCandidate(
            rawCandidate: String?,
            defaultLabel: String,
            defaultScore: Int,
            isTargetSlice: Boolean = false,
            pairedAudioUrl: String? = null
        ): ScoredStreamCandidate? {
            val cleaned = sanitizeCandidateUrl(rawCandidate ?: return null)
            if (!cleaned.startsWith("http", ignoreCase = true)) return null

            val efgMeta = inspectFacebookStreamUrl(cleaned)
            if (efgMeta.isAudioOnly) {
                return ScoredStreamCandidate(
                    url = cleaned,
                    label = "Âm thanh gốc (M4A)",
                    score = 100,
                    mediaType = MediaType.AUDIO,
                    extension = "m4a",
                    audioUrl = null,
                    videoId = efgMeta.videoId
                )
            }

            val bonus = if (isTargetSlice || (targetVideoId != null && efgMeta.videoId == targetVideoId)) 250 else 0
            val bestScore = max(defaultScore, efgMeta.resolutionScore)
            val effectiveScore = bestScore + bonus
            val effectiveLabel = when {
                bestScore >= 1080 -> if (defaultScore >= efgMeta.resolutionScore && defaultScore >= 1080) defaultLabel else (efgMeta.qualityLabel ?: "Full HD 1080p (MP4)")
                bestScore >= 720 -> if (defaultScore >= efgMeta.resolutionScore && defaultScore >= 720) defaultLabel else (efgMeta.qualityLabel ?: "HD 720p (MP4)")
                defaultScore > efgMeta.resolutionScore -> defaultLabel
                else -> efgMeta.qualityLabel ?: defaultLabel
            }

            return ScoredStreamCandidate(
                url = cleaned,
                label = effectiveLabel,
                score = effectiveScore,
                mediaType = MediaType.VIDEO,
                extension = "mp4",
                audioUrl = pairedAudioUrl,
                videoId = efgMeta.videoId
            )
        }

        fun mergeCandidateIntoList(
            list: MutableList<ScoredStreamCandidate>,
            incoming: ScoredStreamCandidate
        ) {
            val existingIdx = list.indexOfFirst { it.url == incoming.url }
            if (existingIdx >= 0) {
                val existing = list[existingIdx]
                val newAudio = existing.audioUrl ?: incoming.audioUrl
                val newVid = existing.videoId ?: incoming.videoId
                val newScore = max(existing.score, incoming.score)
                val newLabel = if (incoming.score > existing.score) incoming.label else existing.label
                list[existingIdx] = existing.copy(
                    label = newLabel,
                    score = newScore,
                    audioUrl = newAudio,
                    videoId = newVid
                )
            } else {
                list.add(incoming)
            }
        }

        fun registerCandidateUrl(
            rawCandidate: String?,
            defaultLabel: String,
            defaultScore: Int,
            isTargetSlice: Boolean = false,
            pairedAudioUrl: String? = null
        ) {
            val built = buildScoredCandidate(
                rawCandidate = rawCandidate,
                defaultLabel = defaultLabel,
                defaultScore = defaultScore,
                isTargetSlice = isTargetSlice,
                pairedAudioUrl = pairedAudioUrl
            ) ?: return

            seenUrls.add(built.url)
            if (targetVideoId == null && !built.videoId.isNullOrBlank() && isTargetSlice) {
                targetVideoId = built.videoId
            }
            mergeCandidateIntoList(candidates, built)

            // If this stream has a videoId matching one of the discovered Story Cards, enrich that Story Card too!
            if (!built.videoId.isNullOrBlank()) {
                val matchingGroup = storyGroups.firstOrNull { it.videoId == built.videoId }
                if (matchingGroup != null) {
                    mergeCandidateIntoList(matchingGroup.candidates, built.copy(score = built.score.coerceAtMost(1200)))
                }
            }
        }

        fun parseFacebookHtml(html: String) {
            val unescaped = unescapeHtmlAndJson(html)
            val storyTokens = extractFacebookStoryTokens(finalResolvedUrl)
            val targetStoryKeys = storyTokens.map { normalizeFacebookStoryCardKey(it) }
                .filter { it.isNotBlank() }
                .toSet()

            // 1. Segment Story Bucket by `UzpfSVNDOj...` Story Card boundaries!
            // Each Story Card slice starts at `match.range.first - 200` (NEVER -25000, which bled into Story #1!)
            // and stops at the first subsequent `UzpfSVNDOj...` match that belongs to a DIFFERENT Story Card!
            val uzpfMatches = uzpfTokenRegex.findAll(unescaped).toList()
            if (uzpfMatches.isNotEmpty()) {
                val matchKeys = uzpfMatches.map { normalizeFacebookStoryCardKey(it.value) }
                for (i in uzpfMatches.indices) {
                    val m = uzpfMatches[i]
                    val cardKey = matchKeys[i]
                    if (cardKey.isBlank()) continue

                    var nextDifferentStart: Int? = null
                    for (j in (i + 1) until uzpfMatches.size) {
                        if (matchKeys[j] != cardKey) {
                            nextDifferentStart = uzpfMatches[j].range.first
                            break
                        }
                    }

                    val sliceStart = (m.range.first - 200).coerceAtLeast(0)
                    val maxEnd = (m.range.last + 1 + 45_000).coerceAtMost(unescaped.length)
                    val sliceEnd = nextDifferentStart?.let { minOf(maxEnd, it) } ?: maxEnd
                    if (sliceEnd <= sliceStart) continue

                    val cardSlice = unescaped.substring(sliceStart, sliceEnd)
                    val sliceStreams = mutableListOf<ScoredStreamCandidate>()
                    val isTargetCard = cardKey in targetStoryKeys

                    extractFacebookStreamsFromSlice(cardSlice, isTargetCard) { u, lbl, sc, _, audUrl ->
                        val built = buildScoredCandidate(
                            rawCandidate = u,
                            defaultLabel = lbl,
                            defaultScore = sc,
                            isTargetSlice = false,
                            pairedAudioUrl = audUrl
                        )
                        if (built != null) {
                            mergeCandidateIntoList(sliceStreams, built)
                        }
                    }

                    if (sliceStreams.none { it.mediaType == MediaType.VIDEO }) {
                        val photoUri = extractFacebookPhotoStoryUrlFromSlice(cardSlice)
                        if (!photoUri.isNullOrBlank()) {
                            mergeCandidateIntoList(
                                sliceStreams,
                                ScoredStreamCandidate(
                                    url = photoUri,
                                    label = "Ảnh Story HD (JPG)",
                                    score = 900,
                                    mediaType = MediaType.IMAGE,
                                    extension = "jpg"
                                )
                            )
                        }
                    }

                    if (sliceStreams.any { it.mediaType == MediaType.VIDEO || it.mediaType == MediaType.IMAGE }) {
                        val group = storyGroupByKey.getOrPut(cardKey) {
                            StoryBucketGroup(key = cardKey, rawToken = m.value).also { storyGroups.add(it) }
                        }
                        if (group.videoId == null) {
                            group.videoId = sliceStreams.firstNotNullOfOrNull { it.videoId }
                                ?: extractFacebookVideoIdFromText(cardSlice)
                        }
                        for (sc in sliceStreams) {
                            mergeCandidateIntoList(group.candidates, sc)
                            registerCandidateUrl(sc.url, sc.label, sc.score, isTargetCard, sc.audioUrl)
                        }
                    }
                }
            }

            // Update `targetVideoId` from the exact matched Story Card if present
            val matchedGroup = storyGroups.firstOrNull {
                it.key in targetStoryKeys || (!hintVideoId.isNullOrBlank() && it.videoId == hintVideoId)
            }
            if (matchedGroup != null && !matchedGroup.videoId.isNullOrBlank()) {
                targetVideoId = matchedGroup.videoId
            }

            // 2. Fallback tight slice scan when `storyGroups` is empty
            val targetSlices = mutableListOf<String>()
            if (storyGroups.isEmpty()) {
                for (token in storyTokens) {
                    var idx = unescaped.indexOf(token)
                    var count = 0
                    while (idx >= 0 && count < 3) {
                        val start = (idx - 200).coerceAtLeast(0)
                        val end = (idx + 18_000).coerceAtMost(unescaped.length)
                        val slice = unescaped.substring(start, end)
                        targetSlices.add(slice)
                        if (targetVideoId == null) {
                            targetVideoId = extractFacebookVideoIdFromText(slice)
                        }
                        idx = unescaped.indexOf(token, idx + token.length)
                        count++
                    }
                }
            }

            if (targetVideoId == null && storyGroups.isEmpty()) {
                targetVideoId = extractFacebookVideoIdFromText(unescaped)
            }

            val vid = targetVideoId
            if (!vid.isNullOrBlank()) {
                var searchIdx = unescaped.indexOf(vid)
                var count = 0
                while (searchIdx >= 0 && count < 4) {
                    val start = (searchIdx - 4_000).coerceAtLeast(0)
                    val end = (searchIdx + 35_000).coerceAtMost(unescaped.length)
                    targetSlices.add(unescaped.substring(start, end))
                    searchIdx = unescaped.indexOf(vid, searchIdx + vid.length)
                    count++
                }
            }

            for (slice in targetSlices) {
                extractFacebookStreamsFromSlice(slice, true) { u, lbl, sc, isT, audUrl ->
                    registerCandidateUrl(u, lbl, sc, isT, audUrl)
                }
            }

            if (storyGroups.isEmpty() && candidates.none { it.mediaType == MediaType.VIDEO && it.score >= 720 }) {
                extractFacebookStreamsFromSlice(unescaped, false) { u, lbl, sc, isT, audUrl ->
                    registerCandidateUrl(u, lbl, sc, isT, audUrl)
                }
            }

            (videoRedirectRegex.findAll(html) + videoRedirectRegex.findAll(unescaped)).forEach { m ->
                val rawSrc = m.groupValues.getOrNull(1).orEmpty()
                if (rawSrc.isNotBlank()) {
                    val decoded = runCatching {
                        URLDecoder.decode(rawSrc.replace("&amp;", "&"), "UTF-8")
                    }.getOrDefault(unescapeHtmlAndJson(rawSrc))
                    registerCandidateUrl(decoded, "SD 360p (MP4)", 360, false, null)
                }
            }

            if (ogVideoUrl.isBlank()) {
                ogVideoUrl = extractMetaContent(html, "og:video:secure_url")
                    .ifBlank { extractMetaContent(html, "og:video") }
                    .ifBlank { extractMetaContent(html, "og:video:url") }
            }

            if (ogImageUrl.isBlank()) {
                ogImageUrl = extractMetaContent(html, "og:image")
                    .ifBlank {
                        Regex(""""preferred_thumbnail"\s*:\s*\{.{0,180}?"uri"\s*:\s*"(https?:[^"]+)"""")
                            .find(unescaped)?.groupValues?.getOrNull(1).orEmpty()
                    }
                    .ifBlank {
                        Regex(""""(?:previewImage|thumbnailImage|preview_image)"\s*:\s*\{.{0,120}?"uri"\s*:\s*"(https?:[^"]+)"""")
                            .find(unescaped)?.groupValues?.getOrNull(1).orEmpty()
                    }
            }

            if (detectedAuthor.isBlank()) {
                detectedAuthor = Regex(""""(?:story_bucket_owner|owner)"\s*:\s*\{[^{}]*"name"\s*:\s*"([^"]+)"""")
                    .find(unescaped)?.groupValues?.getOrNull(1)?.let { decodeJsonUnicodeString(it) }.orEmpty()
            }

            if (ogTitle.isBlank() || ogTitle.equals("Facebook", ignoreCase = true)) {
                val metaTitle = extractMetaContent(html, "og:title")
                    .ifBlank { extractHtmlTagTitle(html) }
                if (metaTitle.isNotBlank() && !metaTitle.equals("Facebook", ignoreCase = true)) {
                    ogTitle = metaTitle
                } else {
                    val relayText = Regex(""""(?:savable_description|message)"\s*:\s*\{[^{}]*"text"\s*:\s*"([^"]+)"""")
                        .find(unescaped)?.groupValues?.getOrNull(1)?.let { decodeJsonUnicodeString(it) }.orEmpty()
                    if (relayText.isNotBlank()) {
                        ogTitle = relayText
                    }
                }
            }

            if (ogDescription.isBlank()) {
                ogDescription = extractMetaContent(html, "og:description")
            }
        }

        fun fetchFacebookDesktopPage(urlToFetch: String): String? {
            val reqBuilder = Request.Builder()
                .url(urlToFetch)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
                .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .get()
            if (desktopCookie.isNotBlank()) {
                reqBuilder.header("Cookie", desktopCookie)
            }
            return runCatching {
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val resolved = unwrapFacebookLoginRedirect(resp.request.url.toString())
                    if (resolved.contains("facebook.com") && !resolved.contains("/login")) {
                        finalResolvedUrl = toDesktopFacebookUrl(resolved)
                    }
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
        }

        // 1. Primary Desktop Comet requests (www.facebook.com & web.facebook.com)
        val initialDesktopTargets = buildList {
            add(finalResolvedUrl)
            if (finalResolvedUrl.contains("://www.facebook.com")) {
                add(finalResolvedUrl.replace("://www.facebook.com", "://web.facebook.com"))
            }
            val vid = targetVideoId
            if (!vid.isNullOrBlank()) {
                add("https://www.facebook.com/watch/?v=$vid")
                add("https://www.facebook.com/reel/$vid")
            }
        }.distinct()

        for (desktopTarget in initialDesktopTargets) {
            val html = fetchFacebookDesktopPage(desktopTarget)
            if (!html.isNullOrBlank()) {
                parseFacebookHtml(html)
            }
            if (candidates.any { it.mediaType == MediaType.VIDEO && it.score >= 720 }) {
                break
            }
        }

        // 2. If still no HD video (e.g., private group permalink or story), probe mbasic/m.facebook.com
        val hasHdVideo = candidates.any { it.mediaType == MediaType.VIDEO && it.score >= 720 }
        val videoIdBeforeMobile = targetVideoId
        if (!hasHdVideo) {
            val baseTarget = finalResolvedUrl.ifBlank { cleanUrl }
            val mbasicUrl = baseTarget
                .replace("://www.facebook.com", "://mbasic.facebook.com")
                .replace("://m.facebook.com", "://mbasic.facebook.com")
                .replace("://web.facebook.com", "://mbasic.facebook.com")
            val mobileUrl = baseTarget
                .replace("://www.facebook.com", "://m.facebook.com")
                .replace("://mbasic.facebook.com", "://m.facebook.com")
                .replace("://web.facebook.com", "://m.facebook.com")

            val fallbackTargets = listOf(
                mbasicUrl to DESKTOP_CHROME_UA,
                mobileUrl to MOBILE_SAFARI_UA
            ).distinctBy { it.first }

            for ((fallbackUrl, ua) in fallbackTargets) {
                val reqBuilder = Request.Builder()
                    .url(fallbackUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
                    .get()
                if (cookie.isNotBlank()) {
                    reqBuilder.header("Cookie", cookie)
                }

                val fallbackHtml = runCatching {
                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val resolved = unwrapFacebookLoginRedirect(resp.request.url.toString())
                        if (resolved.contains("facebook.com") && !resolved.contains("/login") && finalResolvedUrl.contains("/share/")) {
                            finalResolvedUrl = toDesktopFacebookUrl(resolved)
                        }
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                }.getOrNull() ?: continue

                parseFacebookHtml(fallbackHtml)
            }

            // 3. Second-stage HD Upgrade: if mbasic/mobile revealed a new `targetVideoId`, query Desktop www.facebook.com
            val newlyDiscoveredVid = targetVideoId
            val hdUpgradeUrls = buildList {
                if (!finalResolvedUrl.contains("/share/")) {
                    add(finalResolvedUrl)
                    add(finalResolvedUrl.replace("://www.facebook.com", "://web.facebook.com"))
                }
                if (!newlyDiscoveredVid.isNullOrBlank() && newlyDiscoveredVid != videoIdBeforeMobile) {
                    add("https://www.facebook.com/watch/?v=$newlyDiscoveredVid")
                    add("https://www.facebook.com/reel/$newlyDiscoveredVid")
                    add("https://web.facebook.com/watch/?v=$newlyDiscoveredVid")
                }
            }.distinct()

            if (candidates.none { it.score >= 720 }) {
                for (upgradeUrl in hdUpgradeUrls) {
                    val upgradeHtml = fetchFacebookDesktopPage(upgradeUrl)
                    if (!upgradeHtml.isNullOrBlank()) {
                        parseFacebookHtml(upgradeHtml)
                    }
                    if (candidates.any { it.mediaType == MediaType.VIDEO && it.score >= 720 }) {
                        break
                    }
                }
            }
        }

        if (candidates.none { it.mediaType == MediaType.VIDEO } && ogVideoUrl.startsWith("http", ignoreCase = true)) {
            registerCandidateUrl(ogVideoUrl, "HD Video (MP4)", 720, false, null)
        }

        val options = mutableListOf<MediaOption>()
        val finalTargetStoryKeys = extractFacebookStoryTokens(finalResolvedUrl)
            .map { normalizeFacebookStoryCardKey(it) }
            .filter { it.isNotBlank() }
            .toSet()

        if (storyGroups.isNotEmpty()) {
            val targetIdx = storyGroups.indexOfFirst { it.key in finalTargetStoryKeys }.takeIf { it >= 0 }
                ?: storyGroups.indexOfFirst { !targetVideoId.isNullOrBlank() && it.videoId == targetVideoId }.takeIf { it >= 0 }
                ?: 0
            val totalStories = storyGroups.size
            val primaryGroup = storyGroups[targetIdx]
            val primaryGroupAudio = primaryGroup.candidates.firstOrNull { it.mediaType == MediaType.AUDIO }?.url

            val primarySorted = primaryGroup.candidates
                .filter { it.mediaType != MediaType.AUDIO }
                .sortedWith(
                    compareByDescending<ScoredStreamCandidate> { it.mediaType == MediaType.VIDEO }
                        .thenByDescending { it.score }
                )

            var markedHighest = false
            for (cand in primarySorted) {
                val isHighest = if (!markedHighest) {
                    markedHighest = true
                    true
                } else {
                    false
                }
                val resolvedAudio = cand.audioUrl ?: if (
                    cand.mediaType == MediaType.VIDEO && isFacebookDashVideoOnlyUrl(cand.url)
                ) {
                    primaryGroupAudio
                } else {
                    null
                }
                val prefix = if (totalStories > 1) {
                    "Story #${targetIdx + 1} (Link này) • "
                } else {
                    "Story #1 • "
                }
                options.add(
                    MediaOption(
                        url = cand.url,
                        qualityLabel = "$prefix${cand.label}",
                        extension = cand.extension,
                        mediaType = cand.mediaType,
                        isHighestQuality = isHighest,
                        isNoWatermark = true,
                        customHeaders = fbHeaders,
                        audioUrl = resolvedAudio
                    )
                )
            }

            // Include the best Full HD option of EVERY OTHER Story in this person's Story Bucket!
            if (totalStories > 1) {
                for (idx in storyGroups.indices) {
                    if (idx == targetIdx) continue
                    val group = storyGroups[idx]
                    val groupAudio = group.candidates.firstOrNull { it.mediaType == MediaType.AUDIO }?.url
                    val bestCand = group.candidates
                        .filter { it.mediaType != MediaType.AUDIO }
                        .maxWithOrNull(
                            compareBy<ScoredStreamCandidate> { it.mediaType == MediaType.VIDEO }
                                .thenBy { it.score }
                        ) ?: continue

                    val resolvedAudio = bestCand.audioUrl ?: if (
                        bestCand.mediaType == MediaType.VIDEO && isFacebookDashVideoOnlyUrl(bestCand.url)
                    ) {
                        groupAudio
                    } else {
                        null
                    }
                    options.add(
                        MediaOption(
                            url = bestCand.url,
                            qualityLabel = "Story #${idx + 1} • ${bestCand.label}",
                            extension = bestCand.extension,
                            mediaType = bestCand.mediaType,
                            isHighestQuality = false,
                            isNoWatermark = true,
                            customHeaders = fbHeaders,
                            audioUrl = resolvedAudio
                        )
                    )
                }
            }

            if (!primaryGroupAudio.isNullOrBlank()) {
                options.add(
                    MediaOption(
                        url = primaryGroupAudio,
                        qualityLabel = "Âm thanh gốc Story #${targetIdx + 1} (M4A)",
                        extension = "m4a",
                        mediaType = MediaType.AUDIO,
                        isHighestQuality = false,
                        isNoWatermark = true,
                        customHeaders = fbHeaders
                    )
                )
            }
        } else {
            val audioByVid = LinkedHashMap<String, String>()
            candidates.filter { it.mediaType == MediaType.AUDIO }.forEach { aud ->
                if (!aud.videoId.isNullOrBlank() && !audioByVid.containsKey(aud.videoId)) {
                    audioByVid[aud.videoId] = aud.url
                }
            }
            val fallbackDashAudioUrl = targetVideoId?.let { audioByVid[it] }
                ?: candidates.firstOrNull { it.mediaType == MediaType.AUDIO }?.url

            // Separate primary video candidates vs secondary videos on the same page
            val primaryCandidates = candidates.filter {
                it.mediaType == MediaType.AUDIO ||
                    targetVideoId == null ||
                    it.videoId == null ||
                    it.videoId == targetVideoId
            }.ifEmpty { candidates }

            val secondaryByVid = candidates
                .filter { it.mediaType == MediaType.VIDEO && !it.videoId.isNullOrBlank() && targetVideoId != null && it.videoId != targetVideoId }
                .groupBy { it.videoId!! }

            val sortedPrimary = primaryCandidates.sortedWith(
                compareByDescending<ScoredStreamCandidate> { it.mediaType == MediaType.VIDEO }
                    .thenByDescending { it.score }
            )

            var markedHighestVideo = false
            for (cand in sortedPrimary) {
                val isHighest = if (cand.mediaType == MediaType.VIDEO && !markedHighestVideo) {
                    markedHighestVideo = true
                    true
                } else {
                    false
                }
                val resolvedAudioUrl = cand.audioUrl
                    ?: cand.videoId?.let { audioByVid[it] }
                    ?: if (cand.mediaType == MediaType.VIDEO && isFacebookDashVideoOnlyUrl(cand.url)) {
                        fallbackDashAudioUrl
                    } else {
                        null
                    }
                val prefix = if (secondaryByVid.isNotEmpty() && cand.mediaType == MediaType.VIDEO) "Video #1 • " else ""
                options.add(
                    MediaOption(
                        url = cand.url,
                        qualityLabel = "$prefix${cand.label}",
                        extension = cand.extension,
                        mediaType = cand.mediaType,
                        isHighestQuality = isHighest,
                        isNoWatermark = true,
                        customHeaders = fbHeaders,
                        audioUrl = resolvedAudioUrl
                    )
                )
            }

            var extraIdx = 2
            for ((vidKey, vidList) in secondaryByVid) {
                val bestExtra = vidList.maxByOrNull { it.score } ?: continue
                val resolvedAudioUrl = bestExtra.audioUrl
                    ?: audioByVid[vidKey]
                    ?: if (isFacebookDashVideoOnlyUrl(bestExtra.url)) fallbackDashAudioUrl else null
                options.add(
                    MediaOption(
                        url = bestExtra.url,
                        qualityLabel = "Video #$extraIdx • ${bestExtra.label}",
                        extension = bestExtra.extension,
                        mediaType = MediaType.VIDEO,
                        isHighestQuality = false,
                        isNoWatermark = true,
                        customHeaders = fbHeaders,
                        audioUrl = resolvedAudioUrl
                    )
                )
                extraIdx++
            }
        }

        if (ogImageUrl.startsWith("http", ignoreCase = true) && options.none { it.url == ogImageUrl }) {
            options.add(
                MediaOption(
                    url = ogImageUrl,
                    qualityLabel = "Ảnh bìa (Cover)",
                    extension = "jpg",
                    mediaType = MediaType.IMAGE,
                    isHighestQuality = options.isEmpty(),
                    isNoWatermark = true,
                    customHeaders = fbHeaders
                )
            )
        }

        if (options.isEmpty()) return null

        val resolvedAuthor = detectedAuthor.ifBlank { "Facebook" }
        val isStory = finalResolvedUrl.contains("/stories/", ignoreCase = true) || storyGroups.isNotEmpty()
        val hasExplicitTarget = finalTargetStoryKeys.isNotEmpty() || !hintVideoId.isNullOrBlank()
        val targetStoryIdx = if (storyGroups.isNotEmpty()) {
            (storyGroups.indexOfFirst { it.key in finalTargetStoryKeys }.takeIf { it >= 0 }
                ?: storyGroups.indexOfFirst { !targetVideoId.isNullOrBlank() && it.videoId == targetVideoId }.takeIf { it >= 0 }
                ?: 0)
        } else {
            0
        }
        val activeShortId = (
            storyGroups.getOrNull(targetStoryIdx)?.videoId
                ?: targetVideoId
                ?: storyGroups.getOrNull(targetStoryIdx)?.key?.removePrefix("ISC_")
                ?: options.first().url.substringBefore('?').hashCode().toUInt().toString()
            ).takeLast(4)

        val safeTitle = when {
            storyGroups.size > 1 && hasExplicitTarget ->
                "Story #${targetStoryIdx + 1}/${storyGroups.size} của $resolvedAuthor (#$activeShortId)"
            storyGroups.size > 1 ->
                "Trọn bộ ${storyGroups.size} Story của $resolvedAuthor (#$activeShortId)"
            isStory && detectedAuthor.isNotBlank() ->
                "Story của $detectedAuthor (#$activeShortId)"
            isStory ->
                "Facebook Story (#$activeShortId)"
            else -> {
                val baseTitle = ogTitle.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Facebook", ignoreCase = true) &&
                        !it.contains("Log into Facebook", ignoreCase = true)
                } ?: ogDescription.takeIf { it.isNotBlank() }
                    ?: if (detectedAuthor.isNotBlank()) "Video của $detectedAuthor" else "Video Facebook"
                if (baseTitle.contains(activeShortId)) baseTitle else "$baseTitle (#$activeShortId)"
            }
        }

        return ExtractedMediaInfo(
            originalUrl = originalInput,
            cleanUrl = finalResolvedUrl,
            platform = SocialPlatform.FACEBOOK,
            title = safeTitle,
            author = resolvedAuthor,
            thumbnailUrl = ogImageUrl,
            durationSeconds = 0L,
            options = options
        )
    }

    private fun instagramShortcodeToMediaId(shortcode: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = BigInteger.ZERO
        val base = BigInteger.valueOf(64L)
        for (ch in shortcode) {
            val idx = alphabet.indexOf(ch)
            if (idx < 0) return null
            id = id.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        return id.toString().takeIf { it.length >= 10 }
    }

    /**
     * Parses all video versions inside Instagram/Threads `"video_versions":[...]` JSON arrays,
     * sorting highest resolution (1080p Full HD) first.
     */
    private fun extractInstagramVideoVersionsFromText(unescapedText: String): List<ScoredStreamCandidate> {
        val results = mutableListOf<ScoredStreamCandidate>()
        val seen = mutableSetOf<String>()
        val arrayRegex = Regex(""""video_versions"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val objRegex = Regex("""\{[^{}]+\}""")

        for (arrayMatch in arrayRegex.findAll(unescapedText)) {
            val arrayBody = arrayMatch.groupValues.getOrNull(1).orEmpty()
            for (objMatch in objRegex.findAll(arrayBody)) {
                val objText = objMatch.value
                val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(objText)?.groupValues?.getOrNull(1)
                    ?.let { sanitizeCandidateUrl(it) }
                    ?: continue
                if (!url.startsWith("http", ignoreCase = true) || !seen.add(url)) continue

                val width = Regex(""""width"\s*:\s*(\d+)""").find(objText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val height = Regex(""""height"\s*:\s*(\d+)""").find(objText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val maxDim = max(width, height)
                val minDim = if (width > 0 && height > 0) minOf(width, height) else 0
                val score = when {
                    minDim >= 1080 || maxDim >= 1920 -> 1080
                    minDim >= 720 || maxDim >= 1280 -> 720
                    minDim >= 540 -> 540
                    minDim >= 480 -> 480
                    else -> 800
                }
                val dimBadge = if (width > 0 && height > 0) " (${width}x${height})" else ""
                val label = when {
                    score >= 1080 -> "Full HD 1080p$dimBadge (MP4)"
                    score >= 720 -> "HD 720p$dimBadge (MP4)"
                    else -> "SD Video$dimBadge (MP4)"
                }
                results.add(ScoredStreamCandidate(url, label, score, MediaType.VIDEO, "mp4"))
            }
        }
        return results.sortedByDescending { it.score }
    }

    private fun extractInstagramDirect(originalInput: String, cleanUrl: String): ExtractedMediaInfo? {
        val cookie = authCookieStore.getEffectiveCookie(SocialPlatform.INSTAGRAM, cleanUrl)
        val igHeaders = buildMediaHeaders(SocialPlatform.INSTAGRAM, cookie)

        val shortcodeMatch = Regex("""/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(cleanUrl)
        val shortcode = shortcodeMatch?.groupValues?.getOrNull(1)
        val storyPkMatch = Regex("""/stories/[^/]+/(\d{10,25})""").find(cleanUrl)
        val storyPk = storyPkMatch?.groupValues?.getOrNull(1)
        val mediaId = storyPk ?: shortcode?.let { instagramShortcodeToMediaId(it) }
        val shortIgId = (mediaId ?: shortcode ?: cleanUrl.hashCode().toUInt().toString()).takeLast(4)

        val hasSessionId = cookie.contains("sessionid=")

        // 1. If logged in and mediaId is known, query Instagram Mobile API v1 for true 1080p Full HD video_versions & DASH
        if (hasSessionId && !mediaId.isNullOrBlank()) {
            val apiEndpoints = listOf(
                "https://i.instagram.com/api/v1/media/$mediaId/info/",
                "https://www.instagram.com/api/v1/media/$mediaId/info/"
            )
            for (apiEndpoint in apiEndpoints) {
                val apiReq = Request.Builder()
                    .url(apiEndpoint)
                    .header("User-Agent", INSTAGRAM_ANDROID_APP_UA)
                    .header("Accept", "application/json")
                    .header("X-IG-App-ID", IG_WEB_APP_ID)
                    .header("Cookie", cookie)
                    .get()
                    .build()

                val apiBody = runCatching {
                    httpClient.newCall(apiReq).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                }.getOrNull() ?: continue

                val unescapedApi = unescapeHtmlAndJson(apiBody)
                val versionCandidates = extractInstagramVideoVersionsFromText(unescapedApi).toMutableList()
                val seenUrls = versionCandidates.map { it.url }.toMutableSet()

                extractFacebookStreamsFromSlice(unescapedApi, true) { u, lbl, sc, _, audUrl ->
                    val cleaned = sanitizeCandidateUrl(u ?: return@extractFacebookStreamsFromSlice)
                    if (cleaned.startsWith("http", ignoreCase = true) && seenUrls.add(cleaned)) {
                        val mt = if (lbl.contains("M4A", ignoreCase = true)) MediaType.AUDIO else MediaType.VIDEO
                        versionCandidates.add(
                            ScoredStreamCandidate(
                                url = cleaned,
                                label = lbl,
                                score = sc,
                                mediaType = mt,
                                extension = if (mt == MediaType.AUDIO) "m4a" else "mp4",
                                audioUrl = audUrl
                            )
                        )
                    }
                }

                if (versionCandidates.isNotEmpty()) {
                    val thumb = Regex(""""image_versions2"\s*:\s*\{\s*"candidates"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"([^"]+)"""")
                        .find(unescapedApi)?.groupValues?.getOrNull(1)?.let { sanitizeCandidateUrl(it) }.orEmpty()
                    val username = Regex(""""username"\s*:\s*"([^"]+)"""")
                        .find(unescapedApi)?.groupValues?.getOrNull(1)?.let { "@$it" } ?: "Instagram"
                    val captionText = Regex(""""caption"\s*:\s*\{[^{}]*"text"\s*:\s*"([^"]+)"""")
                        .find(unescapedApi)?.groupValues?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                        ?: if (!storyPk.isNullOrBlank()) "Story Instagram của $username (#$shortIgId)" else "Video Instagram của $username (#$shortIgId)"

                    val sortedCands = versionCandidates.sortedWith(
                        compareByDescending<ScoredStreamCandidate> { it.mediaType == MediaType.VIDEO }
                            .thenByDescending { it.score }
                    )
                    var markedHighest = false
                    val options = sortedCands.map { cand ->
                        val isHighest = if (cand.mediaType == MediaType.VIDEO && !markedHighest) {
                            markedHighest = true
                            true
                        } else {
                            false
                        }
                        MediaOption(
                            url = cand.url,
                            qualityLabel = cand.label,
                            extension = cand.extension,
                            mediaType = cand.mediaType,
                            isHighestQuality = isHighest,
                            isNoWatermark = true,
                            customHeaders = igHeaders,
                            audioUrl = cand.audioUrl
                        )
                    }.toMutableList()

                    if (thumb.startsWith("http", ignoreCase = true)) {
                        options.add(
                            MediaOption(
                                url = thumb,
                                qualityLabel = "Ảnh bìa HD (Cover)",
                                extension = "jpg",
                                mediaType = MediaType.IMAGE,
                                isHighestQuality = false,
                                isNoWatermark = true,
                                customHeaders = igHeaders
                            )
                        )
                    }

                    return ExtractedMediaInfo(
                        originalUrl = originalInput,
                        cleanUrl = cleanUrl,
                        platform = SocialPlatform.INSTAGRAM,
                        title = captionText,
                        author = username,
                        thumbnailUrl = thumb,
                        durationSeconds = 0L,
                        options = options
                    )
                }
            }
        }

        // 2. Web / Embed / GraphQL HTML scraping
        val candidateRequests = buildList {
            if (hasSessionId) {
                if (!shortcode.isNullOrBlank()) {
                    add(Triple("https://www.instagram.com/p/$shortcode/?__a=1&__d=dis", DESKTOP_CHROME_UA, true))
                }
                add(Triple(cleanUrl, DESKTOP_CHROME_UA, true))
                add(Triple(cleanUrl, INSTAGRAM_ANDROID_APP_UA, true))
            }
            if (!shortcode.isNullOrBlank()) {
                add(Triple("https://www.instagram.com/p/$shortcode/embed/captioned/", DESKTOP_CHROME_UA, hasSessionId))
                add(Triple("https://www.instagram.com/reel/$shortcode/embed/captioned/", DESKTOP_CHROME_UA, hasSessionId))
            }
            add(Triple(cleanUrl, DESKTOP_CHROME_UA, hasSessionId))
        }.distinctBy { "${it.first}|${it.second}" }

        var fallbackImageResult: ExtractedMediaInfo? = null

        for ((targetUrl, userAgent, includeIgAppHeader) in candidateRequests) {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
            if (cookie.isNotBlank()) {
                reqBuilder.header("Cookie", cookie)
            }
            if (includeIgAppHeader) {
                reqBuilder.header("X-IG-App-ID", IG_WEB_APP_ID)
            }

            val rawHtml = runCatching {
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull() ?: continue

            val unescaped = unescapeHtmlAndJson(rawHtml)

            val scoredVideos = mutableListOf<ScoredStreamCandidate>()
            val seenVideoUrls = mutableSetOf<String>()

            extractInstagramVideoVersionsFromText(unescaped).forEach { cand ->
                if (seenVideoUrls.add(cand.url)) {
                    scoredVideos.add(cand)
                }
            }

            extractFacebookStreamsFromSlice(unescaped, true) { u, lbl, sc, _, audUrl ->
                val cleaned = sanitizeCandidateUrl(u ?: return@extractFacebookStreamsFromSlice)
                if (cleaned.startsWith("http", ignoreCase = true) && seenVideoUrls.add(cleaned)) {
                    val mt = if (lbl.contains("M4A", ignoreCase = true)) MediaType.AUDIO else MediaType.VIDEO
                    scoredVideos.add(
                        ScoredStreamCandidate(
                            url = cleaned,
                            label = lbl,
                            score = sc,
                            mediaType = mt,
                            extension = if (mt == MediaType.AUDIO) "m4a" else "mp4",
                            audioUrl = audUrl
                        )
                    )
                }
            }

            Regex(""""video_url"\s*:\s*"([^"]+)"""").findAll(unescaped).forEach { m ->
                val u = sanitizeCandidateUrl(m.groupValues.getOrNull(1).orEmpty())
                if (u.startsWith("http", ignoreCase = true) && seenVideoUrls.add(u)) {
                    val efgMeta = inspectFacebookStreamUrl(u)
                    val score = if (efgMeta.resolutionScore > 0) efgMeta.resolutionScore else 950
                    val label = efgMeta.qualityLabel ?: "Full HD 1080p (MP4)"
                    scoredVideos.add(ScoredStreamCandidate(u, label, score, MediaType.VIDEO, "mp4"))
                }
            }

            val ogVideo = extractMetaContent(rawHtml, "og:video:secure_url")
                .ifBlank { extractMetaContent(rawHtml, "og:video") }
            if (ogVideo.startsWith("http", ignoreCase = true) && seenVideoUrls.add(ogVideo)) {
                scoredVideos.add(ScoredStreamCandidate(ogVideo, "HD Video (MP4)", 720, MediaType.VIDEO, "mp4"))
            }

            val imageUrl = extractMetaContent(rawHtml, "og:image")
                .ifBlank {
                    Regex(""""display_url"\s*:\s*"([^"]+)"""")
                        .find(unescaped)?.groupValues?.getOrNull(1)?.let { sanitizeCandidateUrl(it) }.orEmpty()
                }

            val username = Regex(""""username"\s*:\s*"([^"]+)"""")
                .find(unescaped)?.groupValues?.getOrNull(1)
                ?.let { "@$it" }
                ?: "Instagram"

            val rawTitle = extractMetaContent(rawHtml, "og:title")
                .ifBlank { extractHtmlTagTitle(rawHtml) }
                .ifBlank { "Instagram Media $username".trim() }
            val title = if (rawTitle.contains(shortIgId)) rawTitle else "$rawTitle (#$shortIgId)"

            val options = mutableListOf<MediaOption>()
            val sortedVideos = scoredVideos.sortedWith(
                compareByDescending<ScoredStreamCandidate> { it.mediaType == MediaType.VIDEO }
                    .thenByDescending { it.score }
            )
            var markedHighest = false
            sortedVideos.forEach { cand ->
                val isHighest = if (cand.mediaType == MediaType.VIDEO && !markedHighest) {
                    markedHighest = true
                    true
                } else {
                    false
                }
                options.add(
                    MediaOption(
                        url = cand.url,
                        qualityLabel = cand.label,
                        extension = cand.extension,
                        mediaType = cand.mediaType,
                        isHighestQuality = isHighest,
                        isNoWatermark = true,
                        customHeaders = igHeaders,
                        audioUrl = cand.audioUrl
                    )
                )
            }

            if (imageUrl.startsWith("http", ignoreCase = true)) {
                options.add(
                    MediaOption(
                        url = imageUrl,
                        qualityLabel = if (options.isEmpty()) "Ảnh gốc HD" else "Ảnh bìa (Cover)",
                        extension = "jpg",
                        mediaType = MediaType.IMAGE,
                        isHighestQuality = options.isEmpty(),
                        isNoWatermark = true,
                        customHeaders = igHeaders
                    )
                )
            }

            if (options.any { it.mediaType == MediaType.VIDEO }) {
                return ExtractedMediaInfo(
                    originalUrl = originalInput,
                    cleanUrl = cleanUrl,
                    platform = SocialPlatform.INSTAGRAM,
                    title = title,
                    author = username,
                    thumbnailUrl = imageUrl,
                    durationSeconds = 0L,
                    options = options
                )
            } else if (options.isNotEmpty() && fallbackImageResult == null) {
                fallbackImageResult = ExtractedMediaInfo(
                    originalUrl = originalInput,
                    cleanUrl = cleanUrl,
                    platform = SocialPlatform.INSTAGRAM,
                    title = title,
                    author = username,
                    thumbnailUrl = imageUrl,
                    durationSeconds = 0L,
                    options = options
                )
            }
        }
        return fallbackImageResult
    }

    /**
     * Threads direct page HTML extractor (sorts 1080p video_versions first).
     */
    private fun extractThreadsDirect(originalInput: String, cleanUrl: String): ExtractedMediaInfo? {
        val cookie = authCookieStore.getEffectiveCookie(SocialPlatform.THREADS, cleanUrl)
        val threadsHeaders = buildMediaHeaders(SocialPlatform.THREADS, cookie)

        val requestBuilder = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)
        }

        val rawHtml = httpClient.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null

        val unescaped = unescapeHtmlAndJson(rawHtml)
        val versionCandidates = extractInstagramVideoVersionsFromText(unescaped)

        val fallbackVideoUrl = Regex(""""video_url"\s*:\s*"([^"]+)"""")
            .find(unescaped)?.groupValues?.getOrNull(1)?.let { sanitizeCandidateUrl(it) }
            ?: extractMetaContent(rawHtml, "og:video:secure_url")
                .ifBlank { extractMetaContent(rawHtml, "og:video") }
                .takeIf { it.isNotBlank() }

        val imageUrl = extractMetaContent(rawHtml, "og:image")
            .ifBlank {
                Regex(""""image_versions2"\s*:\s*\{\s*"candidates"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"([^"]+)"""")
                    .find(unescaped)?.groupValues?.getOrNull(1)?.let { sanitizeCandidateUrl(it) }.orEmpty()
            }

        val title = extractMetaContent(rawHtml, "og:description")
            .ifBlank { extractMetaContent(rawHtml, "og:title") }
            .ifBlank { "Bài đăng Threads" }

        val author = Regex("""@([A-Za-z0-9_.]+)""")
            .find(cleanUrl)?.value
            ?: "Threads"

        val options = mutableListOf<MediaOption>()
        if (versionCandidates.isNotEmpty()) {
            versionCandidates.forEachIndexed { idx, cand ->
                options.add(
                    MediaOption(
                        url = cand.url,
                        qualityLabel = cand.label,
                        extension = "mp4",
                        mediaType = MediaType.VIDEO,
                        isHighestQuality = (idx == 0),
                        isNoWatermark = true,
                        customHeaders = threadsHeaders
                    )
                )
            }
        } else if (!fallbackVideoUrl.isNullOrBlank() && fallbackVideoUrl.startsWith("http", ignoreCase = true)) {
            options.add(
                MediaOption(
                    url = fallbackVideoUrl,
                    qualityLabel = "Full HD Video (MP4)",
                    extension = "mp4",
                    mediaType = MediaType.VIDEO,
                    isHighestQuality = true,
                    isNoWatermark = true,
                    customHeaders = threadsHeaders
                )
            )
        }

        if (imageUrl.startsWith("http", ignoreCase = true)) {
            options.add(
                MediaOption(
                    url = imageUrl,
                    qualityLabel = if (options.isEmpty()) "Ảnh gốc HD" else "Ảnh bìa (Cover)",
                    extension = "jpg",
                    mediaType = MediaType.IMAGE,
                    isHighestQuality = options.isEmpty(),
                    isNoWatermark = true,
                    customHeaders = threadsHeaders
                )
            )
        }

        if (options.isEmpty()) return null

        return ExtractedMediaInfo(
            originalUrl = originalInput,
            cleanUrl = cleanUrl,
            platform = SocialPlatform.THREADS,
            title = title,
            author = author,
            thumbnailUrl = imageUrl,
            durationSeconds = 0L,
            options = options
        )
    }

    fun parseHtmlAndSniffedUrls(
        rawHtml: String,
        pageUrl: String,
        pageTitle: String? = null,
        sniffedUrls: List<String> = emptyList()
    ): ExtractedMediaInfo? {
        val cleanUrl = UrlDetector.cleanUrl(pageUrl.ifBlank { "https://www.facebook.com/" })
        val platform = UrlDetector.detectPlatform(cleanUrl)
        val cookie = authCookieStore.getEffectiveCookie(platform, pageUrl)
        val customHeaders = buildMediaHeaders(platform, cookie)

        val unescaped = unescapeHtmlAndJson(rawHtml)
        val isFacebookStoryUrl = platform == SocialPlatform.FACEBOOK && cleanUrl.contains("/stories/", ignoreCase = true)

        // IMPORTANT: For `/stories/` URLs, do NOT initialize `discoveredFbVideoId` from `extractFacebookVideoIdFromText(unescaped)`
        // because the whole-page HTML contains the entire Story Bucket starting with Story #1!
        var discoveredFbVideoId: String? = extractFacebookVideoIdFromText(cleanUrl)
            ?: if (!isFacebookStoryUrl) extractFacebookVideoIdFromText(unescaped) else null

        // If this is a Facebook Story URL, run `extractFacebookDirect` first so Story Card boundary isolation
        // and multi-story bucket options (`Story #1`, `Story #2`, ...) are preserved intact!
        if (isFacebookStoryUrl) {
            val activeSniffedVid = sniffedUrls.firstOrNull()?.let { inspectFacebookStreamUrl(it).videoId }
            val storyDirectInfo = runCatching {
                extractFacebookDirect(pageUrl, cleanUrl, activeSniffedVid)
            }.getOrNull()
            if (storyDirectInfo != null && storyDirectInfo.options.isNotEmpty()) {
                return storyDirectInfo
            }
        }

        val candidates = mutableListOf<ScoredStreamCandidate>()
        val seenUrls = mutableSetOf<String>()

        fun addCandidate(
            rawCandidateUrl: String?,
            defaultLabel: String,
            defaultScore: Int,
            mediaType: MediaType = MediaType.VIDEO,
            extension: String = if (mediaType == MediaType.AUDIO) "m4a" else if (mediaType == MediaType.IMAGE) "jpg" else "mp4",
            pairedAudioUrl: String? = null
        ) {
            val cleaned = sanitizeCandidateUrl(rawCandidateUrl ?: return)
            if (cleaned.isBlank() || cleaned.startsWith("blob:", ignoreCase = true)) return
            if (!cleaned.startsWith("http", ignoreCase = true)) return
            if (!seenUrls.add(cleaned)) {
                val existingIdx = candidates.indexOfFirst { it.url == cleaned }
                if (existingIdx >= 0) {
                    val existing = candidates[existingIdx]
                    val newAudio = existing.audioUrl ?: pairedAudioUrl
                    val newScore = max(existing.score, defaultScore)
                    val newLabel = if (defaultScore > existing.score) defaultLabel else existing.label
                    if (newAudio != existing.audioUrl || newScore != existing.score) {
                        candidates[existingIdx] = existing.copy(
                            label = newLabel,
                            score = newScore,
                            audioUrl = newAudio
                        )
                    }
                }
                return
            }

            if (mediaType == MediaType.VIDEO) {
                val efgMeta = inspectFacebookStreamUrl(cleaned)
                if (discoveredFbVideoId == null && !efgMeta.videoId.isNullOrBlank()) {
                    discoveredFbVideoId = efgMeta.videoId
                }
                if (efgMeta.isAudioOnly) {
                    candidates.add(
                        ScoredStreamCandidate(
                            url = cleaned,
                            label = "Âm thanh gốc (M4A)",
                            score = 100,
                            mediaType = MediaType.AUDIO,
                            extension = "m4a",
                            videoId = efgMeta.videoId
                        )
                    )
                    return
                }
                val resolvedScore = max(defaultScore, efgMeta.resolutionScore)
                val resolvedLabel = when {
                    resolvedScore >= 1080 -> if (defaultScore >= efgMeta.resolutionScore && defaultScore >= 1080) defaultLabel else (efgMeta.qualityLabel ?: "Full HD 1080p (MP4)")
                    resolvedScore >= 720 -> if (defaultScore >= efgMeta.resolutionScore && defaultScore >= 720) defaultLabel else (efgMeta.qualityLabel ?: "HD 720p (MP4)")
                    defaultScore > efgMeta.resolutionScore -> defaultLabel
                    else -> efgMeta.qualityLabel ?: defaultLabel
                }
                candidates.add(
                    ScoredStreamCandidate(
                        url = cleaned,
                        label = resolvedLabel,
                        score = resolvedScore,
                        mediaType = MediaType.VIDEO,
                        extension = extension,
                        audioUrl = pairedAudioUrl,
                        videoId = efgMeta.videoId
                    )
                )
            } else {
                candidates.add(
                    ScoredStreamCandidate(
                        url = cleaned,
                        label = defaultLabel,
                        score = defaultScore,
                        mediaType = mediaType,
                        extension = extension
                    )
                )
            }
        }

        // 1. Inspect WebView sniffed URLs first so we extract any embedded `video_id` from their `efg=` param!
        sniffedUrls.forEachIndexed { idx, sniffed ->
            val lower = sniffed.lowercase(Locale.ROOT)
            val isAudio = lower.contains(".m4a") || lower.contains(".mp3") || lower.contains("mime=audio")
            if (isAudio) {
                addCandidate(
                    rawCandidateUrl = sniffed,
                    defaultLabel = "Âm thanh Stream #${idx + 1} (M4A)",
                    defaultScore = 100,
                    mediaType = MediaType.AUDIO,
                    extension = "m4a"
                )
            } else {
                addCandidate(
                    rawCandidateUrl = sniffed,
                    defaultLabel = "Video Bắt Trực Tiếp #${idx + 1} (MP4)",
                    defaultScore = 450,
                    mediaType = MediaType.VIDEO,
                    extension = "mp4"
                )
            }
        }

        // 2. Parse Facebook / Meta progressive_urls, DASH 1080p `<Representation><BaseURL>`, and HD/SD fields from current page HTML
        extractFacebookStreamsFromSlice(unescaped, false) { u, lbl, sc, _, audUrl ->
            val mt = if (lbl.contains("M4A", ignoreCase = true)) MediaType.AUDIO else MediaType.VIDEO
            val ext = if (mt == MediaType.AUDIO) "m4a" else "mp4"
            addCandidate(u, lbl, sc, mt, ext, audUrl)
        }

        val videoRedirectRegex = Regex("""/video_redirect/\?src=([^"&'\s<>]+)""")
        (videoRedirectRegex.findAll(rawHtml) + videoRedirectRegex.findAll(unescaped)).forEach { match ->
            val rawSrc = match.groupValues.getOrNull(1).orEmpty()
            val decoded = runCatching {
                URLDecoder.decode(rawSrc.replace("&amp;", "&"), "UTF-8")
            }.getOrDefault(unescapeHtmlAndJson(rawSrc))
            addCandidate(decoded, "SD Trực Tiếp (MP4)", 360)
        }

        // 3. Parse Instagram / Threads / X / TikTok JSON video fields from HTML
        extractInstagramVideoVersionsFromText(unescaped).forEach { cand ->
            addCandidate(cand.url, cand.label, cand.score, cand.mediaType, cand.extension)
        }

        Regex(""""video_url"\s*:\s*"([^"]+)"""").findAll(unescaped).forEach { match ->
            addCandidate(match.groupValues.getOrNull(1), "Full HD Video (MP4)", 900)
        }

        Regex("""\{[^{}]*"content_type"\s*:\s*"video/mp4"[^{}]*\}""").findAll(unescaped).forEach { match ->
            val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(match.value)?.groupValues?.getOrNull(1)
            val bitrate = Regex(""""bitrate"\s*:\s*(\d+)""").find(match.value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val score = if (bitrate >= 2000000) 1080 else if (bitrate >= 800000) 720 else 480
            val label = if (score >= 1080) "Full HD 1080p (MP4)" else if (score >= 720) "HD 720p (MP4)" else "SD Video (MP4)"
            addCandidate(url, label, score)
        }

        Regex(""""(?:playAddr|downloadAddr)"\s*:\s*"([^"]+)"""").findAll(unescaped).forEach { match ->
            addCandidate(match.groupValues.getOrNull(1), "Full HD TikTok (MP4)", 900)
        }

        // 4. Parse OpenGraph and HTML5 <video> / <source> tags
        val ogVideo = extractMetaContent(rawHtml, "og:video:secure_url")
            .ifBlank { extractMetaContent(rawHtml, "og:video") }
            .ifBlank { extractMetaContent(rawHtml, "og:video:url") }
        if (ogVideo.isNotBlank()) {
            addCandidate(ogVideo, "HD Video (MP4)", 700)
        }

        val htmlVideoTagRegex = Regex(
            """<(?:video|source)[^>]+src\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        htmlVideoTagRegex.findAll(rawHtml).forEach { match ->
            addCandidate(match.groupValues.getOrNull(1), "Video Trình Duyệt (MP4)", 380)
        }

        // 5. AUTOMATIC DESKTOP FULL HD UPGRADE
        val desktopUpgradeInfo = runCatching {
            when (platform) {
                SocialPlatform.FACEBOOK -> {
                    val targetUrl = if (!discoveredFbVideoId.isNullOrBlank() && !cleanUrl.contains("/stories/", ignoreCase = true)) {
                        "https://www.facebook.com/watch/?v=$discoveredFbVideoId"
                    } else {
                        cleanUrl
                    }
                    extractFacebookDirect(pageUrl, targetUrl, discoveredFbVideoId)
                }
                SocialPlatform.INSTAGRAM -> extractInstagramDirect(pageUrl, cleanUrl)
                SocialPlatform.X_TWITTER -> extractTwitterDirect(pageUrl, cleanUrl)
                SocialPlatform.TIKTOK -> extractTikTokDirect(pageUrl, cleanUrl)
                SocialPlatform.THREADS -> extractThreadsDirect(pageUrl, cleanUrl)
                SocialPlatform.UNKNOWN -> null
            }
        }.getOrNull()

        desktopUpgradeInfo?.options?.forEachIndexed { idx, opt ->
            val score = when {
                opt.mediaType != MediaType.VIDEO -> 50
                opt.isHighestQuality || opt.qualityLabel.contains("1080", ignoreCase = true) ||
                    opt.qualityLabel.contains("Full HD", ignoreCase = true) -> 1200 - idx
                opt.qualityLabel.contains("720", ignoreCase = true) ||
                    opt.qualityLabel.contains("HD", ignoreCase = true) -> 950 - idx
                else -> 500 - idx
            }
            addCandidate(opt.url, opt.qualityLabel, score, opt.mediaType, opt.extension, opt.audioUrl)
        }

        // 6. Extract Thumbnail & Cover Image
        val ogImage = desktopUpgradeInfo?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: extractMetaContent(rawHtml, "og:image")
                .ifBlank {
                    Regex(""""display_url"\s*:\s*"([^"]+)"""")
                        .find(unescaped)?.groupValues?.getOrNull(1)?.let { sanitizeCandidateUrl(it) }.orEmpty()
                }
                .ifBlank {
                    Regex("""<video[^>]+poster\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(rawHtml)?.groupValues?.getOrNull(1)?.let { sanitizeCandidateUrl(it) }.orEmpty()
                }

        if (ogImage.startsWith("http", ignoreCase = true)) {
            addCandidate(
                rawCandidateUrl = ogImage,
                defaultLabel = if (candidates.isEmpty()) "Ảnh gốc HD" else "Ảnh bìa (Cover)",
                defaultScore = 10,
                mediaType = MediaType.IMAGE,
                extension = "jpg"
            )
        }

        if (candidates.isEmpty()) return null

        val sortedCandidates = candidates.sortedWith(
            compareByDescending<ScoredStreamCandidate> { it.mediaType == MediaType.VIDEO }
                .thenByDescending { it.score }
        )

        val fallbackDashAudioUrl = candidates.firstOrNull { it.mediaType == MediaType.AUDIO }?.url

        var markedHighest = false
        val finalOptions = sortedCandidates.map { cand ->
            val isHighest = if (cand.mediaType == MediaType.VIDEO && !markedHighest) {
                markedHighest = true
                true
            } else {
                false
            }
            val resolvedAudioUrl = cand.audioUrl ?: if (
                cand.mediaType == MediaType.VIDEO && isFacebookDashVideoOnlyUrl(cand.url)
            ) {
                fallbackDashAudioUrl
            } else {
                null
            }
            MediaOption(
                url = cand.url,
                qualityLabel = cand.label,
                extension = cand.extension,
                mediaType = cand.mediaType,
                isHighestQuality = isHighest,
                isNoWatermark = true,
                customHeaders = customHeaders,
                audioUrl = resolvedAudioUrl
            )
        }

        val resolvedTitle = desktopUpgradeInfo?.title?.takeIf {
            it.isNotBlank() && !it.equals("Video Facebook", ignoreCase = true)
        } ?: pageTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: extractMetaContent(rawHtml, "og:title")
                .ifBlank { extractMetaContent(rawHtml, "og:description") }
                .ifBlank { extractHtmlTagTitle(rawHtml) }
                .ifBlank { "Video ${platform.displayName}" }

        return ExtractedMediaInfo(
            originalUrl = pageUrl,
            cleanUrl = cleanUrl,
            platform = platform,
            title = resolvedTitle,
            author = desktopUpgradeInfo?.author ?: platform.displayName,
            thumbnailUrl = ogImage,
            durationSeconds = desktopUpgradeInfo?.durationSeconds ?: 0L,
            options = finalOptions
        )
    }

    /**
     * Universal Fallback Extractor using RapidAPI Auto-Download-All-In-One.
     */
    private fun extractViaRapidApi(
        cleanUrl: String,
        detectedPlatform: SocialPlatform,
        originalInput: String
    ): ExtractedMediaInfo {
        val formBody = FormBody.Builder()
            .add("url", cleanUrl)
            .build()

        val request = Request.Builder()
            .url(RAPID_API_ENDPOINT)
            .post(formBody)
            .header("x-rapidapi-host", RAPID_API_HOST)
            .header("x-rapidapi-key", getEffectiveApiKey())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .build()

        val responseStr = httpClient.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("RapidAPI trả về mã lỗi HTTP ${resp.code}: $bodyStr")
            }
            bodyStr
        }

        val json = JSONObject(responseStr)
        if (json.optBoolean("error", false)) {
            val msg = json.optString("message", "Không thể lấy dữ liệu từ liên kết này.")
            throw IllegalStateException(msg)
        }

        val sourceUrl = json.optString("url", cleanUrl).ifBlank { cleanUrl }
        val platform = if (detectedPlatform != SocialPlatform.UNKNOWN) {
            detectedPlatform
        } else {
            UrlDetector.detectPlatform(sourceUrl)
        }

        val title = json.optString("title", "").trim()
            .ifBlank { "Video ${platform.displayName}" }
        val author = json.optString("author", "").trim()
            .ifBlank { json.optString("source", platform.displayName) }
        val thumbnail = json.optString("thumbnail", "").trim()
        val durationSec = parseDurationSeconds(json.opt("duration"))

        val mediasArray = json.optJSONArray("medias") ?: JSONArray()
        var highestVideoIndex = -1
        var highestResolutionScore = -1

        for (i in 0 until mediasArray.length()) {
            val item = mediasArray.optJSONObject(i) ?: continue
            val typeStr = item.optString("type", "").lowercase(Locale.ROOT)
            val extStr = item.optString("extension", "").lowercase(Locale.ROOT)
            if (typeStr == "video" || extStr == "mp4" || extStr == "webm") {
                val qualityStr = item.optString("quality", "")
                val score = extractResolutionScore(qualityStr)
                if (score > highestResolutionScore) {
                    highestResolutionScore = score
                    highestVideoIndex = i
                }
            }
        }

        val cookie = authCookieStore.getEffectiveCookie(platform, cleanUrl)
        val defaultHeaders = buildMediaHeaders(platform, cookie)

        val options = mutableListOf<MediaOption>()
        for (i in 0 until mediasArray.length()) {
            val item = mediasArray.optJSONObject(i) ?: continue
            val mediaUrl = item.optString("url", "").trim()
            if (mediaUrl.isBlank()) continue

            val typeRaw = item.optString("type", "video").lowercase(Locale.ROOT)
            val rawQuality = item.optString("quality", "").trim()
            val extRaw = item.optString("extension", "").trim().lowercase(Locale.ROOT)
            val formattedSize = item.optString("formattedSize", "").trim()
                .ifBlank { formatBytes(item.optLong("size", 0L)) }

            val mediaType = when {
                typeRaw.contains("audio") || extRaw == "mp3" || extRaw == "m4a" -> MediaType.AUDIO
                typeRaw.contains("image") || typeRaw.contains("photo") ||
                    extRaw in setOf("jpg", "jpeg", "png", "webp") -> MediaType.IMAGE
                else -> MediaType.VIDEO
            }

            val extension = when {
                extRaw.isNotBlank() -> extRaw
                mediaType == MediaType.AUDIO -> "mp3"
                mediaType == MediaType.IMAGE -> "jpg"
                else -> "mp4"
            }

            val isNoWatermark = !rawQuality.contains("watermark", ignoreCase = true) ||
                rawQuality.contains("no watermark", ignoreCase = true) ||
                rawQuality.contains("hd_no_watermark", ignoreCase = true)

            val qualityLabel = formatQualityLabel(rawQuality, mediaType, extension, i + 1)
            val isHighest = (i == highestVideoIndex) ||
                (highestVideoIndex == -1 && i == 0)

            options.add(
                MediaOption(
                    url = mediaUrl,
                    qualityLabel = qualityLabel,
                    extension = extension,
                    mediaType = mediaType,
                    sizeText = formattedSize,
                    isHighestQuality = isHighest,
                    isNoWatermark = isNoWatermark,
                    customHeaders = defaultHeaders
                )
            )
        }

        if (thumbnail.startsWith("http", ignoreCase = true)) {
            options.add(
                MediaOption(
                    url = thumbnail,
                    qualityLabel = "Ảnh bìa (Cover)",
                    extension = "jpg",
                    mediaType = MediaType.IMAGE,
                    isHighestQuality = options.isEmpty(),
                    isNoWatermark = true,
                    customHeaders = defaultHeaders
                )
            )
        }

        return ExtractedMediaInfo(
            originalUrl = originalInput,
            cleanUrl = cleanUrl,
            platform = platform,
            title = title,
            author = author,
            thumbnailUrl = thumbnail,
            durationSeconds = durationSec,
            options = options
        )
    }

    private fun resolveRedirectUrl(url: String, userAgent: String): String {
        val platform = UrlDetector.detectPlatform(url)
        val rawCookie = authCookieStore.getEffectiveCookie(platform, url)
        val effectiveCookie = if (platform == SocialPlatform.FACEBOOK) {
            buildDesktopFacebookCookie(rawCookie)
        } else {
            rawCookie
        }

        fun extractStoryOrCanonicalFromHtml(html: String): String? {
            if (html.isBlank()) return null
            val unescaped = unescapeHtmlAndJson(html.take(120_000))
            val storyUrlMatch = Regex(
                """https?://(?:www\.|m\.|web\.)?facebook\.com/stories/[^/?#"'\s<>]+/UzpfSVNDOj[A-Za-z0-9+/=%_-]+/?""",
                RegexOption.IGNORE_CASE
            ).find(unescaped)?.value
            if (!storyUrlMatch.isNullOrBlank()) return storyUrlMatch

            val ogUrl = extractMetaContent(html, "og:url")
            if (ogUrl.startsWith("http", ignoreCase = true) && !ogUrl.contains("/share/", ignoreCase = true)) {
                return unwrapFacebookLoginRedirect(ogUrl)
            }
            val canonical = Regex(
                """<link[^>]+rel\s*=\s*["']canonical["'][^>]+href\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
            if (!canonical.isNullOrBlank() && canonical.startsWith("http", ignoreCase = true) && !canonical.contains("/share/", ignoreCase = true)) {
                return unwrapFacebookLoginRedirect(canonical)
            }
            return null
        }

        // 1. Try unauthenticated redirect first for Facebook `/share/` links because logged-out `/share/`
        // always issues an HTTP 302 redirect to the exact `/stories/<bucket>/<UzpfSVNDOj...>` or `/reel/<id>` URL
        // (or `/login/?next=https%3A%2F%2Fwww.facebook.com%2Fstories%2F...` which `unwrapFacebookLoginRedirect` unwraps!)
        if (platform == SocialPlatform.FACEBOOK &&
            (url.contains("/share/", ignoreCase = true) || url.contains("fb.watch", ignoreCase = true))
        ) {
            val noCookieUrl = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val unwrapped = unwrapFacebookLoginRedirect(resp.request.url.toString())
                    if (!unwrapped.contains("/share/", ignoreCase = true) && !unwrapped.contains("/login", ignoreCase = true)) {
                        unwrapped
                    } else {
                        val html = resp.body?.string().orEmpty()
                        extractStoryOrCanonicalFromHtml(html) ?: unwrapped
                    }
                }
            }.getOrNull()

            if (!noCookieUrl.isNullOrBlank() &&
                !noCookieUrl.contains("/share/", ignoreCase = true) &&
                !noCookieUrl.contains("/login", ignoreCase = true)
            ) {
                return noCookieUrl
            }
        }

        // 2. Try with effective cookie
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            .header("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .get()
        if (effectiveCookie.isNotBlank()) {
            reqBuilder.header("Cookie", effectiveCookie)
        }
        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val unwrapped = unwrapFacebookLoginRedirect(resp.request.url.toString())
                if (!unwrapped.contains("/share/", ignoreCase = true)) {
                    unwrapped
                } else {
                    val bodyStr = resp.body?.string().orEmpty()
                    extractStoryOrCanonicalFromHtml(bodyStr) ?: unwrapped
                }
            }
        }.getOrDefault(url)
    }

    private fun unescapeHtmlAndJson(raw: String): String {
        return raw
            .replace("""\/""", "/")
            .replace("""\u0025""", "%")
            .replace("""\u0026""", "&")
            .replace("""\u003C""", "<")
            .replace("""\u003E""", ">")
            .replace("""\u003D""", "=")
            .replace("""\u002F""", "/")
            .replace("""\u003c""", "<")
            .replace("""\u003e""", ">")
            .replace("""\u003d""", "=")
            .replace("""\u002f""", "/")
            .replace("&amp;", "&")
            .replace("&#x3d;", "=")
            .replace("&#x3D;", "=")
            .replace("&#x26;", "&")
    }


    private fun decodeJsonUnicodeString(raw: String): String {
        val unescaped = unescapeHtmlAndJson(raw)
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(unescaped) { matchResult ->
            val hex = matchResult.groupValues[1]
            runCatching { hex.toInt(16).toChar().toString() }.getOrDefault(matchResult.value)
        }
    }


    private fun sanitizeCandidateUrl(rawUrl: String): String {
        var cleaned = unescapeHtmlAndJson(rawUrl.trim().trim('"', '\''))
        if (cleaned.startsWith("http%3A%2F%2F", ignoreCase = true) ||
            cleaned.startsWith("https%3A%2F%2F", ignoreCase = true)
        ) {
            runCatching {
                cleaned = URLDecoder.decode(cleaned, "UTF-8")
            }
        }
        return cleaned
    }

    private fun extractMetaContent(html: String, property: String): String {
        val escapedProp = Regex.escape(property)
        val patterns = listOf(
            Regex(
                """<meta[^>]+(?:property|name)\s*=\s*["']$escapedProp["'][^>]+content\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']$escapedProp["']""",
                RegexOption.IGNORE_CASE
            )
        )
        val rawMatch = patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)
        }.orEmpty()
        return unescapeHtmlAndJson(rawMatch).trim()
    }

    private fun extractHtmlTagTitle(html: String): String {
        val match = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1).orEmpty()
        return unescapeHtmlAndJson(match).replace(Regex("""\s+"""), " ").trim()
    }

    private fun extractResolutionScore(quality: String): Int {
        val lower = quality.lowercase(Locale.ROOT)
        val numberMatch = Regex("""(\d{3,4})""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numberMatch != null) return numberMatch
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("2k") || lower.contains("1440") -> 1440
            lower.contains("full") || lower.contains("fhd") || lower.contains("1080") -> 1080
            lower.contains("hd_no_watermark") -> 1050
            lower.contains("hd") || lower.contains("720") -> 720
            lower.contains("no_watermark") -> 600
            lower.contains("sd") || lower.contains("480") -> 480
            else -> 360
        }
    }

    private fun formatQualityLabel(
        rawQuality: String,
        mediaType: MediaType,
        extension: String,
        index: Int
    ): String {
        val upperExt = extension.uppercase(Locale.ROOT)
        if (mediaType == MediaType.AUDIO) {
            return if (rawQuality.isNotBlank()) "Âm thanh $rawQuality ($upperExt)" else "Nhạc nền gốc ($upperExt)"
        }
        if (mediaType == MediaType.IMAGE) {
            return if (rawQuality.isNotBlank()) "Ảnh $rawQuality ($upperExt)" else "Ảnh gốc HD #$index"
        }
        val lower = rawQuality.lowercase(Locale.ROOT)
        return when {
            lower == "hd_no_watermark" -> "Full HD (Không Logo)"
            lower == "no_watermark" -> "HD (Không Logo)"
            lower == "watermark" -> "SD (Có Logo)"
            lower.contains("hd") && !lower.contains("sd") -> "${rawQuality.uppercase(Locale.ROOT)} ($upperExt)"
            rawQuality.isNotBlank() -> "$rawQuality ($upperExt)"
            else -> "Video HD #$index ($upperExt)"
        }
    }

    private fun parseDurationSeconds(rawDuration: Any?): Long {
        if (rawDuration == null) return 0L
        if (rawDuration is Number) return rawDuration.toLong()
        val str = rawDuration.toString().trim()
        if (str.isEmpty()) return 0L
        str.toDoubleOrNull()?.let { return it.toLong() }
        val parts = str.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            val kb = bytes.toDouble() / 1024.0
            String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
