package com.omnisaver.videodownloader.util

import android.content.Context
import android.webkit.CookieManager
import com.omnisaver.videodownloader.model.SocialPlatform

class AuthCookieStore(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_COOKIE_STORE, Context.MODE_PRIVATE)
    }

    fun getManualCookie(platform: SocialPlatform): String {
        return prefs.getString("manual_cookie_${platform.name}", "")?.trim().orEmpty()
    }

    fun setManualCookie(platform: SocialPlatform, cookie: String) {
        prefs.edit()
            .putString("manual_cookie_${platform.name}", cookie.trim())
            .apply()
    }

    fun getWebViewCookie(platform: SocialPlatform, targetUrl: String? = null): String {
        val cookieManager = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            return ""
        }

        val urlsToQuery = buildList {
            if (!targetUrl.isNullOrBlank()) add(targetUrl)
            addAll(getCanonicalDomains(platform))
        }

        val cookiePairs = linkedMapOf<String, String>()
        for (domainUrl in urlsToQuery) {
            val raw = try {
                cookieManager.getCookie(domainUrl)
            } catch (_: Exception) {
                null
            } ?: continue

            raw.split(";").forEach { segment ->
                val trimmed = segment.trim()
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val key = trimmed.substring(0, eqIndex).trim()
                    val value = trimmed.substring(eqIndex + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        cookiePairs[key] = value
                    }
                }
            }
        }

        return cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getEffectiveCookie(platform: SocialPlatform, targetUrl: String? = null): String {
        val manual = getManualCookie(platform)
        val webView = getWebViewCookie(platform, targetUrl)
        if (manual.isBlank()) return webView
        if (webView.isBlank()) return manual

        val merged = linkedMapOf<String, String>()
        listOf(webView, manual).forEach { source ->
            source.split(";").forEach { segment ->
                val trimmed = segment.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        merged[key] = value
                    }
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun isLoggedIn(platform: SocialPlatform): Boolean {
        if (getManualCookie(platform).isNotBlank()) return true
        val cookie = getEffectiveCookie(platform)
        if (cookie.isBlank()) return false

        return when (platform) {
            SocialPlatform.FACEBOOK -> cookie.contains("c_user=") && cookie.contains("xs=")
            SocialPlatform.INSTAGRAM -> cookie.contains("sessionid=")
            SocialPlatform.X_TWITTER -> cookie.contains("auth_token=") || cookie.contains("twid=")
            SocialPlatform.TIKTOK -> cookie.contains("sessionid=") || cookie.contains("sid_tt=") || cookie.contains("uid_tt=")
            SocialPlatform.THREADS -> cookie.contains("sessionid=")
            SocialPlatform.UNKNOWN -> false
        }
    }

    fun extractCookieValue(cookieHeader: String, key: String): String? {
        for (segment in cookieHeader.split(";")) {
            val trimmed = segment.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val k = trimmed.substring(0, eqIdx).trim()
                if (k.equals(key, ignoreCase = true)) {
                    return trimmed.substring(eqIdx + 1).trim()
                }
            }
        }
        return null
    }

    fun clearPlatformManualCookie(platform: SocialPlatform) {
        setManualCookie(platform, "")
    }

    fun clearAllCookies() {
        prefs.edit().clear().apply()
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {
        }
    }

    fun getDefaultLoginUrl(platform: SocialPlatform): String {
        return when (platform) {
            SocialPlatform.FACEBOOK -> "https://m.facebook.com/"
            SocialPlatform.X_TWITTER -> "https://x.com/i/flow/login"
            SocialPlatform.INSTAGRAM -> "https://www.instagram.com/accounts/login/"
            SocialPlatform.TIKTOK -> "https://www.tiktok.com/login"
            SocialPlatform.THREADS -> "https://www.threads.net/login"
            SocialPlatform.UNKNOWN -> "https://www.facebook.com/"
        }
    }

    private fun getCanonicalDomains(platform: SocialPlatform): List<String> {
        return when (platform) {
            SocialPlatform.FACEBOOK -> listOf(
                "https://www.facebook.com",
                "https://m.facebook.com",
                "https://web.facebook.com"
            )
            SocialPlatform.X_TWITTER -> listOf(
                "https://x.com",
                "https://twitter.com",
                "https://api.x.com",
                "https://mobile.twitter.com"
            )
            SocialPlatform.INSTAGRAM -> listOf(
                "https://www.instagram.com",
                "https://i.instagram.com"
            )
            SocialPlatform.TIKTOK -> listOf(
                "https://www.tiktok.com",
                "https://m.tiktok.com",
                "https://vt.tiktok.com"
            )
            SocialPlatform.THREADS -> listOf(
                "https://www.threads.net",
                "https://www.threads.com",
                "https://www.instagram.com"
            )
            SocialPlatform.UNKNOWN -> emptyList()
        }
    }

    companion object {
        private const val PREFS_COOKIE_STORE = "omni_cookie_store"
    }
}
