package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.domain.ThemeMode

@Immutable
data class FreedomGlassColors(
    val isDark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val orbGlow: Color,
    val glassSurface: Color,
    val glassSurfaceElevated: Color,
    val glassBorder: Color,
    val divider: Color,
    val navSurface: Color,
    val primaryAccent: Color,
    val onPrimaryAccent: Color,
    val primarySoft: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val shieldActive: Color,
    val lockedAmber: Color,
    val dangerCoral: Color
)

val LocalFreedomGlassColors = staticCompositionLocalOf { DarkGlassPalette }

private val DarkGlassPalette = FreedomGlassColors(
    isDark = true,
    bgTop = ForestDarkBgTop,
    bgBottom = ForestDarkBgBottom,
    orbGlow = ForestDarkOrbGlow,
    glassSurface = ForestDarkGlassSurface,
    glassSurfaceElevated = ForestDarkGlassSurfaceElevated,
    glassBorder = ForestDarkGlassBorder,
    divider = ForestDarkDivider,
    navSurface = ForestDarkNavSurface,
    primaryAccent = ForestDarkPrimary,
    onPrimaryAccent = ForestDarkOnPrimary,
    primarySoft = ForestDarkPrimarySoft,
    textPrimary = ForestDarkTextPrimary,
    textSecondary = ForestDarkTextSecondary,
    textMuted = ForestDarkTextMuted,
    shieldActive = ShieldActiveDark,
    lockedAmber = LockedAmberDark,
    dangerCoral = DangerCoralDark
)

private val LightGlassPalette = FreedomGlassColors(
    isDark = false,
    bgTop = ForestLightBgTop,
    bgBottom = ForestLightBgBottom,
    orbGlow = ForestLightOrbGlow,
    glassSurface = ForestLightGlassSurface,
    glassSurfaceElevated = ForestLightGlassSurfaceElevated,
    glassBorder = ForestLightGlassBorder,
    divider = ForestLightDivider,
    navSurface = ForestLightNavSurface,
    primaryAccent = ForestLightPrimary,
    onPrimaryAccent = ForestLightOnPrimary,
    primarySoft = ForestLightPrimarySoft,
    textPrimary = ForestLightTextPrimary,
    textSecondary = ForestLightTextSecondary,
    textMuted = ForestLightTextMuted,
    shieldActive = ShieldActiveLight,
    lockedAmber = LockedAmberLight,
    dangerCoral = DangerCoralLight
)

private val FreedomDarkColorScheme = darkColorScheme(
    primary = ForestDarkPrimary,
    onPrimary = ForestDarkOnPrimary,
    primaryContainer = Color(0xFF1D3F29),
    onPrimaryContainer = ForestDarkTextPrimary,
    secondary = ShieldActiveDark,
    onSecondary = Color(0xFF071D10),
    background = ForestDarkBgBottom,
    onBackground = ForestDarkTextPrimary,
    surface = Color(0xFF0E1D13),
    onSurface = ForestDarkTextPrimary,
    surfaceVariant = Color(0xFF172D1F),
    onSurfaceVariant = ForestDarkTextSecondary,
    outline = ForestDarkGlassBorder,
    error = DangerCoralDark
)

private val FreedomLightColorScheme = lightColorScheme(
    primary = ForestLightPrimary,
    onPrimary = ForestLightOnPrimary,
    primaryContainer = Color(0xFFD2E7D6),
    onPrimaryContainer = ForestLightTextPrimary,
    secondary = ShieldActiveLight,
    onSecondary = Color.White,
    background = ForestLightBgTop,
    onBackground = ForestLightTextPrimary,
    surface = Color(0xFFF8FCF9),
    onSurface = ForestLightTextPrimary,
    surfaceVariant = Color(0xFFE3EFE5),
    onSurfaceVariant = ForestLightTextSecondary,
    outline = ForestLightGlassBorder,
    error = DangerCoralLight
)

object FreedomGlass {
    val colors: FreedomGlassColors
        @Composable get() = LocalFreedomGlassColors.current
}

@Composable
fun FreedomTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val glassPalette = if (useDark) DarkGlassPalette else LightGlassPalette
    val m3Scheme = if (useDark) FreedomDarkColorScheme else FreedomLightColorScheme

    CompositionLocalProvider(LocalFreedomGlassColors provides glassPalette) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = FreedomTypography,
            content = content
        )
    }
}
