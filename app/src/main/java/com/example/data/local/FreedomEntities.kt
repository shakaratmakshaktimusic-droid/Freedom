package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.ScheduleCalculator

@Entity(tableName = "blocklists")
data class BlocklistProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val fullInternetBrowsingBlock: Boolean = false,
    val blockedWebCategoriesCsv: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "blocklist_allowed_apps",
    primaryKeys = ["blocklistId", "packageName"],
    indices = [Index("blocklistId")]
)
data class BlocklistAllowedAppEntity(
    val blocklistId: Long,
    val packageName: String,
    val appLabel: String
)

/**
 * User-entered Website Allowlist exceptions (domain or URL path prefix)
 * taking highest precedence over all 16 filter categories, synced community lists,
 * Full Internet Browsing Block, and unknown-site auto-learning.
 */
@Entity(tableName = "allowed_websites")
data class AllowedWebsiteEntity(
    @PrimaryKey val pattern: String,
    val isPathRule: Boolean = false,
    val addedAtMs: Long = System.currentTimeMillis()
)

/**
 * Optional user-entered custom blocked domains (never mixed with hidden community lists).
 */
@Entity(tableName = "custom_blocked_websites")
data class CustomBlockedWebsiteEntity(
    @PrimaryKey val domain: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

/**
 * Hidden 64-bit hashes of unrecognized distracting/adult domains automatically detected
 * on-device by UnknownSiteClassifier. Plain-text domains are NOT stored or displayed.
 */
@Entity(tableName = "auto_learned_hashes")
data class AutoLearnedHashEntity(
    @PrimaryKey val domainHash64: Long,
    val categoryId: String,
    val learnedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "filter_feed_status")
data class FilterFeedStatusEntity(
    @PrimaryKey val feedId: String,
    val categoryId: String,
    val domainCount: Int = 0,
    val etag: String? = null,
    val lastSyncedMs: Long = 0L,
    val lastError: String? = null
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val blocklistId: Long = 1L,
    val isRecurring: Boolean = true,
    val daysMask: Int = ScheduleCalculator.WEEKDAYS_MASK,
    val startMinuteOfDay: Int = 9 * 60,
    val endMinuteOfDay: Int = 17 * 60,
    val oneTimeStartMs: Long = 0L,
    val oneTimeEndMs: Long = 0L,
    val strictMode: Boolean = true,
    val enabled: Boolean = true
) {
    fun toSpec(): ScheduleCalculator.ScheduleSpec = ScheduleCalculator.ScheduleSpec(
        id = id,
        name = name,
        blocklistId = blocklistId,
        isRecurring = isRecurring,
        daysMask = daysMask,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        oneTimeStartMs = oneTimeStartMs,
        oneTimeEndMs = oneTimeEndMs,
        strictMode = strictMode,
        enabled = enabled
    )
}

@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val blocklistId: Long = 1L,
    val label: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val endElapsedRealtimeMs: Long,
    val strictMode: Boolean,
    val fullInternetBrowsingBlock: Boolean = false,
    val blockedWebCategoriesCsv: String = "",
    val isScheduled: Boolean = false,
    val scheduleId: Long? = null,
    val active: Boolean = true,
    val completedNormally: Boolean = false,
    val blockedAttemptsCount: Int = 0
)

@Entity(tableName = "block_events")
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val timestampMs: Long,
    val packageName: String,
    val appLabel: String,
    val reason: String
)
