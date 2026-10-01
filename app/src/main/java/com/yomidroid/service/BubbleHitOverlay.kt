package com.yomidroid.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Draws lightweight outlines for each recognised bubble. Tap selects one bubble
 * and reports its index — host then opens the text panel for that bubble only.
 */
class BubbleHitOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Bubble(val bounds: Rect, val text: String)

    interface Listener {
        fun onBubbleTapped(index: Int, bubble: Bubble)
        fun onBackgroundTapped()
    }

    var listener: Listener? = null
    private var bubbles: List<Bubble> = emptyList()
    private var selected = -1

    // Screen-space transform (bitmap → view), same convention as OverlayTextView
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var cropOffsetX: Float = 0f
    var cropOffsetY: Float = 0f

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2242A5F5
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xCC42A5F5.toInt()
    }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4042A5F5
    }

    fun setBubbles(list: List<Bubble>) {
        bubbles = list
        selected = -1
        invalidate()
    }

    private fun toScreen(r: Rect): RectF = RectF(
        (r.left + cropOffsetX) * scaleX,
        (r.top + cropOffsetY) * scaleY,
        (r.right + cropOffsetX) * scaleX,
        (r.bottom + cropOffsetY) * scaleY
    )

    override fun onDraw(canvas: Canvas) {
        for ((i, b) in bubbles.withIndex()) {
            val rf = toScreen(b.bounds)
            canvas.drawRect(rf, if (i == selected) selectedFill else fillPaint)
            canvas.drawRect(rf, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        for ((i, b) in bubbles.withIndex()) {
            val rf = toScreen(b.bounds)
            if (rf.contains(x, y)) {
                selected = i
                invalidate()
                listener?.onBubbleTapped(i, b)
                return true
            }
        }
        listener?.onBackgroundTapped()
        return true
    }
}
