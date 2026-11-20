package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
 * Clean serene forest-green dark / soft botanical light gradient canvas with
 * restrained ambient glow orbs (blur applied only to static backdrop on API 31+).
 */
@Composable
fun ForestGlassBackdrop(
    mood: BackdropMood = BackdropMood.CALM,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val g = FreedomGlass.colors
    val glowColor = when (mood) {
        BackdropMood.CALM -> g.orbGlow
        BackdropMood.ACTIVE -> g.shieldActive
        BackdropMood.LOCKED -> g.lockedAmber
        BackdropMood.SHIELD -> g.dangerCoral
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(g.bgTop, g.bgBottom)
                )
            )
    ) {
        // Subtle ambient top-right light wash for clean glass depth (zero visual clutter)
        val orbModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Modifier.blur(90.dp)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(260.dp)
                .then(orbModifier)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (g.isDark) 0.24f else 0.34f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(220.dp)
                .then(orbModifier)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            g.primaryAccent.copy(alpha = if (g.isDark) 0.12f else 0.18f),
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
 * Refined translucent glass card matching the clean dark-pine / milky-white aesthetic.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    elevated: Boolean = false,
    accentBorderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = FreedomGlass.colors
    val shape = RoundedCornerShape(cornerRadius)
    val bgColor = if (elevated) g.glassSurfaceElevated else g.glassSurface
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            accentBorderColor?.copy(alpha = 0.55f)
                ?: if (g.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.90f),
            accentBorderColor?.copy(alpha = 0.22f) ?: g.glassBorder
        )
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, borderBrush), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = bgColor,
        tonalElevation = 0.dp,
        shadowElevation = if (g.isDark) 0.dp else 4.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Signature full-width rounded pill CTA button matching the reference screenshots.
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
    val bg = containerColor ?: g.primaryAccent
    val fg = contentColor ?: g.onPrimaryAccent
    Button(
        onClick = onClick,
        enabled = enabled,
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
 * Clean 2-way or 3-way segmented pill switcher (e.g., NOW | LATER | RECURRING, LENGTH | END TIME).
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "segment"
) {
    val g = FreedomGlass.colors
    val outerShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(g.glassSurface)
            .border(1.dp, g.glassBorder, outerShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) g.primaryAccent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .testTag("${testTagPrefix}_${label.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = if (selected) g.onPrimaryAccent else g.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Clean grouped navigation row inside a GlassCard (icon + title + optional trailing value + chevron).
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
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
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
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
