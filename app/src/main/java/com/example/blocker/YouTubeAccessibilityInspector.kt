package com.example.blocker

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.YouTubeClientFamily
import com.example.domain.YouTubeClientProfile
import com.example.domain.YouTubeStudyEngine

/**
 * Ultra-lightweight, bounded single-pass Accessibility tree inspector & in-app
 * playback cutter for:
 *  - Official YouTube
 *  - Morphe YT
 *  - YouTube ReVanced / RVX / patched YouTube clients
 *  - Supported third-party clients (NewPipe, Tubular, PipePipe, LibreTube)
 *
 * Cuts unapproved videos cleanly INSIDE YouTube (Watch collapse + bottom miniplayer ×
 * close, Shorts -> Home tab) instead of evicting the YouTube app to Home (which would
 * trigger Android Picture-in-Picture and let the video keep playing).
 */
object YouTubeAccessibilityInspector {

    data class YouTubeWindowSnapshot(
        val screenMode: YouTubeStudyEngine.ScreenMode,
        val activeChannelId: String? = null,
        val activeVideoId: String? = null,
        val activeChannelTitle: String? = null,
        val activeChannelHandle: String? = null,
        val activeVideoTitle: String? = null,
        val miniPlayerVisible: Boolean = false,
        val miniPlayerSubtitle: String? = null,
        val miniPlayerBounds: Rect? = null,
        val isSearchResultsScreen: Boolean = false,
        val isPlaylistScreen: Boolean = false,
        val isWatchHistoryOrProfileScreen: Boolean = false,
        val activeSearchQuery: String? = null,
        val allVisibleSnippets: List<String> = emptyList(),
        val visibleCardSnippets: List<String> = emptyList()
    )

    private val EMBEDDED_YT_URL_REGEX = Regex(
        pattern = "(?:https?://)?(?:(?:www|m|music)\\.)?(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/[^\\s\"'<>]+",
        option = RegexOption.IGNORE_CASE
    )
    private val CANONICAL_CHANNEL_URL_ID_REGEX = Regex("youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})", RegexOption.IGNORE_CASE)

    /**
     * Extracts ONLY canonical YouTube video IDs and permanent `UC...` channel IDs from
     * accessible URLs or canonical metadata strings.
     * NEVER guesses a channel ID from a display name, video title, or `@handle`.
     */
    fun extractCanonicalIdsFromText(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        val trimmed = raw.trim()

        // Direct exact permanent UC... ID (only when the entire token is a canonical 24-char UC... ID)
        if (YouTubeStudyEngine.isValidPermanentChannelId(trimmed)) {
            return null to trimmed
        }

        var foundVideoId: String? = null
        var foundChannelId: String? = null

        CANONICAL_CHANNEL_URL_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { cid ->
            if (YouTubeStudyEngine.isValidPermanentChannelId(cid)) {
                foundChannelId = cid
            }
        }

        EMBEDDED_YT_URL_REGEX.findAll(trimmed).forEach { match ->
            val parsed = YouTubeStudyEngine.parseYouTubeUrl(match.value) ?: return@forEach
            if (foundVideoId == null && !parsed.videoId.isNullOrBlank()) {
                foundVideoId = parsed.videoId
            }
            if (foundChannelId == null && !parsed.channelId.isNullOrBlank()) {
                foundChannelId = parsed.channelId
            }
        }

        return foundVideoId to foundChannelId
    }

    // View ID fragments shared across Official YouTube, Morphe YT, and YouTube ReVanced/RVX
    private val SHORTS_ID_TOKENS = listOf(
        "reel_watch", "reel_player", "reel_recycler", "shorts_player", "reel_channel",
        "reel_pivot", "reel_dyn_remix"
    )
    private val WATCH_PLAYER_ID_TOKENS = listOf(
        "watch_player", "player_view", "next_gen_watch", "watch_while_time_bar",
        "watch_panel", "player_fragment_container", "video_player", "detail_root"
    )
    private val MINIPLAYER_ID_TOKENS = listOf(
        "miniplayer", "mini_player", "floaty_bar", "floaty_close", "watch_while_miniplayer",
        "modern_miniplayer"
    )
    private val MINIPLAYER_CLOSE_ID_TOKENS = listOf(
        "miniplayer_close", "floaty_close", "modern_miniplayer_close", "close_miniplayer"
    )
    private val CHANNEL_HEADER_ID_TOKENS = listOf(
        "channel_name", "owner_text", "channel_title", "detail_uploader_text_view",
        "uploader_name", "author_name", "reel_channel_bar", "channel_handle"
    )
    private val VIDEO_TITLE_ID_TOKENS = listOf(
        "video_title", "title_text", "detail_video_title_view", "player_video_title"
    )
    private val SEARCH_BAR_ID_TOKENS = listOf(
        "search_edit_text", "search_query", "search_bar_text", "search_box", "toolbar_search"
    )
    private val LIVE_PLAYER_ID_TOKENS = listOf(
        "live_chat_container", "live_chat_header", "live_chat_message",
        "live_chat_text", "time_bar_live", "live_chat_overlay"
    )
    private val NON_SEARCH_TOP_LABELS = setOf(
        "youtube", "search youtube", "search", "search history", "history", "watch history",
        "subscriptions", "library", "home", "shorts", "you", "profile", "notifications",
        "all", "new to you", "videos", "live", "podcasts", "music", "playlists", "watch later",
        "downloads", "settings", "cast", "morphe", "revanced"
    )

    /**
     * Pure helper identifying active YouTube Live player / Live Chat UI indicators.
     * Deliberately excludes the plain "Live" feed filter chip so Home/Search feeds
     * are NEVER misclassified as an active LIVE_PLAYER.
     */
    fun isLivePlayerIndicator(resId: String, text: String, desc: String): Boolean {
        val r = resId.lowercase()
        val t = text.trim().lowercase()
        val d = desc.trim().lowercase()
        if (LIVE_PLAYER_ID_TOKENS.any { r.contains(it) }) return true
        if (t == "live chat" || t == "top chat" || t == "chat replay" ||
            t == "live chat replay" || t == "hide live chat" || t == "show live chat" ||
            d == "live chat" || d == "top chat" || d == "chat replay" ||
            d.contains("live chat") || d.contains("top chat") || d.contains("chat replay")
        ) {
            return true
        }
        if (t.contains("watching now") || d.contains("watching now") ||
            t.contains("started streaming") || d.contains("started streaming")
        ) {
            return true
        }
        return false
    }

    fun inspect(
        root: AccessibilityNodeInfo?,
        client: YouTubeClientProfile,
        screenWidth: Int,
        screenHeight: Int
    ): YouTubeWindowSnapshot {
        if (root == null) {
            return YouTubeWindowSnapshot(screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH)
        }

        var hasShortsContainer = false
        var hasWatchPlayer = false
        var hasMiniPlayer = false
        var hasLiveBadge = false
        var hasLivePlayerControl = false
        var activeChannelId: String? = null
        var activeVideoId: String? = null
        var activeChannelTitle: String? = null
        var activeChannelHandle: String? = null
        var activeVideoTitle: String? = null
        var miniPlayerSubtitle: String? = null
        var miniPlayerBounds: Rect? = null
        var activeSearchQuery: String? = null
        var hasTopSearchActionIndicator = false
        var topToolbarQueryCandidate: String? = null
        var hasPlaylistIndicator = false
        var hasWatchHistoryOrProfileIndicator = false
        val snippets = ArrayList<String>(48)
        val visibleCardSnippets = ArrayList<String>(32)

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        val bounds = Rect()

        val watchHeaderMaxY = (screenHeight * 0.68f).toInt()
        val miniPlayerMinY = (screenHeight * 0.58f).toInt()
        val miniPlayerMaxY = (screenHeight * 0.99f).toInt()
        val searchTopBarMaxY = (screenHeight * 0.16f).toInt()
        val bottomDockMinY = (screenHeight * 0.86f).toInt()
        val cardMinWidth = (screenWidth * 0.24f).toInt()
        val cardMinHeight = (screenHeight * 0.05f).toInt()
        val cardMaxHeight = (screenHeight * 0.55f).toInt()

        while (queue.isNotEmpty() && visited < 210) {
            val (node, depth) = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val cls = node.className?.toString().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val lowerDesc = desc.lowercase()
            val lowerText = text.lowercase()

            if (snippets.size < 48) {
                if (text.length in 2..180) snippets.add(text)
                if (snippets.size < 48 && desc.length in 2..180 && !desc.equals(text, ignoreCase = true)) {
                    snippets.add(desc)
                }
            }

            // Detect Watch History / Library / You / Profile screens explicitly so Morphe YT
            // Watch History or custom default profile tabs NEVER fire Back!
            if (!hasWatchHistoryOrProfileIndicator) {
                val isHistoryOrProfileLabel = lowerText == "history" ||
                    lowerText == "watch history" ||
                    lowerText == "library" ||
                    lowerText == "watch later" ||
                    lowerText == "downloads" ||
                    lowerDesc == "history" ||
                    lowerDesc == "watch history"
                val isSelectedProfileTab = node.isSelected && (
                    lowerText == "you" || lowerText == "library" || lowerText == "history" ||
                        lowerDesc == "you" || lowerDesc.startsWith("you,") ||
                        lowerDesc == "library" || lowerDesc.startsWith("library,") ||
                        lowerDesc == "history" || lowerDesc.startsWith("history,")
                    )
                if (isSelectedProfileTab) {
                    hasWatchHistoryOrProfileIndicator = true
                } else if (isHistoryOrProfileLabel) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top < (screenHeight * 0.22f).toInt()) {
                        hasWatchHistoryOrProfileIndicator = true
                    }
                }
            }

            // Detect Playlist screens (e.g., "Nude Art" playlist with Play all / Shuffle controls)
            if (!hasPlaylistIndicator) {
                val isPlaylistCue = resId.contains("playlist_header") ||
                    resId.contains("playlist_info") ||
                    lowerText == "play all" ||
                    lowerDesc == "play all" ||
                    lowerDesc.contains("save playlist") ||
                    lowerDesc.contains("playlist,") ||
                    lowerText.startsWith("playlist ·") ||
                    lowerText.startsWith("mix -")
                if (isPlaylistCue) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top < (screenHeight * 0.62f).toInt()) {
                        hasPlaylistIndicator = true
                    }
                }
            }

            // Harvest visible feed / search / playlist / recommendation card labels & thumbnail descriptions
            if (visibleCardSnippets.size < 32 && depth in 2..10 && (node.isClickable || cls.contains("Image", true))) {
                val hasCardLabel = (desc.length in 6..220 && !lowerDesc.startsWith("go to channel")) ||
                    (text.length in 6..220 && lowerText !in NON_SEARCH_TOP_LABELS)
                if (hasCardLabel) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top >= searchTopBarMaxY &&
                        bounds.bottom <= bottomDockMinY &&
                        bounds.width() >= cardMinWidth &&
                        bounds.height() in cardMinHeight..cardMaxHeight
                    ) {
                        if (text.length in 6..220 && lowerText !in NON_SEARCH_TOP_LABELS) {
                            visibleCardSnippets.add(text)
                        }
                        if (visibleCardSnippets.size < 32 &&
                            desc.length in 6..220 &&
                            !desc.equals(text, ignoreCase = true)
                        ) {
                            visibleCardSnippets.add(desc)
                        }
                    }
                }
            }

            // 0. Detect active YouTube Search Results bar (strictly distinct from Watch History / Profile!)
            if (activeSearchQuery == null && SEARCH_BAR_ID_TOKENS.any { resId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < searchTopBarMaxY &&
                    text.length in 2..140 &&
                    lowerText !in NON_SEARCH_TOP_LABELS
                ) {
                    activeSearchQuery = text
                }
            }
            if (!hasTopSearchActionIndicator && (
                    lowerDesc == "clear" ||
                        lowerDesc == "clear search" ||
                        lowerDesc == "clear query" ||
                        lowerDesc.contains("search filter") ||
                        resId.contains("search_clear") ||
                        resId.contains("search_edit_text")
                    )
            ) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < searchTopBarMaxY) {
                    hasTopSearchActionIndicator = true
                }
            }
            if (topToolbarQueryCandidate == null && text.length in 2..140 && lowerText !in NON_SEARCH_TOP_LABELS) {
                node.getBoundsInScreen(bounds)
                if (bounds.top in 0 until searchTopBarMaxY && bounds.height() in 16..(screenHeight * 0.10f).toInt()) {
                    topToolbarQueryCandidate = text
                }
            }

            // 1. Detect Shorts player container
            if (!hasShortsContainer && SHORTS_ID_TOKENS.any { resId.contains(it) }) {
                hasShortsContainer = true
            }
            if (!hasShortsContainer && lowerDesc.contains("shorts player")) {
                hasShortsContainer = true
            }

            // 2. Detect Watch player vs. bottom in-app Miniplayer (3 independent signals so fast
            //    swipe-down before buffering finishes can NEVER bypass Study Mode!)
            val looksLikeMiniPlayerNode = MINIPLAYER_ID_TOKENS.any { resId.contains(it) } ||
                lowerDesc.contains("close miniplayer") ||
                lowerDesc.contains("dismiss miniplayer") ||
                lowerDesc.contains("expand miniplayer") ||
                lowerDesc == "close player" ||
                lowerDesc == "close video"

            if (looksLikeMiniPlayerNode) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() in 16..(screenHeight * 0.28f).toInt() &&
                    bounds.top >= miniPlayerMinY &&
                    bounds.bottom <= miniPlayerMaxY
                ) {
                    hasMiniPlayer = true
                    val existingMini = miniPlayerBounds
                    if (existingMini == null || bounds.width() > existingMini.width()) {
                        miniPlayerBounds = Rect(bounds)
                    }
                    if (miniPlayerSubtitle == null) {
                        extractMiniPlayerChannelText(node)?.let { miniPlayerSubtitle = it }
                    }
                }
            }

            // Signal 2b: Any "Pause video" / "Play video" / "Pause" / "Play" / "Replay video" control
            // sitting inside the bottom miniplayer band (right half) when not fullscreen Watch!
            val isSpecificPlayPause = lowerDesc == "pause video" || lowerDesc == "play video" ||
                lowerDesc == "replay video" ||
                resId.contains("miniplayer_play") || resId.contains("floaty")
            val isGenericPlayPause = lowerDesc == "pause" || lowerDesc == "play"

            if (!hasMiniPlayer && (isSpecificPlayPause || isGenericPlayPause)) {
                node.getBoundsInScreen(bounds)
                val rightHalfOrSpecific = isSpecificPlayPause || bounds.left >= (screenWidth * 0.45f).toInt()
                if (rightHalfOrSpecific &&
                    bounds.top >= miniPlayerMinY &&
                    bounds.bottom <= miniPlayerMaxY &&
                    bounds.height() in 16..(screenHeight * 0.16f).toInt()
                ) {
                    hasMiniPlayer = true
                    if (miniPlayerBounds == null) {
                        miniPlayerBounds = Rect(0, bounds.top, screenWidth, bounds.bottom)
                    }
                }
            }

            // Signal 2c: Fast swipe-down BEFORE video buffering finishes!
            // While buffering in the collapsed bottom miniplayer, YouTube / Morphe YT renders
            // a Close ("×") icon or loading ProgressBar/timebar in the bottom strip directly above
            // the navigation dock BEFORE "Pause video" / "Play video" even appears.
            if (!hasMiniPlayer) {
                val isMiniCloseCandidate = lowerDesc == "close" ||
                    lowerDesc == "dismiss" ||
                    resId.endsWith(":id/close_button") ||
                    resId.contains("miniplayer_close") ||
                    resId.contains("floaty_close")
                val isMiniBufferingSpinner = cls.contains("ProgressBar", ignoreCase = true) ||
                    resId.contains("player_loading") ||
                    resId.contains("miniplayer_progress") ||
                    resId.contains("watch_while_time_bar")
                if (isMiniCloseCandidate || isMiniBufferingSpinner) {
                    node.getBoundsInScreen(bounds)
                    val inMiniStripY = bounds.top >= (screenHeight * 0.70f).toInt() &&
                        bounds.bottom <= (screenHeight * 0.94f).toInt()
                    val validCloseShape = isMiniCloseCandidate &&
                        bounds.left >= (screenWidth * 0.72f).toInt() &&
                        bounds.width() in 16..(screenWidth * 0.25f).toInt() &&
                        bounds.height() in 16..(screenHeight * 0.14f).toInt()
                    val validSpinnerShape = isMiniBufferingSpinner &&
                        bounds.height() in 3..(screenHeight * 0.12f).toInt()
                    if (inMiniStripY && (validCloseShape || validSpinnerShape)) {
                        hasMiniPlayer = true
                        if (miniPlayerBounds == null) {
                            miniPlayerBounds = Rect(0, bounds.top, screenWidth, bounds.bottom)
                        }
                        if (miniPlayerSubtitle == null) {
                            extractMiniPlayerChannelText(node.parent ?: node)?.let { miniPlayerSubtitle = it }
                        }
                    }
                }
            }

            if (!hasWatchPlayer && WATCH_PLAYER_ID_TOKENS.any { resId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() > (screenHeight * 0.18f).toInt()) {
                    hasWatchPlayer = true
                } else if (bounds.height() in 24..(screenHeight * 0.18f).toInt() &&
                    bounds.top >= miniPlayerMinY &&
                    bounds.bottom <= miniPlayerMaxY
                ) {
                    hasMiniPlayer = true
                    if (miniPlayerBounds == null) {
                        miniPlayerBounds = Rect(bounds)
                    }
                }
            }

            // 3. Detect Live badge and active Live player / Live Chat controls
            if (!hasLivePlayerControl && isLivePlayerIndicator(resId, text, desc)) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < (screenHeight * 0.85f).toInt()) {
                    hasLivePlayerControl = true
                    hasLiveBadge = true
                }
            }
            if (!hasLiveBadge && (text.equals("LIVE", ignoreCase = true) ||
                    resId.contains("live_badge") ||
                    desc.contains("watching now", ignoreCase = true) ||
                    desc.contains("live chat", ignoreCase = true))
            ) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < watchHeaderMaxY) {
                    hasLiveBadge = true
                }
            }

            // 4. Extract canonical videoId / UC... channelId from accessible URLs (if exposed)
            //    and observational handle / title from watch or Shorts header.
            if (activeVideoId == null || activeChannelId == null) {
                val (vidFromText, cidFromText) = extractCanonicalIdsFromText(text)
                val (vidFromDesc, cidFromDesc) = if (vidFromText == null || cidFromText == null) {
                    extractCanonicalIdsFromText(desc)
                } else {
                    null to null
                }
                val candidateVid = vidFromText ?: vidFromDesc
                val candidateCid = cidFromText ?: cidFromDesc
                if (candidateVid != null || candidateCid != null) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top < watchHeaderMaxY || hasShortsContainer || hasWatchPlayer) {
                        if (activeVideoId == null && candidateVid != null) activeVideoId = candidateVid
                        if (activeChannelId == null && candidateCid != null) activeChannelId = candidateCid
                    }
                }
            }

            if (activeChannelHandle == null) {
                val h = YouTubeStudyEngine.normalizeHandle(text).ifEmpty {
                    YouTubeStudyEngine.normalizeHandle(desc)
                }
                if (h.isNotEmpty()) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top < watchHeaderMaxY || hasShortsContainer) {
                        activeChannelHandle = h
                    }
                }
            }

            if (activeChannelTitle == null) {
                val isChannelIdMatch = CHANNEL_HEADER_ID_TOKENS.any { resId.contains(it) }
                val isGoToChannelDesc = desc.startsWith("Go to channel", ignoreCase = true) ||
                    desc.contains("subscribers", ignoreCase = true)
                if (isChannelIdMatch || isGoToChannelDesc) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top < watchHeaderMaxY || hasShortsContainer) {
                        val candidate = extractChannelNameFromHeader(text, desc)
                        if (!candidate.isNullOrBlank()) {
                            activeChannelTitle = candidate
                        }
                    }
                }
            }

            if (activeVideoTitle == null && VIDEO_TITLE_ID_TOKENS.any { resId.contains(it) }) {
                if (text.length in 3..180) {
                    activeVideoTitle = text
                }
            }

            if (depth < 11) {
                val count = node.childCount.coerceAtMost(12)
                for (i in 0 until count) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        // Secondary watch-page detection for YouTube / Morphe YT / ReVanced when video player uses
        // SurfaceView. CRITICAL: Never run this when `hasMiniPlayer` is already true, and never match
        // "Pause video" alone (which also appears on the bottom miniplayer bar and would misclassify
        // Home/History + miniplayer as WATCH_PLAYER, causing Back to exit Morphe YT!).
        if (!hasWatchPlayer && !hasShortsContainer && !hasMiniPlayer && client.family != YouTubeClientFamily.THIRD_PARTY) {
            val hasExclusiveWatchControl = hasLivePlayerControl || snippets.any { s ->
                s.startsWith("Comments", ignoreCase = true) ||
                    s.contains("Enter fullscreen", ignoreCase = true) ||
                    s.contains("Exit fullscreen", ignoreCase = true) ||
                    s.equals("Minimize", ignoreCase = true) ||
                    isLivePlayerIndicator("", s, s)
            }
            val hasSubscribeAndActionRow = snippets.any { s ->
                s.equals("Subscribe", ignoreCase = true) ||
                    s.equals("Subscribed", ignoreCase = true) ||
                    s.equals("Join", ignoreCase = true)
            } && snippets.any { s ->
                s.equals("Share", ignoreCase = true) ||
                    s.equals("Remix", ignoreCase = true) ||
                    s.equals("Live chat", ignoreCase = true) ||
                    s.contains("like this video", ignoreCase = true) ||
                    s.contains("Dislike this video", ignoreCase = true)
            }
            if (hasExclusiveWatchControl || hasSubscribeAndActionRow) {
                hasWatchPlayer = true
                if (hasLivePlayerControl || snippets.any { isLivePlayerIndicator("", it, it) }) {
                    hasLiveBadge = true
                }
            }
        }

        // If watch/live player is open and channel title wasn't matched by viewId, inspect text
        // right before "Subscribe" / "Subscribed" / "Join"
        if (hasWatchPlayer && activeChannelTitle == null && activeChannelHandle == null) {
            val subIdx = snippets.indexOfFirst {
                it.equals("Subscribe", ignoreCase = true) ||
                    it.equals("Subscribed", ignoreCase = true) ||
                    (hasLiveBadge && it.equals("Join", ignoreCase = true))
            }
            if (subIdx > 0) {
                for (back in 1..3) {
                    val cand = snippets.getOrNull(subIdx - back)?.trim().orEmpty()
                    if (cand.length in 2..60 &&
                        !cand.contains("views", ignoreCase = true) &&
                        !cand.contains("watching now", ignoreCase = true) &&
                        !cand.contains("subscribers", ignoreCase = true) &&
                        !cand.equals("LIVE", ignoreCase = true) &&
                        !cand.equals("Share", ignoreCase = true) &&
                        !cand.equals("Download", ignoreCase = true) &&
                        !cand.equals("Save", ignoreCase = true) &&
                        !cand.equals("Live chat", ignoreCase = true) &&
                        !cand.equals("Top chat", ignoreCase = true)
                    ) {
                        activeChannelTitle = cand
                        break
                    }
                }
            }
        }

        val resolvedSearchQuery = activeSearchQuery ?: if (hasTopSearchActionIndicator) topToolbarQueryCandidate else null
        val isSearchResultsScreen = !hasWatchPlayer && !hasShortsContainer && !hasMiniPlayer &&
            !resolvedSearchQuery.isNullOrBlank() &&
            (activeSearchQuery != null || hasTopSearchActionIndicator)
        val isWatchHistoryOrProfileScreen = !hasWatchPlayer && !hasShortsContainer && !hasMiniPlayer &&
            !isSearchResultsScreen && hasWatchHistoryOrProfileIndicator
        val isPlaylistScreen = !hasWatchPlayer && !hasShortsContainer && !hasMiniPlayer &&
            !isSearchResultsScreen && !isWatchHistoryOrProfileScreen && hasPlaylistIndicator

        val resolvedChannelTitle = activeChannelTitle ?: if (!hasWatchPlayer && hasMiniPlayer) miniPlayerSubtitle else null

        val screenMode = when {
            hasShortsContainer -> YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER
            hasWatchPlayer && hasLiveBadge -> YouTubeStudyEngine.ScreenMode.LIVE_PLAYER
            hasWatchPlayer -> YouTubeStudyEngine.ScreenMode.WATCH_PLAYER
            hasMiniPlayer -> YouTubeStudyEngine.ScreenMode.MINI_PLAYER
            else -> YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH
        }

        val dedupedSnippets = BrowserUrlExtractor.mergeAndDeduplicateEvidence(
            pageSnippets = snippets,
            cardSnippets = emptyList(),
            maxTotal = 48
        )
        val dedupedCards = BrowserUrlExtractor.mergeAndDeduplicateEvidence(
            pageSnippets = visibleCardSnippets,
            cardSnippets = emptyList(),
            maxTotal = 32
        )

        return YouTubeWindowSnapshot(
            screenMode = screenMode,
            activeChannelId = activeChannelId,
            activeVideoId = activeVideoId,
            activeChannelTitle = resolvedChannelTitle,
            activeChannelHandle = activeChannelHandle,
            activeVideoTitle = activeVideoTitle,
            miniPlayerVisible = hasMiniPlayer,
            miniPlayerSubtitle = miniPlayerSubtitle,
            miniPlayerBounds = miniPlayerBounds,
            isSearchResultsScreen = isSearchResultsScreen,
            isPlaylistScreen = isPlaylistScreen,
            isWatchHistoryOrProfileScreen = isWatchHistoryOrProfileScreen,
            activeSearchQuery = resolvedSearchQuery,
            allVisibleSnippets = dedupedSnippets,
            visibleCardSnippets = dedupedCards
        )
    }

    /**
     * Re-scans the LIVE Accessibility tree (e.g., 140ms–620ms after collapsing Watch into
     * Morphe YT / YouTube's bottom miniplayer) to locate the freshly rendered miniplayer bar
     * bounds so right-edge Close (×) taps always hit the real current coordinates.
     */
    fun findLiveMiniPlayerBounds(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): Rect? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        val miniBarTopMin = (screenHeight * 0.58f).toInt()
        val miniBarBottomMax = (screenHeight * 0.99f).toInt()
        var bestContainerBounds: Rect? = null
        var playControlBandBounds: Rect? = null

        while (queue.isNotEmpty() && visited < 150) {
            val node = queue.removeFirst()
            visited++
            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()

            val isMiniContainer = MINIPLAYER_ID_TOKENS.any { resId.contains(it) } ||
                desc.contains("expand miniplayer") ||
                desc.contains("close miniplayer") ||
                desc.contains("dismiss miniplayer") ||
                desc == "close player" ||
                desc == "close video"

            if (isMiniContainer) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= miniBarTopMin &&
                    bounds.bottom <= miniBarBottomMax &&
                    bounds.height() in 20..(screenHeight * 0.28f).toInt()
                ) {
                    // If a small child matched (e.g. close button), use full width at its Y band
                    val candidate = if (bounds.width() >= (screenWidth * 0.45f).toInt()) {
                        Rect(bounds)
                    } else {
                        Rect(0, bounds.top, screenWidth, bounds.bottom)
                    }
                    val currentBest = bestContainerBounds
                    if (currentBest == null || candidate.width() > currentBest.width()) {
                        bestContainerBounds = candidate
                    }
                }
            }

            val isPlayPauseInMiniBand = desc == "pause video" || desc == "play video" ||
                desc == "replay video" || resId.contains("miniplayer_play") ||
                desc == "pause" || desc == "play"
            if (playControlBandBounds == null && isPlayPauseInMiniBand) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= miniBarTopMin &&
                    bounds.bottom <= miniBarBottomMax &&
                    bounds.left >= (screenWidth * 0.45f).toInt() &&
                    bounds.height() in 16..(screenHeight * 0.14f).toInt()
                ) {
                    playControlBandBounds = Rect(0, bounds.top, screenWidth, bounds.bottom)
                }
            }

            // Buffering / loading miniplayer strip fallback (fast swipe-down before playback loads)
            val cls = node.className?.toString().orEmpty()
            val isLoadingMiniStrip = desc == "close" || desc == "dismiss" ||
                cls.contains("ProgressBar", ignoreCase = true) ||
                resId.contains("watch_while_time_bar")
            if (playControlBandBounds == null && isLoadingMiniStrip) {
                node.getBoundsInScreen(bounds)
                val inStripY = bounds.top >= (screenHeight * 0.70f).toInt() &&
                    bounds.bottom <= (screenHeight * 0.94f).toInt()
                val validRightClose = (desc == "close" || desc == "dismiss") &&
                    bounds.left >= (screenWidth * 0.72f).toInt() &&
                    bounds.height() in 16..(screenHeight * 0.14f).toInt()
                val validProgress = !validRightClose &&
                    (cls.contains("ProgressBar", true) || resId.contains("watch_while_time_bar")) &&
                    bounds.height() in 3..(screenHeight * 0.12f).toInt()
                if (inStripY && (validRightClose || validProgress)) {
                    playControlBandBounds = Rect(0, bounds.top, screenWidth, bounds.bottom)
                }
            }

            for (i in 0 until node.childCount.coerceAtMost(10)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return bestContainerBounds ?: playControlBandBounds
    }

    /**
     * Attempts to click YouTube's Pause button on the active player or miniplayer so audio
     * stops immediately before collapsing/closing.
     */
    fun tryPauseActiveVideo(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 110) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            if (desc == "pause video" || desc == "pause" || desc.startsWith("pause ") || text == "pause") {
                if (clickNodeOrParent(node)) return true
            }
            for (i in 0 until node.childCount.coerceAtMost(10)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /**
     * Finds and clicks YouTube's in-app bottom miniplayer Close ("×") button so playback
     * stops completely inside YouTube / Morphe YT without exiting the app into PiP.
     */
    fun tryCloseMiniPlayer(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        val miniBarTopMin = (screenHeight * 0.58f).toInt()
        val miniBarBottomMax = (screenHeight * 0.99f).toInt()
        var miniContainerNode: AccessibilityNodeInfo? = null
        var miniPlayButtonNode: AccessibilityNodeInfo? = null
        val miniPlayBounds = Rect()

        while (queue.isNotEmpty() && visited < 160) {
            val node = queue.removeFirst()
            visited++
            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()

            // Direct match on miniplayer close button ID or description
            val isDirectMiniClose = MINIPLAYER_CLOSE_ID_TOKENS.any { resId.contains(it) } ||
                desc == "close miniplayer" ||
                desc == "dismiss miniplayer" ||
                desc == "close player" ||
                desc == "close video"

            if (isDirectMiniClose && clickNodeOrParent(node)) {
                return true
            }

            // Generic "Close" / "Dismiss" button positioned inside the lower miniplayer band (right half)
            if (desc == "close" || desc == "dismiss" || text == "close" ||
                resId.endsWith(":id/close_button") || resId.contains("close")
            ) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= miniBarTopMin && bounds.bottom <= miniBarBottomMax &&
                    bounds.left >= (screenWidth * 0.65f).toInt() &&
                    bounds.width() in 16..(screenWidth * 0.25f).toInt()
                ) {
                    if (clickNodeOrParent(node)) return true
                }
            }

            // Track miniplayer bar container in case its close button lacks text/desc
            if (miniContainerNode == null && MINIPLAYER_ID_TOKENS.any { resId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= miniBarTopMin && bounds.bottom <= miniBarBottomMax &&
                    bounds.height() in 32..(screenHeight * 0.24f).toInt()
                ) {
                    miniContainerNode = node
                }
            }

            // Track Play/Pause or buffering ProgressBar in the bottom miniplayer band so we can click its right-hand sibling (×)
            val cls = node.className?.toString().orEmpty()
            val isMiniPlayOrBuffering = desc == "pause video" || desc == "play video" || desc == "replay video" ||
                desc == "pause" || desc == "play" || resId.contains("miniplayer_play") ||
                cls.contains("ProgressBar", ignoreCase = true)
            if (miniPlayButtonNode == null && isMiniPlayOrBuffering) {
                node.getBoundsInScreen(bounds)
                val validMinLeft = if (cls.contains("ProgressBar", true)) (screenWidth * 0.35f).toInt() else (screenWidth * 0.45f).toInt()
                if (bounds.top >= miniBarTopMin && bounds.bottom <= miniBarBottomMax &&
                    bounds.left >= validMinLeft &&
                    bounds.height() in 12..(screenHeight * 0.14f).toInt()
                ) {
                    miniPlayButtonNode = node
                    miniPlayBounds.set(bounds)
                }
            }

            for (i in 0 until node.childCount.coerceAtMost(10)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // Structural fallback 1: click the clickable sibling immediately to the right of miniplayer Play/Pause!
        // Works on Morphe YT / ReVanced even when the × button has stripped IDs and empty contentDescription.
        miniPlayButtonNode?.parent?.let { parent ->
            var bestSibling: AccessibilityNodeInfo? = null
            var maxRight = miniPlayBounds.right
            val sibBounds = Rect()
            for (i in 0 until parent.childCount.coerceAtMost(12)) {
                val sib = parent.getChild(i) ?: continue
                if (sib == miniPlayButtonNode) continue
                sib.getBoundsInScreen(sibBounds)
                if (sibBounds.left >= miniPlayBounds.right - 12 &&
                    sibBounds.right > maxRight &&
                    sibBounds.width() in 16..(screenWidth * 0.24f).toInt()
                ) {
                    maxRight = sibBounds.right
                    bestSibling = sib
                }
            }
            if (bestSibling != null && clickNodeOrParent(bestSibling)) {
                return true
            }
        }

        // Structural fallback 2: inside the detected miniplayer container, click the rightmost small clickable button (×)
        miniContainerNode?.let { container ->
            var rightmostNode: AccessibilityNodeInfo? = null
            var maxRight = -1
            val subQueue = ArrayDeque<AccessibilityNodeInfo>()
            subQueue.add(container)
            var scanned = 0
            while (subQueue.isNotEmpty() && scanned < 30) {
                val n = subQueue.removeFirst()
                scanned++
                if (n.isClickable && n !== container) {
                    n.getBoundsInScreen(bounds)
                    if (bounds.width() in 20..(screenWidth * 0.22f).toInt() && bounds.right > maxRight) {
                        maxRight = bounds.right
                        rightmostNode = n
                    }
                }
                for (i in 0 until n.childCount.coerceAtMost(8)) {
                    n.getChild(i)?.let { subQueue.add(it) }
                }
            }
            if (rightmostNode != null && clickNodeOrParent(rightmostNode)) {
                return true
            }
        }
        return false
    }

    /**
     * Exits YouTube Shorts cleanly by switching to any available non-Shorts bottom navigation tab
     * (whatever default/custom tabs the user configured in Official YouTube, Morphe YT, or ReVanced:
     * Home, Subscriptions, History, Library, You, etc.) so Shorts stops immediately inside the app
     * without assuming a "Home" tab exists and without exiting to Android Home.
     */
    fun tryNavigateShortsToHome(
        root: AccessibilityNodeInfo?,
        screenHeight: Int,
        screenWidth: Int = 1080
    ): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        val bottomNavMinY = (screenHeight * 0.82f).toInt()
        var preferredDefaultTab: AccessibilityNodeInfo? = null
        var minTabLeft = Int.MAX_VALUE

        while (queue.isNotEmpty() && visited < 160) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            val resId = node.viewIdResourceName?.lowercase().orEmpty()

            val isShortsOrCreate = desc.contains("shorts") || text.contains("shorts") ||
                desc.contains("create") || text == "+" || desc == "create"

            if (!isShortsOrCreate && !node.isSelected) {
                val isTabLabel = desc == "home" || text == "home" || desc.endsWith(", tab") ||
                    desc.contains("subscriptions") || text == "subscriptions" ||
                    desc.contains("library") || text == "library" ||
                    desc.contains("history") || text == "history" ||
                    desc == "you" || text == "you" ||
                    resId.contains("pivot_bar")

                if (node.isClickable || isTabLabel) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.top >= bottomNavMinY &&
                        bounds.height() in 20..(screenHeight * 0.17f).toInt() &&
                        bounds.width() in 20..(screenWidth * 0.45f).toInt() &&
                        bounds.left < minTabLeft
                    ) {
                        minTabLeft = bounds.left
                        preferredDefaultTab = node
                    }
                }
            }

            for (i in 0 until node.childCount.coerceAtMost(10)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        if (preferredDefaultTab != null && clickNodeOrParent(preferredDefaultTab)) {
            return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < 3) {
            target = target.parent
            hops++
        }
        val clickable = target ?: node
        return runCatching {
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    private fun extractMiniPlayerChannelText(miniNode: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(miniNode)
        val texts = ArrayList<String>(4)
        var scanned = 0
        while (stack.isNotEmpty() && scanned < 20 && texts.size < 4) {
            val n = stack.removeFirst()
            scanned++
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.length in 2..80 &&
                !t.equals("Play", ignoreCase = true) &&
                !t.equals("Pause", ignoreCase = true) &&
                !t.equals("Close", ignoreCase = true)
            ) {
                texts.add(t)
            }
            for (i in 0 until n.childCount.coerceAtMost(6)) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        // In YouTube's miniplayer, the second text line is the channel name (first is video title)
        return texts.getOrNull(1) ?: texts.firstOrNull()
    }

    private fun extractChannelNameFromHeader(text: String, desc: String): String? {
        if (text.length in 2..80 &&
            !text.contains("subscribers", ignoreCase = true) &&
            !text.equals("Subscribe", ignoreCase = true)
        ) {
            return text
        }
        if (desc.startsWith("Go to channel", ignoreCase = true)) {
            val stripped = desc.removePrefix("Go to channel").trim()
            if (stripped.length in 2..80) return stripped
        }
        if (desc.contains("subscribers", ignoreCase = true)) {
            val beforeSubs = desc.substringBefore(',').substringBefore("·").trim()
            if (beforeSubs.length in 2..80) return beforeSubs
        }
        return null
    }
}
