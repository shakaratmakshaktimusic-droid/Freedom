package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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

// Pre-allocated static label arrays (zero list/String.format allocations while scrolling)
private val DURATION_HOUR_LABELS = List(25) { "$it" }
private val DURATION_MINUTE_LABELS = List(60) { "$it" }
private val CLOCK_HOUR_LABELS = List(24) { if (it < 10) "0$it" else "$it" }
private val CLOCK_MINUTE_LABELS = List(60) { if (it < 10) "0$it" else "$it" }
private val WheelCapsuleShape = RoundedCornerShape(14.dp)

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
 * 60 FPS GPU-accelerated 3D rotary scroll wheel picker using Inter tabular numerals.
 */
@Composable
fun DurationWheelPicker(
    totalMinutes: Int,
    onMinutesChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wheelHeight: Dp = 200.dp
) {
    val g = FreedomGlass.colors
    val (currentHours, currentMins) = remember(totalMinutes) {
        DurationWheelBounds.splitDuration(totalMinutes)
    }
    val latestHours by rememberUpdatedState(currentHours)
    val latestMins by rememberUpdatedState(currentMins)
    val latestTotal by rememberUpdatedState(totalMinutes)
    val latestCallback by rememberUpdatedState(onMinutesChanged)

    val itemHeight = 42.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(wheelHeight)
            .testTag("duration_wheel_picker"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight + 4.dp)
                .clip(WheelCapsuleShape)
                .background(
                    if (g.isDark) Color.White.copy(alpha = 0.10f)
                    else g.primaryAccent.copy(alpha = 0.11f)
                )
                .border(
                    width = 1.dp,
                    color = if (g.isDark) Color.White.copy(alpha = 0.14f)
                    else g.primaryAccent.copy(alpha = 0.22f),
                    shape = WheelCapsuleShape
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
                labels = DURATION_HOUR_LABELS,
                selectedIndex = currentHours.coerceIn(0, 24),
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                onSelectedIndexChange = { newHour ->
                    val clamped = DurationWheelBounds.clampDuration(newHour, latestMins)
                    if (clamped != latestTotal) {
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
                    letterSpacing = 0.5.sp
                ),
                color = g.textPrimary,
                modifier = Modifier.padding(start = 2.dp, end = 22.dp)
            )

            RotaryWheelColumn(
                labels = DURATION_MINUTE_LABELS,
                selectedIndex = currentMins.coerceIn(0, 59),
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                onSelectedIndexChange = { newMin ->
                    val clamped = DurationWheelBounds.clampDuration(latestHours, newMin)
                    if (clamped != latestTotal) {
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
                    letterSpacing = 0.5.sp
                ),
                color = g.textPrimary,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

/**
 * Smooth minute-by-minute 24-hour clock time rotary wheel (00:00–23:59).
 */
@Composable
fun ClockTimeWheelPicker(
    minuteOfDay: Int,
    onMinuteOfDayChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wheelHeight: Dp = 184.dp
) {
    val g = FreedomGlass.colors
    val normalized = ((minuteOfDay % 1440) + 1440) % 1440
    val currentHour = normalized / 60
    val currentMinute = normalized % 60

    val latestHour by rememberUpdatedState(currentHour)
    val latestMinute by rememberUpdatedState(currentMinute)
    val latestNormalized by rememberUpdatedState(normalized)
    val latestCallback by rememberUpdatedState(onMinuteOfDayChanged)

    val itemHeight = 40.dp

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
                .clip(WheelCapsuleShape)
                .background(
                    if (g.isDark) Color.White.copy(alpha = 0.10f)
                    else g.primaryAccent.copy(alpha = 0.11f)
                )
                .border(
                    width = 1.dp,
                    color = if (g.isDark) Color.White.copy(alpha = 0.14f)
                    else g.primaryAccent.copy(alpha = 0.22f),
                    shape = WheelCapsuleShape
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
                labels = CLOCK_HOUR_LABELS,
                selectedIndex = currentHour,
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                onSelectedIndexChange = { newH ->
                    val next = newH * 60 + latestMinute
                    if (next != latestNormalized) latestCallback(next)
                },
                modifier = Modifier
                    .width(72.dp)
                    .testTag("clock_wheel_hour")
            )

            Text(
                text = ":",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                color = g.primaryAccent,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            RotaryWheelColumn(
                labels = CLOCK_MINUTE_LABELS,
                selectedIndex = currentMinute,
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                onSelectedIndexChange = { newM ->
                    val next = latestHour * 60 + newM
                    if (next != latestNormalized) latestCallback(next)
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
    labels: List<String>,
    selectedIndex: Int,
    itemHeight: Dp,
    wheelHeight: Dp,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val maxIdx = (labels.size - 1).coerceAtLeast(0)
    val safeInitial = selectedIndex.coerceIn(0, maxIdx)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val verticalPadding = remember(wheelHeight, itemHeight) {
        ((wheelHeight - itemHeight) / 2).coerceAtLeast(0.dp)
    }
    val latestOnSelect by rememberUpdatedState(onSelectedIndexChange)

    // Keep wheel aligned only when caller changes value externally (e.g. clamping 24H -> 00 MIN)
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(0, maxIdx)
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != target) {
            listState.animateScrollToItem(target)
        }
    }

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
                val bounded = idx.coerceIn(0, maxIdx)
                if (initialized) {
                    haptics.tick()
                    latestOnSelect(bounded)
                } else {
                    initialized = true
                }
            }
    }

    val centerTextStyle = remember {
        androidx.compose.ui.text.TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 23.sp,
            fontFeatureSettings = "tnum"
        )
    }
    val outerTextStyle = remember {
        androidx.compose.ui.text.TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            fontFeatureSettings = "tnum"
        )
    }

    Box(modifier = modifier.height(wheelHeight)) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(labels.size, key = { it }) { index ->
                // Scoped derivedStateOf so ONLY the 2 rows entering/leaving center recompose
                val isCenter by remember(index) {
                    derivedStateOf { centeredIndex == index }
                }
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
                            val halfHeight = (wheelHeight.toPx() / 2f).coerceAtLeast(1f)
                            val distanceRatio = ((itemCenter - viewportCenter) / halfHeight).coerceIn(-1f, 1f)
                            val absDist = abs(distanceRatio)
                            val scaleVal = 1f - (absDist * 0.22f)
                            scaleX = scaleVal
                            scaleY = scaleVal
                            alpha = (1f - absDist * 0.72f).coerceIn(0.22f, 1f)
                            rotationX = -distanceRatio * 24f
                            cameraDistance = 16f * density
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labels[index],
                        style = if (isCenter) centerTextStyle else outerTextStyle,
                        color = if (isCenter) g.textPrimary else g.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
