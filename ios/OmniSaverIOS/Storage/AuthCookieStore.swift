import Foundation
import WebKit

final class AuthCookieStore: ObservableObject {
    static let shared = AuthCookieStore()

    private let defaults = UserDefaults.standard
    private let cookiePrefix = "omnisaver_cookie_"
    private let cobaltUrlKey = "omnisaver_custom_cobalt_url"

    @Published private(set) var revision: Int = 0

    private init() {}

    func getCookie(for platform: SocialPlatform) -> String {
        defaults.string(forKey: cookiePrefix + platform.rawValue)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    func saveCookie(_ rawCookie: String, for platform: SocialPlatform) {
        let cleaned = rawCookie
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"^[Cc]ookie\s*:\s*"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(cleaned, forKey: cookiePrefix + platform.rawValue)
        if platform == .instagram && getCookie(for: .threads).isEmpty {
            defaults.set(cleaned, forKey: cookiePrefix + SocialPlatform.threads.rawValue)
        }
        DispatchQueue.main.async {
            self.revision += 1
        }
    }

    func hasCookie(for platform: SocialPlatform) -> Bool {
        let c = getCookie(for: platform)
        if !c.isEmpty { return true }
        if platform == .threads {
            return !getCookie(for: .instagram).isEmpty
        }
        return false
    }

    func getEffectiveCookie(for platform: SocialPlatform, targetUrl: String = "") -> String {
        let direct = getCookie(for: platform)
        if !direct.isEmpty { return direct }
        if platform == .threads {
            let ig = getCookie(for: .instagram)
            if !ig.isEmpty { return ig }
        }
        let detected = UrlDetector.detectPlatform(targetUrl)
        if detected != .unknown {
            return getCookie(for: detected)
        }
        return ""
    }

    func clearCookie(for platform: SocialPlatform) {
        defaults.removeObject(forKey: cookiePrefix + platform.rawValue)
        DispatchQueue.main.async {
            self.revision += 1
        }
    }

    func clearAllCookies() {
        for platform in SocialPlatform.allCases {
            defaults.removeObject(forKey: cookiePrefix + platform.rawValue)
        }
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cookies in
            for cookie in cookies {
                WKWebsiteDataStore.default().httpCookieStore.delete(cookie)
            }
        }
        DispatchQueue.main.async {
            self.revision += 1
        }
    }

    func getCustomCobaltUrl() -> String {
        defaults.string(forKey: cobaltUrlKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    func setCustomCobaltUrl(_ url: String) {
        defaults.set(url.trimmingCharacters(in: .whitespacesAndNewlines), forKey: cobaltUrlKey)
        DispatchQueue.main.async {
            self.revision += 1
        }
    }

    /// Syncs cookies from WKWebView's WKHTTPCookieStore into AuthCookieStore for the given URL/platform.
    func syncFromWebKitCookieStore(for currentUrl: String, completion: ((String) -> Void)? = nil) {
        let platform = UrlDetector.detectPlatform(currentUrl)
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self = self else { return }
            for targetPlatform in SocialPlatform.supportedPlatforms {
                let keywords = targetPlatform.cookieDomainKeywords
                let matched = cookies.filter { cookie in
                    let domain = cookie.domain.lowercased()
                    return keywords.contains(where: { domain.contains($0) })
                }
                guard !matched.isEmpty else { continue }
                var pairMap: [String: String] = [:]
                for c in matched {
                    pairMap[c.name] = c.value
                }
                let headerString = pairMap.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: "; ")
                if !headerString.isEmpty && self.isAuthenticatedCookie(headerString, for: targetPlatform) {
                    self.saveCookie(headerString, for: targetPlatform)
                }
            }
            let resultCookie = platform != .unknown ? self.getCookie(for: platform) : ""
            DispatchQueue.main.async {
                completion?(resultCookie)
            }
        }
    }

    private func isAuthenticatedCookie(_ cookie: String, for platform: SocialPlatform) -> Bool {
        switch platform {
        case .facebook:
            return cookie.contains("c_user=") || cookie.contains("xs=")
        case .instagram, .threads:
            return cookie.contains("sessionid=") || cookie.contains("ds_user_id=")
        case .xTwitter:
            return cookie.contains("auth_token=") || cookie.contains("ct0=")
        case .tiktok:
            return cookie.contains("sessionid=") || cookie.contains("sid_tt=") || cookie.contains("uid_tt=")
        case .unknown:
            return !cookie.isEmpty
        }
    }
}
