package com.example.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FreedomApplication
import com.example.blocker.FreedomAccessibilityService
import com.example.blocker.GuardPermissionStatus
import com.example.data.local.AllowedWebsiteEntity
import com.example.data.local.BlockEventEntity
import com.example.data.local.BlocklistAllowedAppEntity
import com.example.data.local.BlocklistProfileEntity
import com.example.data.local.CustomBlockedWebsiteEntity
import com.example.data.local.FocusSessionEntity
import com.example.data.local.ScheduleEntity
import com.example.data.repository.InstalledAppInfo
import com.example.data.sync.FilterSyncUiState
import com.example.domain.DndMode
import com.example.domain.FilterUpdateFrequency
import com.example.domain.StrictModePolicy
import com.example.domain.ThemeMode
import com.example.domain.WebsiteCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class FreedomUiState(
    val activeSession: FocusSessionEntity? = null,
    val blocklists: List<BlocklistProfileEntity> = emptyList(),
    val allowedAppsByBlocklist: Map<Long, List<BlocklistAllowedAppEntity>> = emptyMap(),
    val allowedPackagesByBlocklist: Map<Long, Set<String>> = emptyMap(),
    val allowedWebsites: List<AllowedWebsiteEntity> = emptyList(),
    val customBlockedWebsites: List<CustomBlockedWebsiteEntity> = emptyList(),
    val autoLearnedCount: Int = 0,
    val schedules: List<ScheduleEntity> = emptyList(),
    val recentSessions: List<FocusSessionEntity> = emptyList(),
    val recentBlockEvents: List<BlockEventEntity> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val permissions: GuardPermissionStatus? = null,
    val filterSync: FilterSyncUiState = FilterSyncUiState(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lockedModeDefault: Boolean = true,
    val dndEnabled: Boolean = false,
    val dndMode: DndMode = DndMode.PRIORITY_ONLY,
    val hideBlockedNotifications: Boolean = false,
    val autoDetectUnknownSites: Boolean = true,
    val filterUpdateFrequency: FilterUpdateFrequency = FilterUpdateFrequency.DAILY
) {
    val isSessionActive: Boolean
        get() = activeSession?.active == true

    val isStrictModeActive: Boolean
        get() = activeSession?.active == true && activeSession.strictMode

    fun allowedPackagesFor(blocklistId: Long): Set<String> =
        allowedPackagesByBlocklist[blocklistId] ?: emptySet()
}

class FreedomViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FreedomApplication).container
    private val repository = container.repository
    val permissionChecker = container.permissionChecker

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(true)
    private val _permissions = MutableStateFlow(permissionChecker.check())

    /**
     * Isolated 1-second countdown flow so live timer ticks ONLY recompose the
     * active-session countdown label instead of the entire UI tree.
     */
    private val _remainingMillis = MutableStateFlow(0L)
    val remainingMillis: StateFlow<Long> = _remainingMillis.asStateFlow()

    private val _themeMode = MutableStateFlow(repository.getThemeMode())
    private val _lockedModeDefault = MutableStateFlow(repository.isLockedModeDefault())
    private val _dndEnabled = MutableStateFlow(repository.isDndEnabled())
    private val _dndMode = MutableStateFlow(repository.getDndMode())
    private val _hideBlockedNotifications = MutableStateFlow(repository.isHideBlockedNotificationsEnabled())
    private val _autoDetectUnknownSites = MutableStateFlow(repository.isAutoDetectUnknownSitesEnabled())
    private val _filterUpdateFrequency = MutableStateFlow(repository.getFilterUpdateFrequency())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private data class CoreDbBundle(
        val active: FocusSessionEntity?,
        val blocklists: List<BlocklistProfileEntity>,
        val allowedAppsByBlocklist: Map<Long, List<BlocklistAllowedAppEntity>>,
        val allowedPackagesByBlocklist: Map<Long, Set<String>>,
        val allowedWebsites: List<AllowedWebsiteEntity>,
        val customBlockedWebsites: List<CustomBlockedWebsiteEntity>
    )

    private data class SecondaryBundle(
        val schedules: List<ScheduleEntity>,
        val recentSessions: List<FocusSessionEntity>,
        val events: List<BlockEventEntity>,
        val autoLearnedCount: Int,
        val filterSync: FilterSyncUiState
    )

    private data class PrefsBundle(
        val themeMode: ThemeMode,
        val lockedModeDefault: Boolean,
        val dndEnabled: Boolean,
        val dndMode: DndMode,
        val hideBlockedNotifications: Boolean,
        val autoDetectUnknownSites: Boolean,
        val filterUpdateFrequency: FilterUpdateFrequency
    )

    private val coreDbFlow = combine(
        repository.activeSessionFlow,
        repository.blocklistsFlow,
        repository.allBlocklistAllowedAppsFlow,
        repository.allowedWebsitesFlow,
        repository.customBlockedWebsitesFlow
    ) { active, blocklists, apps, allowedWebs, customBlocked ->
        val grouped = apps.groupBy { it.blocklistId }
        val pkgSets = grouped.mapValues { (_, list) -> list.map { it.packageName }.toSet() }
        CoreDbBundle(
            active = active,
            blocklists = blocklists,
            allowedAppsByBlocklist = grouped,
            allowedPackagesByBlocklist = pkgSets,
            allowedWebsites = allowedWebs,
            customBlockedWebsites = customBlocked
        )
    }

    private val secondaryFlow = combine(
        repository.schedulesFlow,
        repository.recentSessionsFlow,
        repository.recentBlockEventsFlow,
        repository.autoLearnedCountFlow,
        repository.filterSyncEngine.syncState
    ) { schedules, recent, events, learnedCount, sync ->
        SecondaryBundle(schedules, recent, events, learnedCount, sync)
    }

    private val prefsFlow = combine(
        _themeMode,
        _lockedModeDefault,
        _dndEnabled,
        _dndMode,
        _hideBlockedNotifications
    ) { theme, locked, dnd, dndMode, hideNotifs ->
        PrefsBundle(
            themeMode = theme,
            lockedModeDefault = locked,
            dndEnabled = dnd,
            dndMode = dndMode,
            hideBlockedNotifications = hideNotifs,
            autoDetectUnknownSites = _autoDetectUnknownSites.value,
            filterUpdateFrequency = _filterUpdateFrequency.value
        )
    }.combine(_autoDetectUnknownSites) { bundle, autoDetect ->
        bundle.copy(autoDetectUnknownSites = autoDetect)
    }.combine(_filterUpdateFrequency) { bundle, freq ->
        bundle.copy(filterUpdateFrequency = freq)
    }

    private val runtimeFlow = combine(
        _installedApps,
        _isLoadingApps,
        _permissions,
        FreedomAccessibilityService.isRunning
    ) { apps, loading, perms, _ ->
        Triple(apps, loading, perms)
    }

    val uiState: StateFlow<FreedomUiState> = combine(
        coreDbFlow,
        secondaryFlow,
        prefsFlow,
        runtimeFlow
    ) { core, sec, prefs, runtime ->
        val (apps, loading, perms) = runtime
        FreedomUiState(
            activeSession = core.active,
            blocklists = core.blocklists,
            allowedAppsByBlocklist = core.allowedAppsByBlocklist,
            allowedPackagesByBlocklist = core.allowedPackagesByBlocklist,
            allowedWebsites = core.allowedWebsites,
            customBlockedWebsites = core.customBlockedWebsites,
            autoLearnedCount = sec.autoLearnedCount,
            schedules = sec.schedules,
            recentSessions = sec.recentSessions,
            recentBlockEvents = sec.events,
            installedApps = apps,
            isLoadingApps = loading,
            permissions = perms,
            filterSync = sec.filterSync,
            themeMode = prefs.themeMode,
            lockedModeDefault = prefs.lockedModeDefault,
            dndEnabled = prefs.dndEnabled,
            dndMode = prefs.dndMode,
            hideBlockedNotifications = prefs.hideBlockedNotifications,
            autoDetectUnknownSites = prefs.autoDetectUnknownSites,
            filterUpdateFrequency = prefs.filterUpdateFrequency
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = FreedomUiState(
            permissions = permissionChecker.check(),
            themeMode = repository.getThemeMode()
        )
    )

    init {
        refreshInstalledApps()
        viewModelScope.launch {
            repository.reconcileSessionState()
        }
        // Only tick countdown while a focus session is active (0 recompositions when idle!)
        viewModelScope.launch {
            while (isActive) {
                val active = uiState.value.activeSession
                if (active != null && active.active) {
                    val rem = computeRemaining(active)
                    _remainingMillis.value = rem
                    if (rem == 0L) {
                        repository.reconcileSessionState()
                    }
                    delay(1_000L)
                } else {
                    if (_remainingMillis.value != 0L) {
                        _remainingMillis.value = 0L
                    }
                    delay(1_000L)
                }
            }
        }
    }

    private fun computeRemaining(active: FocusSessionEntity): Long {
        val wallRem = (active.endWallMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val monoRem = if (active.endElapsedRealtimeMs > 0L) {
            (active.endElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            0L
        }
        return maxOf(wallRem, monoRem)
    }

    fun refreshPermissions() {
        _permissions.value = permissionChecker.check()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            _installedApps.value = repository.loadLaunchableApps()
            _isLoadingApps.value = false
        }
    }

    // --- Sessions (Now, Later, Recurring) ---
    fun startSessionNow(
        label: String,
        blocklistId: Long,
        durationMinutes: Int,
        strictMode: Boolean
    ) {
        viewModelScope.launch {
            val perms = permissionChecker.check()
            _permissions.value = perms
            repository.startImmediateSession(label, blocklistId, durationMinutes, strictMode)
            _remainingMillis.value = durationMinutes * 60_000L
            if (!perms.isCoreBlockingReady) {
                _messages.tryEmit("Session started — enable Accessibility in Settings for real-time blocking.")
            } else {
                _messages.tryEmit(if (strictMode) "Locked session started." else "Focus session started.")
            }
        }
    }

    fun scheduleSessionLater(
        name: String,
        blocklistId: Long,
        startWallMs: Long,
        endWallMs: Long,
        strictMode: Boolean
    ) {
        viewModelScope.launch {
            when (val res = repository.scheduleLaterSession(name, blocklistId, startWallMs, endWallMs, strictMode)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Scheduled session saved.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun saveRecurringSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            when (val res = repository.saveRecurringSchedule(schedule)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Recurring schedule saved.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun toggleSchedule(schedule: ScheduleEntity, enabled: Boolean) {
        viewModelScope.launch {
            when (val res = repository.toggleScheduleEnabled(schedule, enabled)) {
                StrictModePolicy.GuardResult.Permitted -> Unit
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun deleteSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            when (val res = repository.deleteSchedule(schedule)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Schedule removed.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun stopActiveSession() {
        viewModelScope.launch {
            when (val res = repository.stopActiveSession()) {
                StrictModePolicy.GuardResult.Permitted -> {
                    _remainingMillis.value = 0L
                    _messages.tryEmit("Session ended.")
                }
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun extendActiveSession(extraMinutes: Int) {
        viewModelScope.launch {
            repository.extendActiveSession(extraMinutes)
            _remainingMillis.value = (_remainingMillis.value + extraMinutes * 60_000L)
            _messages.tryEmit("Extended by $extraMinutes min.")
        }
    }

    // --- Reusable Blocklist Profiles ---
    fun saveBlocklistProfile(
        id: Long,
        name: String,
        fullInternetBrowsingBlock: Boolean,
        blockedWebCategories: Set<WebsiteCategory>,
        allowedApps: Map<String, String>,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (
                val res = repository.createOrUpdateBlocklist(
                    id = id,
                    name = name,
                    fullInternetBrowsingBlock = fullInternetBrowsingBlock,
                    blockedWebCategories = blockedWebCategories,
                    allowedApps = allowedApps
                )
            ) {
                StrictModePolicy.GuardResult.Permitted -> {
                    _messages.tryEmit("Blocklist saved.")
                    onSuccess()
                }
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun deleteBlocklistProfile(profileId: Long) {
        viewModelScope.launch {
            when (val res = repository.deleteBlocklist(profileId)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Blocklist deleted.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    // --- Website Allowlist (URL Exceptions) & Custom Blocked Sites ---
    fun addAllowedWebsite(rawUrl: String) {
        viewModelScope.launch {
            when (val res = repository.addAllowedWebsite(rawUrl)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Website added to Allowlist.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun removeAllowedWebsite(pattern: String) {
        viewModelScope.launch {
            repository.removeAllowedWebsite(pattern)
            _messages.tryEmit("Removed from Allowed Websites.")
        }
    }

    fun addCustomBlockedWebsite(rawDomain: String) {
        viewModelScope.launch {
            when (val res = repository.addCustomBlockedWebsite(rawDomain)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Custom website blocked.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    fun removeCustomBlockedWebsite(domain: String) {
        viewModelScope.launch {
            when (val res = repository.removeCustomBlockedWebsite(domain)) {
                StrictModePolicy.GuardResult.Permitted -> _messages.tryEmit("Removed custom site.")
                is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
            }
        }
    }

    // --- Filter Sync & Settings ---
    fun updateFilterListsNow() {
        viewModelScope.launch {
            val result = repository.filterSyncEngine.syncAllFeeds(forceDownload = true)
            result.onSuccess {
                _messages.tryEmit("Website filter lists updated.")
            }.onFailure {
                _messages.tryEmit("Could not reach filter feeds — offline protection stays active.")
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        repository.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setLockedModeDefault(enabled: Boolean) {
        when (val res = repository.setLockedModeDefault(enabled)) {
            StrictModePolicy.GuardResult.Permitted -> _lockedModeDefault.value = enabled
            is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
        }
    }

    fun setDndEnabled(enabled: Boolean) {
        repository.setDndEnabled(enabled)
        _dndEnabled.value = enabled
    }

    fun setDndMode(mode: DndMode) {
        repository.setDndMode(mode)
        _dndMode.value = mode
    }

    fun setHideBlockedNotifications(enabled: Boolean) {
        repository.setHideBlockedNotificationsEnabled(enabled)
        _hideBlockedNotifications.value = enabled
    }

    fun setAutoDetectUnknownSites(enabled: Boolean) {
        when (val res = repository.setAutoDetectUnknownSitesEnabled(enabled)) {
            StrictModePolicy.GuardResult.Permitted -> _autoDetectUnknownSites.value = enabled
            is StrictModePolicy.GuardResult.Denied -> _messages.tryEmit(res.reason)
        }
    }

    fun setFilterUpdateFrequency(freq: FilterUpdateFrequency) {
        repository.setFilterUpdateFrequency(freq)
        _filterUpdateFrequency.value = freq
    }
}
