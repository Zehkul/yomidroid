package com.yomidroid.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * Minimal OCR text panel: recognised text as plain Japanese, tap → char index
 * for longest-match dictionary lookup. Shows a highlight over the matched span.
 */
class OcrTextSelectPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onCharTapped(text: String, charIndex: Int)
        fun onDismiss()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val textView: TextView
    private var currentText: String = ""
    private var highlightStart: Int = -1
    private var highlightEnd: Int = -1

    init {
        // More transparent so underlying manga stays readable
        setBackgroundColor(0x99101010.toInt())
        setPadding(
            (8 * density).toInt(),
            (6 * density).toInt(),
            (8 * density).toInt(),
            (6 * density).toInt()
        )

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.SERIF
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(false)
            highlightColor = 0x6642A5F5
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
                val idx = if (offset >= currentText.length && currentText.isNotEmpty()) {
                    currentText.length - 1
                } else offset
                listener?.onCharTapped(currentText, idx)
                true
            } else {
                event.action == MotionEvent.ACTION_DOWN
            }
        }
    }

    fun setOcrText(text: String) {
        currentText = text
        highlightStart = -1
        highlightEnd = -1
        textView.text = text
    }

    /** Highlight [start, end) in the panel (e.g. matched dictionary term). */
    fun setHighlight(start: Int, end: Int) {
        if (currentText.isEmpty()) return
        val s = start.coerceIn(0, currentText.length)
        val e = end.coerceIn(s, currentText.length)
        highlightStart = s
        highlightEnd = e
        if (s >= e) {
            textView.text = currentText
            return
        }
        val span = SpannableString(currentText)
        span.setSpan(
            BackgroundColorSpan(0xAA42A5F5.toInt()),
            s,
            e,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.setSpan(
            ForegroundColorSpan(Color.WHITE),
            s,
            e,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        textView.text = span
    }

    fun getOcrText(): String = currentText
}
