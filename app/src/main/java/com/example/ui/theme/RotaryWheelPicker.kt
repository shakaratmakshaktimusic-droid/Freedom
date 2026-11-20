package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * Pure duration-clamping helper for the 0–24 hour minute-by-minute rotary wheel:
 *  - Minimum total duration is 1 minute (0h 0m clamps to 0h 1m)
 *  - Maximum total duration is 24 hours = 1440 minutes (24h clamps minutes to 0)
 */
object DurationWheelBounds {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 24 * 60

    fun clampDuration(hours: Int, minutes: Int): Int {
        val safeH = hours.coerceIn(0, 24)
        val safeM = if (safeH == 24) 0 else minutes.coerceIn(0, 59)
        return (safeH * 60 + safeM).coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    fun splitDuration(totalMinutes: Int): Pair<Int, Int> {
        val clamped = totalMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        return (clamped / 60) to (clamped % 60)
    }
}

/**
 * ColorOS-inspired 3D-perspective rotary scroll wheel picker matching Screenshot 1:
 *  - Smooth GPU graphicsLayer perspective tilt, scale, and opacity falloff
 *  - 120 Hz snap fling physics with subtle CLOCK_TICK haptics on every detent
 *  - Translucent central highlight capsule across the selected row
 */
@Composable
fun DurationWheelPicker(
    totalMinutes: Int,
    onMinutesChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wheelHeight: Dp = 204.dp
) {
    val g = FreedomGlass.colors
    val (currentHours, currentMins) = remember(totalMinutes) {
        DurationWheelBounds.splitDuration(totalMinutes)
    }
    val latestHours by rememberUpdatedState(currentHours)
    val latestMins by rememberUpdatedState(currentMins)
    val latestCallback by rememberUpdatedState(onMinutesChanged)

    val itemHeight = 42.dp
    val capsuleShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(wheelHeight)
            .testTag("duration_wheel_picker"),
        contentAlignment = Alignment.Center
    ) {
        // Central frosted highlight bar matching Screenshot 1
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight + 4.dp)
                .clip(capsuleShape)
                .background(
                    if (g.isDark) Color.White.copy(alpha = 0.11f)
                    else g.primaryAccent.copy(alpha = 0.12f)
                )
                .border(
                    width = 1.dp,
                    color = if (g.isDark) Color.White.copy(alpha = 0.14f)
                    else g.primaryAccent.copy(alpha = 0.22f),
                    shape = capsuleShape
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hours wheel (0..24)
            RotaryWheelColumn(
                items = (0..24).toList(),
                selectedIndex = currentHours.coerceIn(0, 24),
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                formatItem = { "$it" },
                onSelectedIndexChange = { newHour ->
                    val clamped = DurationWheelBounds.clampDuration(newHour, latestMins)
                    if (clamped != totalMinutes) {
                        latestCallback(clamped)
                    }
                },
                modifier = Modifier
                    .width(64.dp)
                    .testTag("wheel_hours")
            )
            Text(
                text = "H",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                ),
                color = g.textPrimary,
                modifier = Modifier.padding(start = 2.dp, end = 22.dp)
            )

            // Minutes wheel (0..59)
            RotaryWheelColumn(
                items = (0..59).toList(),
                selectedIndex = currentMins.coerceIn(0, 59),
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                formatItem = { "$it" },
                onSelectedIndexChange = { newMin ->
                    val clamped = DurationWheelBounds.clampDuration(latestHours, newMin)
                    if (clamped != totalMinutes) {
                        latestCallback(clamped)
                    }
                },
                modifier = Modifier
                    .width(64.dp)
                    .testTag("wheel_minutes")
            )
            Text(
                text = "MIN",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                ),
                color = g.textPrimary,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

/**
 * Smooth minute-by-minute 24-hour clock time rotary wheel (00:00–23:59) used in
 * End Time, Later, and Recurring schedule editors.
 */
@Composable
fun ClockTimeWheelPicker(
    minuteOfDay: Int,
    onMinuteOfDayChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wheelHeight: Dp = 186.dp
) {
    val g = FreedomGlass.colors
    val normalized = ((minuteOfDay % 1440) + 1440) % 1440
    val currentHour = normalized / 60
    val currentMinute = normalized % 60

    val latestHour by rememberUpdatedState(currentHour)
    val latestMinute by rememberUpdatedState(currentMinute)
    val latestCallback by rememberUpdatedState(onMinuteOfDayChanged)

    val itemHeight = 40.dp
    val capsuleShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(wheelHeight)
            .testTag("clock_time_wheel_picker"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight + 4.dp)
                .clip(capsuleShape)
                .background(
                    if (g.isDark) Color.White.copy(alpha = 0.11f)
                    else g.primaryAccent.copy(alpha = 0.12f)
                )
                .border(
                    width = 1.dp,
                    color = if (g.isDark) Color.White.copy(alpha = 0.14f)
                    else g.primaryAccent.copy(alpha = 0.22f),
                    shape = capsuleShape
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryWheelColumn(
                items = (0..23).toList(),
                selectedIndex = currentHour,
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                formatItem = { String.format("%02d", it) },
                onSelectedIndexChange = { newH ->
                    val next = newH * 60 + latestMinute
                    if (next != normalized) latestCallback(next)
                },
                modifier = Modifier
                    .width(72.dp)
                    .testTag("clock_wheel_hour")
            )

            Text(
                text = ":",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold
                ),
                color = g.primaryAccent,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            RotaryWheelColumn(
                items = (0..59).toList(),
                selectedIndex = currentMinute,
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                formatItem = { String.format("%02d", it) },
                onSelectedIndexChange = { newM ->
                    val next = latestHour * 60 + newM
                    if (next != normalized) latestCallback(next)
                },
                modifier = Modifier
                    .width(72.dp)
                    .testTag("clock_wheel_minute")
            )
        }
    }
}

@Composable
private fun RotaryWheelColumn(
    items: List<Int>,
    selectedIndex: Int,
    itemHeight: Dp,
    wheelHeight: Dp,
    formatItem: (Int) -> String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val safeInitial = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val verticalPadding = ((wheelHeight - itemHeight) / 2).coerceAtLeast(0.dp)
    val latestOnSelect by rememberUpdatedState(onSelectedIndexChange)

    // Keep wheel aligned if caller changes value externally (e.g. clamping 24H -> 00 MIN)
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != target) {
            listState.animateScrollToItem(target)
        }
    }

    // Track currently centered item continuously for crisp haptic tick & live updates
    val centeredIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                safeInitial
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visible.minByOrNull { info ->
                    abs((info.offset + info.size / 2) - viewportCenter)
                }?.index ?: safeInitial
            }
        }
    }

    LaunchedEffect(listState) {
        var initialized = false
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .collect { idx ->
                val bounded = idx.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                if (initialized) {
                    haptics.tick()
                    latestOnSelect(bounded)
                } else {
                    initialized = true
                }
            }
    }

    Box(modifier = modifier.height(wheelHeight)) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items.size, key = { it }) { index ->
                val isCenter = index == centeredIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val layoutInfo = listState.layoutInfo
                            val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            val viewportCenter =
                                (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                            val itemCenter = if (info != null) {
                                info.offset + info.size / 2f
                            } else {
                                viewportCenter
                            }
                            val distanceRatio =
                                ((itemCenter - viewportCenter) / (wheelHeight.toPx() / 2f))
                                    .coerceIn(-1f, 1f)
                            val absDist = abs(distanceRatio)
                            val scaleVal = 1f - (absDist * 0.28f)
                            scaleX = scaleVal
                            scaleY = scaleVal
                            alpha = (1f - absDist * 0.78f).coerceIn(0.18f, 1f)
                            rotationX = -distanceRatio * 38f
                            cameraDistance = 14f * density
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatItem(items[index]),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = JetBrainsMono,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 23.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (isCenter) g.textPrimary else g.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
