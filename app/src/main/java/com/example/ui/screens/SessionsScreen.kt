package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.local.BlocklistProfileEntity
import com.example.data.local.ScheduleEntity
import com.example.domain.ScheduleCalculator
import com.example.domain.WebsiteCategory
import com.example.ui.FreedomUiState
import com.example.ui.theme.ClockTimeWheelPicker
import com.example.ui.theme.DurationWheelBounds
import com.example.ui.theme.DurationWheelPicker
import com.example.ui.theme.FreedomGlass
import com.example.ui.theme.FreedomMotion
import com.example.ui.theme.FreedomSwitch
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassDivider
import com.example.ui.theme.GlassGroupedRow
import com.example.ui.theme.GlassPillButton
import com.example.ui.theme.GlassSegmentedControl
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.colorOsPressScale
import com.example.ui.theme.rememberFreedomHaptics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class SessionTabMode(val label: String) {
    NOW("Now"),
    LATER("Later"),
    RECURRING("Recurring")
}

private data class DurationPreset(val label: String, val minutes: Int)

private val DURATION_PRESETS = listOf(
    DurationPreset("15 MIN", 15),
    DurationPreset("25 MIN", 25),
    DurationPreset("1 HR", 60),
    DurationPreset("2 HRS", 120),
    DurationPreset("5 HRS", 300),
    DurationPreset("24 HRS", 1440)
)

/**
 * Clean 3-mode Sessions screen with ColorOS fluid transitions and minute-by-minute
 * 3D rotary wheel time pickers everywhere time is selected:
 *   - NOW (Length presets + inline 0–24h Custom Duration Wheel / End Time Clock Wheel)
 *   - LATER (Start Time Clock Wheel + presets or inline 0–24h Custom Duration Wheel)
 *   - RECURRING (Starts & Ends Clock Wheel inside schedule editor)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionsScreen(
    uiState: FreedomUiState,
    onStartSessionNow: (label: String, blocklistId: Long, durationMinutes: Int, strictMode: Boolean) -> Unit,
    onScheduleLater: (name: String, blocklistId: Long, startWallMs: Long, endWallMs: Long, strictMode: Boolean) -> Unit,
    onSaveRecurring: (ScheduleEntity) -> Unit,
    onToggleSchedule: (ScheduleEntity, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleEntity) -> Unit,
    onNavigateToBlocklists: () -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    var activeTab by remember { mutableStateOf(SessionTabMode.NOW) }

    val defaultBlocklistId = uiState.blocklists.firstOrNull()?.id ?: 1L
    var selectedBlocklistId by remember(defaultBlocklistId) { mutableLongStateOf(defaultBlocklistId) }
    val selectedBlocklist = uiState.blocklists.firstOrNull { it.id == selectedBlocklistId }
        ?: uiState.blocklists.firstOrNull()

    var showBlocklistSheet by remember { mutableStateOf(false) }

    // NOW tab state
    val dayOfWeekName = remember {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    }
    var selectedMinutes by remember { mutableIntStateOf(25) }
    var customMode by remember { mutableStateOf(false) }
    var useEndTimeMode by remember { mutableStateOf(false) }
    var endTimeMinuteOfDay by remember {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 60) }
        mutableIntStateOf((cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) % 1440)
    }
    var sessionName by remember { mutableStateOf("$dayOfWeekName 25m Session") }
    var customNameEdited by remember { mutableStateOf(false) }
    var strictMode by remember(uiState.lockedModeDefault) { mutableStateOf(uiState.lockedModeDefault) }

    // LATER tab state
    var laterName by remember { mutableStateOf("Planned Focus") }
    var laterTomorrow by remember { mutableStateOf(false) }
    var laterStartMinute by remember {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
        mutableIntStateOf((cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) % 1440)
    }
    var laterDurationMinutes by remember { mutableIntStateOf(60) }
    var laterCustomDurationMode by remember { mutableStateOf(false) }
    var laterStrictMode by remember(uiState.lockedModeDefault) { mutableStateOf(uiState.lockedModeDefault) }

    // RECURRING tab dialog state
    var editingRecurring by remember { mutableStateOf<ScheduleEntity?>(null) }

    fun updateDefaultSessionName(minutes: Int) {
        if (!customNameEdited) {
            val h = minutes / 60
            val m = minutes % 60
            val tag = when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
            sessionName = "$dayOfWeekName $tag Session"
        }
    }

    fun computeNowDurationMinutes(): Int {
        if (!useEndTimeMode) return selectedMinutes.coerceIn(DurationWheelBounds.MIN_MINUTES, DurationWheelBounds.MAX_MINUTES)
        val cal = Calendar.getInstance()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        var diff = endTimeMinuteOfDay - nowMinute
        if (diff <= 0) diff += 1440
        return diff.coerceIn(DurationWheelBounds.MIN_MINUTES, DurationWheelBounds.MAX_MINUTES)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Centered top title + ColorOS fluid sliding 3-way segmented pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = g.textPrimary
                )
                Spacer(Modifier.height(16.dp))
                GlassSegmentedControl(
                    options = SessionTabMode.entries.map { it.label },
                    selectedIndex = activeTab.ordinal,
                    onSelect = { activeTab = SessionTabMode.entries[it] },
                    testTagPrefix = "sessions_tab"
                )
            }

            // Calm, zero-bounce directional transition between NOW | LATER | RECURRING
            AnimatedContent(
                targetState = activeTab,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    (slideInHorizontally(
                        animationSpec = FreedomMotion.SlideOffsetSpec,
                        initialOffsetX = { if (forward) it / 14 else -it / 14 }
                    ) + fadeIn(FreedomMotion.FastFadeIn)) togetherWith (slideOutHorizontally(
                        animationSpec = FreedomMotion.SlideOffsetSpec,
                        targetOffsetX = { if (forward) -it / 18 else it / 18 }
                    ) + fadeOut(FreedomMotion.FastFadeOut))
                },
                label = "sessions_subtab_content"
            ) { tabMode ->
                when (tabMode) {
                    SessionTabMode.NOW -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Name glass card
                            item(key = "now_name_card") {
                                GlassCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Name",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = g.textPrimary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        BasicTextField(
                                            value = sessionName,
                                            onValueChange = {
                                                sessionName = it
                                                customNameEdited = true
                                            },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = g.primaryAccent,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.End
                                            ),
                                            cursorBrush = SolidColor(g.primaryAccent),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("session_name_input")
                                        )
                                    }
                                }
                            }

                            // Blocklist & Locked Mode grouped card
                            item(key = "now_config_card") {
                                GlassCard(contentPadding = PaddingValues(vertical = 2.dp)) {
                                    GlassGroupedRow(
                                        title = "Blocklist",
                                        leadingIcon = Icons.Rounded.Shield,
                                        trailingText = selectedBlocklist?.name ?: "Choose",
                                        onClick = { showBlocklistSheet = true },
                                        modifier = Modifier.testTag("session_blocklist_row")
                                    )
                                    GlassDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = null,
                                            tint = if (strictMode) g.lockedAmber else g.textSecondary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Locked Mode",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                color = g.textPrimary
                                            )
                                            Text(
                                                text = "Cannot stop early; Settings requires 5-min wait",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = g.textSecondary
                                            )
                                        }
                                        FreedomSwitch(
                                            checked = strictMode,
                                            onCheckedChange = { strictMode = it },
                                            modifier = Modifier.testTag("session_locked_mode_switch")
                                        )
                                    }
                                }
                            }

                            // Duration / End Time glass card with 3D Rotary Wheel Picker
                            item(key = "now_duration_card") {
                                GlassCard(
                                    contentPadding = PaddingValues(16.dp)
                                ) {
                                    GlassSegmentedControl(
                                        options = listOf("Length", "End Time"),
                                        selectedIndex = if (useEndTimeMode) 1 else 0,
                                        onSelect = { useEndTimeMode = (it == 1) },
                                        testTagPrefix = "duration_mode"
                                    )
                                    Spacer(Modifier.height(18.dp))

                                    AnimatedContent(
                                        targetState = useEndTimeMode,
                                        transitionSpec = {
                                            (fadeIn(FreedomMotion.FastFadeIn) + slideInHorizontally(
                                                animationSpec = FreedomMotion.SlideOffsetSpec,
                                                initialOffsetX = { if (targetState) it / 16 else -it / 16 }
                                            )) togetherWith (fadeOut(FreedomMotion.FastFadeOut) + slideOutHorizontally(
                                                animationSpec = FreedomMotion.SlideOffsetSpec,
                                                targetOffsetX = { if (targetState) -it / 18 else it / 18 }
                                            ))
                                        },
                                        label = "length_vs_endtime"
                                    ) { endMode ->
                                        if (!endMode) {
                                            Column {
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    DURATION_PRESETS.forEach { preset ->
                                                        val isSelected = !customMode && selectedMinutes == preset.minutes
                                                        DurationPillChip(
                                                            label = preset.label,
                                                            selected = isSelected,
                                                            onClick = {
                                                                customMode = false
                                                                selectedMinutes = preset.minutes
                                                                updateDefaultSessionName(preset.minutes)
                                                            },
                                                            testTag = "duration_preset_${preset.minutes}"
                                                        )
                                                    }
                                                    DurationPillChip(
                                                        label = if (customMode) formatMinutesCompact(selectedMinutes) else "CUSTOM",
                                                        selected = customMode,
                                                        leadingIcon = Icons.Rounded.Edit,
                                                        onClick = { customMode = true },
                                                        testTag = "duration_preset_custom"
                                                    )
                                                }

                                                // Inline 3D 0-24H Minute-by-Minute Rotary Wheel matching Screenshot 1
                                                AnimatedVisibility(
                                                    visible = customMode,
                                                    enter = expandVertically(FreedomMotion.FluidSizeSpring) + fadeIn(tween(220)),
                                                    exit = shrinkVertically(FreedomMotion.FluidSizeSpring) + fadeOut(tween(160))
                                                ) {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        Spacer(Modifier.height(16.dp))
                                                        DurationWheelPicker(
                                                            totalMinutes = selectedMinutes,
                                                            onMinutesChanged = { mins ->
                                                                selectedMinutes = mins
                                                                updateDefaultSessionName(mins)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // End Time 3D Rotary Clock Wheel
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "Focus ends at",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = g.textSecondary
                                                )
                                                Spacer(Modifier.height(10.dp))
                                                ClockTimeWheelPicker(
                                                    minuteOfDay = endTimeMinuteOfDay,
                                                    onMinuteOfDayChanged = { endTimeMinuteOfDay = it }
                                                )
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    text = "Total duration: ${formatMinutesHuman(computeNowDurationMinutes())}",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = InterFontFamily,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFeatureSettings = "tnum"
                                                    ),
                                                    color = g.primaryAccent
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SessionTabMode.LATER -> {
                        val laterSchedules = remember(uiState.schedules) {
                            uiState.schedules.filter { !it.isRecurring }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item(key = "later_name_card") {
                                GlassCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Name",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = g.textPrimary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        BasicTextField(
                                            value = laterName,
                                            onValueChange = { laterName = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = g.primaryAccent,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.End
                                            ),
                                            cursorBrush = SolidColor(g.primaryAccent),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            item(key = "later_config_card") {
                                GlassCard(contentPadding = PaddingValues(vertical = 2.dp)) {
                                    GlassGroupedRow(
                                        title = "Blocklist",
                                        leadingIcon = Icons.Rounded.Shield,
                                        trailingText = selectedBlocklist?.name ?: "Choose",
                                        onClick = { showBlocklistSheet = true }
                                    )
                                    GlassDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = null,
                                            tint = if (laterStrictMode) g.lockedAmber else g.textSecondary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            text = "Locked Mode",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = g.textPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        FreedomSwitch(
                                            checked = laterStrictMode,
                                            onCheckedChange = { laterStrictMode = it }
                                        )
                                    }
                                }
                            }

                            item(key = "later_time_card") {
                                GlassCard(
                                    contentPadding = PaddingValues(18.dp)
                                ) {
                                    GlassSegmentedControl(
                                        options = listOf("Today", "Tomorrow"),
                                        selectedIndex = if (laterTomorrow) 1 else 0,
                                        onSelect = { laterTomorrow = (it == 1) },
                                        testTagPrefix = "later_day"
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Start Time",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = g.textSecondary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    ClockTimeWheelPicker(
                                        minuteOfDay = laterStartMinute,
                                        onMinuteOfDayChanged = { laterStartMinute = it },
                                        wheelHeight = 168.dp
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Duration",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = g.textSecondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(25, 45, 60, 120, 180).forEach { mins ->
                                            DurationPillChip(
                                                label = formatMinutesCompact(mins),
                                                selected = !laterCustomDurationMode && laterDurationMinutes == mins,
                                                onClick = {
                                                    laterCustomDurationMode = false
                                                    laterDurationMinutes = mins
                                                }
                                            )
                                        }
                                        DurationPillChip(
                                            label = if (laterCustomDurationMode) {
                                                formatMinutesCompact(laterDurationMinutes)
                                            } else {
                                                "CUSTOM"
                                            },
                                            selected = laterCustomDurationMode,
                                            leadingIcon = Icons.Rounded.Edit,
                                            onClick = { laterCustomDurationMode = true }
                                        )
                                    }
                                    AnimatedVisibility(
                                        visible = laterCustomDurationMode,
                                        enter = expandVertically(FreedomMotion.FluidSizeSpring) + fadeIn(tween(220)),
                                        exit = shrinkVertically(FreedomMotion.FluidSizeSpring) + fadeOut(tween(160))
                                    ) {
                                        Column {
                                            Spacer(Modifier.height(14.dp))
                                            DurationWheelPicker(
                                                totalMinutes = laterDurationMinutes,
                                                onMinutesChanged = { laterDurationMinutes = it },
                                                wheelHeight = 176.dp
                                            )
                                        }
                                    }
                                }
                            }

                            if (laterSchedules.isNotEmpty()) {
                                item(key = "later_upcoming_header") {
                                    Text(
                                        text = "Upcoming One-Time Sessions",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = g.textPrimary
                                    )
                                }
                                items(laterSchedules, key = { it.id }) { sched ->
                                    val fmt = remember { SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()) }
                                    GlassCard(contentPadding = PaddingValues(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = sched.name,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                    color = g.textPrimary
                                                )
                                                Text(
                                                    text = fmt.format(Date(sched.oneTimeStartMs)),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = g.textSecondary
                                                )
                                            }
                                            IconButton(onClick = { onDeleteSchedule(sched) }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "Delete",
                                                    tint = g.textSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SessionTabMode.RECURRING -> {
                        val recurringList = remember(uiState.schedules) {
                            uiState.schedules.filter { it.isRecurring }
                        }
                        val blocklistNamesById = remember(uiState.blocklists) {
                            uiState.blocklists.associate { it.id to it.name }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (recurringList.isEmpty()) {
                                item(key = "recurring_empty") {
                                    GlassCard(contentPadding = PaddingValues(24.dp)) {
                                        Text(
                                            text = "No Recurring Schedules",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = g.textPrimary
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "Automate daily, weekday, or overnight focus blocks so Freedom activates on schedule without effort.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = g.textSecondary
                                        )
                                    }
                                }
                            } else {
                                items(recurringList, key = { it.id }) { sched ->
                                    val profileName = blocklistNamesById[sched.blocklistId] ?: "Blocklist"
                                    GlassCard(
                                        contentPadding = PaddingValues(18.dp),
                                        onClick = { editingRecurring = sched }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (sched.strictMode) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Lock,
                                                            contentDescription = null,
                                                            tint = g.lockedAmber,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = sched.name,
                                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = g.textPrimary
                                                    )
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = "${ScheduleCalculator.formatMinuteOfDay(sched.startMinuteOfDay)} – " +
                                                        "${ScheduleCalculator.formatMinuteOfDay(sched.endMinuteOfDay)} · " +
                                                        ScheduleCalculator.formatDaysSummary(sched.daysMask),
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = InterFontFamily,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFeatureSettings = "tnum"
                                                    ),
                                                    color = g.primaryAccent
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = profileName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = g.textSecondary
                                                )
                                            }
                                            FreedomSwitch(
                                                checked = sched.enabled,
                                                onCheckedChange = { onToggleSchedule(sched, it) }
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

        // Sticky Bottom Primary Pill CTA
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(g.bgBottom.copy(alpha = 0.84f))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (activeTab) {
                SessionTabMode.NOW -> {
                    GlassPillButton(
                        text = "Start the Session",
                        onClick = {
                            onStartSessionNow(
                                sessionName,
                                selectedBlocklist?.id ?: defaultBlocklistId,
                                computeNowDurationMinutes(),
                                strictMode
                            )
                        },
                        testTag = "start_session_now_button"
                    )
                }

                SessionTabMode.LATER -> {
                    GlassPillButton(
                        text = "Schedule Later Session",
                        onClick = {
                            val startCal = Calendar.getInstance().apply {
                                if (laterTomorrow) add(Calendar.DAY_OF_YEAR, 1)
                                set(Calendar.HOUR_OF_DAY, laterStartMinute / 60)
                                set(Calendar.MINUTE, laterStartMinute % 60)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            if (!laterTomorrow && startCal.timeInMillis <= System.currentTimeMillis()) {
                                startCal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                            val startMs = startCal.timeInMillis
                            val endMs = startMs + laterDurationMinutes * 60_000L
                            onScheduleLater(
                                laterName,
                                selectedBlocklist?.id ?: defaultBlocklistId,
                                startMs,
                                endMs,
                                laterStrictMode
                            )
                        },
                        testTag = "schedule_later_button"
                    )
                }

                SessionTabMode.RECURRING -> {
                    GlassPillButton(
                        text = "Add Recurring Schedule",
                        onClick = {
                            editingRecurring = ScheduleEntity(
                                name = "Workday Focus",
                                blocklistId = selectedBlocklist?.id ?: defaultBlocklistId,
                                isRecurring = true,
                                daysMask = ScheduleCalculator.WEEKDAYS_MASK,
                                startMinuteOfDay = 9 * 60,
                                endMinuteOfDay = 17 * 60,
                                strictMode = uiState.lockedModeDefault,
                                enabled = true
                            )
                        },
                        testTag = "add_recurring_schedule_button"
                    )
                }
            }
        }
    }

    // Blocklist Picker Bottom Sheet
    if (showBlocklistSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBlocklistSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = g.glassSurfaceElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Choose Blocklist",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = g.textPrimary
                    )
                    TextButton(onClick = {
                        showBlocklistSheet = false
                        onNavigateToBlocklists()
                    }) {
                        Text("Manage", color = g.primaryAccent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                uiState.blocklists.forEach { profile ->
                    val isPicked = profile.id == selectedBlocklist?.id
                    val appCount = uiState.allowedPackagesFor(profile.id).size
                    val catCount = WebsiteCategory.parseCsv(profile.blockedWebCategoriesCsv).size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isPicked) g.primarySoft else Color.Transparent)
                            .clickable {
                                selectedBlocklistId = profile.id
                                showBlocklistSheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = if (isPicked) g.primaryAccent else g.textSecondary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = g.textPrimary
                            )
                            Text(
                                text = formatProfileSubtitle(profile, appCount, catCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                        if (isPicked) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = g.primaryAccent
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Recurring Schedule Editor Dialog (using smooth Rotary Clock Wheel!)
    editingRecurring?.let { initial ->
        RecurringScheduleEditorDialog(
            initial = initial,
            blocklists = uiState.blocklists,
            onDismiss = { editingRecurring = null },
            onDelete = if (initial.id != 0L) {
                {
                    onDeleteSchedule(initial)
                    editingRecurring = null
                }
            } else null,
            onSave = { updated ->
                onSaveRecurring(updated)
                editingRecurring = null
            }
        )
    }
}

@Composable
private fun DurationPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    testTag: String? = null
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)

    val bgColor by animateColorAsState(
        targetValue = if (selected) g.primaryAccent else g.glassSurfaceElevated,
        animationSpec = tween(140),
        label = "chip_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) g.onPrimaryAccent else g.primaryAccent,
        animationSpec = tween(140),
        label = "chip_fg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) g.primaryAccent else g.glassBorder,
        animationSpec = tween(140),
        label = "chip_border"
    )

    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .colorOsPressScale(interactionSource, pressedScale = 0.97f)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = {
                    haptics.tap()
                    onClick()
                }
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .let { if (testTag != null) it.testTag(testTag) else it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = contentColor
        )
    }
}

@Composable
private fun RecurringScheduleEditorDialog(
    initial: ScheduleEntity,
    blocklists: List<BlocklistProfileEntity>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (ScheduleEntity) -> Unit
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    var name by remember { mutableStateOf(initial.name) }
    var daysMask by remember { mutableIntStateOf(initial.daysMask) }
    var startMin by remember { mutableIntStateOf(initial.startMinuteOfDay) }
    var endMin by remember { mutableIntStateOf(initial.endMinuteOfDay) }
    var editingEndWheel by remember { mutableStateOf(false) }
    var strict by remember { mutableStateOf(initial.strictMode) }
    var blocklistId by remember { mutableLongStateOf(initial.blocklistId) }

    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = g.glassSurfaceElevated,
        title = {
            Text(
                text = if (initial.id == 0L) "New Recurring Schedule" else "Edit Schedule",
                color = g.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.textPrimary),
                    cursorBrush = SolidColor(g.primaryAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(g.glassSurface)
                        .border(1.dp, g.glassBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                )

                // Day-of-week pills with subtle haptics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    dayLabels.forEachIndexed { idx, letter ->
                        val bit = ScheduleCalculator.dayIndexToBit(idx)
                        val active = (daysMask and bit) != 0
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (active) g.primaryAccent else g.glassSurface)
                                .border(1.dp, if (active) g.primaryAccent else g.glassBorder, CircleShape)
                                .clickable {
                                    haptics.tap()
                                    daysMask = if (active) daysMask and bit.inv() else daysMask or bit
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = letter,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (active) g.onPrimaryAccent else g.textSecondary
                            )
                        }
                    }
                }

                // Sliding Starts | Ends selector + Rotary Clock Wheel
                GlassSegmentedControl(
                    options = listOf(
                        "Starts ${ScheduleCalculator.formatMinuteOfDay(startMin)}",
                        "Ends ${ScheduleCalculator.formatMinuteOfDay(endMin)}"
                    ),
                    selectedIndex = if (editingEndWheel) 1 else 0,
                    onSelect = { editingEndWheel = (it == 1) },
                    testTagPrefix = "recurring_time_tab"
                )

                AnimatedContent(
                    targetState = editingEndWheel,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                    label = "recurring_wheel_switch"
                ) { isEnd ->
                    ClockTimeWheelPicker(
                        minuteOfDay = if (isEnd) endMin else startMin,
                        onMinuteOfDayChanged = { updated ->
                            if (isEnd) endMin = updated else startMin = updated
                        },
                        wheelHeight = 154.dp
                    )
                }

                // Blocklist selector cycle
                if (blocklists.size > 1) {
                    val currentProfile = blocklists.firstOrNull { it.id == blocklistId } ?: blocklists.first()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(g.glassSurface)
                            .clickable {
                                haptics.tap()
                                val nextIdx = (blocklists.indexOfFirst { it.id == currentProfile.id } + 1) % blocklists.size
                                blocklistId = blocklists[nextIdx].id
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Blocklist", color = g.textSecondary)
                        Text(currentProfile.name, color = g.primaryAccent, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Locked Mode", color = g.textPrimary, fontWeight = FontWeight.Medium)
                    FreedomSwitch(checked = strict, onCheckedChange = { strict = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (daysMask != 0 && startMin != endMin) {
                        haptics.tap()
                        onSave(
                            initial.copy(
                                name = name.trim().ifEmpty { "Focus Schedule" },
                                blocklistId = blocklistId,
                                daysMask = daysMask,
                                startMinuteOfDay = startMin,
                                endMinuteOfDay = endMin,
                                strictMode = strict
                            )
                        )
                    }
                }
            ) {
                Text("Save", color = g.primaryAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = {
                        haptics.tap()
                        onDelete()
                    }) {
                        Text("Delete", color = g.dangerCoral)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = g.textSecondary)
                }
            }
        }
    )
}

internal fun formatMinutesCompact(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}H ${m}M"
        h > 0 -> if (h == 1) "1 HR" else "$h HRS"
        else -> "$m MIN"
    }
}

internal fun formatMinutesHuman(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> if (h == 1) "1 hr" else "$h hrs"
        else -> "$m min"
    }
}

internal fun formatProfileSubtitle(
    profile: BlocklistProfileEntity,
    allowedAppCount: Int,
    webCategoryCount: Int
): String {
    val appsPart = "$allowedAppCount allowed app${if (allowedAppCount == 1) "" else "s"}"
    val webPart = when {
        profile.fullInternetBrowsingBlock -> "full internet browsing block"
        webCategoryCount > 0 -> "$webCategoryCount web filter categor${if (webCategoryCount == 1) "y" else "ies"}"
        else -> "no web categories"
    }
    return "$appsPart · $webPart"
}
