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
    }
}
