package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blocker.PermissionStatusChecker
import com.example.domain.DndMode
import com.example.domain.FilterUpdateFrequency
import com.example.domain.ThemeMode
import com.example.ui.FreedomUiState
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomMotion
import com.example.ui.theme.FreedomSwitch
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassGroupedRow
import com.example.ui.theme.GlassPillButton
import com.example.ui.theme.GlassSegmentedControl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SettingsSubScreen {
    ROOT,
    BLOCKING_SETTINGS,
    FILTER_UPDATES,
    ABOUT
}

/**
 * Clean Settings & Blocking Settings screens inspired directly by Screenshots 2 & 6:
 *  - Configure Freedom grouped card (Blocking Settings, Website Filter Updates, Appearance, About)
 *  - Blocking Settings: Locked Mode (5-min Settings wait -> 3-min auto-relock + hard-locked system apps),
 *    Do Not Disturb + DnD Mode, Disable Notifications from blocked apps, and Blocking Permissions checklist.
 *  - Website Filter Updates: aggregate sync health, Update Now button, auto-update cadence, Smart Auto-Detect
 *    toggle — NEVER displaying any blocked domain lists.
 */
@Composable
fun SettingsScreen(
    uiState: FreedomUiState,
    permissionChecker: PermissionStatusChecker,
    openBlockingDirectly: Boolean = false,
    onConsumeOpenBlockingDirectly: () -> Unit = {},
    onRefreshPermissions: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetLockedModeDefault: (Boolean) -> Unit,
    onSetDndEnabled: (Boolean) -> Unit,
    onSetDndMode: (DndMode) -> Unit,
    onSetHideNotifications: (Boolean) -> Unit,
    onSetAutoDetectUnknownSites: (Boolean) -> Unit,
    onSetFilterFrequency: (FilterUpdateFrequency) -> Unit,
    onUpdateFiltersNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subScreen by remember(openBlockingDirectly) {
        mutableStateOf(
            if (openBlockingDirectly) {
                onConsumeOpenBlockingDirectly()
                SettingsSubScreen.BLOCKING_SETTINGS
            } else {
                SettingsSubScreen.ROOT
            }
        )
    }

    AnimatedContent(
        targetState = subScreen,
        transitionSpec = {
            val enteringChild = targetState != SettingsSubScreen.ROOT
            (slideInHorizontally(
                animationSpec = FreedomMotion.SlideOffsetSpec,
                initialOffsetX = { if (enteringChild) it / 8 else -it / 10 }
            ) + fadeIn(FreedomMotion.FastFadeIn)) togetherWith (slideOutHorizontally(
                animationSpec = FreedomMotion.SlideOffsetSpec,
                targetOffsetX = { if (enteringChild) -it / 10 else it / 8 }
            ) + fadeOut(FreedomMotion.FastFadeOut))
        },
        label = "settings_subscreen"
    ) { active ->
        when (active) {
            SettingsSubScreen.ROOT -> {
                SettingsRootView(
                    uiState = uiState,
                    onOpenBlockingSettings = { subScreen = SettingsSubScreen.BLOCKING_SETTINGS },
                    onOpenFilterUpdates = { subScreen = SettingsSubScreen.FILTER_UPDATES },
                    onOpenAbout = { subScreen = SettingsSubScreen.ABOUT },
                    onSetThemeMode = onSetThemeMode,
                    modifier = modifier
                )
            }

            SettingsSubScreen.BLOCKING_SETTINGS -> {
                BackHandler { subScreen = SettingsSubScreen.ROOT }
                BlockingSettingsView(
                    uiState = uiState,
                    permissionChecker = permissionChecker,
                    onRefreshPermissions = onRefreshPermissions,
                    onSetLockedModeDefault = onSetLockedModeDefault,
                    onSetDndEnabled = onSetDndEnabled,
                    onSetDndMode = onSetDndMode,
                    onSetHideNotifications = onSetHideNotifications,
                    onBack = { subScreen = SettingsSubScreen.ROOT },
                    modifier = modifier
                )
            }

            SettingsSubScreen.FILTER_UPDATES -> {
                BackHandler { subScreen = SettingsSubScreen.ROOT }
                FilterUpdatesView(
                    uiState = uiState,
                    onSetAutoDetectUnknownSites = onSetAutoDetectUnknownSites,
                    onSetFilterFrequency = onSetFilterFrequency,
                    onUpdateFiltersNow = onUpdateFiltersNow,
                    onBack = { subScreen = SettingsSubScreen.ROOT },
                    modifier = modifier
                )
            }

            SettingsSubScreen.ABOUT -> {
                BackHandler { subScreen = SettingsSubScreen.ROOT }
                AboutAndLimitationsView(
                    onBack = { subScreen = SettingsSubScreen.ROOT },
                    modifier = modifier
                )
            }
        }
    }
}

@Composable
private fun SettingsRootView(
    uiState: FreedomUiState,
    onOpenBlockingSettings: () -> Unit,
    onOpenFilterUpdates: () -> Unit,
    onOpenAbout: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    val perms = uiState.permissions
    val readinessText = if (perms != null) "${perms.readinessScore}/${perms.totalChecks}" else ""

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "settings_title") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = g.textPrimary
                )
            }
        }

        item(key = "settings_configure") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Configure Freedom",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
                GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    GlassGroupedRow(
                        title = "Blocking Settings",
                        subtitle = "Locked Mode, Do Not Disturb, Notifications & Permissions",
                        leadingIcon = Icons.Rounded.Shield,
                        trailingText = readinessText,
                        trailingTint = if (perms?.isCoreBlockingReady == true) g.shieldActive else g.lockedAmber,
                        onClick = onOpenBlockingSettings,
                        modifier = Modifier.testTag("settings_blocking_row")
                    )
                    GlassDivider()
                    GlassGroupedRow(
                        title = "Website Filter Updates",
                        subtitle = "Regular hidden domain updates & unknown-site auto-blocking",
                        leadingIcon = Icons.Rounded.CloudSync,
                        trailingText = if (uiState.filterSync.isSyncing) "Syncing…" else uiState.filterUpdateFrequency.label,
                        onClick = onOpenFilterUpdates,
                        modifier = Modifier.testTag("settings_filter_updates_row")
                    )
                    GlassDivider()
                    GlassGroupedRow(
                        title = "More Info & Limitations",
                        subtitle = "Zero-VPN architecture & Android OS boundaries",
                        leadingIcon = Icons.Rounded.Info,
                        onClick = onOpenAbout,
                        modifier = Modifier.testTag("settings_about_row")
                    )
                }
            }
        }

        // Theme selector card (Forest Dark / Clean Light / System)
        item(key = "settings_appearance") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.DarkMode,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Theme",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = g.textPrimary
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    GlassSegmentedControl(
                        options = ThemeMode.entries.map { it.label },
                        selectedIndex = uiState.themeMode.ordinal,
                        onSelect = { onSetThemeMode(ThemeMode.entries[it]) },
                        testTagPrefix = "theme_mode"
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockingSettingsView(
    uiState: FreedomUiState,
    permissionChecker: PermissionStatusChecker,
    onRefreshPermissions: () -> Unit,
    onSetLockedModeDefault: (Boolean) -> Unit,
    onSetDndEnabled: (Boolean) -> Unit,
    onSetDndMode: (DndMode) -> Unit,
    onSetHideNotifications: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    val context = LocalContext.current
    val perms = uiState.permissions

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermissions()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = g.primaryAccent)
            }
            Text(
                text = "Blocking Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Locked Mode section
            item(key = "blocking_locked_mode") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Locked Mode",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Locked Mode by default",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = g.textPrimary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Disables quitting active sessions, blocks non-essential system apps & installers, and enforces a 5-minute continuous wait for 3-minute emergency Settings access.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            FreedomSwitch(
                                checked = uiState.lockedModeDefault,
                                onCheckedChange = onSetLockedModeDefault,
                                modifier = Modifier.testTag("settings_locked_mode_switch")
                            )
                        }
                    }
                }
            }

            // Advanced section: DND & Disable Notifications
            item(key = "blocking_advanced") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Advanced",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Do Not Disturb",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = g.textPrimary
                                )
                                Text(
                                    text = "Automatically enable DnD during active sessions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            FreedomSwitch(
                                checked = uiState.dndEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && perms?.dndPolicyAccessGranted != true) {
                                        runCatching { context.startActivity(permissionChecker.createDndPolicySettingsIntent()) }
                                    }
                                    onSetDndEnabled(enabled)
                                },
                                modifier = Modifier.testTag("settings_dnd_switch")
                            )
                        }

                        GlassDivider()

                        GlassGroupedRow(
                            title = "DnD Mode",
                            trailingText = uiState.dndMode.label,
                            onClick = {
                                val next = if (uiState.dndMode == DndMode.PRIORITY_ONLY) {
                                    DndMode.ALARMS_ONLY
                                } else {
                                    DndMode.PRIORITY_ONLY
                                }
                                onSetDndMode(next)
                            },
                            modifier = Modifier.testTag("settings_dnd_mode_row")
                        )

                        GlassDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Disable Notifications",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = g.textPrimary
                                )
                                Text(
                                    text = "Hides notifications from blocked apps during sessions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            FreedomSwitch(
                                checked = uiState.hideBlockedNotifications,
                                onCheckedChange = { enabled ->
                                    if (enabled && perms?.notificationListenerEnabled != true) {
                                        runCatching { context.startActivity(permissionChecker.createNotificationListenerSettingsIntent()) }
                                    }
                                    onSetHideNotifications(enabled)
                                },
                                modifier = Modifier.testTag("settings_hide_notifs_switch")
                            )
                        }
                    }
                }
            }

            // Blocking Permissions Checklist (Zero VPN!)
            item(key = "blocking_permissions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Blocking Permissions",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        PermissionRowItem(
                            title = "Block Apps & Websites (Accessibility)",
                            subtitle = "Core zero-VPN engine for apps, browsers, in-app WebViews & Settings wait",
                            granted = perms?.isCoreBlockingReady == true,
                            onClick = {
                                runCatching { context.startActivity(permissionChecker.createAccessibilitySettingsIntent()) }
                            }
                        )
                        GlassDivider()
                        PermissionRowItem(
                            title = "Uninstall Protection (Device Admin)",
                            subtitle = "Prevents uninstalling Freedom during Locked Mode",
                            granted = perms?.deviceAdminEnabled == true,
                            onClick = {
                                runCatching { context.startActivity(permissionChecker.createDeviceAdminIntent()) }
                            }
                        )
                        GlassDivider()
                        PermissionRowItem(
                            title = "Hide Blocked Notifications",
                            subtitle = "Notification access to silence blocked apps during sessions",
                            granted = perms?.notificationListenerEnabled == true,
                            onClick = {
                                runCatching { context.startActivity(permissionChecker.createNotificationListenerSettingsIntent()) }
                            }
                        )
                        GlassDivider()
                        PermissionRowItem(
                            title = "Do Not Disturb Access",
                            subtitle = "Allows Freedom to switch DnD on and restore it after sessions",
                            granted = perms?.dndPolicyAccessGranted == true,
                            onClick = {
                                runCatching { context.startActivity(permissionChecker.createDndPolicySettingsIntent()) }
                            }
                        )
                        GlassDivider()
                        PermissionRowItem(
                            title = "Exact Schedule Alarms",
                            subtitle = "Ensures Later and Recurring sessions start on time in Doze mode",
                            granted = perms?.exactAlarmGranted == true,
                            onClick = {
                                runCatching { context.startActivity(permissionChecker.createExactAlarmSettingsIntent()) }
                            }
                        )
                        GlassDivider()
                        PermissionRowItem(
                            title = "Block Alerts & Session Notifications",
                            subtitle = "Shows the 'Blocked by Freedom' notification banner and live focus timer",
                            granted = perms?.notificationsGranted == true,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    runCatching { context.startActivity(permissionChecker.createAppDetailsIntent()) }
                                }
                            }
                        )
                        GlassDivider()
                        PermissionRowItem(
                            title = "Unrestricted Battery",
                            subtitle = "Stops Android from killing Freedom in the background",
                            granted = perms?.batteryOptimizationIgnored == true,
                            onClick = {
                                runCatching { context.startActivity(permissionChecker.createBatteryOptimizationIntent()) }
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PermissionRowItem(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    val g = FreedomGlass.colors
    GlassGroupedRow(
        title = title,
        subtitle = subtitle,
        leadingIcon = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
        trailingText = if (granted) "Active" else "Enable",
        trailingTint = if (granted) g.shieldActive else g.lockedAmber,
        onClick = onClick
    )
}

@Composable
private fun FilterUpdatesView(
    uiState: FreedomUiState,
    onSetAutoDetectUnknownSites: (Boolean) -> Unit,
    onSetFilterFrequency: (FilterUpdateFrequency) -> Unit,
    onUpdateFiltersNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    val sync = uiState.filterSync
    val dateFmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val lastSyncedLabel = if (sync.lastSyncedMs > 0L) {
        "Last synced ${dateFmt.format(Date(sync.lastSyncedMs))}"
    } else {
        "Using built-in offline protection — tap below to sync live lists"
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = g.primaryAccent)
            }
            Text(
                text = "Website Filter Updates",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                GlassCard(contentPadding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CloudSync,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sync.currentStatusText,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = lastSyncedLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Filter lists stay completely hidden in compact 64-bit binary indexes (~2 MB RAM) and never display blocked site names.",
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )

                    if (uiState.autoLearnedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${uiState.autoLearnedCount} newly discovered sites silently added to local protection.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = g.shieldActive
                        )
                    }

                    if (sync.isSyncing) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { sync.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = g.primaryAccent,
                            trackColor = g.divider
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    GlassPillButton(
                        text = if (sync.isSyncing) "Updating Lists…" else "Update Filter Lists Now",
                        enabled = !sync.isSyncing,
                        onClick = onUpdateFiltersNow,
                        testTag = "update_filter_lists_button"
                    )
                }
            }

            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = "Automatic Update Schedule",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Uses ETag caching so unchanged lists download 0 bytes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    GlassSegmentedControl(
                        options = FilterUpdateFrequency.entries.map { it.label },
                        selectedIndex = uiState.filterUpdateFrequency.ordinal,
                        onSelect = { onSetFilterFrequency(FilterUpdateFrequency.entries[it]) },
                        testTagPrefix = "filter_freq"
                    )
                }
            }

            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = g.primaryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smart Auto-Detect Unknown Sites",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Ultra-light (<2ms) on-page check catches unrecognized sites matching your active categories and silently blocks & remembers them. Add any site to Allowed Websites if you want an exception.",
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FreedomSwitch(
                            checked = uiState.autoDetectUnknownSites,
                            onCheckedChange = onSetAutoDetectUnknownSites,
                            modifier = Modifier.testTag("auto_detect_unknown_switch")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutAndLimitationsView(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = g.primaryAccent)
            }
            Text(
                text = "How Freedom Works",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = g.textPrimary
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = "Zero-VPN Blocking & Privacy",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Freedom operates locally on your phone with no account and no VPN. Internet access is used solely to refresh public open-source domain filter feeds into hidden 64-bit hash files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.textSecondary
                    )
                }
            }
            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = "Locked Mode & Emergency Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "During Locked Mode, non-essential system apps, app stores, and package installers remain locked. Android Settings can only be opened after waiting continuously on screen for 5 minutes, unlocking for 3 minutes before auto-relocking. Emergency calls, Phone, Launcher, and Keyboards always stay available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.textSecondary
                    )
                }
            }
            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = "Honest Android OS Limits",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "On a standard non-rooted Android device, no app can prevent Safe Mode reboot, ADB commands over USB, factory reset, or OS SystemUI Quick Settings icon taps. On Android 13+, sideloaded installs require tapping 'Allow restricted settings' in App Info before enabling Accessibility.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.textSecondary
                    )
                }
            }
        }
    }
}
