package com.example.blocker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
 * High-priority full-screen blocking shield using TYPE_ACCESSIBILITY_OVERLAY when
 * attached to FreedomAccessibilityService (requires ZERO SYSTEM_ALERT_WINDOW permission
 * and renders ABOVE split-screen panes, floating pop-ups, and Picture-in-Picture windows!).
 *
 * Supports both brief flash-shield mode (eliminating flicker while BlockOverlayActivity opens)
 * and persistent PiP/multi-window cover mode until the blocked window is dismissed.
 */
class FreedomOverlayManager(
    private val context: Context,
    private val useAccessibilityOverlay: Boolean = true
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var activeView: View? = null
    private var removeRunnable: Runnable? = null

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
            removeOverlayInternal()

            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#F50E2F1A"))
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(72, 72, 72, 72)
            }
            val badgeView = TextView(context).apply {
                text = "FREEDOM SHIELD"
                setTextColor(Color.parseColor("#92BC82"))
                textSize = 12f
                letterSpacing = 0.14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 18)
            }
            val titleView = TextView(context).apply {
                text = title
                setTextColor(Color.parseColor("#F4FAF5"))
                textSize = 23f
                gravity = Gravity.CENTER
            }
            val subView = TextView(context).apply {
                text = subtitle
                setTextColor(Color.parseColor("#B6CEBB"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 32)
            }
            column.addView(badgeView)
            column.addView(titleView)
            column.addView(subView)

            if (onDismissAction != null) {
                val homeBtn = Button(context).apply {
                    text = "Return to Home"
                    setTextColor(Color.parseColor("#0A2213"))
                    setBackgroundColor(Color.parseColor("#92BC82"))
                    setPadding(48, 24, 48, 24)
                    setOnClickListener {
                        removeOverlayInternal()
                        onDismissAction()
                    }
                }
                column.addView(homeBtn)
            }

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
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
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
                if (durationMs > 0L) {
                    val runnable = Runnable { removeOverlayInternal() }
                    removeRunnable = runnable
                    mainHandler.postDelayed(runnable, durationMs)
                }
            }
        }
    }

    fun dismiss() {
        mainHandler.post { removeOverlayInternal() }
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
    }
}
