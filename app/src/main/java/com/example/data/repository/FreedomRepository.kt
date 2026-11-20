package com.example.data.repository

import android.content.Context
import android.os.SystemClock
import com.example.blocker.FreedomMonitorService
import com.example.blocker.FreedomNotificationListenerService
import com.example.blocker.SessionAlarmScheduler
import com.example.data.local.AllowedWebsiteEntity
import com.example.data.local.ApprovedYouTubeChannelEntity
import com.example.data.local.AutoLearnedHashEntity
import com.example.data.local.BlockEventEntity
import com.example.data.local.BlocklistAllowedAppEntity
import com.example.data.local.BlocklistProfileEntity
import com.example.data.local.CustomBlockedWebsiteEntity
import com.example.data.local.FastBlockStateStore
import com.example.data.local.FocusSessionEntity
import com.example.data.local.FreedomDao
import com.example.data.local.SafeSearchProviderRuleEntity
import com.example.data.local.ScheduleEntity
import com.example.data.local.YouTubeVideoChannelCacheEntity
import com.example.data.remote.YouTubeChannelResolver
import com.example.data.sync.FilterListSyncEngine
import com.example.domain.ChannelLookupState
import com.example.domain.ContentProtectionConfig
import com.example.domain.DndMode
import com.example.domain.DomainBlockMatcher
import com.example.domain.FilterUpdateFrequency
import com.example.domain.SafeSearchEnforceMethod
import com.example.domain.SafeSearchEngine
import com.example.domain.SafeSearchProviderRule
import com.example.domain.ScheduleCalculator
import com.example.domain.StrictModePolicy
import com.example.domain.ThemeMode
import com.example.domain.VerifiedYouTubeChannel
import com.example.domain.WebsiteCategory
import com.example.domain.YouTubeStudyEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FreedomRepository(
    private val context: Context,
    private val dao: FreedomDao,
    private val fastStore: FastBlockStateStore,
    private val installedAppsProvider: InstalledAppsProvider,
    private val alarmScheduler: SessionAlarmScheduler,
    val filterSyncEngine: FilterListSyncEngine,
    val youtubeChannelResolver: YouTubeChannelResolver = YouTubeChannelResolver()
) {
    private val reconcileMutex = Mutex()

    val blocklistsFlow: Flow<List<BlocklistProfileEntity>> = dao.observeBlocklists()
    val allBlocklistAllowedAppsFlow: Flow<List<BlocklistAllowedAppEntity>> = dao.observeAllBlocklistAllowedApps()
    val allowedWebsitesFlow: Flow<List<AllowedWebsiteEntity>> = dao.observeAllowedWebsites()
    val customBlockedWebsitesFlow: Flow<List<CustomBlockedWebsiteEntity>> = dao.observeCustomBlockedWebsites()
    val autoLearnedCountFlow: Flow<Int> = dao.observeAutoLearnedCount()
    val schedulesFlow: Flow<List<ScheduleEntity>> = dao.observeSchedules()
    val activeSessionFlow: Flow<FocusSessionEntity?> = dao.observeActiveSession()
    val recentSessionsFlow: Flow<List<FocusSessionEntity>> = dao.observeRecentSessions()
    val recentBlockEventsFlow: Flow<List<BlockEventEntity>> = dao.observeRecentBlockEvents()
    val approvedYouTubeChannelsFlow: Flow<List<ApprovedYouTubeChannelEntity>> = dao.observeApprovedYouTubeChannels()
    val safeSearchProviderRulesFlow: Flow<List<SafeSearchProviderRuleEntity>> = dao.observeSafeSearchProviderRules()

    suspend fun initialize() {
        ensureDefaultBlocklistExists()
        ensureDefaultSafeSearchRulesSeeded()
        syncApprovedYouTubeChannelsToFastStore()
        filterSyncEngine.loadCachedIndexIntoMemory()
        reconcileSessionState()
        filterSyncEngine.syncIfDue()
    }

    suspend fun onDeviceBooted() {
        ensureDefaultBlocklistExists()
        filterSyncEngine.loadCachedIndexIntoMemory()
        val active = dao.getActiveSession()
        if (active != null) {
            val nowWall = System.currentTimeMillis()
            if (active.endWallMs > nowWall) {
                val remaining = active.endWallMs - nowWall
                val newMonoEnd = SystemClock.elapsedRealtime() + remaining
                val updated = active.copy(endElapsedRealtimeMs = newMonoEnd)
                dao.updateSession(updated)
                syncFastStoreForSession(updated)
            }
        }
        reconcileSessionState()
    }

    private suspend fun ensureDefaultBlocklistExists() {
        if (dao.countBlocklists() == 0) {
            dao.insertBlocklist(
                BlocklistProfileEntity(
                    name = "Deep Focus",
                    fullInternetBrowsingBlock = false,
                    blockedWebCategoriesCsv = WebsiteCategory.toCsv(
                        listOf(
                            WebsiteCategory.SOCIAL,
                            WebsiteCategory.META,
                            WebsiteCategory.TV_VIDEO,
                            WebsiteCategory.GAMBLING,
                            WebsiteCategory.ADULT
                        )
                    )
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Reusable Blocklist Profiles (Allowlist Apps + 16 Hidden Web Categories + Full Browsing Block)
    // -------------------------------------------------------------------------

    suspend fun createOrUpdateBlocklist(
        id: Long,
        name: String,
        fullInternetBrowsingBlock: Boolean,
        blockedWebCategories: Set<WebsiteCategory>,
        allowedApps: Map<String, String>
    ): StrictModePolicy.GuardResult {
        val activeSnap = fastStore.readSnapshot()
        val isActiveStrictProfile = activeSnap.isStillActive(System.currentTimeMillis(), SystemClock.elapsedRealtime()) &&
            activeSnap.strictMode && activeSnap.blocklistId == id

        if (isActiveStrictProfile) {
            val existing = dao.getBlocklistById(id)
            if (existing != null) {
                val oldCats = WebsiteCategory.parseCsv(existing.blockedWebCategoriesCsv)
                val removedAnyCategory = oldCats.any { it !in blockedWebCategories }
                val turnedOffFullBrowsing = existing.fullInternetBrowsingBlock && !fullInternetBrowsingBlock
                if (removedAnyCategory || turnedOffFullBrowsing) {
                    return StrictModePolicy.canWeakenBlocklist(true)
                }
                val oldAllowed = dao.getAllowedPackagesForBlocklist(id).toSet()
                val addedAnyApp = allowedApps.keys.any { it !in oldAllowed }
                if (addedAnyApp) {
                    return StrictModePolicy.canAddAllowedApp(true)
                }
            }
        }

        val cleanName = name.trim().ifEmpty { "My Blocklist" }
        val profileId = if (id == 0L) {
            dao.insertBlocklist(
                BlocklistProfileEntity(
                    name = cleanName,
                    fullInternetBrowsingBlock = fullInternetBrowsingBlock,
                    blockedWebCategoriesCsv = WebsiteCategory.toCsv(blockedWebCategories)
                )
            )
        } else {
            val existing = dao.getBlocklistById(id)
            dao.updateBlocklist(
                BlocklistProfileEntity(
                    id = id,
                    name = cleanName,
                    fullInternetBrowsingBlock = fullInternetBrowsingBlock,
                    blockedWebCategoriesCsv = WebsiteCategory.toCsv(blockedWebCategories),
                    createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis()
                )
            )
            id
        }

        dao.clearBlocklistAllowedApps(profileId)
        if (allowedApps.isNotEmpty()) {
            dao.upsertBlocklistAllowedApps(
                allowedApps.map { (pkg, label) ->
                    BlocklistAllowedAppEntity(blocklistId = profileId, packageName = pkg, appLabel = label)
                }
            )
        }

        // If this profile is currently active, sync fast store immediately
        if (activeSnap.isStillActive(System.currentTimeMillis(), SystemClock.elapsedRealtime()) &&
            activeSnap.blocklistId == profileId
        ) {
            refreshActiveFastStoreRules(profileId)
        }

        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun deleteBlocklist(profileId: Long): StrictModePolicy.GuardResult {
        val all = dao.getAllBlocklists()
        if (all.size <= 1) {
            return StrictModePolicy.GuardResult.Denied("Keep at least one Blocklist profile.")
        }
        val activeSnap = fastStore.readSnapshot()
        if (activeSnap.isStillActive(System.currentTimeMillis(), SystemClock.elapsedRealtime()) &&
            activeSnap.blocklistId == profileId
        ) {
            return StrictModePolicy.GuardResult.Denied("Cannot delete a Blocklist while it is enforcing an active session.")
        }
        dao.clearBlocklistAllowedApps(profileId)
        dao.deleteBlocklist(profileId)
        return StrictModePolicy.GuardResult.Permitted
    }

    // -------------------------------------------------------------------------
    // Website Allowlist (Explicit User URL Exceptions) & Custom Blocked Sites
    // -------------------------------------------------------------------------

    suspend fun addAllowedWebsite(rawUrl: String): StrictModePolicy.GuardResult {
        val guard = StrictModePolicy.canAddAllowedWebsite(isStrictModeCurrentlyActive())
        if (guard is StrictModePolicy.GuardResult.Denied) return guard

        val normalized = DomainBlockMatcher.normalizeAllowRule(rawUrl)
            ?: return StrictModePolicy.GuardResult.Denied("Enter a valid domain or URL (e.g., wikipedia.org or youtube.com/@channel).")

        dao.upsertAllowedWebsite(
            AllowedWebsiteEntity(
                pattern = normalized.first,
                isPathRule = normalized.second
            )
        )
        fastStore.writeAllowedWebPatterns(dao.getAllowedWebsitePatterns().toSet())
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun removeAllowedWebsite(pattern: String) {
        dao.deleteAllowedWebsite(pattern)
        fastStore.writeAllowedWebPatterns(dao.getAllowedWebsitePatterns().toSet())
    }

    suspend fun addCustomBlockedWebsite(rawDomain: String): StrictModePolicy.GuardResult {
        val normalized = DomainBlockMatcher.normalizeDomain(rawDomain)
            ?: return StrictModePolicy.GuardResult.Denied("Enter a valid domain (e.g., example.com).")
        dao.upsertCustomBlockedWebsite(CustomBlockedWebsiteEntity(domain = normalized))
        fastStore.writeCustomBlockedDomains(dao.getCustomBlockedDomains().toSet())
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun removeCustomBlockedWebsite(domain: String): StrictModePolicy.GuardResult {
        val guard = StrictModePolicy.canWeakenBlocklist(isStrictModeCurrentlyActive())
        if (guard is StrictModePolicy.GuardResult.Denied) return guard
        dao.deleteCustomBlockedWebsite(domain)
        fastStore.writeCustomBlockedDomains(dao.getCustomBlockedDomains().toSet())
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun recordAutoLearnedDomainHash(hash64: Long, category: WebsiteCategory) {
        dao.insertAutoLearnedHash(
            AutoLearnedHashEntity(
                domainHash64 = hash64,
                categoryId = category.id
            )
        )
    }

    // -------------------------------------------------------------------------
    // Sessions: NOW (Immediate), LATER (One-Time Future), RECURRING (Schedules)
    // -------------------------------------------------------------------------

    suspend fun startImmediateSession(
        label: String,
        blocklistId: Long,
        durationMinutes: Int,
        strictMode: Boolean
    ) {
        reconcileMutex.withLock {
            val profile = dao.getBlocklistById(blocklistId) ?: dao.getAllBlocklists().firstOrNull()
            val resolvedProfileId = profile?.id ?: 1L
            val nowWall = System.currentTimeMillis()
            val nowMono = SystemClock.elapsedRealtime()
            val durationMs = durationMinutes.coerceIn(1, 24 * 60) * 60_000L

            dao.deactivateAllSessions(completedNormally = false)
            val session = FocusSessionEntity(
                blocklistId = resolvedProfileId,
                label = label.trim().ifEmpty { "Focus Session" },
                startWallMs = nowWall,
                endWallMs = nowWall + durationMs,
                endElapsedRealtimeMs = nowMono + durationMs,
                strictMode = strictMode,
                fullInternetBrowsingBlock = profile?.fullInternetBrowsingBlock ?: false,
                blockedWebCategoriesCsv = profile?.blockedWebCategoriesCsv ?: "",
                isScheduled = false,
                scheduleId = null,
                active = true
            )
            val newId = dao.insertSession(session)
            val persisted = session.copy(id = newId)

            syncFastStoreForSession(persisted)
            alarmScheduler.scheduleSessionEnd(persisted.endWallMs)
            FreedomMonitorService.sync(context)
            FreedomNotificationListenerService.sweepNow()
        }
    }

    suspend fun scheduleLaterSession(
        name: String,
        blocklistId: Long,
        startWallMs: Long,
        endWallMs: Long,
        strictMode: Boolean
    ): StrictModePolicy.GuardResult {
        if (endWallMs <= startWallMs) {
            return StrictModePolicy.GuardResult.Denied("End time must be after start time.")
        }
        val cleanName = name.trim().ifEmpty { "Scheduled Session" }
        dao.insertSchedule(
            ScheduleEntity(
                name = cleanName,
                blocklistId = blocklistId,
                isRecurring = false,
                daysMask = 0,
                startMinuteOfDay = 0,
                endMinuteOfDay = 0,
                oneTimeStartMs = startWallMs,
                oneTimeEndMs = endWallMs,
                strictMode = strictMode,
                enabled = true
            )
        )
        reconcileSessionState()
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun saveRecurringSchedule(schedule: ScheduleEntity): StrictModePolicy.GuardResult {
        val active = dao.getActiveSession()
        if (schedule.id != 0L) {
            val guard = StrictModePolicy.canModifyOrDisableSchedule(
                scheduleId = schedule.id,
                activeStrictScheduleId = active?.scheduleId,
                isStrictModeActive = isStrictModeCurrentlyActive()
            )
            if (guard is StrictModePolicy.GuardResult.Denied) return guard
            dao.updateSchedule(schedule.copy(isRecurring = true))
        } else {
            dao.insertSchedule(schedule.copy(isRecurring = true))
        }
        reconcileSessionState()
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun toggleScheduleEnabled(schedule: ScheduleEntity, enabled: Boolean): StrictModePolicy.GuardResult {
        if (!enabled) {
            val active = dao.getActiveSession()
            val guard = StrictModePolicy.canModifyOrDisableSchedule(
                scheduleId = schedule.id,
                activeStrictScheduleId = active?.scheduleId,
                isStrictModeActive = isStrictModeCurrentlyActive()
            )
            if (guard is StrictModePolicy.GuardResult.Denied) return guard
        }
        dao.updateSchedule(schedule.copy(enabled = enabled))
        reconcileSessionState()
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun deleteSchedule(schedule: ScheduleEntity): StrictModePolicy.GuardResult {
        val active = dao.getActiveSession()
        val guard = StrictModePolicy.canModifyOrDisableSchedule(
            scheduleId = schedule.id,
            activeStrictScheduleId = active?.scheduleId,
            isStrictModeActive = isStrictModeCurrentlyActive()
        )
        if (guard is StrictModePolicy.GuardResult.Denied) return guard
        dao.deleteSchedule(schedule.id)
        reconcileSessionState()
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun stopActiveSession(): StrictModePolicy.GuardResult {
        return reconcileMutex.withLock {
            val active = dao.getActiveSession() ?: return@withLock StrictModePolicy.GuardResult.Permitted
            val guard = StrictModePolicy.canStopSession(active.strictMode)
            if (guard is StrictModePolicy.GuardResult.Denied) return@withLock guard

            dao.updateSession(active.copy(active = false, completedNormally = false))
            fastStore.clearSession()
            alarmScheduler.cancelSessionEnd()
            FreedomMonitorService.stop(context)
            StrictModePolicy.GuardResult.Permitted
        }
    }

    suspend fun extendActiveSession(extraMinutes: Int) {
        reconcileMutex.withLock {
            val active = dao.getActiveSession() ?: return
            val addMs = extraMinutes.coerceAtLeast(1) * 60_000L
            val updated = active.copy(
                endWallMs = active.endWallMs + addMs,
                endElapsedRealtimeMs = active.endElapsedRealtimeMs + addMs
            )
            dao.updateSession(updated)
            syncFastStoreForSession(updated)
            alarmScheduler.scheduleSessionEnd(updated.endWallMs)
            FreedomMonitorService.sync(context)
        }
    }

    suspend fun reconcileSessionState() {
        reconcileMutex.withLock {
            val nowWall = System.currentTimeMillis()
            val nowMono = SystemClock.elapsedRealtime()

            dao.disableExpiredOneTimeSchedules(nowWall)

            var active = dao.getActiveSession()
            if (active != null) {
                val wallExpired = nowWall >= active.endWallMs
                val monoExpired = active.endElapsedRealtimeMs <= 0L || nowMono >= active.endElapsedRealtimeMs
                if (wallExpired && monoExpired) {
                    dao.updateSession(active.copy(active = false, completedNormally = true))
                    active = null
                }
            }

            val enabledSchedules = dao.getEnabledSchedules().map { it.toSpec() }
            val activeScheduleWindow = ScheduleCalculator.findDominantActiveWindow(enabledSchedules, nowWall)

            if (active == null && activeScheduleWindow != null) {
                val primaryProfile = dao.getBlocklistById(activeScheduleWindow.schedule.blocklistId)
                    ?: dao.getAllBlocklists().firstOrNull()
                val allProfiles = activeScheduleWindow.allActiveBlocklistIds.mapNotNull { dao.getBlocklistById(it) }
                val unionCats = allProfiles.flatMap { WebsiteCategory.parseCsv(it.blockedWebCategoriesCsv) }.toSet()
                val anyFullBrowsing = allProfiles.any { it.fullInternetBrowsingBlock }

                val remainingMs = (activeScheduleWindow.endEpochMs - nowWall).coerceAtLeast(1_000L)
                val newSession = FocusSessionEntity(
                    blocklistId = primaryProfile?.id ?: 1L,
                    label = activeScheduleWindow.schedule.name,
                    startWallMs = activeScheduleWindow.startEpochMs,
                    endWallMs = activeScheduleWindow.endEpochMs,
                    endElapsedRealtimeMs = nowMono + remainingMs,
                    strictMode = activeScheduleWindow.schedule.strictMode,
                    fullInternetBrowsingBlock = anyFullBrowsing,
                    blockedWebCategoriesCsv = WebsiteCategory.toCsv(unionCats),
                    isScheduled = true,
                    scheduleId = activeScheduleWindow.schedule.id,
                    active = true
                )
                val id = dao.insertSession(newSession)
                active = newSession.copy(id = id)
            } else if (active != null && activeScheduleWindow != null) {
                val mergedStrict = active.strictMode || activeScheduleWindow.schedule.strictMode
                val mergedEndWall = maxOf(active.endWallMs, activeScheduleWindow.endEpochMs)
                val neededRemaining = (mergedEndWall - nowWall).coerceAtLeast(0L)
                val mergedMono = maxOf(active.endElapsedRealtimeMs, nowMono + neededRemaining)
                if (mergedStrict != active.strictMode || mergedEndWall != active.endWallMs) {
                    val updated = active.copy(
                        strictMode = mergedStrict,
                        endWallMs = mergedEndWall,
                        endElapsedRealtimeMs = mergedMono
                    )
                    dao.updateSession(updated)
                    active = updated
                }
            }

            if (active != null) {
                syncFastStoreForSession(active)
                alarmScheduler.scheduleSessionEnd(active.endWallMs)
                FreedomMonitorService.sync(context)
            } else {
                fastStore.clearSession()
                alarmScheduler.cancelSessionEnd()
                FreedomMonitorService.stop(context)
            }

            val nextTransition = ScheduleCalculator.findNextTransition(enabledSchedules, nowWall)
            alarmScheduler.scheduleNextScheduleTransition(nextTransition?.triggerEpochMs)
        }
    }

    private suspend fun syncFastStoreForSession(session: FocusSessionEntity) {
        val allowedApps = dao.getAllowedPackagesForBlocklist(session.blocklistId).toSet()
        val allowedWebs = dao.getAllowedWebsitePatterns().toSet()
        val customBlocked = dao.getCustomBlockedDomains().toSet()
        fastStore.writeSession(
            isActive = true,
            sessionId = session.id,
            blocklistId = session.blocklistId,
            label = session.label,
            endWallTimeMs = session.endWallMs,
            endElapsedRealtimeMs = session.endElapsedRealtimeMs,
            strictMode = session.strictMode,
            fullInternetBrowsingBlock = session.fullInternetBrowsingBlock,
            allowedPackages = allowedApps,
            allowedWebPatterns = allowedWebs,
            customBlockedDomains = customBlocked,
            blockedWebCategories = WebsiteCategory.parseCsv(session.blockedWebCategoriesCsv)
        )
    }

    private suspend fun refreshActiveFastStoreRules(blocklistId: Long) {
        val profile = dao.getBlocklistById(blocklistId) ?: return
        val allowedApps = dao.getAllowedPackagesForBlocklist(blocklistId).toSet()
        val allowedWebs = dao.getAllowedWebsitePatterns().toSet()
        val customBlocked = dao.getCustomBlockedDomains().toSet()
        fastStore.updateActiveRules(
            allowedPackages = allowedApps,
            allowedWebPatterns = allowedWebs,
            customBlockedDomains = customBlocked,
            blockedWebCategories = WebsiteCategory.parseCsv(profile.blockedWebCategoriesCsv),
            fullInternetBrowsingBlock = profile.fullInternetBrowsingBlock
        )
    }

    // -------------------------------------------------------------------------
    // Preferences & Logging
    // -------------------------------------------------------------------------

    fun getThemeMode(): ThemeMode = fastStore.getThemeMode()
    fun setThemeMode(mode: ThemeMode) = fastStore.setThemeMode(mode)

    fun isLockedModeDefault(): Boolean = fastStore.isLockedModeDefault()
    fun setLockedModeDefault(enabled: Boolean): StrictModePolicy.GuardResult {
        if (!enabled) {
            val guard = StrictModePolicy.canDisableLockedModeSetting(isStrictModeCurrentlyActive())
            if (guard is StrictModePolicy.GuardResult.Denied) return guard
        }
        fastStore.setLockedModeDefault(enabled)
        return StrictModePolicy.GuardResult.Permitted
    }

    fun isDndEnabled(): Boolean = fastStore.isDndEnabled()
    fun setDndEnabled(enabled: Boolean) {
        fastStore.setDndEnabled(enabled)
        if (fastStore.readSnapshot().isStillActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            FreedomMonitorService.sync(context)
        }
    }

    fun getDndMode(): DndMode = fastStore.getDndMode()
    fun setDndMode(mode: DndMode) {
        fastStore.setDndMode(mode)
        if (fastStore.readSnapshot().isStillActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            FreedomMonitorService.sync(context)
        }
    }

    fun isHideBlockedNotificationsEnabled(): Boolean = fastStore.isHideBlockedNotificationsEnabled()
    fun setHideBlockedNotificationsEnabled(enabled: Boolean) {
        fastStore.setHideBlockedNotificationsEnabled(enabled)
        if (enabled) FreedomNotificationListenerService.sweepNow()
    }

    fun isAutoDetectUnknownSitesEnabled(): Boolean = fastStore.isAutoDetectUnknownSitesEnabled()
    fun setAutoDetectUnknownSitesEnabled(enabled: Boolean): StrictModePolicy.GuardResult {
        if (!enabled) {
            val guard = StrictModePolicy.canDisableAutoDetect(isStrictModeCurrentlyActive())
            if (guard is StrictModePolicy.GuardResult.Denied) return guard
        }
        fastStore.setAutoDetectUnknownSitesEnabled(enabled)
        return StrictModePolicy.GuardResult.Permitted
    }

    fun getFilterUpdateFrequency(): FilterUpdateFrequency = fastStore.getFilterUpdateFrequency()
    fun setFilterUpdateFrequency(freq: FilterUpdateFrequency) = fastStore.setFilterUpdateFrequency(freq)

    suspend fun recordBlockEvent(
        sessionId: Long,
        packageName: String,
        appLabel: String,
        reason: String
    ) {
        if (sessionId > 0L) {
            dao.incrementBlockedAttempts(sessionId)
        }
        dao.insertBlockEvent(
            BlockEventEntity(
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                packageName = packageName,
                appLabel = appLabel,
                reason = reason
            )
        )
    }

    fun isStrictModeCurrentlyActive(): Boolean {
        val snap = fastStore.readSnapshot()
        return snap.strictMode && snap.isStillActive(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime()
        )
    }

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> =
        installedAppsProvider.loadLaunchableApps()

    // -------------------------------------------------------------------------
    // Advanced Content Protection: YouTube Study Mode, Universal SafeSearch,
    // and In-App Adult-Content Filtering
    // -------------------------------------------------------------------------

    fun getContentProtectionConfig(): ContentProtectionConfig =
        fastStore.readContentProtectionConfig()

    fun updateContentProtectionConfig(
        transform: (ContentProtectionConfig) -> ContentProtectionConfig
    ): StrictModePolicy.GuardResult {
        val current = fastStore.readContentProtectionConfig()
        val updated = transform(current)
        val strictActive = isStrictModeCurrentlyActive()

        if (strictActive) {
            if (current.youtubeStudyModeEnabled && !updated.youtubeStudyModeEnabled) {
                return StrictModePolicy.canDisableContentProtection("YouTube Study Mode", true)
            }
            if (current.safeSearchEnabled && !updated.safeSearchEnabled) {
                return StrictModePolicy.canDisableContentProtection("Universal SafeSearch", true)
            }
            if (current.inAppAdultFilterEnabled && !updated.inAppAdultFilterEnabled) {
                return StrictModePolicy.canDisableContentProtection("In-App Adult Protection", true)
            }
            if (!current.youtubeAllowShortsFromApproved && updated.youtubeAllowShortsFromApproved) {
                return StrictModePolicy.canWeakenContentProtectionRule("YouTube Shorts blocking", true)
            }
            if (!current.youtubeAllowLiveFromApproved && updated.youtubeAllowLiveFromApproved) {
                return StrictModePolicy.canWeakenContentProtectionRule("YouTube Live blocking", true)
            }
            if (current.youtubeMaskFeedCards && !updated.youtubeMaskFeedCards) {
                return StrictModePolicy.canWeakenContentProtectionRule("YouTube feed card masking", true)
            }
            if (current.blockUnverifiedSearchEnginesInStrictMode && !updated.blockUnverifiedSearchEnginesInStrictMode) {
                return StrictModePolicy.canWeakenContentProtectionRule("Unverified search engine blocking", true)
            }
            if (current.blockImageSearchInStrictMode && !updated.blockImageSearchInStrictMode) {
                return StrictModePolicy.canWeakenContentProtectionRule("Strict Mode Image Search blocking", true)
            }
            if (current.blockUnfilterableAppsInStrictMode && !updated.blockUnfilterableAppsInStrictMode) {
                return StrictModePolicy.canWeakenContentProtectionRule("Unfilterable app blocking", true)
            }
        }

        fastStore.writeContentProtectionConfig(updated)
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun lookupYouTubeChannel(rawInput: String): ChannelLookupState {
        val apiKey = fastStore.readContentProtectionConfig().youtubeDataApiKey
        return youtubeChannelResolver.resolveInput(rawInput, apiKey)
    }

    suspend fun verifyManualYouTubeChannelId(rawChannelId: String): ChannelLookupState {
        val apiKey = fastStore.readContentProtectionConfig().youtubeDataApiKey
        return youtubeChannelResolver.verifyByPermanentChannelId(rawChannelId, apiKey)
    }

    /**
     * Saves an already-verified YouTube channel to the shared offline Room allowlist.
     * Rejects unverified/malformed UC... IDs, prevents duplicates, and blocks edits during Strict Mode.
     */
    suspend fun approveVerifiedYouTubeChannel(
        channel: VerifiedYouTubeChannel
    ): StrictModePolicy.GuardResult {
        val guard = StrictModePolicy.canModifyApprovedYouTubeChannels(isStrictModeCurrentlyActive())
        if (guard is StrictModePolicy.GuardResult.Denied) return guard

        val cid = channel.channelId.trim()
        if (!YouTubeStudyEngine.isValidPermanentChannelId(cid)) {
            return StrictModePolicy.GuardResult.Denied("Channel must have a verified permanent UC... Channel ID before approval.")
        }
        val cleanTitle = channel.title.trim()
        if (cleanTitle.isEmpty()) {
            return StrictModePolicy.GuardResult.Denied("Channel identity could not be verified.")
        }

        val existing = dao.getApprovedYouTubeChannelById(cid)
        if (existing != null) {
            return StrictModePolicy.GuardResult.Denied("\"${existing.title}\" is already in your approved channels list.")
        }

        dao.upsertApprovedYouTubeChannel(
            ApprovedYouTubeChannelEntity(
                channelId = cid,
                handle = YouTubeStudyEngine.normalizeHandle(channel.handle),
                title = cleanTitle,
                avatarUrl = channel.avatarUrl.trim(),
                subscriberSummary = channel.subscriberSummary.trim(),
                verifiedSource = channel.verifiedSource.name,
                verifiedAtMs = System.currentTimeMillis()
            )
        )
        syncApprovedYouTubeChannelsToFastStore()
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun removeApprovedYouTubeChannel(channelId: String): StrictModePolicy.GuardResult {
        val guard = StrictModePolicy.canModifyApprovedYouTubeChannels(isStrictModeCurrentlyActive())
        if (guard is StrictModePolicy.GuardResult.Denied) return guard

        dao.deleteApprovedYouTubeChannel(channelId)
        syncApprovedYouTubeChannelsToFastStore()
        return StrictModePolicy.GuardResult.Permitted
    }

    suspend fun verifyAndCacheDirectVideoChannel(videoId: String): String? {
        dao.getCachedVideoChannel(videoId)?.let { cached ->
            fastStore.putVerifiedVideoChannelInFastCache(videoId, cached.channelId)
            return cached.channelId
        }
        val apiKey = fastStore.readContentProtectionConfig().youtubeDataApiKey
        val resolved = youtubeChannelResolver.resolveVideoToPermanentChannelId(videoId, apiKey)
            ?: return null
        val (channelId, title) = resolved
        dao.upsertVideoChannelCache(
            YouTubeVideoChannelCacheEntity(
                videoId = videoId,
                channelId = channelId,
                channelTitle = title,
                verifiedAtMs = System.currentTimeMillis()
            )
        )
        fastStore.putVerifiedVideoChannelInFastCache(videoId, channelId)
        return channelId
    }

    suspend fun syncApprovedYouTubeChannelsToFastStore() {
        val channels = dao.getAllApprovedYouTubeChannels()
        val videoCache = dao.getRecentVideoChannelCache().associate { it.videoId to it.channelId }
        fastStore.writeApprovedChannelsSnapshot(channels, videoCache)
    }

    suspend fun ensureDefaultSafeSearchRulesSeeded() {
        if (dao.countSafeSearchProviderRules() == 0) {
            val entities = SafeSearchEngine.DEFAULT_PROVIDER_RULES.map { it.toEntity() }
            dao.upsertSafeSearchProviderRules(entities)
        }
        val persisted = dao.getAllSafeSearchProviderRules().map { it.toDomain() }
        if (persisted.isNotEmpty()) {
            fastStore.updateSafeSearchRulesInMemory(persisted)
        }
    }

    suspend fun refreshSafeSearchProviderRules(): Int {
        // Re-seeds any newly added built-in provider rules while preserving existing entries safely
        val existingIds = dao.getAllSafeSearchProviderRules().map { it.providerId }.toSet()
        val missingOrRefreshed = SafeSearchEngine.DEFAULT_PROVIDER_RULES.map { it.toEntity() }
        if (missingOrRefreshed.isNotEmpty()) {
            dao.upsertSafeSearchProviderRules(missingOrRefreshed)
        }
        val all = dao.getAllSafeSearchProviderRules().map { it.toDomain() }
        if (all.isNotEmpty()) {
            fastStore.updateSafeSearchRulesInMemory(all)
        }
        return (all.size - existingIds.size).coerceAtLeast(0)
    }

    private fun SafeSearchProviderRule.toEntity(): SafeSearchProviderRuleEntity =
        SafeSearchProviderRuleEntity(
            providerId = providerId,
            displayName = displayName,
            hostsCsv = hosts.joinToString(","),
            searchPathsCsv = searchPaths.joinToString(","),
            strictParamKey = strictParamKey,
            strictParamValue = strictParamValue,
            unsafeParamValuesCsv = unsafeParamValues.joinToString(","),
            settingsPathsCsv = settingsPaths.joinToString(","),
            imageIndicatorsCsv = imageIndicators.joinToString(","),
            enforceMethod = enforceMethod.name,
            updatedAtMs = System.currentTimeMillis()
        )

    private fun SafeSearchProviderRuleEntity.toDomain(): SafeSearchProviderRule =
        SafeSearchProviderRule(
            providerId = providerId,
            displayName = displayName,
            hosts = hostsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            searchPaths = searchPathsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            strictParamKey = strictParamKey,
            strictParamValue = strictParamValue,
            unsafeParamValues = unsafeParamValuesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            settingsPaths = settingsPathsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            imageIndicators = imageIndicatorsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            enforceMethod = SafeSearchEnforceMethod.fromName(enforceMethod)
        )
}
