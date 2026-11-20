package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import com.example.ui.theme.FreedomMotion
import com.example.ui.theme.FreedomTheme
import com.example.ui.theme.rememberFreedomHaptics

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

private val DockShape = RoundedCornerShape(26.dp)
private val DockCapsuleShape = RoundedCornerShape(20.dp)

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
        uiState.isSessionActive -> BackdropMood.ACTIVE
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
                // Smooth, critically-damped directional tab transitions (zero rubbery bounce)
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        val movingRight = targetState.ordinal >= initialState.ordinal
                        val enterOffset: (Int) -> Int = { fullWidth ->
                            if (movingRight) fullWidth / 14 else -fullWidth / 14
                        }
                        val exitOffset: (Int) -> Int = { fullWidth ->
                            if (movingRight) -fullWidth / 18 else fullWidth / 18
                        }
                        (slideInHorizontally(
                            animationSpec = FreedomMotion.SlideOffsetSpec,
                            initialOffsetX = enterOffset
                        ) + fadeIn(animationSpec = FreedomMotion.FastFadeIn)) togetherWith (slideOutHorizontally(
                            animationSpec = FreedomMotion.SlideOffsetSpec,
                            targetOffsetX = exitOffset
                        ) + fadeOut(animationSpec = FreedomMotion.FastFadeOut))
                    },
                    label = "freedom_main_tabs"
                ) { tab ->
                    when (tab) {
                        FreedomTab.TODAY -> TodayScreen(
                            uiState = uiState,
                            remainingMillisFlow = viewModel.remainingMillis,
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
}

/**
 * Floating frosted glass bottom dock with GPU-translated sliding active capsule
 * (zero layout invalidations during tab switches).
 */
@Composable
private fun FreedomBottomNavBar(
    currentTab: FreedomTab,
    onSelectTab: (FreedomTab) -> Unit
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val tabs = FreedomTab.entries
    val selectedIdx = currentTab.ordinal
    val density = LocalDensity.current

    val rimBorder = remember(g.isDark, g.glassBorder) {
        BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    if (g.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.96f),
                    g.glassBorder
                )
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = DockShape,
            color = g.navSurface,
            shadowElevation = if (g.isDark) 4.dp else 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(DockShape)
                .border(rimBorder, DockShape)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
            ) {
                val tabWidth = maxWidth / tabs.size
                val tabWidthPx = with(density) { tabWidth.toPx() }
                val animatedIndex by animateFloatAsState(
                    targetValue = selectedIdx.toFloat(),
                    animationSpec = FreedomMotion.CrispFloatSpec,
                    label = "dock_active_capsule"
                )

                // GPU-only sliding capsule under the active tab
                Box(
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = animatedIndex * tabWidthPx
                        }
                        .clip(DockCapsuleShape)
                        .background(g.primarySoft)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEach { tab ->
                        val selected = tab == currentTab
                        val interactionSource = remember { MutableInteractionSource() }
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) g.primaryAccent else g.textSecondary,
                            animationSpec = tween(durationMillis = 150),
                            label = "dock_tint"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(DockCapsuleShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) {
                                    if (!selected) {
                                        haptics.tap()
                                        onSelectTab(tab)
                                    }
                                }
                                .testTag(tab.tag),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
