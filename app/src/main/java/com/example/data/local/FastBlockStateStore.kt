package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.domain.BlockDecisionEngine
import com.example.domain.DndMode
import com.example.domain.FilterUpdateFrequency
import com.example.domain.SettingsWaitStateMachine
import com.example.domain.ThemeMode
import com.example.domain.WebsiteCategory

/**
 * Thread-safe, atomic SharedPreferences mirror of active blocking state,
 * active profile allowlisted apps, Website Allowlist URLs, custom blocked domains,
 * active 16-category flags, DND / Notification Shield preferences, ThemeMode, and
 * monotonic 3-minute Settings temporary unlock expiry.
 */
class FastBlockStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val stateLock = Any()

    @Volatile
    private var cachedSnapshot: BlockDecisionEngine.ActiveBlockSnapshot? = null

    fun readSnapshot(): BlockDecisionEngine.ActiveBlockSnapshot {
        cachedSnapshot?.let { return it }
        synchronized(stateLock) {
            cachedSnapshot?.let { return it }
            val loaded = BlockDecisionEngine.ActiveBlockSnapshot(
                isActive = prefs.getBoolean(KEY_IS_ACTIVE, false),
                sessionId = prefs.getLong(KEY_SESSION_ID, -1L),
                blocklistId = prefs.getLong(KEY_BLOCKLIST_ID, 1L),
                label = prefs.getString(KEY_LABEL, "Focus Session") ?: "Focus Session",
                endWallTimeMs = prefs.getLong(KEY_END_WALL_MS, 0L),
                endElapsedRealtimeMs = prefs.getLong(KEY_END_ELAPSED_MS, 0L),
                strictMode = prefs.getBoolean(KEY_STRICT_MODE, false),
                fullInternetBrowsingBlock = prefs.getBoolean(KEY_FULL_BROWSING_BLOCK, false),
                settingsUnlockedUntilElapsedMs = prefs.getLong(KEY_SETTINGS_UNLOCKED_UNTIL_ELAPSED, 0L),
                allowedPackages = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet(),
                allowedWebPatterns = prefs.getStringSet(KEY_ALLOWED_WEB_PATTERNS, emptySet())?.toSet() ?: emptySet(),
                customBlockedDomains = prefs.getStringSet(KEY_CUSTOM_BLOCKED_DOMAINS, emptySet())?.toSet() ?: emptySet(),
                blockedWebCategories = WebsiteCategory.parseCsv(prefs.getString(KEY_BLOCKED_WEB_CATS, "") ?: ""),
                autoDetectUnknownSites = prefs.getBoolean(KEY_AUTO_DETECT_UNKNOWN, true),
                enableDndDuringSession = prefs.getBoolean(KEY_DND_ENABLED, false),
                hideBlockedNotifications = prefs.getBoolean(KEY_HIDE_NOTIFS_ENABLED, false)
            )
            cachedSnapshot = loaded
            return loaded
        }
    }

    fun writeSession(
        isActive: Boolean,
        sessionId: Long,
        blocklistId: Long,
        label: String,
        endWallTimeMs: Long,
        endElapsedRealtimeMs: Long,
        strictMode: Boolean,
        fullInternetBrowsingBlock: Boolean,
        allowedPackages: Set<String>,
        allowedWebPatterns: Set<String>,
        customBlockedDomains: Set<String>,
        blockedWebCategories: Set<WebsiteCategory>
    ) = synchronized(stateLock) {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, isActive)
            .putLong(KEY_SESSION_ID, sessionId)
            .putLong(KEY_BLOCKLIST_ID, blocklistId)
            .putString(KEY_LABEL, label)
            .putLong(KEY_END_WALL_MS, endWallTimeMs)
            .putLong(KEY_END_ELAPSED_MS, endElapsedRealtimeMs)
            .putBoolean(KEY_STRICT_MODE, strictMode)
            .putBoolean(KEY_FULL_BROWSING_BLOCK, fullInternetBrowsingBlock)
            .putStringSet(KEY_ALLOWED_PACKAGES, allowedPackages.toSet())
            .putStringSet(KEY_ALLOWED_WEB_PATTERNS, allowedWebPatterns.toSet())
            .putStringSet(KEY_CUSTOM_BLOCKED_DOMAINS, customBlockedDomains.toSet())
            .putString(KEY_BLOCKED_WEB_CATS, WebsiteCategory.toCsv(blockedWebCategories))
            .apply()
        cachedSnapshot = null
    }

    fun clearSession() = synchronized(stateLock) {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, false)
            .putLong(KEY_SESSION_ID, -1L)
            .putLong(KEY_END_WALL_MS, 0L)
            .putLong(KEY_END_ELAPSED_MS, 0L)
            .putBoolean(KEY_STRICT_MODE, false)
            .putBoolean(KEY_FULL_BROWSING_BLOCK, false)
            .putString(KEY_BLOCKED_WEB_CATS, "")
            .putLong(KEY_SETTINGS_UNLOCKED_UNTIL_ELAPSED, 0L)
            .apply()
        cachedSnapshot = null
    }

    fun updateActiveRules(
        allowedPackages: Set<String>,
        allowedWebPatterns: Set<String>,
        customBlockedDomains: Set<String>,
        blockedWebCategories: Set<WebsiteCategory>,
        fullInternetBrowsingBlock: Boolean
    ) = synchronized(stateLock) {
        prefs.edit()
            .putStringSet(KEY_ALLOWED_PACKAGES, allowedPackages.toSet())
            .putStringSet(KEY_ALLOWED_WEB_PATTERNS, allowedWebPatterns.toSet())
            .putStringSet(KEY_CUSTOM_BLOCKED_DOMAINS, customBlockedDomains.toSet())
            .putString(KEY_BLOCKED_WEB_CATS, WebsiteCategory.toCsv(blockedWebCategories))
            .putBoolean(KEY_FULL_BROWSING_BLOCK, fullInternetBrowsingBlock)
            .apply()
        cachedSnapshot = null
    }

    fun writeAllowedWebPatterns(patterns: Set<String>) = synchronized(stateLock) {
        prefs.edit().putStringSet(KEY_ALLOWED_WEB_PATTERNS, patterns.toSet()).apply()
        cachedSnapshot = null
    }

    fun writeCustomBlockedDomains(domains: Set<String>) = synchronized(stateLock) {
        prefs.edit().putStringSet(KEY_CUSTOM_BLOCKED_DOMAINS, domains.toSet()).apply()
        cachedSnapshot = null
    }

    // --- Settings Waiting Room Temporary Unlock (3 min monotonic) ---
    fun grantTemporarySettingsUnlock(nowElapsedRealtimeMs: Long): Long = synchronized(stateLock) {
        val expiresAt = SettingsWaitStateMachine.computeNewUnlockExpiry(nowElapsedRealtimeMs)
        prefs.edit().putLong(KEY_SETTINGS_UNLOCKED_UNTIL_ELAPSED, expiresAt).apply()
        cachedSnapshot = null
        expiresAt
    }

    fun clearTemporarySettingsUnlock() = synchronized(stateLock) {
        prefs.edit().putLong(KEY_SETTINGS_UNLOCKED_UNTIL_ELAPSED, 0L).apply()
        cachedSnapshot = null
    }

    // --- User Preferences ---
    fun getThemeMode(): ThemeMode =
        ThemeMode.fromId(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.id))

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.id).apply()
    }

    fun isLockedModeDefault(): Boolean = prefs.getBoolean(KEY_LOCKED_MODE_DEFAULT, true)

    fun setLockedModeDefault(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKED_MODE_DEFAULT, enabled).apply()
    }

    fun isDndEnabled(): Boolean = prefs.getBoolean(KEY_DND_ENABLED, false)

    fun setDndEnabled(enabled: Boolean) = synchronized(stateLock) {
        prefs.edit().putBoolean(KEY_DND_ENABLED, enabled).apply()
        cachedSnapshot = null
    }

    fun getDndMode(): DndMode = DndMode.fromId(prefs.getString(KEY_DND_MODE, DndMode.PRIORITY_ONLY.id))

    fun setDndMode(mode: DndMode) {
        prefs.edit().putString(KEY_DND_MODE, mode.id).apply()
    }

    fun isHideBlockedNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_HIDE_NOTIFS_ENABLED, false)

    fun setHideBlockedNotificationsEnabled(enabled: Boolean) = synchronized(stateLock) {
        prefs.edit().putBoolean(KEY_HIDE_NOTIFS_ENABLED, enabled).apply()
        cachedSnapshot = null
    }

    fun isAutoDetectUnknownSitesEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_DETECT_UNKNOWN, true)

    fun setAutoDetectUnknownSitesEnabled(enabled: Boolean) = synchronized(stateLock) {
        prefs.edit().putBoolean(KEY_AUTO_DETECT_UNKNOWN, enabled).apply()
        cachedSnapshot = null
    }

    fun getFilterUpdateFrequency(): FilterUpdateFrequency =
        FilterUpdateFrequency.fromId(prefs.getString(KEY_FILTER_UPDATE_FREQ, FilterUpdateFrequency.DAILY.id))

    fun setFilterUpdateFrequency(freq: FilterUpdateFrequency) {
        prefs.edit().putString(KEY_FILTER_UPDATE_FREQ, freq.id).apply()
    }

    fun getLastFilterSyncMs(): Long = prefs.getLong(KEY_LAST_FILTER_SYNC_MS, 0L)

    fun setLastFilterSyncMs(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_FILTER_SYNC_MS, timestampMs).apply()
    }

    // --- Always-On Content Protection Config & Fast Channel Allowlist Snapshot (<1ms reads) ---
    @Volatile
    private var cachedContentProtectionConfig: com.example.domain.ContentProtectionConfig? = null

    @Volatile
    private var cachedApprovedChannels: com.example.domain.YouTubeStudyEngine.ApprovedChannelSnapshot? = null

    @Volatile
    private var cachedSafeSearchRules: List<com.example.domain.SafeSearchProviderRule> =
        com.example.domain.SafeSearchEngine.DEFAULT_PROVIDER_RULES

    fun readContentProtectionConfig(): com.example.domain.ContentProtectionConfig {
        cachedContentProtectionConfig?.let { return it }
        synchronized(stateLock) {
            cachedContentProtectionConfig?.let { return it }
            val loaded = com.example.domain.ContentProtectionConfig(
                youtubeStudyModeEnabled = prefs.getBoolean(KEY_YT_STUDY_MODE, false),
                youtubeMaskFeedCards = prefs.getBoolean(KEY_YT_MASK_FEED_CARDS, true),
                youtubeAllowShortsFromApproved = prefs.getBoolean(KEY_YT_ALLOW_SHORTS, false),
                youtubeAllowLiveFromApproved = prefs.getBoolean(KEY_YT_ALLOW_LIVE, false),
                youtubeDataApiKey = prefs.getString(KEY_YT_API_KEY, "") ?: "",
                safeSearchEnabled = prefs.getBoolean(KEY_SAFE_SEARCH_ENABLED, false),
                blockUnverifiedSearchEnginesInStrictMode = prefs.getBoolean(KEY_BLOCK_UNVERIFIED_SEARCH, true),
                inAppAdultFilterEnabled = prefs.getBoolean(KEY_IN_APP_ADULT_FILTER, false),
                blockImageSearchInStrictMode = prefs.getBoolean(KEY_BLOCK_IMAGE_SEARCH_STRICT, false),
                blockUnfilterableAppsInStrictMode = prefs.getBoolean(KEY_BLOCK_UNFILTERABLE_APPS_STRICT, false)
            )
            cachedContentProtectionConfig = loaded
            return loaded
        }
    }

    fun writeContentProtectionConfig(config: com.example.domain.ContentProtectionConfig) = synchronized(stateLock) {
        prefs.edit()
            .putBoolean(KEY_YT_STUDY_MODE, config.youtubeStudyModeEnabled)
            .putBoolean(KEY_YT_MASK_FEED_CARDS, config.youtubeMaskFeedCards)
            .putBoolean(KEY_YT_ALLOW_SHORTS, config.youtubeAllowShortsFromApproved)
            .putBoolean(KEY_YT_ALLOW_LIVE, config.youtubeAllowLiveFromApproved)
            .putString(KEY_YT_API_KEY, config.youtubeDataApiKey.trim())
            .putBoolean(KEY_SAFE_SEARCH_ENABLED, config.safeSearchEnabled)
            .putBoolean(KEY_BLOCK_UNVERIFIED_SEARCH, config.blockUnverifiedSearchEnginesInStrictMode)
            .putBoolean(KEY_IN_APP_ADULT_FILTER, config.inAppAdultFilterEnabled)
            .putBoolean(KEY_BLOCK_IMAGE_SEARCH_STRICT, config.blockImageSearchInStrictMode)
            .putBoolean(KEY_BLOCK_UNFILTERABLE_APPS_STRICT, config.blockUnfilterableAppsInStrictMode)
            .apply()
        cachedContentProtectionConfig = config
    }

    fun readApprovedChannelsSnapshot(): com.example.domain.YouTubeStudyEngine.ApprovedChannelSnapshot {
        cachedApprovedChannels?.let { return it }
        synchronized(stateLock) {
            cachedApprovedChannels?.let { return it }
            val ids = prefs.getStringSet(KEY_APPROVED_YT_IDS, emptySet())?.toSet() ?: emptySet()
            val handles = prefs.getStringSet(KEY_APPROVED_YT_HANDLES, emptySet())?.toSet() ?: emptySet()
            val titles = prefs.getStringSet(KEY_APPROVED_YT_TITLES, emptySet())?.toSet() ?: emptySet()
            val vidPairs = prefs.getStringSet(KEY_YT_VIDEO_CACHE_PAIRS, emptySet()).orEmpty()
            val vidMap = HashMap<String, String>(vidPairs.size)
            for (pair in vidPairs) {
                val sep = pair.indexOf('|')
                if (sep > 0 && sep < pair.lastIndex) {
                    vidMap[pair.substring(0, sep)] = pair.substring(sep + 1)
                }
            }
            val loaded = com.example.domain.YouTubeStudyEngine.ApprovedChannelSnapshot(
                channelIds = ids,
                normalizedHandles = handles,
                normalizedTitles = titles,
                videoToChannelIdCache = vidMap
            )
            cachedApprovedChannels = loaded
            return loaded
        }
    }

    fun writeApprovedChannelsSnapshot(
        channels: List<ApprovedYouTubeChannelEntity>,
        videoCache: Map<String, String> = readApprovedChannelsSnapshot().videoToChannelIdCache
    ) = synchronized(stateLock) {
        val ids = HashSet<String>(channels.size)
        val handles = HashSet<String>(channels.size)
        val titles = HashSet<String>(channels.size)
        for (ch in channels) {
            ids.add(ch.channelId.trim())
            val h = com.example.domain.YouTubeStudyEngine.normalizeHandle(ch.handle)
            if (h.isNotEmpty()) handles.add(h)
            val t = com.example.domain.YouTubeStudyEngine.normalizeChannelTitle(ch.title)
            if (t.isNotEmpty()) titles.add(t)
        }
        val pairs = videoCache.entries.take(300).map { "${it.key}|${it.value}" }.toSet()
        prefs.edit()
            .putStringSet(KEY_APPROVED_YT_IDS, ids)
            .putStringSet(KEY_APPROVED_YT_HANDLES, handles)
            .putStringSet(KEY_APPROVED_YT_TITLES, titles)
            .putStringSet(KEY_YT_VIDEO_CACHE_PAIRS, pairs)
            .apply()
        cachedApprovedChannels = com.example.domain.YouTubeStudyEngine.ApprovedChannelSnapshot(
            channelIds = ids,
            normalizedHandles = handles,
            normalizedTitles = titles,
            videoToChannelIdCache = HashMap(videoCache)
        )
    }

    fun putVerifiedVideoChannelInFastCache(videoId: String, channelId: String) = synchronized(stateLock) {
        val current = readApprovedChannelsSnapshot()
        if (current.videoToChannelIdCache[videoId] == channelId) return@synchronized
        val updatedMap = HashMap(current.videoToChannelIdCache)
        updatedMap[videoId] = channelId
        val pairs = updatedMap.entries.take(300).map { "${it.key}|${it.value}" }.toSet()
        prefs.edit().putStringSet(KEY_YT_VIDEO_CACHE_PAIRS, pairs).apply()
        cachedApprovedChannels = current.copy(videoToChannelIdCache = updatedMap)
    }

    fun getSafeSearchRules(): List<com.example.domain.SafeSearchProviderRule> = cachedSafeSearchRules

    fun updateSafeSearchRulesInMemory(rules: List<com.example.domain.SafeSearchProviderRule>) {
        if (rules.isNotEmpty()) {
            cachedSafeSearchRules = rules
        }
    }

    companion object {
        private const val PREFS_NAME = "freedom_fast_block_state_v3"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_BLOCKLIST_ID = "blocklist_id"
        private const val KEY_LABEL = "label"
        private const val KEY_END_WALL_MS = "end_wall_ms"
        private const val KEY_END_ELAPSED_MS = "end_elapsed_ms"
        private const val KEY_STRICT_MODE = "strict_mode"
        private const val KEY_FULL_BROWSING_BLOCK = "full_browsing_block"
        private const val KEY_SETTINGS_UNLOCKED_UNTIL_ELAPSED = "settings_unlocked_until_elapsed"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_ALLOWED_WEB_PATTERNS = "allowed_web_patterns"
        private const val KEY_CUSTOM_BLOCKED_DOMAINS = "custom_blocked_domains"
        private const val KEY_BLOCKED_WEB_CATS = "blocked_web_cats"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOCKED_MODE_DEFAULT = "locked_mode_default"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_DND_MODE = "dnd_mode"
        private const val KEY_HIDE_NOTIFS_ENABLED = "hide_notifs_enabled"
        private const val KEY_AUTO_DETECT_UNKNOWN = "auto_detect_unknown"
        private const val KEY_FILTER_UPDATE_FREQ = "filter_update_freq"
        private const val KEY_LAST_FILTER_SYNC_MS = "last_filter_sync_ms"

        // Content Protection Keys
        private const val KEY_YT_STUDY_MODE = "cp_yt_study_mode"
        private const val KEY_YT_MASK_FEED_CARDS = "cp_yt_mask_feed_cards"
        private const val KEY_YT_ALLOW_SHORTS = "cp_yt_allow_shorts"
        private const val KEY_YT_ALLOW_LIVE = "cp_yt_allow_live"
        private const val KEY_YT_API_KEY = "cp_yt_api_key"
        private const val KEY_SAFE_SEARCH_ENABLED = "cp_safe_search_enabled"
        private const val KEY_BLOCK_UNVERIFIED_SEARCH = "cp_block_unverified_search"
        private const val KEY_IN_APP_ADULT_FILTER = "cp_in_app_adult_filter"
        private const val KEY_BLOCK_IMAGE_SEARCH_STRICT = "cp_block_image_search_strict"
        private const val KEY_BLOCK_UNFILTERABLE_APPS_STRICT = "cp_block_unfilterable_apps_strict"
        private const val KEY_APPROVED_YT_IDS = "cp_approved_yt_ids"
        private const val KEY_APPROVED_YT_HANDLES = "cp_approved_yt_handles"
        private const val KEY_APPROVED_YT_TITLES = "cp_approved_yt_titles"
        private const val KEY_YT_VIDEO_CACHE_PAIRS = "cp_yt_video_cache_pairs"
    }
}
