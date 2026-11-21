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
        val isIncognitoOrPrivateMode: Boolean = false,
        val isBrowserHomeOrHistoryScreen: Boolean = false
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
        var hasBrowserChromePageMarker = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < 96) {
            val (node, depth) = queue.removeFirst()
            visited++

            val cls = node.className?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()

            if (cls.contains("WebView", ignoreCase = true) ||
                viewId.contains("webview") ||
                viewId.contains("compositor_view_holder")
            ) {
                hasWebViewNode = true
            }

            if (isKnownBrowser && !hasBrowserChromePageMarker) {
                val lowerText = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val lowerDesc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                if (viewId.contains("ntp_content") ||
                    viewId.contains("new_tab_page") ||
                    viewId.contains("history_page") ||
                    viewId.contains("bookmarks_page") ||
                    viewId.contains("downloads_page") ||
                    lowerText == "new tab" ||
                    lowerText == "history" ||
                    lowerText == "bookmarks" ||
                    lowerText == "downloads" ||
                    lowerDesc == "new tab"
                ) {
                    hasBrowserChromePageMarker = true
                }
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

            if (depth < 8) {
                val childCount = node.childCount.coerceAtMost(14)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        val isBrowserHomeOrHistory = isKnownBrowser &&
            foundUrl == null &&
            hasBrowserChromePageMarker

        return WebInspectionResult(
            extractedHost = foundUrl?.host,
            extractedFullUrl = foundUrl?.fullNormalizedUrl,
            rawUrlWithQuery = foundRawUrl,
            isKnownBrowser = isKnownBrowser,
            isCustomTabOrWebView = hasWebViewNode,
            isIncognitoOrPrivateMode = isIncognito,
            isBrowserHomeOrHistoryScreen = isBrowserHomeOrHistory
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

    private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")

    data class CombinedContentEvidence(
        val pageSnippets: List<String>,
        val visibleCardSnippets: List<String>,
        val mergedEvidence: List<String>,
        val visibleCardCount: Int
    )

    /**
     * Pure-Kotlin evidence combiner & case-insensitive deduplicator.
     * Preserves page-text priority first, appends distinct visible-card/thumbnail labels,
     * and enforces a strict upper bound so downstream classifiers remain <2 ms.
     */
    fun mergeAndDeduplicateEvidence(
        pageSnippets: List<String>,
        cardSnippets: List<String>,
        maxTotal: Int = 64
    ): List<String> {
        if (maxTotal <= 0) return emptyList()
        val seen = HashSet<String>(maxTotal * 2)
        val out = ArrayList<String>(maxTotal.coerceAtMost(pageSnippets.size + cardSnippets.size))

        fun addCandidate(raw: String) {
            if (out.size >= maxTotal) return
            val cleaned = raw.trim().take(240)
            if (cleaned.isEmpty()) return
            val lower = cleaned.lowercase()
            if (cleaned.length < 2 && lower !in SHORT_IMPORTANT_TOKENS) return
            val dedupKey = WHITESPACE_COLLAPSE_REGEX.replace(lower, " ")
            if (seen.add(dedupKey)) {
                out.add(cleaned)
            }
        }

        for (s in pageSnippets) {
            addCandidate(s)
            if (out.size >= maxTotal) return out
        }
        for (s in cardSnippets) {
            addCandidate(s)
            if (out.size >= maxTotal) return out
        }
        return out
    }

    /**
     * Single-pass, strictly bounded Universal Content Detection collector.
     *
     * Combines page text/descriptions with visible visual cards, thumbnails, and recommendation
     * labels in ONE bounded BFS traversal (avoiding redundant subtree rescans) using the
     * **actual Android display dimensions** (never inferred from AccessibilityNodeInfo root bounds).
     */
    fun collectCombinedContentEvidence(
        root: AccessibilityNodeInfo?,
        displayWidth: Int,
        displayHeight: Int,
        maxPageSnippets: Int = 44,
        maxCards: Int = 18,
        maxTotalEvidence: Int = 64
    ): CombinedContentEvidence {
        if (root == null) {
            return CombinedContentEvidence(emptyList(), emptyList(), emptyList(), 0)
        }
        val hasValidDisplay = displayWidth > 0 && displayHeight > 0
        val pageSnippets = ArrayList<String>(maxPageSnippets)
        val cardSnippets = ArrayList<String>(maxCards * 3)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var cardCount = 0

        val rect = android.graphics.Rect()
        val minW = if (hasValidDisplay) (displayWidth * 0.18f).toInt() else Int.MAX_VALUE
        val minH = if (hasValidDisplay) (displayHeight * 0.05f).toInt() else Int.MAX_VALUE
        val maxH = if (hasValidDisplay) (displayHeight * 0.65f).toInt() else 0
        val minTopVis = if (hasValidDisplay) (displayHeight * 0.04f).toInt() else 0
        val maxBottomVis = if (hasValidDisplay) (displayHeight * 0.96f).toInt() else 0

        while (queue.isNotEmpty() && visited < 240 &&
            (pageSnippets.size < maxPageSnippets || cardCount < maxCards)
        ) {
            val (node, depth) = queue.removeFirst()
            visited++

            val cls = node.className?.toString().orEmpty()
            val isImageOrThumbnail = cls.contains("Image", ignoreCase = true) ||
                node.viewIdResourceName?.lowercase()?.let { id ->
                    id.contains("thumb") || id.contains("poster") || id.contains("cover") || id.contains("card")
                } == true

            // Check whether this node is a visible visual card / thumbnail container inside actual display bounds.
            // When harvested as a card, collect its bounded subtree labels once and DO NOT re-enqueue
            // its children into the outer BFS queue (prevents scanning the same subtree twice).
            val isCardCandidate = hasValidDisplay &&
                cardCount < maxCards &&
                depth in 2..12 &&
                (node.isClickable || isImageOrThumbnail)

            if (isCardCandidate) {
                node.getBoundsInScreen(rect)
                val onScreen = rect.bottom > minTopVis &&
                    rect.top < maxBottomVis &&
                    rect.width() >= minW &&
                    rect.height() in minH..maxH
                if (onScreen) {
                    val labels = collectSmallNodeText(node, limit = 6)
                    if (labels.isNotEmpty()) {
                        cardCount++
                        cardSnippets.addAll(labels)
                        continue
                    }
                }
            }

            if (pageSnippets.size < maxPageSnippets) {
                extractTextAndDescription(node, pageSnippets, maxPageSnippets)
            }

            if (depth < 16) {
                val childCount = node.childCount.coerceAtMost(16)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        val merged = mergeAndDeduplicateEvidence(
            pageSnippets = pageSnippets,
            cardSnippets = cardSnippets,
            maxTotal = maxTotalEvidence
        )
        return CombinedContentEvidence(
            pageSnippets = pageSnippets,
            visibleCardSnippets = cardSnippets,
            mergedEvidence = merged,
            visibleCardCount = cardCount
        )
    }

    /**
     * Collects a strictly bounded sample (max 48 short snippets) of visible text and
     * contentDescription nodes for UnknownSiteClassifier and InAppAdultFilterEngine.
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

            extractTextAndDescription(node, snippets, maxSnippets)

            if (depth < 16 && snippets.size < maxSnippets) {
                val childCount = node.childCount.coerceAtMost(16)
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return mergeAndDeduplicateEvidence(snippets, emptyList(), maxTotal = maxSnippets)
    }

    /**
     * Collects bounded visible clickable/card items with screen bounds and text/alt-labels
     * inside browsers, Google Search, Google Images, or inspectable apps using actual
     * Android display dimensions (never inferred from partial window root bounds).
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

    private fun extractTextAndDescription(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        limit: Int
    ) {
        if (out.size >= limit) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && (text.length >= 2 || text.lowercase() in SHORT_IMPORTANT_TOKENS)) {
            out.add(text.take(240))
        }
        if (out.size >= limit) return
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() &&
            !desc.equals(text, ignoreCase = true) &&
            (desc.length >= 2 || desc.lowercase() in SHORT_IMPORTANT_TOKENS)
        ) {
            out.add(desc.take(240))
        }
    }

    private fun collectSmallNodeText(node: AccessibilityNodeInfo, limit: Int): List<String> {
        val result = ArrayList<String>(limit)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var scanned = 0
        while (stack.isNotEmpty() && result.size < limit && scanned < 18) {
            val curr = stack.removeFirst()
            scanned++
            extractTextAndDescription(curr, result, limit)
            for (i in 0 until curr.childCount.coerceAtMost(6)) {
                curr.getChild(i)?.let { stack.add(it) }
            }
        }
        return result
    }
}
