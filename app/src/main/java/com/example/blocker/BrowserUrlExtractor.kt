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
        val isKnownBrowser: Boolean = false,
        val isCustomTabOrWebView: Boolean = false
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
        "org.torproject.torbrowser" to listOf("org.torproject.torbrowser:id/mozac_browser_toolbar_url_view")
    )

    val KNOWN_BROWSER_PACKAGES: Set<String> = BROWSER_URL_BAR_IDS.keys

    private val GENERIC_URL_VIEW_ID_SUFFIXES = listOf(
        "url_bar", "url_field", "location_bar", "address_bar", "omnibar",
        "url_view", "display_url", "toolbar_url", "webview_url", "title_url"
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

        // 1. Fast path: known browser URL bar IDs
        val knownIds = BROWSER_URL_BAR_IDS[packageName]
        if (knownIds != null) {
            for (viewId in knownIds) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull()
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val extracted = DomainBlockMatcher.extractUrlFromUiText(node.text?.toString())
                            ?: DomainBlockMatcher.extractUrlFromUiText(node.contentDescription?.toString())
                        if (extracted != null) {
                            return WebInspectionResult(
                                extractedHost = extracted.host,
                                extractedFullUrl = extracted.fullNormalizedUrl,
                                isKnownBrowser = true,
                                isCustomTabOrWebView = classLooksLikeWebContainer
                            )
                        }
                    }
                }
            }
        }

        // 2. Bounded shallow scan for Chrome Custom Tabs, generic browsers, & embedded WebViews
        var hasWebViewNode = classLooksLikeWebContainer
        var foundUrl: DomainBlockMatcher.ExtractedUrl? = null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < 48) {
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
                    }
                }
            }

            if (foundUrl != null && (hasWebViewNode || isKnownBrowser)) {
                break
            }

            if (depth < 5) {
                val childCount = node.childCount.coerceAtMost(14)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        return WebInspectionResult(
            extractedHost = foundUrl?.host,
            extractedFullUrl = foundUrl?.fullNormalizedUrl,
            isKnownBrowser = isKnownBrowser,
            isCustomTabOrWebView = hasWebViewNode
        )
    }

    /**
     * Collects a strictly bounded sample (max 40 short snippets) of visible text nodes
     * for UnknownSiteClassifier. Called ONLY once per newly navigated unknown domain.
     */
    fun collectBoundedPageSnippets(root: AccessibilityNodeInfo?, maxSnippets: Int = 40): List<String> {
        if (root == null) return emptyList()
        val snippets = ArrayList<String>(maxSnippets)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && snippets.size < maxSnippets && visited < 90) {
            val (node, depth) = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length in 3..140) {
                snippets.add(text)
            } else {
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty() && desc.length in 3..140) {
                    snippets.add(desc)
                }
            }

            if (depth < 7 && snippets.size < maxSnippets) {
                val childCount = node.childCount.coerceAtMost(12)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return snippets
    }
}
