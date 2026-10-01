package com.yomidroid.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * Kaku-style OCR result panel: shows recognised text as normal Japanese text.
 * Taps map to a character index so the host can run longest-match dictionary
 * lookup — no synthetic per-glyph boxes (manga-ocr does not provide them).
 */
class OcrTextSelectPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        /** [charIndex] is the code-unit index in [text] under the tap. */
        fun onCharTapped(text: String, charIndex: Int)
        fun onDismiss()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val textView: TextView
    private var currentText: String = ""

    init {
        val bg = GradientDrawable().apply {
            setColor(0xF01E1E1E.toInt())
            cornerRadius = 12f * density
            setStroke((1.5f * density).toInt(), 0xFF42A5F5.toInt())
        }
        background = bg
        setPadding(
            (12 * density).toInt(),
            (10 * density).toInt(),
            (12 * density).toInt(),
            (10 * density).toInt()
        )
        elevation = 8f * density

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.SERIF
            setLineSpacing(0f, 1.25f)
            // Soft wrap; vertical text is still shown left-to-right in lines,
            // which is fine for lookup — the point is selectable runs of text.
            setTextIsSelectable(false)
            // Transparent highlight when we draw our own later
            highlightColor = 0x5542A5F5
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                textView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        textView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && currentText.isNotEmpty()) {
                val tv = v as TextView
                val layout = tv.layout ?: return@setOnTouchListener false
                val x = event.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                val line = layout.getLineForVertical(y.coerceIn(0, max(0, layout.height - 1)))
                val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                    .coerceIn(0, currentText.length)
                // Prefer the character under the tap; if at end, step back one.
                val idx = if (offset >= currentText.length && currentText.isNotEmpty()) {
                    currentText.length - 1
                } else {
                    offset
                }
                listener?.onCharTapped(currentText, idx)
                true
            } else {
                // Consume DOWN so the parent doesn't steal the gesture.
                event.action == MotionEvent.ACTION_DOWN
            }
        }
    }

    fun setOcrText(text: String) {
        currentText = text
        // Insert newlines between runs of CJK if the source already has them;
        // otherwise show as a single block.
        textView.text = text
    }

    fun getOcrText(): String = currentText
}
