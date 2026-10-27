package com.omnisaver.videodownloader.ota

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class OtaReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val publishedAt: String = "",
    val fileSizeBytes: Long = -1L,
    val sha256: String = "",
    val forceUpdate: Boolean = false,
    val isNewerThanCurrent: Boolean = false,
    val sourceLabel: String = "OTA Manifest"
)

class OtaUpdateManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "omnisaver_ota_prefs"
        private const val KEY_OTA_ENDPOINT = "ota_endpoint_url"
        private const val KEY_AUTO_CHECK = "ota_auto_check_startup"
        private const val KEY_LAST_CHECK_TIME = "ota_last_check_ms"
        private const val KEY_PENDING_APK_PATH = "ota_pending_apk_path"

        // Default OTA Manifest endpoint hosted on GitHub (trung1560x2/OmniSaver)
        const val DEFAULT_OTA_ENDPOINT = "https://raw.githubusercontent.com/trung1560x2/OmniSaver/main/ota-version.json"
        const val FALLBACK_GITHUB_RELEASES_API = "https://api.github.com/repos/trung1560x2/OmniSaver/releases/latest"
        private const val LEGACY_PLACEHOLDER_ENDPOINT = "https://raw.githubusercontent.com/omnisaver/releases/main/ota-version.json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo).toInt()
        } catch (_: Exception) {
            5
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.2.0-OTA"
        } catch (_: Exception) {
            "2.2.0-OTA"
        }
    }

    fun getOtaEndpointUrl(): String {
        val saved = prefs.getString(KEY_OTA_ENDPOINT, null)?.trim()
        if (saved.isNullOrEmpty() || saved == LEGACY_PLACEHOLDER_ENDPOINT) {
            return DEFAULT_OTA_ENDPOINT
        }
        return saved
    }

    fun setOtaEndpointUrl(url: String) {
        val cleaned = url.trim()
        prefs.edit().putString(KEY_OTA_ENDPOINT, cleaned.ifEmpty { DEFAULT_OTA_ENDPOINT }).apply()
    }

    fun isAutoCheckEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun getLastCheckTimestamp(): Long {
        return prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    /**
     * Checks the configured OTA endpoint for a new release.
     * Supports:
     * 1. Custom JSON Manifest (`ota-version.json`)
     * 2. GitHub Repository URL (`https://github.com/<owner>/<repo>`) or GitHub Releases API
     * 3. Direct `.apk` URL (`https://.../OmniSaver.apk`)
     */
    suspend fun checkForUpdate(customEndpointOverride: String? = null): Result<OtaReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawEndpoint = (customEndpointOverride ?: getOtaEndpointUrl()).trim()
                if (rawEndpoint.isBlank()) {
                    throw IllegalArgumentException("Chưa cấu hình địa chỉ máy chủ OTA.")
                }

                val normalizedEndpoint = normalizeEndpointUrl(rawEndpoint)

                // Case 1: Direct APK URL
                if (normalizedEndpoint.substringBefore("?").endsWith(".apk", ignoreCase = true)) {
                    prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                    return@runCatching OtaReleaseInfo(
                        versionCode = getCurrentVersionCode() + 1,
                        versionName = "Direct-APK",
                        apkUrl = normalizedEndpoint,
                        changelog = "Cài đặt trực tiếp từ đường dẫn tệp APK chỉ định:\n$normalizedEndpoint",
                        publishedAt = "Trực tiếp",
                        fileSizeBytes = -1L,
                        forceUpdate = false,
                        isNewerThanCurrent = true,
                        sourceLabel = "Direct APK Link"
                    )
                }

                val requestBuilder = Request.Builder()
                    .url(normalizedEndpoint)
                    .header("User-Agent", "OmniSaver-OTA-Updater/${getCurrentVersionName()} (Android)")
                    .header("Cache-Control", "no-cache")

                if (normalizedEndpoint.contains("api.github.com/repos/")) {
                    requestBuilder.header("Accept", "application/vnd.github+json")
                } else {
                    requestBuilder.header("Accept", "application/json, text/plain, */*")
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (normalizedEndpoint == DEFAULT_OTA_ENDPOINT) {
                            // Fallback to GitHub Releases API directly if raw manifest is not reachable
                            val ghReq = Request.Builder()
                                .url(FALLBACK_GITHUB_RELEASES_API)
                                .header("User-Agent", "OmniSaver-OTA-Updater/${getCurrentVersionName()} (Android)")
                                .header("Accept", "application/vnd.github+json")
                                .build()
                            httpClient.newCall(ghReq).execute().use { ghRes ->
                                if (ghRes.isSuccessful) {
                                    val ghBody = ghRes.body?.string()?.trim().orEmpty()
                                    if (ghBody.isNotEmpty()) {
                                        prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                                        return@runCatching parseGitHubReleaseJson(ghBody)
                                    }
                                }
                            }
                        }
                        throw IllegalStateException("Máy chủ OTA trả về mã lỗi HTTP ${response.code}")
                    }

                    val body = response.body?.string()?.trim()
                        ?: throw IllegalStateException("Phản hồi từ máy chủ OTA trống.")

                    prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()

                    if (normalizedEndpoint.contains("api.github.com/repos/") ||
                        (body.startsWith("{") && body.contains("\"tag_name\"") && body.contains("\"assets\""))
                    ) {
                        parseGitHubReleaseJson(body)
                    } else {
                        parseCustomManifestJson(body, normalizedEndpoint)
                    }
                }
            }
        }

    /**
     * Converts `https://github.com/owner/repo` or `https://github.com/owner/repo/releases`
     * automatically into `https://api.github.com/repos/owner/repo/releases/latest`.
     */
    private fun normalizeEndpointUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val ghRegex = Regex("""^https?://(?:www\.)?github\.com/([^/\s]+)/([^/\s#?]+?)(?:\.git)?(?:/releases(?:/latest)?)?/?$""", RegexOption.IGNORE_CASE)
        val match = ghRegex.find(trimmed)
        if (match != null) {
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            return "https://api.github.com/repos/$owner/$repo/releases/latest"
        }
        return trimmed
    }

    private fun parseCustomManifestJson(jsonStr: String, baseUrl: String): OtaReleaseInfo {
        val obj = JSONObject(jsonStr)
        val currentCode = getCurrentVersionCode()
        val currentName = getCurrentVersionName()

        val remoteCode = obj.optInt("versionCode", obj.optInt("version_code", 0))
        val remoteName = obj.optString(
            "versionName",
            obj.optString("version_name", obj.optString("version", "Unknown"))
        ).removePrefix("v").removePrefix("V")

        var rawApkUrl = obj.optString(
            "apkUrl",
            obj.optString("apk_url", obj.optString("downloadUrl", obj.optString("url", "")))
        ).trim()

        if (rawApkUrl.isBlank()) {
            throw IllegalStateException("File JSON OTA không chứa trường 'apkUrl' hợp lệ.")
        }

        // Resolve relative apkUrl against manifest base URL if needed
        if (!rawApkUrl.startsWith("http://") && !rawApkUrl.startsWith("https://")) {
            val baseDir = baseUrl.substringBeforeLast("/", "")
            if (baseDir.isNotBlank()) {
                rawApkUrl = "$baseDir/${rawApkUrl.removePrefix("/")}"
            }
        }

        val changelog = obj.optString(
            "changelog",
            obj.optString("releaseNotes", obj.optString("notes", "Bản cập nhật cải thiện hiệu năng và độ ổn định."))
        )
        val publishedAt = obj.optString("publishedAt", obj.optString("date", ""))
        val fileSize = obj.optLong("fileSizeBytes", obj.optLong("size", -1L))
        val sha256 = obj.optString("sha256", "").trim()
        val forceUpdate = obj.optBoolean("forceUpdate", obj.optBoolean("force", false))

        val isNewer = if (remoteCode > 0) {
            remoteCode > currentCode
        } else {
            compareVersionStrings(remoteName, currentName) > 0
        }

        return OtaReleaseInfo(
            versionCode = if (remoteCode > 0) remoteCode else currentCode,
            versionName = remoteName,
            apkUrl = rawApkUrl,
            changelog = changelog,
            publishedAt = publishedAt,
            fileSizeBytes = fileSize,
            sha256 = sha256,
            forceUpdate = forceUpdate,
            isNewerThanCurrent = isNewer,
            sourceLabel = "JSON Manifest"
        )
    }

    private fun parseGitHubReleaseJson(jsonStr: String): OtaReleaseInfo {
        val obj = if (jsonStr.startsWith("[")) {
            val arr = JSONArray(jsonStr)
            if (arr.length() == 0) throw IllegalStateException("Chưa có bản Release nào trên GitHub.")
            arr.getJSONObject(0)
        } else {
            JSONObject(jsonStr)
        }

        val tagName = obj.optString("tag_name", obj.optString("name", "")).trim()
        val cleanVersionName = tagName.removePrefix("v").removePrefix("V").ifBlank { "Latest" }
        val bodyNotes = obj.optString("body", "").ifBlank { "Bản phát hành mới trên GitHub Releases." }
        val publishedAt = obj.optString("published_at", "").take(10)

        val assets = obj.optJSONArray("assets") ?: JSONArray()
        var apkDownloadUrl = ""
        var apkSize = -1L

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val downloadUrl = asset.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                apkDownloadUrl = downloadUrl
                apkSize = asset.optLong("size", -1L)
                break
            }
        }

        if (apkDownloadUrl.isBlank()) {
            throw IllegalStateException("Bản Release '$tagName' trên GitHub không đính kèm tệp .apk nào trong mục Assets.")
        }

        val currentCode = getCurrentVersionCode()
        val currentName = getCurrentVersionName()
        val isNewer = compareVersionStrings(cleanVersionName, currentName) > 0

        return OtaReleaseInfo(
            versionCode = if (isNewer) currentCode + 1 else currentCode,
            versionName = cleanVersionName,
            apkUrl = apkDownloadUrl,
            changelog = bodyNotes,
            publishedAt = publishedAt,
            fileSizeBytes = apkSize,
            sha256 = "",
            forceUpdate = false,
            isNewerThanCurrent = isNewer,
            sourceLabel = "GitHub Releases"
        )
    }

    /**
     * Compares two semantic version strings (e.g. "2.3.0" vs "2.2.0-OTA").
     */
    fun compareVersionStrings(v1: String, v2: String): Int {
        val parts1 = Regex("""\d+""").findAll(v1).mapNotNull { it.value.toIntOrNull() }.toList()
        val parts2 = Regex("""\d+""").findAll(v2).mapNotNull { it.value.toIntOrNull() }.toList()
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    /**
     * Downloads the OTA APK file into the app's external/internal cache directory,
     * validates the APK ZIP header (`PK\x03\x04`) and optional SHA-256 hash.
     */
    suspend fun downloadUpdateApk(
        releaseInfo: OtaReleaseInfo,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val otaDir = File(context.externalCacheDir ?: context.cacheDir, "ota_updates")
            if (!otaDir.exists()) {
                otaDir.mkdirs()
            }

            // Clean old cached APKs
            otaDir.listFiles()?.forEach { runCatching { it.delete() } }

            val safeVersion = releaseInfo.versionName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            val targetApk = File(otaDir, "OmniSaver_OTA_${safeVersion}.apk")

            val request = Request.Builder()
                .url(releaseInfo.apkUrl)
                .header("User-Agent", "OmniSaver-OTA-Updater/${getCurrentVersionName()} (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Không thể tải APK cập nhật (HTTP ${response.code}).")
                }

                val body = response.body ?: throw IllegalStateException("Dữ liệu APK tải về trống.")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: releaseInfo.fileSizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(targetApk).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        var read: Int
                        var lastReportedPercent = -1

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (totalBytes > 0) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (percent != lastReportedPercent || downloaded % (256 * 1024) < 32 * 1024) {
                                lastReportedPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent, downloaded, totalBytes)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Validate APK file integrity (must be > 100 KB and start with ZIP/APK magic bytes PK\x03\x04)
            if (!targetApk.exists() || targetApk.length() < 100 * 1024) {
                targetApk.delete()
                throw IllegalStateException("Tệp tải về quá nhỏ hoặc không phải là gói APK hợp lệ.")
            }

            val magic = ByteArray(4)
            FileInputStream(targetApk).use { fis ->
                if (fis.read(magic) != 4 ||
                    magic[0] != 0x50.toByte() ||
                    magic[1] != 0x4B.toByte() ||
                    magic[2] != 0x03.toByte() ||
                    magic[3] != 0x04.toByte()
                ) {
                    targetApk.delete()
                    throw IllegalStateException("Tệp tải về không đúng định dạng APK (có thể link trả về trang web thay vì file .apk trực tiếp).")
                }
            }

            // Verify SHA-256 if specified in manifest
            if (releaseInfo.sha256.isNotBlank() && releaseInfo.sha256.length == 64) {
                val actualHash = computeSha256(targetApk)
                if (!actualHash.equals(releaseInfo.sha256, ignoreCase = true)) {
                    targetApk.delete()
                    throw IllegalStateException("Mã kiểm tra toàn vẹn SHA-256 của APK không khớp!")
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100, targetApk.length(), targetApk.length())
            }

            targetApk
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Triggers Android's system PackageInstaller for the downloaded APK.
     * Returns true if the installer intent was launched directly, or false if the user was
     * redirected to grant `ACTION_MANAGE_UNKNOWN_APP_SOURCES` permission first.
     */
    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                prefs.edit().putString(KEY_PENDING_APK_PATH, apkFile.absolutePath).apply()
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return false
            }
        }

        prefs.edit().remove(KEY_PENDING_APK_PATH).apply()
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    /**
     * Resumes pending APK installation if the user just returned from enabling
     * "Install unknown apps" permission in Android Settings.
     */
    fun resumePendingInstallIfPermitted(): Boolean {
        val pendingPath = prefs.getString(KEY_PENDING_APK_PATH, null) ?: return false
        val file = File(pendingPath)
        if (!file.exists()) {
            prefs.edit().remove(KEY_PENDING_APK_PATH).apply()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return false
        }
        return installApk(file)
    }
}
