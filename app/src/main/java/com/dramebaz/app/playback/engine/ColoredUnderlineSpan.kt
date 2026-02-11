package com.dramebaz.app.playback.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LineBackgroundSpan
import android.text.style.ReplacementSpan
import android.text.style.UpdateAppearance

/**
 * Custom UnderlineSpan that supports custom colors.
 *
 * Android's built-in UnderlineSpan doesn't support custom colors - it uses the text color.
 * This span allows specifying an underline color independent of the text color.
 *
 * @param color The color to use for the underline
 * @param strokeWidth The thickness of the underline in pixels (default: 3f for visibility)
 */
class ColoredUnderlineSpan(
    private val color: Int,
    private val strokeWidth: Float = 3f
) : CharacterStyle(), UpdateAppearance {

    override fun updateDrawState(tp: TextPaint) {
        // Enable underline
        tp.isUnderlineText = true

        // Set the underline color by changing the paint color temporarily
        // Note: This approach uses a custom underline via drawLine in TextPaint
        tp.underlineColor = color
        tp.underlineThickness = strokeWidth
    }
}

/**
 * Reliable colored underline implementation using ReplacementSpan.
 * This draws the text normally and adds a colored underline below it.
 * Works on all API levels.
 *
 * @param color The color to use for the underline
 * @param strokeWidth The thickness of the underline in density-independent pixels
 */
class ColoredUnderlineSpanCompat(
    private val color: Int,
    private val strokeWidth: Float = 4f
) : ReplacementSpan() {

    private val underlinePaint = Paint().apply {
        this.color = this@ColoredUnderlineSpanCompat.color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        // Return the width of the text - we don't change the size
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        // Draw the text normally first
        canvas.drawText(text, start, end, x, y.toFloat(), paint)

        // Calculate underline position and width
        val textWidth = paint.measureText(text, start, end)
        val density = canvas.density.toFloat().coerceAtLeast(1f)
        val underlineY = y + (paint.descent() * 0.3f)  // Position slightly below baseline

        // Set stroke width based on density
        underlinePaint.strokeWidth = strokeWidth * density / 2f

        // Draw the colored underline
        canvas.drawLine(x, underlineY, x + textWidth, underlineY, underlinePaint)
    }
}

/**
 * Alternative: Rounded rectangle highlight span (like a highlighter marker).
 * This provides a more visible highlighting effect than underline.
 *
 * @param color The background color for the highlight
 * @param cornerRadius The corner radius for rounded rectangle
 * @param paddingVertical Vertical padding around text
 */
class RoundedHighlightSpan(
    private val color: Int,
    private val cornerRadius: Float = 4f,
    private val paddingVertical: Float = 2f
) : ReplacementSpan() {

    private val highlightPaint = Paint().apply {
        this.color = this@RoundedHighlightSpan.color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val rect = RectF()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val textWidth = paint.measureText(text, start, end)
        val density = canvas.density.toFloat().coerceAtLeast(1f)
        val padding = paddingVertical * density
        val radius = cornerRadius * density

        // Draw rounded rectangle background
        rect.set(x, top.toFloat() + padding, x + textWidth, bottom.toFloat() - padding)
        canvas.drawRoundRect(rect, radius, radius, highlightPaint)

        // Draw the text on top
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
    }
}

