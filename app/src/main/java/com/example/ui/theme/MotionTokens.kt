package com.example.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * ColorOS-inspired interruptible spring physics & subtle haptic tokens.
 * All scale/translation curves run in GPU graphicsLayer for 120 Hz smoothness.
 */
object FreedomMotion {
    val FluidFloatSpring = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = 380f
    )
    val SnappyFloatSpring = spring<Float>(
        dampingRatio = 0.74f,
        stiffness = 520f
    )
    val PressFloatSpring = spring<Float>(
        dampingRatio = 0.68f,
        stiffness = 580f
    )
    val SnappyDpSpring = spring<Dp>(
        dampingRatio = 0.76f,
        stiffness = 480f
    )
    val FluidIntOffsetSpring = spring<IntOffset>(
        dampingRatio = 0.86f,
        stiffness = 400f
    )
    val FluidSizeSpring = spring<IntSize>(
        dampingRatio = 0.84f,
        stiffness = 380f
    )
    val SmoothSpringNoBounce = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 420f
    )
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
 * ColorOS tactile press-down spring scale executed strictly in GPU graphicsLayer.
 */
fun Modifier.colorOsPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.965f
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = FreedomMotion.PressFloatSpring,
        label = "coloros_press_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
