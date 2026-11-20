package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FreedomDao {

    // --- Reusable Blocklist Profiles ---
    @Query("SELECT * FROM blocklists ORDER BY id ASC")
    fun observeBlocklists(): Flow<List<BlocklistProfileEntity>>

    @Query("SELECT * FROM blocklists ORDER BY id ASC")
    suspend fun getAllBlocklists(): List<BlocklistProfileEntity>

    @Query("SELECT * FROM blocklists WHERE id = :id")
    suspend fun getBlocklistById(id: Long): BlocklistProfileEntity?

    @Query("SELECT COUNT(*) FROM blocklists")
    suspend fun countBlocklists(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocklist(profile: BlocklistProfileEntity): Long

    @Update
    suspend fun updateBlocklist(profile: BlocklistProfileEntity)

    @Query("DELETE FROM blocklists WHERE id = :id")
    suspend fun deleteBlocklist(id: Long)

    // --- Allowed Apps per Blocklist (Allowlist Mode) ---
    @Query("SELECT * FROM blocklist_allowed_apps ORDER BY appLabel COLLATE NOCASE ASC")
    fun observeAllBlocklistAllowedApps(): Flow<List<BlocklistAllowedAppEntity>>

    @Query("SELECT packageName FROM blocklist_allowed_apps WHERE blocklistId = :blocklistId")
    suspend fun getAllowedPackagesForBlocklist(blocklistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlocklistAllowedApp(app: BlocklistAllowedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlocklistAllowedApps(apps: List<BlocklistAllowedAppEntity>)

    @Query("DELETE FROM blocklist_allowed_apps WHERE blocklistId = :blocklistId AND packageName = :packageName")
    suspend fun deleteBlocklistAllowedApp(blocklistId: Long, packageName: String)

    @Query("DELETE FROM blocklist_allowed_apps WHERE blocklistId = :blocklistId")
    suspend fun clearBlocklistAllowedApps(blocklistId: Long)

    // --- Website Allowlist (Explicit User URL Exceptions) ---
    @Query("SELECT * FROM allowed_websites ORDER BY addedAtMs DESC")
    fun observeAllowedWebsites(): Flow<List<AllowedWebsiteEntity>>

    @Query("SELECT pattern FROM allowed_websites")
    suspend fun getAllowedWebsitePatterns(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllowedWebsite(entry: AllowedWebsiteEntity)

    @Query("DELETE FROM allowed_websites WHERE pattern = :pattern")
    suspend fun deleteAllowedWebsite(pattern: String)

    // --- Optional Custom Blocked Websites ---
    @Query("SELECT * FROM custom_blocked_websites ORDER BY addedAtMs DESC")
    fun observeCustomBlockedWebsites(): Flow<List<CustomBlockedWebsiteEntity>>

    @Query("SELECT domain FROM custom_blocked_websites")
    suspend fun getCustomBlockedDomains(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomBlockedWebsite(entry: CustomBlockedWebsiteEntity)

    @Query("DELETE FROM custom_blocked_websites WHERE domain = :domain")
    suspend fun deleteCustomBlockedWebsite(domain: String)

    // --- Hidden Auto-Learned Hashes ---
    @Query("SELECT * FROM auto_learned_hashes")
    suspend fun getAllAutoLearnedHashes(): List<AutoLearnedHashEntity>

    @Query("SELECT COUNT(*) FROM auto_learned_hashes")
    fun observeAutoLearnedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAutoLearnedHash(entry: AutoLearnedHashEntity)

    @Query("DELETE FROM auto_learned_hashes")
    suspend fun clearAutoLearnedHashes()

    // --- Filter Feed Status ---
    @Query("SELECT * FROM filter_feed_status ORDER BY feedId ASC")
    fun observeFilterFeedStatuses(): Flow<List<FilterFeedStatusEntity>>

    @Query("SELECT * FROM filter_feed_status")
    suspend fun getFilterFeedStatuses(): List<FilterFeedStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFilterFeedStatus(status: FilterFeedStatusEntity)

    // --- Schedules (Later & Recurring) ---
    @Query("SELECT * FROM schedules ORDER BY isRecurring DESC, startMinuteOfDay ASC, oneTimeStartMs ASC")
    fun observeSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun getEnabledSchedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Long)

    @Query("UPDATE schedules SET enabled = 0 WHERE isRecurring = 0 AND oneTimeEndMs > 0 AND oneTimeEndMs <= :nowMs")
    suspend fun disableExpiredOneTimeSchedules(nowMs: Long)

    // --- Focus Sessions ---
    @Query("SELECT * FROM focus_sessions WHERE active = 1 ORDER BY endWallMs DESC LIMIT 1")
    fun observeActiveSession(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE active = 1 ORDER BY endWallMs DESC LIMIT 1")
    suspend fun getActiveSession(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE active = 0 ORDER BY startWallMs DESC LIMIT 20")
    fun observeRecentSessions(): Flow<List<FocusSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSessionEntity): Long

    @Update
    suspend fun updateSession(session: FocusSessionEntity)

    @Query("UPDATE focus_sessions SET active = 0, completedNormally = :completedNormally WHERE active = 1")
    suspend fun deactivateAllSessions(completedNormally: Boolean)

    @Query("UPDATE focus_sessions SET blockedAttemptsCount = blockedAttemptsCount + 1 WHERE id = :sessionId")
    suspend fun incrementBlockedAttempts(sessionId: Long)

    // --- Block Events ---
    @Query("SELECT * FROM block_events ORDER BY timestampMs DESC LIMIT 60")
    fun observeRecentBlockEvents(): Flow<List<BlockEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockEvent(event: BlockEventEntity)

    @Query("DELETE FROM block_events")
    suspend fun clearBlockEvents()
}
