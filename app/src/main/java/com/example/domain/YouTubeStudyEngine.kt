package com.example.domain

import java.net.URLDecoder

/**
 * Pure Kotlin Regain-style YouTube Study Mode decision engine.
 *
 * Core behavior:
 *  - Preserves YouTube's normal browsing interface (Home, Search, Subscriptions, Recommendations).
 *  - The shared Approved Channel Allowlist (anchored by permanent UC... channel IDs +
 *    verified @handles + verified canonical titles) determines what videos are accessible.
 *  - Feed cards from approved channels stay completely unobstructed; feed cards from unapproved
 *    channels are flagged for visual masking before interaction.
 *  - Watch playback and direct video links are verified against approved channels.
 *  - Shorts and Live streams are blocked unless explicitly enabled AND from an approved channel.
 *  - Fails closed during Strict Mode whenever channel attribution is missing or unverified,
 *    or when running inside an unverifiable YouTube client.
 */
object YouTubeStudyEngine {

    private val CHANNEL_ID_REGEX = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val HANDLE_REGEX = Regex("@[A-Za-z0-9._-]{3,30}")
    private val WHITESPACE_REGEX = Regex("\\s+")

    enum class ScreenMode {
        FEED_OR_SEARCH,
        WATCH_PLAYER,
        SHORTS_PLAYER,
        LIVE_PLAYER,
        CHANNEL_PAGE
    }

    data class ApprovedChannelSnapshot(
        val channelIds: Set<String> = emptySet(), // Canonical UC... IDs
        val normalizedHandles: Set<String> = emptySet(), // Lowercase "@handle"
        val normalizedTitles: Set<String> = emptySet(), // Normalized lowercase channel display names
        val videoToChannelIdCache: Map<String, String> = emptyMap() // videoId -> UC...
    ) {
        val isEmpty: Boolean
            get() = channelIds.isEmpty()

        fun isChannelIdApproved(channelId: String?): Boolean {
            if (channelId.isNullOrBlank()) return false
            return channelId.trim() in channelIds
        }

        fun matchesAttribution(
            channelId: String? = null,
            handle: String? = null,
            channelTitle: String? = null
        ): Boolean {
            if (!channelId.isNullOrBlank() && channelId.trim() in channelIds) return true
            val normHandle = normalizeHandle(handle)
            if (normHandle.isNotEmpty() && normHandle in normalizedHandles) return true
            val normTitle = normalizeChannelTitle(channelTitle)
            if (normTitle.isNotEmpty() && normTitle in normalizedTitles) return true
            return false
        }
    }

    data class ParsedYouTubeLink(
        val videoId: String? = null,
        val channelId: String? = null,
        val handle: String? = null,
        val isShort: Boolean = false,
        val isLive: Boolean = false,
        val isDirectVideoLink: Boolean = false
    )

    sealed class StudyDecision {
        data object Allow : StudyDecision()
        data class BlockPlayback(
            val reason: String,
            val notificationText: String,
            val navigateHome: Boolean = false
        ) : StudyDecision()
        data class NeedsVideoChannelVerification(
            val videoId: String,
            val isShort: Boolean,
            val isLive: Boolean
        ) : StudyDecision()
    }

    fun isValidPermanentChannelId(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return CHANNEL_ID_REGEX.matches(raw.trim())
    }

    fun isValidVideoId(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return VIDEO_ID_REGEX.matches(raw.trim())
    }

    fun normalizeHandle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim().lowercase()
        val match = HANDLE_REGEX.find(trimmed)?.value ?: return ""
        return match
    }

    fun normalizeChannelTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // Strip common YouTube metadata suffixes attached in Accessibility contentDescriptions
        val cleaned = raw
            .replace("\u200B", "")
            .replace("Verified", "", ignoreCase = true)
            .replace("Official Artist Channel", "", ignoreCase = true)
            .replace("Go to channel", "", ignoreCase = true)
            .trim()
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
        return cleaned
    }

    /**
     * Parses any YouTube URL (desktop, mobile, youtu.be, Shorts, Live, Embed, Channel, @handle)
     * into structured IDs without storing or logging the URL.
     */
    fun parseYouTubeUrl(rawUrl: String?): ParsedYouTubeLink? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val host = DomainBlockMatcher.normalizeDomain(withScheme) ?: return null
        val isYouTubeHost = host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host == "youtube-nocookie.com"
        if (!isYouTubeHost) return null

        val afterScheme = withScheme.substringAfter("://")
        val pathAndQuery = afterScheme.substringAfter('/', "")
        val pathOnly = pathAndQuery.substringBefore('?').substringBefore('#').trimEnd('/')
        val query = pathAndQuery.substringAfter('?', "").substringBefore('#')
        val segments = pathOnly.split('/').filter { it.isNotBlank() }

        // 1. youtu.be/<videoId>
        if (host == "youtu.be" && segments.isNotEmpty()) {
            val vid = segments[0]
            if (isValidVideoId(vid)) {
                return ParsedYouTubeLink(videoId = vid, isDirectVideoLink = true)
            }
        }

        // 2. youtube.com/watch?v=<videoId>
        if (segments.firstOrNull().equals("watch", ignoreCase = true)) {
            val vid = extractQueryParam(query, "v")
            if (isValidVideoId(vid)) {
                return ParsedYouTubeLink(videoId = vid, isDirectVideoLink = true)
            }
        }

        // 3. youtube.com/shorts/<videoId>
        if (segments.firstOrNull().equals("shorts", ignoreCase = true)) {
            val vid = segments.getOrNull(1)?.takeIf { isValidVideoId(it) }
            return ParsedYouTubeLink(
                videoId = vid,
                isShort = true,
                isDirectVideoLink = vid != null
            )
        }

        // 4. youtube.com/live/<videoId>
        if (segments.firstOrNull().equals("live", ignoreCase = true)) {
            val vid = segments.getOrNull(1)?.takeIf { isValidVideoId(it) }
            return ParsedYouTubeLink(
                videoId = vid,
                isLive = true,
                isDirectVideoLink = vid != null
            )
        }

        // 5. youtube.com/embed/<videoId>
        if (segments.firstOrNull().equals("embed", ignoreCase = true)) {
            val vid = segments.getOrNull(1)?.takeIf { isValidVideoId(it) }
            return ParsedYouTubeLink(videoId = vid, isDirectVideoLink = vid != null)
        }

        // 6. youtube.com/channel/UC...
        if (segments.firstOrNull().equals("channel", ignoreCase = true)) {
            val cid = segments.getOrNull(1)?.takeIf { isValidPermanentChannelId(it) }
            if (cid != null) {
                return ParsedYouTubeLink(channelId = cid)
            }
        }

        // 7. youtube.com/@handle
        val firstSeg = segments.firstOrNull().orEmpty()
        if (firstSeg.startsWith("@")) {
            val handle = normalizeHandle(firstSeg)
            if (handle.isNotEmpty()) {
                return ParsedYouTubeLink(handle = handle)
            }
        }

        return ParsedYouTubeLink()
    }

    /**
     * Evaluates whether a YouTube client app (Official YouTube, Morphe YT, ReVanced, etc.)
     * is allowed to run under Study Mode + Strict Mode.
     */
    fun evaluateClientCompatibility(
        client: YouTubeClientProfile,
        strictMode: Boolean
    ): StudyDecision {
        if (!client.supportTier.canVerifyChannelsReliably && strictMode) {
            return StudyDecision.BlockPlayback(
                reason = "Unverifiable YouTube client (${client.displayName}) blocked in Strict Mode",
                notificationText = "${client.displayName} cannot verify channels reliably and is blocked in Strict Mode.",
                navigateHome = true
            )
        }
        return StudyDecision.Allow
    }

    /**
     * Evaluates an active Watch, Shorts, or Live playback screen inside any supported YouTube client.
     */
    fun evaluateActivePlayback(
        screenMode: ScreenMode,
        detectedChannelId: String?,
        detectedHandle: String?,
        detectedChannelTitle: String?,
        approved: ApprovedChannelSnapshot,
        config: ContentProtectionConfig,
        strictMode: Boolean
    ): StudyDecision {
        if (!config.youtubeStudyModeEnabled) return StudyDecision.Allow

        // Shorts rule: blocked by default unless explicitly permitted AND from an approved channel
        if (screenMode == ScreenMode.SHORTS_PLAYER) {
            if (!config.youtubeAllowShortsFromApproved) {
                return StudyDecision.BlockPlayback(
                    reason = "YouTube Shorts blocked by Study Mode",
                    notificationText = "YouTube Shorts are blocked by Freedom Study Mode."
                )
            }
            val matched = approved.matchesAttribution(detectedChannelId, detectedHandle, detectedChannelTitle)
            if (!matched) {
                val hasAttribution = !detectedChannelId.isNullOrBlank() ||
                    !detectedHandle.isNullOrBlank() ||
                    !detectedChannelTitle.isNullOrBlank()
                if (hasAttribution || strictMode) {
                    return StudyDecision.BlockPlayback(
                        reason = if (hasAttribution) {
                            "Short from unapproved channel blocked"
                        } else {
                            "Unverified Short blocked in Strict Mode"
                        },
                        notificationText = "Only approved channels can play in Study Mode."
                    )
                }
            }
            return StudyDecision.Allow
        }

        // Live rule: blocked by default unless explicitly permitted AND from an approved channel
        if (screenMode == ScreenMode.LIVE_PLAYER) {
            if (!config.youtubeAllowLiveFromApproved) {
                return StudyDecision.BlockPlayback(
                    reason = "YouTube Live stream blocked by Study Mode",
                    notificationText = "Live streams are blocked by Freedom Study Mode."
                )
            }
            val matched = approved.matchesAttribution(detectedChannelId, detectedHandle, detectedChannelTitle)
            if (!matched) {
                return StudyDecision.BlockPlayback(
                    reason = "Live stream from unapproved or unverified channel blocked",
                    notificationText = "Only approved channels can play in Study Mode."
                )
            }
            return StudyDecision.Allow
        }

        // Standard Watch Player screen
        if (screenMode == ScreenMode.WATCH_PLAYER) {
            if (approved.isEmpty) {
                return StudyDecision.BlockPlayback(
                    reason = "No approved channels configured in YouTube Study Mode",
                    notificationText = "Add approved channels in Freedom Study Mode to watch videos."
                )
            }
            val matched = approved.matchesAttribution(detectedChannelId, detectedHandle, detectedChannelTitle)
            if (matched) return StudyDecision.Allow

            val hasAttribution = !detectedChannelId.isNullOrBlank() ||
                !detectedHandle.isNullOrBlank() ||
                !detectedChannelTitle.isNullOrBlank()

            if (hasAttribution) {
                return StudyDecision.BlockPlayback(
                    reason = "Video from unapproved YouTube channel blocked",
                    notificationText = "Channel not in your Study Mode allowlist."
                )
            }

            // Fail-closed in Strict Mode if channel attribution cannot be verified!
            if (strictMode) {
                return StudyDecision.BlockPlayback(
                    reason = "Unverifiable YouTube channel blocked in Strict Mode",
                    notificationText = "Playback blocked — channel could not be verified in Strict Mode."
                )
            }
        }

        return StudyDecision.Allow
    }

    /**
     * Evaluates a direct YouTube URL opened inside a browser or in-app WebView.
     */
    fun evaluateDirectYouTubeUrl(
        rawUrl: String?,
        approved: ApprovedChannelSnapshot,
        config: ContentProtectionConfig,
        strictMode: Boolean
    ): StudyDecision {
        if (!config.youtubeStudyModeEnabled) return StudyDecision.Allow
        val parsed = parseYouTubeUrl(rawUrl) ?: return StudyDecision.Allow

        if (parsed.isShort && !config.youtubeAllowShortsFromApproved) {
            return StudyDecision.BlockPlayback(
                reason = "Direct YouTube Shorts link blocked by Study Mode",
                notificationText = "YouTube Shorts are blocked by Freedom Study Mode."
            )
        }

        if (parsed.isLive && !config.youtubeAllowLiveFromApproved) {
            return StudyDecision.BlockPlayback(
                reason = "Direct YouTube Live link blocked by Study Mode",
                notificationText = "Live streams are blocked by Freedom Study Mode."
            )
        }

        // Direct channel URL (e.g. youtube.com/channel/UC... or youtube.com/@handle)
        if (!parsed.channelId.isNullOrBlank() || !parsed.handle.isNullOrBlank()) {
            val matched = approved.matchesAttribution(
                channelId = parsed.channelId,
                handle = parsed.handle,
                channelTitle = null
            )
            return if (matched) {
                StudyDecision.Allow
            } else {
                StudyDecision.BlockPlayback(
                    reason = "Unapproved YouTube channel link blocked",
                    notificationText = "This channel is not in your Study Mode allowlist."
                )
            }
        }

        // Direct video URL (watch?v=..., youtu.be/..., shorts/..., embed/...)
        val vid = parsed.videoId
        if (!vid.isNullOrBlank()) {
            if (approved.isEmpty) {
                return StudyDecision.BlockPlayback(
                    reason = "Direct YouTube video blocked (no approved channels)",
                    notificationText = "Add approved channels in Freedom Study Mode first."
                )
            }
            val cachedChannelId = approved.videoToChannelIdCache[vid]
            if (cachedChannelId != null) {
                return if (approved.isChannelIdApproved(cachedChannelId)) {
                    StudyDecision.Allow
                } else {
                    StudyDecision.BlockPlayback(
                        reason = "Direct link to unapproved channel video blocked",
                        notificationText = "Video blocked — channel is not in your Study Mode allowlist."
                    )
                }
            }
            // Unknown video ID: request privacy-minimal videoId->channelId verification
            return StudyDecision.NeedsVideoChannelVerification(
                videoId = vid,
                isShort = parsed.isShort,
                isLive = parsed.isLive
            )
        }

        return StudyDecision.Allow
    }

    /**
     * Evaluates a single visible feed / search / recommendation card inside YouTube or Morphe YT.
     * Returns true if the card should be visually masked (unapproved or blocked Short/Live),
     * or false if the card belongs to an approved channel and should stay completely accessible!
     */
    fun shouldMaskFeedCard(
        cardTextCandidates: List<String>,
        isShortCard: Boolean,
        isLiveCard: Boolean,
        approved: ApprovedChannelSnapshot,
        config: ContentProtectionConfig
    ): Boolean {
        if (!config.youtubeStudyModeEnabled) return false
        if (isShortCard && !config.youtubeAllowShortsFromApproved) return true
        if (isLiveCard && !config.youtubeAllowLiveFromApproved) return true
        if (approved.isEmpty) return true

        // Check every candidate attribution segment on the video card
        for (raw in cardTextCandidates) {
            if (raw.isBlank()) continue
            val normHandle = normalizeHandle(raw)
            if (normHandle.isNotEmpty() && normHandle in approved.normalizedHandles) {
                return false
            }
            val normTitle = normalizeChannelTitle(raw)
            if (normTitle.isNotEmpty() && normTitle in approved.normalizedTitles) {
                return false
            }
            // YouTube feed accessibility descriptions often join metadata with " · ", " - ", or ", "
            // e.g. "Linear Algebra - 3Blue1Brown - 1.2M views - 2 years ago"
            val parts = raw.split(" · ", " • ", " - ", " — ", "\n")
            for (part in parts) {
                val pHandle = normalizeHandle(part)
                if (pHandle.isNotEmpty() && pHandle in approved.normalizedHandles) {
                    return false
                }
                val pNorm = normalizeChannelTitle(part)
                if (pNorm.length >= 2 && pNorm in approved.normalizedTitles) {
                    return false
                }
            }
        }
        return true
    }

    fun extractQueryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        val pairs = query.split('&')
        for (pair in pairs) {
            val eqIdx = pair.indexOf('=')
            val k = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            if (k.equals(key, ignoreCase = true)) {
                val v = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
                return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
        }
        return null
    }
}
