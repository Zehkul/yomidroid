package com.yomidroid.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kaku-style movable / resizable selection rectangle for region OCR.
 *
 * - Drag the interior to move the host window (caller updates LayoutParams).
 * - Drag a corner handle to resize (caller updates LayoutParams).
 * - Double-tap the interior to confirm the selection.
 *
 * The view itself does not own WindowManager params; it reports gestures via
 * [Listener] so the accessibility service can update the overlay window.
 */
class SelectionRectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** [dx]/[dy] in screen pixels since the previous move event. */
        fun onMove(dx: Float, dy: Float)
        /** New width/height after a corner resize, in pixels. */
        fun onResize(newWidth: Int, newHeight: Int, anchorLeft: Boolean, anchorTop: Boolean)
        /**
         * Gesture finished after a move or resize. Host should debounce and
         * auto-scan (Kaku-style continuous selection).
         */
        fun onSettled()
        /** User double-tapped to force an immediate rescan. */
        fun onConfirm()
        /** User long-pressed — optional dismiss / cancel. */
        fun onCancel()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleSize = 28f * density
    private val borderWidth = 3f * density
    private val minSize = (40f * density).toInt()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = 0xFF42A5F5.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1842A5F5
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF42A5F5.toInt()
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f * density, 0f, 1f * density, Color.BLACK)
    }

    private enum class Mode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

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

    fun updateAccentColor(color: Int) {
        borderPaint.color = color
        handlePaint.color = color
        fillPaint.color = (color and 0x00FFFFFF) or 0x18000000
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = borderWidth / 2f
        val rect = RectF(inset, inset, w - inset, h - inset)

        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, borderPaint)

        // Corner handles
        drawHandle(canvas, 0f, 0f)
        drawHandle(canvas, w - handleSize, 0f)
        drawHandle(canvas, 0f, h - handleSize)
        drawHandle(canvas, w - handleSize, h - handleSize)

        if (h > 48f * density) {
            canvas.drawText("double-tap to scan", w / 2f, h / 2f + hintPaint.textSize / 3f, hintPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val r = RectF(x, y, x + handleSize, y + handleSize)
        canvas.drawRect(r, handlePaint)
        canvas.drawRect(r, handleBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                        // Resize from gesture-start geometry so min-size clamping does
                        // not desync the edge from the finger when reversing direction.
                        Mode.RESIZE_TL -> applyResizeAbsolute(
                            event.rawX, event.rawY, anchorLeft = false, anchorTop = false
                        )
                        Mode.RESIZE_TR -> applyResizeAbsolute(
                            event.rawX, event.rawY, anchorLeft = true, anchorTop = false
                        )
                        Mode.RESIZE_BL -> applyResizeAbsolute(
                            event.rawX, event.rawY, anchorLeft = false, anchorTop = true
                        )
                        Mode.RESIZE_BR -> applyResizeAbsolute(
                            event.rawX, event.rawY, anchorLeft = true, anchorTop = true
                        )
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
                        // Kaku-style: rescan after the selection is repositioned.
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
        if (x <= handleSize && y <= handleSize) return Mode.RESIZE_TL
        if (x >= w - handleSize && y <= handleSize) return Mode.RESIZE_TR
        if (x <= handleSize && y >= h - handleSize) return Mode.RESIZE_BL
        if (x >= w - handleSize && y >= h - handleSize) return Mode.RESIZE_BR
        return Mode.MOVE
    }

    /**
     * Resize from the geometry at gesture start plus total finger delta.
     * Clamping to [minSize] still applies, but reversing direction immediately
     * tracks the finger again (no stuck offset from incremental clamp).
     */
    private fun applyResizeAbsolute(
        rawX: Float,
        rawY: Float,
        anchorLeft: Boolean,
        anchorTop: Boolean
    ) {
        val totalDx = rawX - gestureStartRawX
        val totalDy = rawY - gestureStartRawY
        val newW = if (anchorLeft) {
            // right edge follows the finger
            max(minSize, (gestureStartW + totalDx).toInt())
        } else {
            // left edge follows the finger
            max(minSize, (gestureStartW - totalDx).toInt())
        }
        val newH = if (anchorTop) {
            max(minSize, (gestureStartH + totalDy).toInt())
        } else {
            max(minSize, (gestureStartH - totalDy).toInt())
        }
        listener?.onResize(newW, newH, anchorLeft, anchorTop)
    }
}
