package com.example.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * ColorOS-inspired serene forest-green dark / botanical porcelain light canvas
 * with smoothly breathing ambient radial glow washes.
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
        animationSpec = tween(durationMillis = 650),
        label = "backdrop_glow"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(g.bgTop, g.bgBottom)
                )
            )
    ) {
        val orbModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Modifier.blur(96.dp)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(280.dp)
                .then(orbModifier)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (g.isDark) 0.26f else 0.34f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(240.dp)
                .then(orbModifier)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            g.primaryAccent.copy(alpha = if (g.isDark) 0.13f else 0.18f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        content()
    }
}

/**
 * ColorOS Luminous Glass card with generous continuous corners (24.dp),
 * top-lit hairline specular rim, smooth spring content resizing, and tactile press bounce.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    elevated: Boolean = false,
    accentBorderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    animateSize: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = FreedomGlass.colors
    val haptics = rememberFreedomHaptics()
    val shape = RoundedCornerShape(cornerRadius)
    val bgColor = if (elevated) g.glassSurfaceElevated else g.glassSurface
    val interactionSource = remember { MutableInteractionSource() }
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            accentBorderColor?.copy(alpha = 0.58f)
                ?: if (g.isDark) Color.White.copy(alpha = 0.17f) else Color.White.copy(alpha = 0.94f),
            accentBorderColor?.copy(alpha = 0.22f) ?: g.glassBorder
        )
    )

    val clickableModifier = if (onClick != null) {
        Modifier
            .colorOsPressScale(interactionSource, pressedScale = 0.975f)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = {
                    haptics.tap()
                    onClick()
                }
            )
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .let { if (animateSize) it.animateContentSize(animationSpec = FreedomMotion.FluidSizeSpring) else it }
            .clip(shape)
            .border(BorderStroke(1.dp, borderBrush), shape)
            .then(clickableModifier),
        shape = shape,
        color = bgColor,
        tonalElevation = 0.dp,
        shadowElevation = if (g.isDark) 0.dp else 5.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Signature full-width rounded pill CTA with ColorOS spring press-scale & subtle haptic feedback.
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
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = g.textMuted.copy(alpha = 0.28f),
            disabledContentColor = g.textSecondary.copy(alpha = 0.6f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .colorOsPressScale(interactionSource, pressedScale = 0.965f)
            .let { if (testTag != null) it.testTag(testTag) else it }
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * ColorOS fluid sliding-capsule segmented pill switcher (NOW | LATER | RECURRING, LENGTH | END TIME).
 * The active pill glides smoothly across options using spring physics instead of snapping.
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
    val outerShape = RoundedCornerShape(50)
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(outerShape)
            .background(g.glassSurface)
            .border(1.dp, g.glassBorder, outerShape)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * safeIndex,
            animationSpec = FreedomMotion.SnappyDpSpring,
            label = "segment_thumb_offset"
        )

        // Sliding active capsule
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(g.primaryAccent)
        )

        // Interactive labels layered cleanly over the sliding capsule
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == safeIndex
                val labelColor by animateColorAsState(
                    targetValue = if (selected) g.onPrimaryAccent else g.textSecondary,
                    animationSpec = tween(durationMillis = 180),
                    label = "segment_label_color"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
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
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
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
 * Clean ColorOS grouped navigation row inside a GlassCard.
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
    val haptics = rememberFreedomHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .let {
                if (onClick != null) {
                    it
                        .colorOsPressScale(interactionSource, pressedScale = 0.985f)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = androidx.compose.material3.ripple(),
                            onClick = {
                                haptics.tap()
                                onClick()
                            }
                        )
                } else it
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = g.textPrimary.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp)
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
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
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
                tint = g.textSecondary,
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
