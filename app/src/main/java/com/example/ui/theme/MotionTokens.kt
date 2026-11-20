package com.example.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Calm, responsive, zero-bounce motion curves engineered for consistent 60 FPS
 * on ordinary Android devices. All transforms run strictly on the GPU graphicsLayer.
 */
object FreedomMotion {
    val CrispFloatSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 480f
    )
    val PressFloatSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 620f
    )
    val SlideOffsetSpec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 480f
    )
    val ExpandSizeSpec = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 440f
    )
    val FastFadeIn = tween<Float>(durationMillis = 170)
    val FastFadeOut = tween<Float>(durationMillis = 120)

    // Preserved aliases for existing call sites
    val FluidFloatSpring = CrispFloatSpec
    val SnappyFloatSpring = CrispFloatSpec
    val FluidIntOffsetSpring = SlideOffsetSpec
    val FluidSizeSpring = ExpandSizeSpec
}

@Stable
class FreedomHaptics(private val view: android.view.View) {
    /** Crisp subtle tick for rotary wheel detents and switches. */
    fun tick() {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(flag)
    }

    /** Clean tactile tap for chips, segmented pills, tabs, and primary actions. */
    fun tap() {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(flag)
    }
}

@Composable
fun rememberFreedomHaptics(): FreedomHaptics {
    val view = LocalView.current
    return remember(view) { FreedomHaptics(view) }
}

/**
 * Subtle, non-bouncy press-down feedback executed solely in GPU graphicsLayer
 * (no Modifier.composed overhead).
 */
@Composable
fun Modifier.colorOsPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.98f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = FreedomMotion.PressFloatSpec,
        label = "press_scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
