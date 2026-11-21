package com.example.domain

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Dedicated Universal SafeSearch Enforcement Engine.
 *
 * Strictly separated from Website Blocking and YouTube Channel Allowlisting.
 * Supports:
 *  - Google SafeSearch Filter (`safe=active`) across Web, Images, Videos, News, Shopping
 *  - Bing Strict (`adlt=strict`) across Web, Images, Videos
 *  - DuckDuckGo Strict (`kp=1`)
 *  - Yahoo Strict (`vm=r`)
 *  - Brave Search Strict (`safesearch=strict`)
 *  - Ecosia Strict (`safesearch=strict`)
 *  - Startpage Strict (`family_filter=1`)
 *  - Qwant Strict (`s=2`)
 *  - Yandex Family (`fyandex=1`)
 *
 * Also detects:
 *  - Attempts to visit SafeSearch settings pages or pass explicit disable params during Strict Mode
 *  - Google Images / Image Search tabs when Strict Mode Image Search Block is active
 *  - Unknown / unverified alternative search engines (blocked in Strict Mode when enabled)
 */
object SafeSearchEngine {

    sealed class Decision {
        data object NotASearchUrl : Decision()
        data class Compliant(val providerName: String, val searchQuery: String?) : Decision()
        data class EnforceRedirect(
            val providerId: String,
            val providerName: String,
            val enforcedUrl: String,
            val searchQuery: String?
        ) : Decision()
        data class BlockSettingsTamper(
            val providerName: String,
            val reason: String
        ) : Decision()
        data class BlockImageSearch(
            val providerName: String
        ) : Decision()
        data class BlockUnverifiedSearchEngine(
            val host: String
        ) : Decision()
    }

    val DEFAULT_PROVIDER_RULES: List<SafeSearchProviderRule> = listOf(
        SafeSearchProviderRule(
            providerId = "google",
            displayName = "Google SafeSearch",
            hosts = listOf(
                "google.com", "google.co.in", "google.co.uk", "google.ca",
                "google.com.au", "google.de", "google.fr", "google.co.jp",
                "google.com.br", "google.es", "google.it", "google.nl",
                "images.google.com", "images.google.co.in", "images.google.co.uk"
            ),
            searchPaths = listOf("/search", "/imgres", "/imghp"),
            strictParamKey = "safe",
            strictParamValue = "active",
            unsafeParamValues = listOf("off", "images", "moderate"),
            settingsPaths = listOf("/safesearch", "/preferences"),
            imageIndicators = listOf("tbm=isch", "udm=2", "/imghp", "/imgres", "images.google.")
        ),
        SafeSearchProviderRule(
            providerId = "bing",
            displayName = "Bing Strict",
            hosts = listOf("bing.com"),
            searchPaths = listOf("/search", "/images/search", "/videos/search", "/news/search"),
            strictParamKey = "adlt",
            strictParamValue = "strict",
            unsafeParamValues = listOf("off", "demote", "moderate"),
            settingsPaths = listOf("/account/general", "/account/settings"),
            imageIndicators = listOf("/images/search", "/images/")
        ),
        SafeSearchProviderRule(
            providerId = "duckduckgo",
            displayName = "DuckDuckGo Strict",
            hosts = listOf("duckduckgo.com", "html.duckduckgo.com", "lite.duckduckgo.com"),
            searchPaths = listOf("/", "/html", "/lite"),
            strictParamKey = "kp",
            strictParamValue = "1",
            unsafeParamValues = listOf("-2", "-1"),
            settingsPaths = listOf("/settings"),
            imageIndicators = listOf("iax=images", "ia=images")
        ),
        SafeSearchProviderRule(
            providerId = "yahoo",
            displayName = "Yahoo Strict",
            hosts = listOf("search.yahoo.com", "images.search.yahoo.com", "video.search.yahoo.com", "in.search.yahoo.com"),
            searchPaths = listOf("/search", "/yhs/search"),
            strictParamKey = "vm",
            strictParamValue = "r",
            unsafeParamValues = listOf("p", "i"),
            settingsPaths = listOf("/preferences"),
            imageIndicators = listOf("images.search.yahoo.com")
        ),
        SafeSearchProviderRule(
            providerId = "brave",
            displayName = "Brave Search Strict",
            hosts = listOf("search.brave.com"),
            searchPaths = listOf("/search", "/images", "/videos", "/news"),
            strictParamKey = "safesearch",
            strictParamValue = "strict",
            unsafeParamValues = listOf("off", "moderate"),
            settingsPaths = listOf("/settings"),
            imageIndicators = listOf("/images")
        ),
        SafeSearchProviderRule(
            providerId = "ecosia",
            displayName = "Ecosia Strict",
            hosts = listOf("ecosia.org"),
            searchPaths = listOf("/search", "/images", "/videos"),
            strictParamKey = "safesearch",
            strictParamValue = "strict",
            unsafeParamValues = listOf("off", "moderate"),
            settingsPaths = listOf("/settings"),
            imageIndicators = listOf("/images")
        ),
        SafeSearchProviderRule(
            providerId = "startpage",
            displayName = "Startpage Strict",
            hosts = listOf("startpage.com"),
            searchPaths = listOf("/sp/search", "/do/search", "/do/dsearch"),
            strictParamKey = "family_filter",
            strictParamValue = "1",
            unsafeParamValues = listOf("0"),
            settingsPaths = listOf("/sp/settings", "/do/mypage"),
            imageIndicators = listOf("cat=pics", "cat=images")
        ),
        SafeSearchProviderRule(
            providerId = "qwant",
            displayName = "Qwant Strict",
            hosts = listOf("qwant.com"),
            searchPaths = listOf("/"),
            strictParamKey = "s",
            strictParamValue = "2",
            unsafeParamValues = listOf("0", "1"),
            settingsPaths = listOf("/settings"),
            imageIndicators = listOf("t=images")
        ),
        SafeSearchProviderRule(
            providerId = "yandex",
            displayName = "Yandex Family",
            hosts = listOf("yandex.com", "yandex.ru", "ya.ru"),
            searchPaths = listOf("/search", "/images/search", "/video/search"),
            strictParamKey = "fyandex",
            strictParamValue = "1",
            unsafeParamValues = listOf("0"),
            settingsPaths = listOf("/tune/search"),
            imageIndicators = listOf("/images/")
        )
    )

    // Known alternative / unverified or adult-permissive search engines
    private val KNOWN_UNVERIFIED_SEARCH_HOSTS = setOf(
        "searx.be", "searx.space", "searxng.org", "whoogle.sdf.org",
        "mojeek.com", "swisscows.com", "gibiru.com", "metager.org",
        "dogpile.com", "metacrawler.com", "webcrawler.com", "ask.com",
        "aol.com", "search.aol.com", "baidu.com", "sogou.com",
        "naver.com", "search.naver.com", "presearch.com", "kagi.com",
        "yep.com", "marginalia.nu", "gigablast.com", "excite.com"
    )

    private val NON_SEARCH_QUERY_HOSTS = setOf(
        "youtube.com", "youtu.be", "wikipedia.org", "github.com",
        "stackoverflow.com", "amazon.com", "amazon.in", "flipkart.com",
        "reddit.com", "linkedin.com", "docs.google.com", "drive.google.com",
        "mail.google.com", "calendar.google.com", "maps.google.com",
        "translate.google.com", "scholar.google.com", "classroom.google.com"
    )

    private val SEARCH_QUERY_PARAM_KEYS = listOf("q", "query", "p", "text", "wd", "k", "search_query")

    /**
     * Evaluates a full browser URL (in-memory only — never stored or logged) against the
     * dedicated SafeSearch rules database.
     */
    fun evaluateUrl(
        rawUrl: String?,
        config: ContentProtectionConfig,
        strictMode: Boolean,
        rules: List<SafeSearchProviderRule> = DEFAULT_PROVIDER_RULES
    ): Decision {
        if (!config.safeSearchEnabled || rawUrl.isNullOrBlank()) {
            return Decision.NotASearchUrl
        }

        val normalizedInput = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }

        val host = DomainBlockMatcher.normalizeDomain(normalizedInput) ?: return Decision.NotASearchUrl
        val afterScheme = normalizedInput.substringAfter("://")
        val rawHostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val pathAndQuery = afterScheme.substringAfter('/', "")
        val path = "/" + pathAndQuery.substringBefore('?').substringBefore('#').trimStart('/')
        val query = pathAndQuery.substringAfter('?', "").substringBefore('#')
        val lowerPath = path.lowercase()
        val lowerQuery = query.lowercase()
        val lowerFull = "$host$lowerPath?$lowerQuery"

        val matchedProvider = findProviderForHost(host, rules)
        if (matchedProvider != null) {
            // 1. Detect SafeSearch settings tampering during Strict Mode
            if (strictMode && matchedProvider.settingsPaths.any { lowerPath.startsWith(it.lowercase()) }) {
                return Decision.BlockSettingsTamper(
                    providerName = matchedProvider.displayName,
                    reason = "SafeSearch settings locked during Strict Mode"
                )
            }

            val params = parseQueryParams(query)
            val searchQuery = extractSearchQuery(params)

            // 2. Detect explicit attempt to turn SafeSearch OFF via query parameter
            val currentVal = params[matchedProvider.strictParamKey]?.lowercase()
            if (strictMode && currentVal != null && currentVal in matchedProvider.unsafeParamValues) {
                val enforced = buildEnforcedUrl(
                    providerId = matchedProvider.providerId,
                    rawHostPart = rawHostPart,
                    path = path,
                    params = params,
                    strictKey = matchedProvider.strictParamKey,
                    strictValue = matchedProvider.strictParamValue
                )
                return Decision.EnforceRedirect(
                    providerId = matchedProvider.providerId,
                    providerName = matchedProvider.displayName,
                    enforcedUrl = enforced,
                    searchQuery = searchQuery
                )
            }

            // 3. Detect Image Search when Strict Mode Image Search Block is active
            val isImageSearch = matchedProvider.imageIndicators.any { indicator ->
                lowerFull.contains(indicator.lowercase())
            }
            if (isImageSearch && strictMode && config.blockImageSearchInStrictMode) {
                return Decision.BlockImageSearch(matchedProvider.displayName)
            }

            // 4. Determine if this URL is an active search results URL for this provider
            val isSearchRequest = isProviderSearchRequest(matchedProvider, lowerPath, params)
            if (!isSearchRequest) {
                return Decision.NotASearchUrl
            }

            // 5. Enforce strictest supported SafeSearch parameter
            if (matchedProvider.enforceMethod == SafeSearchEnforceMethod.URL_PARAM) {
                if (currentVal != matchedProvider.strictParamValue.lowercase()) {
                    val enforced = buildEnforcedUrl(
                        providerId = matchedProvider.providerId,
                        rawHostPart = rawHostPart,
                        path = path,
                        params = params,
                        strictKey = matchedProvider.strictParamKey,
                        strictValue = matchedProvider.strictParamValue
                    )
                    return Decision.EnforceRedirect(
                        providerId = matchedProvider.providerId,
                        providerName = matchedProvider.displayName,
                        enforcedUrl = enforced,
                        searchQuery = searchQuery
                    )
                }
            }

            return Decision.Compliant(
                providerName = matchedProvider.displayName,
                searchQuery = searchQuery
            )
        }

        // 6. Unknown / Alternative Search Engine Detection (when configured in Strict Mode)
        if (strictMode && config.blockUnverifiedSearchEnginesInStrictMode) {
            if (isUnverifiedSearchEngine(host, lowerPath, query)) {
                return Decision.BlockUnverifiedSearchEngine(host)
            }
        }

        return Decision.NotASearchUrl
    }

    /**
     * Checks visible browser page text for SafeSearch toggle tampering inside settings pages
     * (e.g., user opened Google SafeSearch settings and tapped "Off" or "Blur").
     */
    fun detectSafeSearchSettingsTamperText(
        host: String?,
        visibleSnippets: List<String>,
        strictMode: Boolean
    ): Boolean {
        if (!strictMode || host.isNullOrBlank()) return false
        val isSearchHost = DEFAULT_PROVIDER_RULES.any { rule ->
            rule.hosts.any { h -> host == h || host.endsWith(".$h") }
        }
        if (!isSearchHost) return false

        val joined = visibleSnippets.joinToString(" ").lowercase()
        val onSettingsUi = joined.contains("safesearch") ||
            joined.contains("safe search") ||
            joined.contains("explicit results") ||
            joined.contains("strict filtering")
        val showsWeakenedOption = joined.contains("safesearch is off") ||
            joined.contains("safesearch: off") ||
            joined.contains("filter explicit results") ||
            joined.contains("blur explicit images") ||
            joined.contains("turn off safesearch")
        return onSettingsUi && showsWeakenedOption
    }

    private fun findProviderForHost(
        host: String,
        rules: List<SafeSearchProviderRule>
    ): SafeSearchProviderRule? {
        // Handle Google country ccTLDs dynamically (e.g., google.co.za, google.com.mx, images.google.*)
        if (host.startsWith("google.") || host.startsWith("images.google.")) {
            return rules.firstOrNull { it.providerId == "google" }
        }
        return rules.firstOrNull { rule ->
            rule.hosts.any { h -> host == h || host.endsWith(".$h") }
        }
    }

    private fun isProviderSearchRequest(
        rule: SafeSearchProviderRule,
        lowerPath: String,
        params: Map<String, String>
    ): Boolean {
        val hasQuery = extractSearchQuery(params) != null
        if (rule.providerId == "duckduckgo" || rule.providerId == "qwant") {
            return hasQuery
        }
        val pathMatches = rule.searchPaths.any { sp ->
            val p = sp.lowercase()
            lowerPath == p || lowerPath.startsWith("$p/")
        }
        return pathMatches && (hasQuery || lowerPath.contains("image") || lowerPath.contains("img"))
    }

    private fun isUnverifiedSearchEngine(
        host: String,
        lowerPath: String,
        query: String
    ): Boolean {
        if (NON_SEARCH_QUERY_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        if (KNOWN_UNVERIFIED_SEARCH_HOSTS.any { host == it || host.endsWith(".$it") }) return true

        // Heuristic: unknown host serving `/search?q=...` or `search.<domain>`
        val params = parseQueryParams(query)
        val hasSearchQuery = extractSearchQuery(params) != null
        val looksLikeSearchEndpoint = host.startsWith("search.") ||
            lowerPath == "/search" ||
            lowerPath == "/web" ||
            lowerPath.startsWith("/search/")
        return hasSearchQuery && looksLikeSearchEndpoint
    }

    fun extractSearchQuery(params: Map<String, String>): String? {
        for (key in SEARCH_QUERY_PARAM_KEYS) {
            val value = params[key]?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    fun parseQueryParams(query: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (query.isBlank()) return map
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
            val decodedKey = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            if (decodedKey.isNotBlank()) {
                map[decodedKey] = decodedVal
            }
        }
        return map
    }

    private fun buildEnforcedUrl(
        providerId: String,
        rawHostPart: String,
        path: String,
        params: LinkedHashMap<String, String>,
        strictKey: String,
        strictValue: String
    ): String {
        val updated = LinkedHashMap(params)
        // Remove case-variant duplicates of strictKey
        val toRemove = updated.keys.filter { it.equals(strictKey, ignoreCase = true) }
        for (k in toRemove) updated.remove(k)
        updated[strictKey] = strictValue
        // On Google Search, `ssui=on` locks SafeSearch across ALL signed-in Google accounts
        if (providerId == "google") {
            updated["ssui"] = "on"
        }

        val queryEncoded = updated.entries.joinToString("&") { (k, v) ->
            val ek = runCatching { URLEncoder.encode(k, "UTF-8") }.getOrDefault(k)
            val ev = runCatching { URLEncoder.encode(v, "UTF-8") }.getOrDefault(v)
            "$ek=$ev"
        }
        val cleanHost = rawHostPart.ifBlank { "www.google.com" }
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "https://$cleanHost$cleanPath?$queryEncoded"
    }
}
