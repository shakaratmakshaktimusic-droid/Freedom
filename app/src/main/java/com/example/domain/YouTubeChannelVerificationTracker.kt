package com.example.domain

/**
 * Video-session-bound, bounded-cache, race-safe YouTube channel verification tracker.
 *
 * Enforces strict channel identity invariants:
 *  1. Never guesses a permanent `UC...` channel ID from a display name or video title.
 *  2. Never treats a `@handle` as a verified permanent `UC...` channel ID.
 *  3. Never reuses the previous video's channel ID after switching videos.
 *  4. Binds every async verification lookup to the initiating `videoId` and discards
 *     stale results whenever the user switches videos before completion.
 *  5. Prevents duplicate concurrent lookups for the same `videoId`.
 *  6. Uses a strictly bounded LRU cache with TTL expiry for both verified results and
 *     short-lived failure cooldowns.
 */
class YouTubeChannelVerificationTracker(
    private val maxCacheEntries: Int = 128,
    private val verifiedTtlMs: Long = 30 * 60 * 1000L,
    private val failedTtlMs: Long = 20_000L
) {
    private data class CacheEntry(
        val status: YouTubeStudyEngine.ChannelAttributionStatus,
        val channelId: String?,
        val failureReason: String?,
        val recordedAtMs: Long
    )

    data class AttributionResolution(
        val attribution: YouTubeStudyEngine.ChannelAttribution,
        val sessionKey: String?,
        val videoSwitched: Boolean,
        val shouldStartAsyncLookupForVideoId: String?
    )

    data class LookupCompletion(
        val videoId: String,
        val appliedToCurrentVideo: Boolean,
        val attribution: YouTubeStudyEngine.ChannelAttribution
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxCacheEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxCacheEntries
        }
    }
    private val inFlightVideoIds = HashSet<String>()
    private var currentSessionKey: String? = null
    private var currentVideoId: String? = null

    /**
     * Resolves the honest [YouTubeStudyEngine.ChannelAttribution] for the currently visible
     * YouTube playback screen without ever fabricating a `UC...` channel ID or leaking the
     * previous video's identity across a video switch.
     */
    fun evaluateCurrentPlayback(
        activeVideoId: String?,
        extractedChannelId: String?,
        observedHandle: String?,
        observedDisplayTitle: String?,
        observedVideoTitle: String?,
        persistedVideoToChannelMap: Map<String, String>,
        nowMs: Long
    ): AttributionResolution = synchronized(lock) {
        val validVideoId = activeVideoId?.trim()?.takeIf { YouTubeStudyEngine.isValidVideoId(it) }
        val validDirectCid = extractedChannelId?.trim()?.takeIf { YouTubeStudyEngine.isValidPermanentChannelId(it) }
        val normHandle = YouTubeStudyEngine.normalizeHandle(observedHandle).ifEmpty { null }
        val cleanTitle = observedDisplayTitle?.trim()?.takeIf { it.isNotEmpty() }

        val newSessionKey = buildSessionKey(
            videoId = validVideoId,
            observedVideoTitle = observedVideoTitle,
            observedHandle = normHandle,
            observedDisplayTitle = cleanTitle
        )
        val switched = newSessionKey != null && newSessionKey != currentSessionKey
        if (switched || (newSessionKey == null && currentSessionKey != null)) {
            currentSessionKey = newSessionKey
            currentVideoId = validVideoId
        } else if (validVideoId != null && currentVideoId != validVideoId) {
            currentVideoId = validVideoId
        }

        // Case 1: Stable permanent UC... channel ID directly confirmed on this screen
        if (validDirectCid != null) {
            if (validVideoId != null) {
                cache[validVideoId] = CacheEntry(
                    status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
                    channelId = validDirectCid,
                    failureReason = null,
                    recordedAtMs = nowMs
                )
            }
            return AttributionResolution(
                attribution = YouTubeStudyEngine.ChannelAttribution(
                    status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
                    channelId = validDirectCid,
                    videoId = validVideoId,
                    observedHandle = normHandle,
                    observedDisplayTitle = cleanTitle
                ),
                sessionKey = newSessionKey,
                videoSwitched = switched,
                shouldStartAsyncLookupForVideoId = null
            )
        }

        // Case 2: Canonical 11-char videoId is known — verify strictly via videoId cache/lookup
        if (validVideoId != null) {
            val cached = cache[validVideoId]
            if (cached != null) {
                val ttl = if (cached.status == YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED) {
                    verifiedTtlMs
                } else {
                    failedTtlMs
                }
                if ((nowMs - cached.recordedAtMs) in 0L..ttl) {
                    return AttributionResolution(
                        attribution = YouTubeStudyEngine.ChannelAttribution(
                            status = cached.status,
                            channelId = cached.channelId,
                            videoId = validVideoId,
                            observedHandle = normHandle,
                            observedDisplayTitle = cleanTitle,
                            failureReason = cached.failureReason
                        ),
                        sessionKey = newSessionKey,
                        videoSwitched = switched,
                        shouldStartAsyncLookupForVideoId = null
                    )
                } else {
                    cache.remove(validVideoId)
                }
            }

            // Check persisted Room/FastStore verified videoId -> UC... mapping
            val persistedCid = persistedVideoToChannelMap[validVideoId]
                ?.trim()
                ?.takeIf { YouTubeStudyEngine.isValidPermanentChannelId(it) }
            if (persistedCid != null) {
                cache[validVideoId] = CacheEntry(
                    status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
                    channelId = persistedCid,
                    failureReason = null,
                    recordedAtMs = nowMs
                )
                return AttributionResolution(
                    attribution = YouTubeStudyEngine.ChannelAttribution(
                        status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
                        channelId = persistedCid,
                        videoId = validVideoId,
                        observedHandle = normHandle,
                        observedDisplayTitle = cleanTitle
                    ),
                    sessionKey = newSessionKey,
                    videoSwitched = switched,
                    shouldStartAsyncLookupForVideoId = null
                )
            }

            // Already verifying this exact videoId -> PENDING (prevent duplicate concurrent lookups)
            if (validVideoId in inFlightVideoIds) {
                return AttributionResolution(
                    attribution = YouTubeStudyEngine.ChannelAttribution(
                        status = YouTubeStudyEngine.ChannelAttributionStatus.PENDING,
                        channelId = null,
                        videoId = validVideoId,
                        observedHandle = normHandle,
                        observedDisplayTitle = cleanTitle
                    ),
                    sessionKey = newSessionKey,
                    videoSwitched = switched,
                    shouldStartAsyncLookupForVideoId = null
                )
            }

            // Start ONE async verification lookup bound to this videoId -> PENDING
            inFlightVideoIds.add(validVideoId)
            return AttributionResolution(
                attribution = YouTubeStudyEngine.ChannelAttribution(
                    status = YouTubeStudyEngine.ChannelAttributionStatus.PENDING,
                    channelId = null,
                    videoId = validVideoId,
                    observedHandle = normHandle,
                    observedDisplayTitle = cleanTitle
                ),
                sessionKey = newSessionKey,
                videoSwitched = switched,
                shouldStartAsyncLookupForVideoId = validVideoId
            )
        }

        // Case 3: Neither UC... ID nor videoId is exposed on this screen.
        // Remain strictly honest: status is UNKNOWN (never fabricate a UC... ID from handle/title!).
        return AttributionResolution(
            attribution = YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.UNKNOWN,
                channelId = null,
                videoId = null,
                observedHandle = normHandle,
                observedDisplayTitle = cleanTitle
            ),
            sessionKey = newSessionKey,
            videoSwitched = switched,
            shouldStartAsyncLookupForVideoId = null
        )
    }

    /**
     * Completes an async lookup initiated for [videoId].
     * Always clears the in-flight lock, validates the resolved permanent `UC...` ID, records
     * bounded cache state, and reports [LookupCompletion.appliedToCurrentVideo] = false if
     * the user already switched to a different video so stale results are safely discarded.
     */
    fun completeAsyncLookup(
        videoId: String,
        resolvedChannelId: String?,
        failureReason: String? = null,
        nowMs: Long
    ): LookupCompletion = synchronized(lock) {
        val vid = videoId.trim()
        inFlightVideoIds.remove(vid)

        val validCid = resolvedChannelId?.trim()?.takeIf { YouTubeStudyEngine.isValidPermanentChannelId(it) }
        val attribution = if (validCid != null) {
            cache[vid] = CacheEntry(
                status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
                channelId = validCid,
                failureReason = null,
                recordedAtMs = nowMs
            )
            YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
                channelId = validCid,
                videoId = vid
            )
        } else {
            val reason = failureReason?.takeIf { it.isNotBlank() }
                ?: if (resolvedChannelId != null) "Malformed channel ID returned" else "Channel lookup failed or timed out"
            cache[vid] = CacheEntry(
                status = YouTubeStudyEngine.ChannelAttributionStatus.FAILED,
                channelId = null,
                failureReason = reason,
                recordedAtMs = nowMs
            )
            YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.FAILED,
                channelId = null,
                videoId = vid,
                failureReason = reason
            )
        }

        val stillActive = currentVideoId == vid
        LookupCompletion(
            videoId = vid,
            appliedToCurrentVideo = stillActive,
            attribution = attribution
        )
    }

    /**
     * Clears the active video session (e.g. when returning to Home/Feed or leaving YouTube)
     * so no previous video's attribution ever carries into a subsequent video.
     */
    fun clearActiveSession() = synchronized(lock) {
        currentSessionKey = null
        currentVideoId = null
    }

    /**
     * Explicitly invalidates a specific cached videoId (e.g. on allowlist change or refresh).
     */
    fun invalidateVideo(videoId: String): Unit = synchronized(lock) {
        cache.remove(videoId.trim())
    }

    fun clearAllCache() = synchronized(lock) {
        cache.clear()
        inFlightVideoIds.clear()
        currentSessionKey = null
        currentVideoId = null
    }

    fun isLookupInFlight(videoId: String): Boolean = synchronized(lock) {
        videoId.trim() in inFlightVideoIds
    }

    fun cachedSize(): Int = synchronized(lock) { cache.size }

    private fun buildSessionKey(
        videoId: String?,
        observedVideoTitle: String?,
        observedHandle: String?,
        observedDisplayTitle: String?
    ): String? {
        if (!videoId.isNullOrBlank()) return "vid:$videoId"
        val normVidTitle = observedVideoTitle?.trim()?.lowercase()?.take(90).orEmpty()
        val normCh = (observedHandle ?: YouTubeStudyEngine.normalizeChannelTitle(observedDisplayTitle)).take(60)
        if (normVidTitle.isEmpty() && normCh.isEmpty()) return null
        return "ui:$normVidTitle|$normCh"
    }
}
