package com.example.blocker

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.YouTubeClientFamily
import com.example.domain.YouTubeClientProfile
import com.example.domain.YouTubeStudyEngine

/**
 * Ultra-lightweight, bounded single-pass Accessibility tree inspector for:
 *  - Official YouTube
 *  - Morphe YT
 *  - YouTube ReVanced / RVX / patched YouTube clients
 *  - Supported third-party clients (NewPipe, Tubular, PipePipe, LibreTube)
 *
 * Strictly bounded (< 120 visited nodes per pass) to keep scrolling smooth at 60 FPS.
 */
object YouTubeAccessibilityInspector {

    data class FeedCardInspection(
        val bounds: Rect,
        val textCandidates: List<String>,
        val isShortCard: Boolean,
        val isLiveCard: Boolean
    )

    data class YouTubeWindowSnapshot(
        val screenMode: YouTubeStudyEngine.ScreenMode,
        val activeChannelTitle: String? = null,
        val activeChannelHandle: String? = null,
        val activeVideoTitle: String? = null,
        val visibleFeedCards: List<FeedCardInspection> = emptyList(),
        val allVisibleSnippets: List<String> = emptyList()
    )

    // View ID fragments shared across Official YouTube, Morphe YT, and YouTube ReVanced/RVX
    private val SHORTS_ID_TOKENS = listOf(
        "reel_watch", "reel_player", "reel_recycler", "shorts_player", "reel_channel",
        "reel_pivot", "reel_dyn_remix"
    )
    private val WATCH_PLAYER_ID_TOKENS = listOf(
        "watch_player", "player_view", "next_gen_watch", "watch_while_time_bar",
        "watch_panel", "player_fragment_container", "video_player", "detail_root"
    )
    private val CHANNEL_HEADER_ID_TOKENS = listOf(
        "channel_name", "owner_text", "channel_title", "detail_uploader_text_view",
        "uploader_name", "author_name", "reel_channel_bar", "channel_handle"
    )
    private val VIDEO_TITLE_ID_TOKENS = listOf(
        "video_title", "title_text", "detail_video_title_view", "player_video_title"
    )

    fun inspect(
        root: AccessibilityNodeInfo?,
        client: YouTubeClientProfile,
        screenWidth: Int,
        screenHeight: Int,
        collectFeedCards: Boolean
    ): YouTubeWindowSnapshot {
        if (root == null) {
            return YouTubeWindowSnapshot(screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH)
        }

        var hasShortsContainer = false
        var hasWatchPlayer = false
        var hasLiveBadge = false
        var activeChannelTitle: String? = null
        var activeChannelHandle: String? = null
        var activeVideoTitle: String? = null
        val feedCards = ArrayList<FeedCardInspection>(14)
        val snippets = ArrayList<String>(32)

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        val bounds = Rect()

        val cardMinW = (screenWidth * 0.40f).toInt()
        val cardMinH = (screenHeight * 0.10f).toInt()
        val cardMaxH = (screenHeight * 0.54f).toInt()
        val watchHeaderMaxY = (screenHeight * 0.68f).toInt()
        val feedTopSafeY = (screenHeight * 0.08f).toInt()
        val feedBottomSafeY = (screenHeight * 0.90f).toInt()

        while (queue.isNotEmpty() && visited < 120) {
            val (node, depth) = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()

            if (snippets.size < 32) {
                if (text.length in 2..150) snippets.add(text)
                else if (desc.length in 2..150) snippets.add(desc)
            }

            // 1. Detect Shorts player container
            if (!hasShortsContainer && SHORTS_ID_TOKENS.any { resId.contains(it) }) {
                hasShortsContainer = true
            }
            if (!hasShortsContainer && desc.contains("shorts player", ignoreCase = true)) {
                hasShortsContainer = true
            }

            // 2. Detect Watch player container
            if (!hasWatchPlayer && WATCH_PLAYER_ID_TOKENS.any { resId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                // Ignore minimized mini-player bar at bottom of screen
                if (bounds.height() > (screenHeight * 0.16f).toInt()) {
                    hasWatchPlayer = true
                }
            }

            // 3. Detect Live badge on active player
            if (!hasLiveBadge && (text.equals("LIVE", ignoreCase = true) ||
                    desc.contains("watching now", ignoreCase = true) ||
                    desc.contains("live chat", ignoreCase = true))
            ) {
                node.getBoundsInScreen(bounds)
                if (bounds.top < watchHeaderMaxY) {
                    hasLiveBadge = true
                }
            }

            // 4. Extract active channel handle / title from watch or Shorts header
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

            // 5. Collect visible feed / search / recommendation cards for Regain-style masking
            if (collectFeedCards &&
                client.supportsFeedCardMasking &&
                !hasShortsContainer &&
                feedCards.size < 12 &&
                depth >= 3 &&
                node.isClickable
            ) {
                node.getBoundsInScreen(bounds)
                val insideFeedViewport = bounds.top >= feedTopSafeY &&
                    bounds.bottom <= feedBottomSafeY &&
                    bounds.width() >= cardMinW &&
                    bounds.height() in cardMinH..cardMaxH
                // On watch screen, only mask recommendation cards BELOW the video player
                val belowPlayerIfWatching = !hasWatchPlayer || bounds.top > (screenHeight * 0.42f).toInt()
                if (insideFeedViewport && belowPlayerIfWatching) {
                    val cardTexts = extractCardTexts(node)
                    if (looksLikeVideoCard(cardTexts, desc)) {
                        val joined = cardTexts.joinToString(" ").lowercase()
                        val isShort = joined.contains("shorts") || joined.contains("#shorts")
                        val isLive = joined.contains("watching") || joined.contains(" live ") || joined.endsWith(" live")
                        feedCards.add(
                            FeedCardInspection(
                                bounds = Rect(bounds),
                                textCandidates = cardTexts,
                                isShortCard = isShort,
                                isLiveCard = isLive
                            )
                        )
                        // Don't descend further inside a captured card
                        continue
                    }
                }
            }

            if (depth < 9) {
                val count = node.childCount.coerceAtMost(10)
                for (i in 0 until count) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        // Secondary watch-page detection for YouTube / Morphe YT / ReVanced when video player uses SurfaceView
        if (!hasWatchPlayer && !hasShortsContainer && client.family != YouTubeClientFamily.THIRD_PARTY) {
            val hasWatchControls = snippets.any { s ->
                s.equals("Subscribe", ignoreCase = true) ||
                    s.equals("Subscribed", ignoreCase = true) ||
                    s.startsWith("Comments", ignoreCase = true) ||
                    s.contains("Pause video", ignoreCase = true) ||
                    s.contains("Play video", ignoreCase = true) ||
                    s.contains("Enter fullscreen", ignoreCase = true)
            }
            if (hasWatchControls) {
                hasWatchPlayer = true
            }
        }

        // If watch player is open and channel title wasn't matched by viewId, look for the text right next to "Subscribe"
        if (hasWatchPlayer && activeChannelTitle == null && activeChannelHandle == null) {
            val subIdx = snippets.indexOfFirst {
                it.equals("Subscribe", ignoreCase = true) || it.equals("Subscribed", ignoreCase = true)
            }
            if (subIdx > 0) {
                for (back in 1..3) {
                    val cand = snippets.getOrNull(subIdx - back)?.trim().orEmpty()
                    if (cand.length in 2..60 &&
                        !cand.contains("views", ignoreCase = true) &&
                        !cand.contains("subscribers", ignoreCase = true) &&
                        !cand.equals("Share", ignoreCase = true) &&
                        !cand.equals("Download", ignoreCase = true) &&
                        !cand.equals("Save", ignoreCase = true)
                    ) {
                        activeChannelTitle = cand
                        break
                    }
                }
            }
        }

        val screenMode = when {
            hasShortsContainer -> YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER
            hasWatchPlayer && hasLiveBadge -> YouTubeStudyEngine.ScreenMode.LIVE_PLAYER
            hasWatchPlayer -> YouTubeStudyEngine.ScreenMode.WATCH_PLAYER
            else -> YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH
        }

        return YouTubeWindowSnapshot(
            screenMode = screenMode,
            activeChannelTitle = activeChannelTitle,
            activeChannelHandle = activeChannelHandle,
            activeVideoTitle = activeVideoTitle,
            visibleFeedCards = feedCards,
            allVisibleSnippets = snippets
        )
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

    private fun extractCardTexts(cardNode: AccessibilityNodeInfo): List<String> {
        val texts = ArrayList<String>(8)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(cardNode)
        var scanned = 0
        while (stack.isNotEmpty() && texts.size < 8 && scanned < 22) {
            val n = stack.removeFirst()
            scanned++
            n.text?.toString()?.trim()?.takeIf { it.length in 2..180 }?.let { texts.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.length in 2..220 }?.let { texts.add(it) }
            for (i in 0 until n.childCount.coerceAtMost(6)) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return texts
    }

    private fun looksLikeVideoCard(cardTexts: List<String>, rootDesc: String): Boolean {
        if (cardTexts.isEmpty()) return false
        val joined = (cardTexts + rootDesc).joinToString(" ").lowercase()
        // Exclude bottom navigation bar, top chips, and search bar items
        if (joined.length < 10) return false
        val hasVideoSignals = joined.contains("views") ||
            joined.contains("ago") ||
            joined.contains("minutes") ||
            joined.contains("seconds") ||
            joined.contains("watching") ||
            joined.contains("shorts") ||
            joined.contains("play video") ||
            joined.contains("go to channel") ||
            joined.contains("·") ||
            cardTexts.size >= 2
        return hasVideoSignals
    }
}
