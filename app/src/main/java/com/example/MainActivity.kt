package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.FreedomViewModel
import com.example.ui.screens.BlocklistsScreen
import com.example.ui.screens.SessionsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TodayScreen
import com.example.ui.theme.BackdropMood
import com.example.ui.theme.ForestGlassBackdrop
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomTheme

private enum class FreedomTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    TODAY("Today", Icons.Rounded.WbSunny, Icons.Outlined.WbSunny, "nav_today"),
    SESSIONS("Sessions", Icons.Rounded.Flag, Icons.Outlined.Flag, "nav_sessions"),
    BLOCKLISTS("Blocklists", Icons.Rounded.Shield, Icons.Outlined.Shield, "nav_blocklists"),
    SETTINGS("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: FreedomViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            FreedomTheme(themeMode = themeMode) {
                FreedomAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}

@Composable
private fun FreedomAppRoot(viewModel: FreedomViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentTab by rememberSaveable { mutableStateOf(FreedomTab.TODAY) }
    var openBlockingSettingsDirectly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val mood = when {
        uiState.isStrictModeActive -> BackdropMood.LOCKED
        uiState.activeSession?.active == true && uiState.remainingMillis > 0L -> BackdropMood.ACTIVE
        else -> BackdropMood.CALM
    }

    ForestGlassBackdrop(mood = mood) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                FreedomBottomNavBar(
                    currentTab = currentTab,
                    onSelectTab = { currentTab = it }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    FreedomTab.TODAY -> TodayScreen(
                        uiState = uiState,
                        onStartSessionClick = { currentTab = FreedomTab.SESSIONS },
                        onStopSession = { viewModel.stopActiveSession() },
                        onExtendSession = { viewModel.extendActiveSession(it) },
                        onToggleSchedule = { sched, enabled -> viewModel.toggleSchedule(sched, enabled) },
                        onOpenSettingsPermissions = {
                            openBlockingSettingsDirectly = true
                            currentTab = FreedomTab.SETTINGS
                        }
                    )

                    FreedomTab.SESSIONS -> SessionsScreen(
                        uiState = uiState,
                        onStartSessionNow = { label, blocklistId, minutes, strict ->
                            viewModel.startSessionNow(label, blocklistId, minutes, strict)
                            currentTab = FreedomTab.TODAY
                        },
                        onScheduleLater = { name, blocklistId, startMs, endMs, strict ->
                            viewModel.scheduleSessionLater(name, blocklistId, startMs, endMs, strict)
                        },
                        onSaveRecurring = { viewModel.saveRecurringSchedule(it) },
                        onToggleSchedule = { sched, enabled -> viewModel.toggleSchedule(sched, enabled) },
                        onDeleteSchedule = { viewModel.deleteSchedule(it) },
                        onNavigateToBlocklists = { currentTab = FreedomTab.BLOCKLISTS }
                    )

                    FreedomTab.BLOCKLISTS -> BlocklistsScreen(
                        uiState = uiState,
                        onSaveBlocklist = { id, name, fullBrowsing, cats, apps, onSuccess ->
                            viewModel.saveBlocklistProfile(id, name, fullBrowsing, cats, apps, onSuccess)
                        },
                        onDeleteBlocklist = { viewModel.deleteBlocklistProfile(it) },
                        onAddAllowedWebsite = { viewModel.addAllowedWebsite(it) },
                        onRemoveAllowedWebsite = { viewModel.removeAllowedWebsite(it) },
                        onAddCustomBlockedWebsite = { viewModel.addCustomBlockedWebsite(it) },
                        onRemoveCustomBlockedWebsite = { viewModel.removeCustomBlockedWebsite(it) }
                    )

                    FreedomTab.SETTINGS -> SettingsScreen(
                        uiState = uiState,
                        permissionChecker = viewModel.permissionChecker,
                        openBlockingDirectly = openBlockingSettingsDirectly,
                        onConsumeOpenBlockingDirectly = { openBlockingSettingsDirectly = false },
                        onRefreshPermissions = { viewModel.refreshPermissions() },
                        onSetThemeMode = { viewModel.setThemeMode(it) },
                        onSetLockedModeDefault = { viewModel.setLockedModeDefault(it) },
                        onSetDndEnabled = { viewModel.setDndEnabled(it) },
                        onSetDndMode = { viewModel.setDndMode(it) },
                        onSetHideNotifications = { viewModel.setHideBlockedNotifications(it) },
                        onSetAutoDetectUnknownSites = { viewModel.setAutoDetectUnknownSites(it) },
                        onSetFilterFrequency = { viewModel.setFilterUpdateFrequency(it) },
                        onUpdateFiltersNow = { viewModel.updateFilterListsNow() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FreedomBottomNavBar(
    currentTab: FreedomTab,
    onSelectTab: (FreedomTab) -> Unit
) {
    val g = FreedomGlass.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(g.navSurface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 1.dp, color = g.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FreedomTab.entries.forEach { tab ->
                val selected = tab == currentTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 6.dp)
                        .testTag(tab.tag),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                        tint = if (selected) g.primaryAccent else g.textSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) g.primaryAccent else g.textSecondary
                    )
                }
            }
        }
    }
}
