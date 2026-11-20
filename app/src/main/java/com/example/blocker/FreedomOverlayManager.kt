package com.example.blocker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Rock-solid, flicker-free TYPE_ACCESSIBILITY_OVERLAY fallback shield.
 *
 * When already visible, subsequent showShield() calls update the existing views
 * in-place instead of removing and re-adding the WindowManager view — completely
 * eliminating flickering on OEM floating/mini-window retries.
 */
class FreedomOverlayManager(
    private val context: Context,
    private val useAccessibilityOverlay: Boolean = true
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var activeView: View? = null
    private var titleViewRef: TextView? = null
    private var subtitleViewRef: TextView? = null
    private var actionButtonRef: Button? = null
    private var removeRunnable: Runnable? = null

    val isVisible: Boolean
        get() = activeView != null

    fun showShield(
        title: String,
        subtitle: String,
        persistent: Boolean = false,
        onDismissAction: (() -> Unit)? = null
    ) {
        showInstantShield(
            title = title,
            subtitle = subtitle,
            durationMs = if (persistent) 0L else 950L,
            onDismissAction = onDismissAction
        )
    }

    fun showInstantShield(
        title: String,
        subtitle: String,
        durationMs: Long = 950L,
        onDismissAction: (() -> Unit)? = null
    ) {
        if (!useAccessibilityOverlay && !Settings.canDrawOverlays(context)) return

        mainHandler.post {
            // Update existing overlay in-place to guarantee ZERO flicker
            val existing = activeView
            if (existing != null && existing.isAttachedToWindow) {
                titleViewRef?.text = title
                subtitleViewRef?.text = subtitle
                actionButtonRef?.apply {
                    visibility = if (onDismissAction != null) View.VISIBLE else View.GONE
                    setOnClickListener {
                        removeOverlayInternal()
                        onDismissAction?.invoke()
                    }
                }
                scheduleAutoRemove(durationMs)
                return@post
            }

            removeOverlayInternal()

            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#FA0C2214"))
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
            }
            val badgeView = TextView(context).apply {
                text = "FREEDOM SHIELD"
                setTextColor(Color.parseColor("#97C387"))
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            }
            val titleView = TextView(context).apply {
                text = title
                setTextColor(Color.parseColor("#F5FAF6"))
                textSize = 24f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val subView = TextView(context).apply {
                text = subtitle
                setTextColor(Color.parseColor("#C0D6C4"))
                textSize = 14.5f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(28))
            }
            val pillBg = GradientDrawable().apply {
                setColor(Color.parseColor("#97C387"))
                cornerRadius = dp(28).toFloat()
            }
            val homeBtn = Button(context).apply {
                text = "Return to Home"
                isAllCaps = false
                setTextColor(Color.parseColor("#0A1D10"))
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = pillBg
                setPadding(dp(32), dp(14), dp(32), dp(14))
                visibility = if (onDismissAction != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    removeOverlayInternal()
                    onDismissAction?.invoke()
                }
            }

            column.addView(badgeView)
            column.addView(titleView)
            column.addView(subView)
            column.addView(
                homeBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                }
            )

            container.addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val windowType = resolveWindowType()
            val flags = if (onDismissAction != null) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
            )

            runCatching {
                windowManager.addView(container, params)
                activeView = container
                titleViewRef = titleView
                subtitleViewRef = subView
                actionButtonRef = homeBtn
                scheduleAutoRemove(durationMs)
            }
        }
    }

    fun dismiss() {
        mainHandler.post { removeOverlayInternal() }
    }

    private fun scheduleAutoRemove(durationMs: Long) {
        removeRunnable?.let { mainHandler.removeCallbacks(it) }
        removeRunnable = null
        if (durationMs > 0L) {
            val runnable = Runnable { removeOverlayInternal() }
            removeRunnable = runnable
            mainHandler.postDelayed(runnable, durationMs)
        }
    }

    private fun resolveWindowType(): Int {
        if (useAccessibilityOverlay) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun removeOverlayInternal() {
        removeRunnable?.let { mainHandler.removeCallbacks(it) }
        removeRunnable = null
        activeView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        activeView = null
        titleViewRef = null
        subtitleViewRef = null
        actionButtonRef = null
    }
}
