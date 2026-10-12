package com.yomidroid.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/**
 * Kaku-style movable / resizable selection rectangle for region OCR.
 *
 * - Drag anywhere except the bottom-right corner to move.
 * - Drag the bottom-right corner (small grip) to resize.
 * - Double-tap to force rescan; long-press to cancel.
 */
class SelectionRectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** Finger down on the rect — cancel any in-flight scan/UI immediately. */
        fun onGestureStart()
        fun onMove(dx: Float, dy: Float)
        fun onResize(newWidth: Int, newHeight: Int, anchorLeft: Boolean, anchorTop: Boolean)
        fun onSettled()
        fun onConfirm()
        fun onCancel()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** Hit area for BR resize — modest, not large corner plates. */
    private val resizeHit = 22f * density
    private val gripLen = 12f * density
    private val borderWidth = 2f * density
    private val minWidth = (20f * density).toInt()
    private val minHeight = (40f * density).toInt()

    // Stroke-only frame (no fill) so screenshots of the selected region are not
    // blue-tinted. Dual light/dark stroke stays visible on light and dark pages.
    private val borderOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth + density
        color = 0xE0000000.toInt()
    }
    private val borderInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = 0xE0FFFFFF.toInt()
    }
    private val gripOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xE0000000.toInt()
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xE0FFFFFF.toInt()
    }

    private enum class Mode { NONE, MOVE, RESIZE_BR }

    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var gestureStartW = 0
    private var gestureStartH = 0
    private var gestureStartRawX = 0f
    private var gestureStartRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private val doubleTapMs = 300L
    private val longPressMs = 500L
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        listener?.onCancel()
    }


    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = borderWidth / 2f
        val rect = RectF(inset, inset, w - inset, h - inset)
        // No fill — content under the rect stays color-accurate for OCR.
        canvas.drawRect(rect, borderOuterPaint)
        canvas.drawRect(rect, borderInnerPaint)

        // Small L-shaped grip at bottom-right only (no large corner plates).
        val gx = w - inset - 2f * density
        val gy = h - inset - 2f * density
        canvas.drawLine(gx - gripLen, gy, gx, gy, gripOuterPaint)
        canvas.drawLine(gx, gy - gripLen, gx, gy, gripOuterPaint)
        canvas.drawLine(gx - gripLen, gy, gx, gy, gripPaint)
        canvas.drawLine(gx, gy - gripLen, gx, gy, gripPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Abort any OCR / panel still running from the previous settle
                // before this gesture moves or resizes the rect.
                listener?.onGestureStart()
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                gestureStartW = width
                gestureStartH = height
                gestureStartRawX = event.rawX
                gestureStartRawY = event.rawY
                moved = false
                longPressTriggered = false
                mode = hitTest(event.x, event.y)
                handler.postDelayed(longPressRunnable, longPressMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (!moved && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (moved && !longPressTriggered) {
                    when (mode) {
                        Mode.MOVE -> listener?.onMove(dx, dy)
                        Mode.RESIZE_BR -> applyResizeAbsolute(event.rawX, event.rawY)
                        Mode.NONE -> {}
                    }
                }
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_UP && !longPressTriggered) {
                    if (moved) {
                        listener?.onSettled()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapMs) {
                            lastTapTime = 0L
                            listener?.onConfirm()
                        } else {
                            lastTapTime = now
                        }
                    }
                }
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Mode {
        val w = width.toFloat()
        val h = height.toFloat()
        // Only bottom-right corner resizes; everywhere else moves.
        if (x >= w - resizeHit && y >= h - resizeHit) return Mode.RESIZE_BR
        return Mode.MOVE
    }

    private fun applyResizeAbsolute(rawX: Float, rawY: Float) {
        val totalDx = rawX - gestureStartRawX
        val totalDy = rawY - gestureStartRawY
        val newW = max(minWidth, (gestureStartW + totalDx).toInt())
        val newH = max(minHeight, (gestureStartH + totalDy).toInt())
        // Anchor top-left (left+top fixed).
        listener?.onResize(newW, newH, anchorLeft = true, anchorTop = true)
    }
}
