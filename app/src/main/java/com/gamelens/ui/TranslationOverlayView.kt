package com.gamelens.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Transparent overlay that draws translated text inside bounding boxes
 * on the game screen during live mode. Each box corresponds to an OCR
 * text group and is filled with a semi-transparent background so the
 * translated text is readable over game graphics. Font size auto-scales
 * to fill each box.
 */
class TranslationOverlayView(context: Context) : View(context) {

    data class TextBox(
        val translatedText: String,
        /** Bounding box in original bitmap pixel coordinates. */
        val bounds: Rect
    )

    private val dp = context.resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val minTextSizePx = 8f * dp
    private val boxPadding = 4f * dp

    private var boxes: List<TextBox> = emptyList()
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var displayScaleX = 1f
    private var displayScaleY = 1f
    private var screenshotW = 1
    private var screenshotH = 1

    /** Cached layouts to avoid recomputation on every draw. */
    private var cachedLayouts: List<Pair<RectF, StaticLayout>>? = null

    fun setBoxes(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        this.boxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) updateScales()
        cachedLayouts = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScales()
        cachedLayouts = null
    }

    private fun updateScales() {
        displayScaleX = width.toFloat() / screenshotW
        displayScaleY = height.toFloat() / screenshotH
    }

    private fun mapRect(r: Rect): RectF {
        val left   = (r.left   + cropOffsetX) * displayScaleX
        val top    = (r.top    + cropOffsetY) * displayScaleY
        val right  = (r.right  + cropOffsetX) * displayScaleX
        val bottom = (r.bottom + cropOffsetY) * displayScaleY
        return RectF(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return

        val layouts = cachedLayouts ?: buildLayouts()
        cachedLayouts = layouts

        for ((screenRect, layout) in layouts) {
            // Draw background
            canvas.drawRect(screenRect, bgPaint)

            // Draw text centered in box
            canvas.save()
            val textX = screenRect.left + boxPadding
            val textY = screenRect.top + (screenRect.height() - layout.height) / 2f
            canvas.translate(textX, textY)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildLayouts(): List<Pair<RectF, StaticLayout>> {
        return boxes.map { box ->
            val screenRect = mapRect(box.bounds)
            val availW = (screenRect.width() - 2 * boxPadding).toInt().coerceAtLeast(1)
            val availH = screenRect.height() - 2 * boxPadding
            val layout = fitText(box.translatedText, availW, availH)
            Pair(screenRect, layout)
        }
    }

    /**
     * Creates a [StaticLayout] for [text] that fits within [availW] x [availH] pixels.
     * Starts with a large font size and shrinks until the text fits, capped at [minTextSizePx].
     */
    private fun fitText(text: String, availW: Int, availH: Float): StaticLayout {
        var lo = minTextSizePx
        var hi = availH.coerceAtLeast(minTextSizePx)
        var bestLayout = makeLayout(text, lo, availW)

        // Binary search for the largest font size that fits
        repeat(10) {
            val mid = (lo + hi) / 2f
            val layout = makeLayout(text, mid, availW)
            if (layout.height <= availH) {
                bestLayout = layout
                lo = mid
            } else {
                hi = mid
            }
        }
        return bestLayout
    }

    private fun makeLayout(text: String, textSize: Float, width: Int): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(100)
            .build()
    }
}
