package com.example.blocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Ultra-lightweight, single-Canvas TYPE_ACCESSIBILITY_OVERLAY for Regain-style
 * unapproved YouTube card masking and pre-interaction explicit search/image card covering.
 *
 * Performance & Stability Guarantees:
 *  - Uses ONE single pooled WindowManager overlay view (never adds/removes multiple views
 *    per scroll frame) and updates card Rects in-place via `invalidate()`.
 *  - Uses FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE so native YouTube & browser scrolling
 *    remains 100% smooth at 60 FPS with zero gesture lag or flicker.
 *  - Approved-channel cards are never masked; if a user taps a masked unapproved video card,
 *    the instant Watch-screen / Direct-Link gate blocks playback before it plays.
 */
class ContentMaskOverlayManager(private val context: Context) {

    data class MaskItem(
        val rect: Rect,
        val label: String,
        val isAdultShield: Boolean = false
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var canvasView: MaskCanvasView? = null

    fun updateMasks(items: List<MaskItem>) {
        if (items.isEmpty()) {
            clear()
            return
        }
        mainHandler.post {
            val existing = canvasView
            if (existing != null && existing.isAttachedToWindow) {
                existing.setMasks(items)
                return@post
            }
            val view = MaskCanvasView(context).apply {
                setMasks(items)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            runCatching {
                windowManager.addView(view, params)
                canvasView = view
            }
        }
    }

    fun clear() {
        mainHandler.post {
            val view = canvasView ?: return@post
            view.setMasks(emptyList())
            runCatching { windowManager.removeViewImmediate(view) }
            canvasView = null
        }
    }

    private class MaskCanvasView(context: Context) : View(context) {
        private var masks: List<MaskItem> = emptyList()
        private val density = context.resources.displayMetrics.density
        private val cornerRadius = 16f * density
        private val rectF = RectF()

        private val studyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F50D2216")
            style = Paint.Style.FILL
        }
        private val adultFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FA151A1C")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6697C387")
            style = Paint.Style.STROKE
            strokeWidth = 1.25f * density
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8F5EB")
            textSize = 13.5f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#97C387")
            textSize = 11.5f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        fun setMasks(items: List<MaskItem>) {
            masks = items
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (masks.isEmpty()) return
            val inset = 4f * density
            for (item in masks) {
                rectF.set(
                    item.rect.left + inset,
                    item.rect.top + inset,
                    item.rect.right - inset,
                    item.rect.bottom - inset
                )
                if (rectF.width() < 40f || rectF.height() < 32f) continue

                val fill = if (item.isAdultShield) adultFillPaint else studyFillPaint
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fill)
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)

                val cx = rectF.centerX()
                val cy = rectF.centerY()
                if (rectF.height() >= 68f * density) {
                    canvas.drawText(item.label, cx, cy - 4f * density, titlePaint)
                    val sub = if (item.isAdultShield) "Covered by Freedom Protection" else "Freedom Study Mode"
                    canvas.drawText(sub, cx, cy + 14f * density, subtitlePaint)
                } else {
                    canvas.drawText(item.label, cx, cy + 5f * density, titlePaint)
                }
            }
        }
    }
}
