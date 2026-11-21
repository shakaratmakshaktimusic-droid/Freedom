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
        val activeChannelTitle: String? = null,
        val activeChannelHandle: String? = null,
        val activeVideoTitle: String? = null,
        val miniPlayerVisible: Boolean = false,
        val miniPlayerSubtitle: String? = null,
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
        var activeChannelTitle: String? = null
        var activeChannelHandle: String? = null
        var activeVideoTitle: String? = null
        var miniPlayerSubtitle: String? = null
        val snippets = ArrayList<String>(36)

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        val bounds = Rect()

        val watchHeaderMaxY = (screenHeight * 0.68f).toInt()
        val miniPlayerMinY = (screenHeight * 0.65f).toInt()

        while (queue.isNotEmpty() && visited < 130) {
            val (node, depth) = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()

            if (snippets.size < 36) {
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

            // 2. Detect Watch player vs. bottom in-app Miniplayer
            if (MINIPLAYER_ID_TOKENS.any { resId.contains(it) } ||
                desc.contains("close miniplayer", ignoreCase = true) ||
                desc.contains("expand miniplayer", ignoreCase = true)
            ) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() in 20..(screenHeight * 0.24f).toInt() && bounds.top >= miniPlayerMinY) {
                    hasMiniPlayer = true
                    if (miniPlayerSubtitle == null) {
                        extractMiniPlayerChannelText(node)?.let { miniPlayerSubtitle = it }
                    }
                }
            }

            if (!hasWatchPlayer && WATCH_PLAYER_ID_TOKENS.any { resId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() > (screenHeight * 0.18f).toInt()) {
                    hasWatchPlayer = true
                } else if (bounds.height() in 28..(screenHeight * 0.18f).toInt() && bounds.top >= miniPlayerMinY) {
                    hasMiniPlayer = true
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
                    s.contains("Enter fullscreen", ignoreCase = true)
            }
            if (hasWatchControls) {
                hasWatchPlayer = true
            }
        }

        // If watch player is open and channel title wasn't matched by viewId, inspect text right before "Subscribe"
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

        val resolvedChannelTitle = activeChannelTitle ?: if (!hasWatchPlayer && hasMiniPlayer) miniPlayerSubtitle else null

        val screenMode = when {
            hasShortsContainer -> YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER
            hasWatchPlayer && hasLiveBadge -> YouTubeStudyEngine.ScreenMode.LIVE_PLAYER
            hasWatchPlayer -> YouTubeStudyEngine.ScreenMode.WATCH_PLAYER
            hasMiniPlayer -> YouTubeStudyEngine.ScreenMode.MINI_PLAYER
            else -> YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH
        }

        return YouTubeWindowSnapshot(
            screenMode = screenMode,
            activeChannelTitle = resolvedChannelTitle,
            activeChannelHandle = activeChannelHandle,
            activeVideoTitle = activeVideoTitle,
            miniPlayerVisible = hasMiniPlayer,
            miniPlayerSubtitle = miniPlayerSubtitle,
            allVisibleSnippets = snippets
        )
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
     * stops completely inside YouTube without exiting the app into PiP.
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
        val miniBarTopMin = (screenHeight * 0.65f).toInt()
        val miniBarBottomMax = (screenHeight * 0.96f).toInt()
        var miniContainerNode: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty() && visited < 140) {
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
            if (desc == "close" || desc == "dismiss" || text == "close" || resId.endsWith(":id/close_button")) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= miniBarTopMin && bounds.bottom <= miniBarBottomMax &&
                    bounds.left >= (screenWidth * 0.65f).toInt()
                ) {
                    if (clickNodeOrParent(node)) return true
                }
            }

            // Track miniplayer bar container in case its close button lacks text/desc
            if (miniContainerNode == null && MINIPLAYER_ID_TOKENS.any { resId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= miniBarTopMin && bounds.height() in 36..(screenHeight * 0.22f).toInt()) {
                    miniContainerNode = node
                }
            }

            for (i in 0 until node.childCount.coerceAtMost(10)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // Fallback: inside the detected miniplayer container, click the rightmost small clickable button (×)
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
     * Exits YouTube Shorts cleanly by clicking YouTube's bottom navigation "Home" tab
     * so Shorts stops immediately while staying inside YouTube (no app exit / no PiP).
     */
    fun tryNavigateShortsToHome(
        root: AccessibilityNodeInfo?,
        screenHeight: Int
    ): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val bounds = Rect()
        val bottomNavMinY = (screenHeight * 0.84f).toInt()

        while (queue.isNotEmpty() && visited < 130) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            if (desc == "home" || text == "home" || desc.startsWith("home, tab")) {
                node.getBoundsInScreen(bounds)
                if (bounds.top >= bottomNavMinY) {
                    if (clickNodeOrParent(node)) return true
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(10)) {
                node.getChild(i)?.let { queue.add(it) }
            }
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
