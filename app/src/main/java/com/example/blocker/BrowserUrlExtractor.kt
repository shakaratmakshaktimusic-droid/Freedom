package com.example.blocker

import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.DomainBlockMatcher

/**
 * Zero-VPN browser, Chrome Custom Tab, and embedded in-app WebView extractor.
 *
 * Capabilities:
 *  - Recognizes 20+ Android browsers and Chrome Custom Tab / WebView activities.
 *  - Extracts both normalized hostname and normalized host+path URL (for Website Allowlist path rules).
 *  - Detects embedded android.webkit.WebView surfaces so Full Internet Browsing Block
 *    catches links clicked inside other apps without requiring a VPN.
 *  - Collects a strictly bounded (~40 short text snippets) page-header sample ONLY when
 *    UnknownSiteClassifier needs to score a newly navigated unknown domain.
 */
object BrowserUrlExtractor {

    data class WebInspectionResult(
        val extractedHost: String? = null,
        val extractedFullUrl: String? = null,
        val rawUrlWithQuery: String? = null, // Kept strictly in RAM only (never persisted or logged)
        val isKnownBrowser: Boolean = false,
        val isCustomTabOrWebView: Boolean = false,
        val isIncognitoOrPrivateMode: Boolean = false
    )

    data class VisibleCardCandidate(
        val bounds: android.graphics.Rect,
        val snippets: List<String>
    )

    private val BROWSER_URL_BAR_IDS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "com.chrome.canary" to listOf("com.chrome.canary:id/url_bar"),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
        "org.mozilla.firefox" to listOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title"
        ),
        "org.mozilla.firefox_beta" to listOf("org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.fenix" to listOf("org.mozilla.fenix:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.focus" to listOf("org.mozilla.focus:id/display_url"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
        "com.opera.gx" to listOf("com.opera.gx:id/url_field"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
        "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
        "com.ecosia.android" to listOf("com.ecosia.android:id/url_bar"),
        "com.yandex.browser" to listOf("com.yandex.browser:id/bro_omnibar_address_title_text"),
        "com.UCMobile.intl" to listOf("com.UCMobile.intl:id/url_text"),
        "com.mi.globalbrowser" to listOf("com.mi.globalbrowser:id/url_tv", "com.mi.globalbrowser:id/search_hint"),
        "com.android.browser" to listOf("com.android.browser:id/url"),
        "mark.via.gp" to listOf("mark.via.gp:id/url_bar"),
        "org.torproject.torbrowser" to listOf("org.torproject.torbrowser:id/mozac_browser_toolbar_url_view"),
        "com.vivo.browser" to listOf("com.vivo.browser:id/url_bar", "com.vivo.browser:id/url_text", "com.vivo.browser:id/search_input"),
        "com.heytap.browser" to listOf("com.heytap.browser:id/url_bar", "com.heytap.browser:id/url_input"),
        "com.coloros.browser" to listOf("com.coloros.browser:id/url_bar", "com.coloros.browser:id/url_input"),
        "org.cromite.cromite" to listOf("org.cromite.cromite:id/url_bar"),
        "com.alohamobile.browser" to listOf("com.alohamobile.browser:id/url_bar", "com.alohamobile.browser:id/address_bar"),
        "com.mycompany.app.soulbrowser" to listOf("com.mycompany.app.soulbrowser:id/url_bar", "com.mycompany.app.soulbrowser:id/address"),
        "com.transsion.phoenix" to listOf("com.transsion.phoenix:id/url_bar", "com.transsion.phoenix:id/search_box")
    )

    val KNOWN_BROWSER_PACKAGES: Set<String> = BROWSER_URL_BAR_IDS.keys

    private val GENERIC_URL_VIEW_ID_SUFFIXES = listOf(
        "url_bar", "url_field", "location_bar", "address_bar", "omnibar",
        "url_view", "display_url", "toolbar_url", "webview_url", "title_url"
    )

    private val RAW_URL_REGEX = Regex(
        pattern = "(?:https?://)?[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+(?::\\d+)?(?:/[^\\s]*)?",
        option = RegexOption.IGNORE_CASE
    )

    fun isBrowserPackage(packageName: String): Boolean = packageName in KNOWN_BROWSER_PACKAGES

    fun inspectWindow(
        root: AccessibilityNodeInfo?,
        packageName: String,
        windowClassName: String?
    ): WebInspectionResult {
        val isKnownBrowser = isBrowserPackage(packageName)
        val lowerClass = windowClassName?.lowercase().orEmpty()
        val classLooksLikeWebContainer =
            lowerClass.contains("customtab") ||
                lowerClass.contains("webview") ||
                lowerClass.contains("inappbrowser") ||
                lowerClass.contains("browseractivity") ||
                lowerClass.contains("linkpreview")

        if (root == null) {
            return WebInspectionResult(
                isKnownBrowser = isKnownBrowser,
                isCustomTabOrWebView = classLooksLikeWebContainer
            )
        }

        val isIncognito = detectIncognitoOrPrivateMode(root)

        // 1. Fast path: known browser URL bar IDs
        val knownIds = BROWSER_URL_BAR_IDS[packageName]
        if (knownIds != null) {
            for (viewId in knownIds) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull()
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val rawCandidate = node.text?.toString() ?: node.contentDescription?.toString()
                        val extracted = DomainBlockMatcher.extractUrlFromUiText(rawCandidate)
                        if (extracted != null) {
                            return WebInspectionResult(
                                extractedHost = extracted.host,
                                extractedFullUrl = extracted.fullNormalizedUrl,
                                rawUrlWithQuery = extractRawUrlWithQuery(rawCandidate),
                                isKnownBrowser = true,
                                isCustomTabOrWebView = classLooksLikeWebContainer,
                                isIncognitoOrPrivateMode = isIncognito
                            )
                        }
                    }
                }
            }
        }

        // 2. Bounded shallow scan for Chrome Custom Tabs, generic browsers, & embedded WebViews
        var hasWebViewNode = classLooksLikeWebContainer
        var foundUrl: DomainBlockMatcher.ExtractedUrl? = null
        var foundRawUrl: String? = null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < 64) {
            val (node, depth) = queue.removeFirst()
            visited++

            val cls = node.className?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()

            if (cls.contains("WebView", ignoreCase = true)) {
                hasWebViewNode = true
            }

            if (foundUrl == null) {
                val isUrlBarCandidate = GENERIC_URL_VIEW_ID_SUFFIXES.any { viewId.contains(it) }
                if (isUrlBarCandidate || hasWebViewNode || isKnownBrowser) {
                    val txt = node.text?.toString()
                    val desc = node.contentDescription?.toString()
                    val extracted = DomainBlockMatcher.extractUrlFromUiText(txt)
                        ?: DomainBlockMatcher.extractUrlFromUiText(desc)
                    if (extracted != null) {
                        foundUrl = extracted
                        foundRawUrl = extractRawUrlWithQuery(txt) ?: extractRawUrlWithQuery(desc)
                    }
                }
            }

            if (foundUrl != null && (hasWebViewNode || isKnownBrowser)) {
                break
            }

            if (depth < 6) {
                val childCount = node.childCount.coerceAtMost(14)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        return WebInspectionResult(
            extractedHost = foundUrl?.host,
            extractedFullUrl = foundUrl?.fullNormalizedUrl,
            rawUrlWithQuery = foundRawUrl,
            isKnownBrowser = isKnownBrowser,
            isCustomTabOrWebView = hasWebViewNode,
            isIncognitoOrPrivateMode = isIncognito
        )
    }

    /**
     * Lightweight toolbar-only check for Incognito / Private browsing mode across Chrome,
     * Brave, Edge, Firefox, Samsung Internet, and Opera so Freedom NEVER fires an external
     * Intent that would kick an Incognito tab into Normal mode.
     */
    private fun detectIncognitoOrPrivateMode(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 36) {
            val (node, depth) = queue.removeFirst()
            visited++
            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()

            if (resId.contains("incognito") ||
                resId.contains("private_browsing") ||
                resId.contains("secret_mode") ||
                desc.contains("incognito") ||
                desc.contains("private tab") ||
                desc.contains("secret mode") ||
                text == "incognito" ||
                text.startsWith("you've gone incognito") ||
                text.startsWith("you’ve gone incognito")
            ) {
                return true
            }
            if (depth < 4) {
                for (i in 0 until node.childCount.coerceAtMost(10)) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return false
    }

    /**
     * Extracts raw URL preserving query parameters in memory (e.g. `safe=active`, `tbm=isch`, `v=...`).
     */
    fun extractRawUrlWithQuery(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        return RAW_URL_REGEX.find(rawText.trim())?.value
    }

    private val SHORT_IMPORTANT_TOKENS = setOf("r", "a", "x", "18", "18+", "nc-17", "tv-ma")

    /**
     * Collects a strictly bounded sample (max 48 short snippets) of visible text nodes
     * for UnknownSiteClassifier and InAppAdultFilterEngine.
     * Reaches deep enough into Chrome/browser WebView DOM hierarchies (depth <= 16)
     * while preserving short maturity badges ("R", "A", "18+") and truncating long paragraphs.
     */
    fun collectBoundedPageSnippets(root: AccessibilityNodeInfo?, maxSnippets: Int = 48): List<String> {
        if (root == null) return emptyList()
        val snippets = ArrayList<String>(maxSnippets)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && snippets.size < maxSnippets && visited < 220) {
            val (node, depth) = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && (text.length >= 2 || text.lowercase() in SHORT_IMPORTANT_TOKENS)) {
                snippets.add(text.take(240))
            } else {
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty() && (desc.length >= 2 || desc.lowercase() in SHORT_IMPORTANT_TOKENS)) {
                    snippets.add(desc.take(240))
                }
            }

            if (depth < 16 && snippets.size < maxSnippets) {
                val childCount = node.childCount.coerceAtMost(16)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return snippets
    }

    /**
     * Collects bounded visible clickable/card items with screen bounds and text/alt-labels
     * inside browsers, Google Search, Google Images, or inspectable apps so flagged
     * adult previews can be covered in-place before interaction.
     */
    fun collectBoundedVisibleCards(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        maxCards: Int = 18
    ): List<VisibleCardCandidate> {
        if (root == null || screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val out = ArrayList<VisibleCardCandidate>(maxCards)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        val rect = android.graphics.Rect()
        val minW = (screenWidth * 0.22f).toInt()
        val minH = (screenHeight * 0.06f).toInt()
        val maxH = (screenHeight * 0.62f).toInt()

        while (queue.isNotEmpty() && out.size < maxCards && visited < 110) {
            val (node, depth) = queue.removeFirst()
            visited++

            val isCandidateContainer = (node.isClickable || node.className?.toString()?.contains("Image", true) == true) &&
                depth >= 2
            if (isCandidateContainer) {
                node.getBoundsInScreen(rect)
                val onScreen = rect.bottom > 80 && rect.top < screenHeight - 40 &&
                    rect.width() >= minW && rect.height() in minH..maxH
                if (onScreen) {
                    val snippets = collectSmallNodeText(node, limit = 6)
                    if (snippets.isNotEmpty()) {
                        out.add(VisibleCardCandidate(android.graphics.Rect(rect), snippets))
                        continue
                    }
                }
            }

            if (depth < 8 && out.size < maxCards) {
                val count = node.childCount.coerceAtMost(10)
                for (i in 0 until count) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return out
    }

    private fun collectSmallNodeText(node: AccessibilityNodeInfo, limit: Int): List<String> {
        val result = ArrayList<String>(limit)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var scanned = 0
        while (stack.isNotEmpty() && result.size < limit && scanned < 18) {
            val curr = stack.removeFirst()
            scanned++
            curr.text?.toString()?.trim()?.takeIf { it.length in 2..160 }?.let { result.add(it) }
            curr.contentDescription?.toString()?.trim()?.takeIf { it.length in 2..160 }?.let { result.add(it) }
            for (i in 0 until curr.childCount.coerceAtMost(6)) {
                curr.getChild(i)?.let { stack.add(it) }
            }
        }
        return result
    }
}
