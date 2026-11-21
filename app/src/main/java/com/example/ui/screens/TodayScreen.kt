package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blocker.FreedomMonitorService
import com.example.data.local.ScheduleEntity
import com.example.domain.ScheduleCalculator
import com.example.domain.WebsiteCategory
import com.example.ui.FreedomUiState
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomMotion
import com.example.ui.theme.FreedomSwitch
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassPillButton
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.rememberFreedomHaptics
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Clean Today dashboard:
 *  - Calm time-of-day greeting + shield readiness pill
 *  - Primary START A SESSION CTA pill
 *  - Collapsible Active Sessions glass card (isolated 1-second timer recomposition scope)
 *  - Collapsible Scheduled Sessions glass card (Later & Recurring)
 */
@Composable
fun TodayScreen(
    uiState: FreedomUiState,
    remainingMillisFlow: StateFlow<Long>,
    onStartSessionClick: () -> Unit,
    onStopSession: () -> Unit,
    onExtendSession: (Int) -> Unit,
    onToggleSchedule: (ScheduleEntity, Boolean) -> Unit,
    onOpenSettingsPermissions: () -> Unit,
    onOpenContentProtection: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    var activeExpanded by remember { mutableStateOf(true) }
    var scheduledExpanded by remember { mutableStateOf(true) }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "Good Morning"
            hour < 17 -> "Good Afternoon"
            else -> "Good Evening"
        }
    }

    val activeSession = uiState.activeSession?.takeIf { it.active }
    val perms = uiState.permissions
    val isReady = perms?.isCoreBlockingReady == true
    val blocklistNamesById = remember(uiState.blocklists) {
        uiState.blocklists.associate { it.id to it.name }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header with brand mark + shield readiness indicator
        item(key = "today_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(g.primarySoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = "Freedom",
                            tint = g.primaryAccent,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Freedom",
                        style = MaterialTheme.typography.titleLarge,
                        color = g.textPrimary
                    )
                }

                // Shield status pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(g.glassSurface)
                        .border(1.dp, g.glassBorder, RoundedCornerShape(50))
                        .clickable { onOpenSettingsPermissions() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("today_shield_status_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isReady) Icons.Rounded.Shield else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (isReady) g.shieldActive else g.lockedAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isReady) "Shield Ready" else "Setup Needed",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isReady) g.shieldActive else g.lockedAmber
                    )
                }
            }
        }

        // Greeting + Primary CTA
        item(key = "today_greeting") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineLarge,
                    color = g.textPrimary
                )
                Text(
                    text = if (activeSession != null) {
                        "Your focus shield is active and keeping distractions away."
                    } else {
                        "Reclaim your attention with distraction-free focus sessions."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = g.textSecondary
                )
                Spacer(Modifier.height(6.dp))
                GlassPillButton(
                    text = if (activeSession != null) "Configure Sessions" else "Start a Session",
                    onClick = onStartSessionClick,
                    testTag = "today_start_session_button"
                )
            }
        }

        // YouTube Study Mode Quick Card (SafeSearch & Adult Protection run silently in backend)
        item(key = "today_youtube_study_card") {
            val studyOn = uiState.contentProtection.youtubeStudyModeEnabled
            val approvedCount = uiState.approvedYouTubeChannels.size
            val summaryText = if (studyOn) {
                "$approvedCount approved channels · YouTube, Morphe YT & ReVanced"
            } else {
                "Allow only verified channels across YouTube & Morphe YT"
            }

            GlassCard(
                accentBorderColor = if (studyOn) g.shieldActive else null,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                onClick = onOpenContentProtection,
                modifier = Modifier.testTag("today_content_protection_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (studyOn) g.shieldActive.copy(alpha = 0.16f) else g.primarySoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = "YouTube Study Mode",
                            tint = if (studyOn) g.shieldActive else g.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "YouTube Study Mode",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = g.textPrimary
                        )
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                    Text(
                        text = if (studyOn) "On" else "Set Up",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (studyOn) g.shieldActive else g.primaryAccent
                    )
                }
            }
        }

        // Collapsible Active Section
        item(key = "today_active_section") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CollapsibleSectionHeader(
                    title = "Active",
                    expanded = activeExpanded,
                    onToggle = { activeExpanded = !activeExpanded }
                )

                AnimatedVisibility(
                    visible = activeExpanded,
                    enter = expandVertically(FreedomMotion.ExpandSizeSpec) + fadeIn(FreedomMotion.FastFadeIn),
                    exit = shrinkVertically(FreedomMotion.ExpandSizeSpec) + fadeOut(FreedomMotion.FastFadeOut)
                ) {
                    if (activeSession == null) {
                        GlassCard(contentPadding = PaddingValues(20.dp)) {
                            Text(
                                text = "No Active Sessions",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Quickly focus on a task at hand and stay protected from distracting apps and websites.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = g.textSecondary
                            )
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = onStartSessionClick,
                                    modifier = Modifier.testTag("today_empty_start_button")
                                ) {
                                    Text(
                                        text = "START A SESSION",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp
                                        ),
                                        color = g.primaryAccent
                                    )
                                }
                            }
                        }
                    } else {
                        val catCount = remember(activeSession.blockedWebCategoriesCsv) {
                            WebsiteCategory.parseCsv(activeSession.blockedWebCategoriesCsv).size
                        }
                        val allowedCount = uiState.allowedPackagesFor(activeSession.blocklistId).size
                        GlassCard(
                            accentBorderColor = if (activeSession.strictMode) g.lockedAmber else g.shieldActive,
                            contentPadding = PaddingValues(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (activeSession.strictMode) {
                                            Icon(
                                                imageVector = Icons.Rounded.Lock,
                                                contentDescription = "Locked Mode",
                                                tint = g.lockedAmber,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = activeSession.label,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = g.textPrimary
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    val webDesc = when {
                                        activeSession.fullInternetBrowsingBlock -> "All browsing blocked"
                                        catCount > 0 -> "$catCount web categories blocked"
                                        else -> "Apps allowlist only"
                                    }
                                    Text(
                                        text = "$allowedCount allowed apps · $webDesc",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = g.textSecondary
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                ActiveSessionCountdown(
                                    remainingMillisFlow = remainingMillisFlow,
                                    color = if (activeSession.strictMode) g.lockedAmber else g.primaryAccent
                                )
                            }

                            Spacer(Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${activeSession.blockedAttemptsCount} distractions blocked",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = g.textSecondary
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onExtendSession(15) },
                                        shape = RoundedCornerShape(50),
                                        border = BorderStroke(1.dp, g.primaryAccent.copy(alpha = 0.55f)),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("today_extend_15_button")
                                    ) {
                                        Text(
                                            text = "+15m",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = g.primaryAccent
                                        )
                                    }
                                    if (!activeSession.strictMode) {
                                        OutlinedButton(
                                            onClick = onStopSession,
                                            shape = RoundedCornerShape(50),
                                            border = BorderStroke(1.dp, g.dangerCoral.copy(alpha = 0.55f)),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("today_end_session_button")
                                        ) {
                                            Text(
                                                text = "End",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = g.dangerCoral
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Collapsible Scheduled Section
        item(key = "today_scheduled_section") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CollapsibleSectionHeader(
                    title = "Scheduled",
                    expanded = scheduledExpanded,
                    onToggle = { scheduledExpanded = !scheduledExpanded }
                )

                AnimatedVisibility(
                    visible = scheduledExpanded,
                    enter = expandVertically(FreedomMotion.ExpandSizeSpec) + fadeIn(FreedomMotion.FastFadeIn),
                    exit = shrinkVertically(FreedomMotion.ExpandSizeSpec) + fadeOut(FreedomMotion.FastFadeOut)
                ) {
                    if (uiState.schedules.isEmpty()) {
                        GlassCard(contentPadding = PaddingValues(20.dp)) {
                            Text(
                                text = "No Scheduled Sessions",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = g.textPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Schedule Later or Recurring sessions so Freedom activates automatically when you need focus most.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = g.textSecondary
                            )
                        }
                    } else {
                        val timeFmt = remember { SimpleDateFormat("EEE, h:mm a", Locale.getDefault()) }
                        GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                            uiState.schedules.forEachIndexed { idx, sched ->
                                val profileName = blocklistNamesById[sched.blocklistId] ?: "Blocklist"
                                val timingSummary = if (sched.isRecurring) {
                                    "${ScheduleCalculator.formatDaysSummary(sched.daysMask)} · " +
                                        "${ScheduleCalculator.formatMinuteOfDay(sched.startMinuteOfDay)}–${ScheduleCalculator.formatMinuteOfDay(sched.endMinuteOfDay)}"
                                } else {
                                    "One-time · ${timeFmt.format(Date(sched.oneTimeStartMs))}"
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (sched.strictMode) Icons.Rounded.Lock else Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = if (sched.strictMode) g.lockedAmber else g.primaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sched.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = g.textPrimary
                                        )
                                        Text(
                                            text = "$timingSummary · $profileName",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = g.textSecondary
                                        )
                                    }
                                    FreedomSwitch(
                                        checked = sched.enabled,
                                        onCheckedChange = { onToggleSchedule(sched, it) }
                                    )
                                }
                                if (idx < uiState.schedules.lastIndex) {
                                    GlassDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "today_bottom_spacer") {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Isolated composable collecting the 1-second timer so countdown ticks NEVER
 * recompose sibling cards, navigation chrome, or other screens.
 */
@Composable
private fun ActiveSessionCountdown(
    remainingMillisFlow: StateFlow<Long>,
    color: Color
) {
    val remainingMillis by remainingMillisFlow.collectAsStateWithLifecycle()
    Text(
        text = FreedomMonitorService.formatDuration(remainingMillis),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum"
        ),
        color = color
    )
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -180f,
        animationSpec = FreedomMotion.CrispFloatSpec,
        label = "section_chevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptics.tap()
                onToggle()
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = g.textPrimary
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = g.textSecondary,
            modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
        )
    }
}
