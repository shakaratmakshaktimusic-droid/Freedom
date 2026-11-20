package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class BackdropMood {
    CALM,
    ACTIVE,
    LOCKED,
    SHIELD
}

private val PillShape = RoundedCornerShape(50)

/**
 * High-performance Forest Dark / Botanical Light glassmorphism backdrop.
 * Uses a single drawBehind radial-gradient pass instead of expensive RenderEffect
 * Modifier.blur(96.dp) layers, keeping 60 FPS locked during scrolling & tab switches.
 */
@Composable
fun ForestGlassBackdrop(
    mood: BackdropMood = BackdropMood.CALM,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val g = FreedomGlass.colors
    val targetGlow = when (mood) {
        BackdropMood.CALM -> g.orbGlow
        BackdropMood.ACTIVE -> g.shieldActive
        BackdropMood.LOCKED -> g.lockedAmber
        BackdropMood.SHIELD -> g.dangerCoral
    }
    val glowColor by animateColorAsState(
        targetValue = targetGlow,
        animationSpec = tween(durationMillis = 420),
        label = "backdrop_glow"
    )

    val bgBrush = remember(g.bgTop, g.bgBottom) {
        Brush.verticalGradient(colors = listOf(g.bgTop, g.bgBottom))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgBrush)
            .drawBehind {
                val topOrbRadius = size.minDimension * 0.85f
                val topAlpha = if (g.isDark) 0.24f else 0.30f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = topAlpha),
                            glowColor.copy(alpha = topAlpha * 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.92f, size.height * 0.06f),
                        radius = topOrbRadius
                    ),
                    radius = topOrbRadius,
                    center = Offset(size.width * 0.92f, size.height * 0.06f)
                )

                val bottomOrbRadius = size.minDimension * 0.72f
                val bottomAlpha = if (g.isDark) 0.12f else 0.16f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            g.primaryAccent.copy(alpha = bottomAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.08f, size.height * 0.92f),
                        radius = bottomOrbRadius
                    ),
                    radius = bottomOrbRadius,
                    center = Offset(size.width * 0.08f, size.height * 0.92f)
                )
            }
    ) {
        content()
    }
}

/**
 * Refined glass card with consistent 22.dp corners, cached specular border stroke,
 * crisp light/dark contrast, and lightweight GPU press feedback when clickable.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    elevated: Boolean = false,
    accentBorderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    animateSize: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = FreedomGlass.colors
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val bgColor = if (elevated) g.glassSurfaceElevated else g.glassSurface
    val borderStroke = remember(g.isDark, g.glassBorder, accentBorderColor) {
        BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accentBorderColor?.copy(alpha = 0.62f)
                        ?: if (g.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.96f),
                    accentBorderColor?.copy(alpha = 0.24f) ?: g.glassBorder
                )
            )
        )
    }

    val finalModifier = if (onClick != null) {
        val haptics = rememberFreedomHaptics()
        val interactionSource = remember { MutableInteractionSource() }
        modifier
            .fillMaxWidth()
            .colorOsPressScale(interactionSource, pressedScale = 0.985f)
            .clip(shape)
            .border(borderStroke, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = {
                    haptics.tap()
                    onClick()
                }
            )
    } else {
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(borderStroke, shape)
    }

    Surface(
        modifier = finalModifier,
        shape = shape,
        color = bgColor,
        tonalElevation = 0.dp,
        shadowElevation = if (g.isDark) 0.dp else 3.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Signature full-width rounded pill CTA with crisp Inter 700 typography and GPU press feedback.
 */
@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    testTag: String? = null
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val bg = containerColor ?: g.primaryAccent
    val fg = contentColor ?: g.onPrimaryAccent
    Button(
        onClick = {
            haptics.tap()
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = g.textMuted.copy(alpha = 0.28f),
            disabledContentColor = g.textSecondary.copy(alpha = 0.6f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .colorOsPressScale(interactionSource, pressedScale = 0.975f)
            .let { if (testTag != null) it.testTag(testTag) else it }
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 60 FPS GPU-translated sliding-capsule segmented pill switcher.
 * Uses graphicsLayer translationX instead of layout-triggering Modifier.offset.
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "segment"
) {
    if (options.isEmpty()) return
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(PillShape)
            .background(g.glassSurface)
            .border(1.dp, g.glassBorder, PillShape)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val segmentWidthPx = with(density) { segmentWidth.toPx() }
        val animatedIndex by animateFloatAsState(
            targetValue = safeIndex.toFloat(),
            animationSpec = FreedomMotion.CrispFloatSpec,
            label = "segment_thumb_index"
        )

        // GPU-only sliding active capsule (zero relayout passes during glide)
        Box(
            modifier = Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = animatedIndex * segmentWidthPx
                }
                .clip(PillShape)
                .background(g.primaryAccent)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == safeIndex
                val labelColor by animateColorAsState(
                    targetValue = if (selected) g.onPrimaryAccent else g.textSecondary,
                    animationSpec = tween(durationMillis = 150),
                    label = "segment_label_color"
                )
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(PillShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            if (index != safeIndex) {
                                haptics.tap()
                                onSelect(index)
                            }
                        }
                        .testTag("${testTagPrefix}_${label.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            letterSpacing = 0.6.sp
                        ),
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Clean grouped navigation row inside a GlassCard with balanced Inter 600/400 hierarchy.
 */
@Composable
fun GlassGroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
    trailingTint: Color? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val g = FreedomGlass.colors
    val rowModifier = if (onClick != null) {
        val haptics = rememberFreedomHaptics()
        val interactionSource = remember { MutableInteractionSource() }
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = {
                    haptics.tap()
                    onClick()
                }
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = g.textPrimary.copy(alpha = 0.9f),
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = g.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = trailingTint ?: g.primaryAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = g.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 18.dp),
        thickness = 1.dp,
        color = FreedomGlass.colors.divider
    )
}

@Composable
fun FreedomSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    Switch(
        checked = checked,
        onCheckedChange = { next ->
            haptics.tick()
            onCheckedChange(next)
        },
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = g.onPrimaryAccent,
            checkedTrackColor = g.primaryAccent,
            checkedBorderColor = g.primaryAccent,
            uncheckedThumbColor = g.textSecondary,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = g.textSecondary.copy(alpha = 0.55f)
        )
    )
}
