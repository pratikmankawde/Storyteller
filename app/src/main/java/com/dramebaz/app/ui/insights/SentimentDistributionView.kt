package com.dramebaz.app.ui.insights

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.dramebaz.app.data.models.SentimentDistribution

/**
 * INS-003: Sentiment Distribution Visualization
 * Custom view that displays a horizontal segmented bar chart showing
 * positive/neutral/negative sentiment distribution.
 */
class SentimentDistributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Colors
    private val positiveColor = Color.parseColor("#4CAF50")  // Green
    private val neutralColor = Color.parseColor("#9E9E9E")   // Grey
    private val negativeColor = Color.parseColor("#F44336")  // Red
    private val backgroundColor = Color.parseColor("#E0E0E0") // Light grey background

    // Data
    private var distribution: SentimentDistribution? = null
    private var animationProgress = 0f

    // Dimensions
    private val barHeight = 24f.dpToPx()
    private val cornerRadius = 12f.dpToPx()
    private val labelPadding = 8f.dpToPx()
    private val percentageTextSize = 12f.dpToPx()
    private val labelTextSize = 11f.dpToPx()

    // Paints
    private val positivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = positiveColor
    }

    private val neutralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neutralColor
    }

    private val negativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = negativeColor
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = backgroundColor
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = percentageTextSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#757575")
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
    }

    // Rects
    private val barRect = RectF()
    private val segmentRect = RectF()

    fun setDistribution(distribution: SentimentDistribution, animate: Boolean = true) {
        this.distribution = distribution
        if (animate) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    animationProgress = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animationProgress = 1f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (barHeight + labelPadding * 2 + labelTextSize * 2 + 16f.dpToPx()).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val dist = distribution ?: return
        if (animationProgress == 0f) return

        val barTop = paddingTop.toFloat()
        val barLeft = paddingLeft.toFloat()
        val barRight = (width - paddingRight).toFloat()
        val barWidth = barRight - barLeft
        
        barRect.set(barLeft, barTop, barRight, barTop + barHeight)
        
        // Draw background
        canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, backgroundPaint)

        // Calculate segment widths with animation
        val posWidth = (barWidth * dist.positive / 100f) * animationProgress
        val neuWidth = (barWidth * dist.neutral / 100f) * animationProgress
        val negWidth = (barWidth * dist.negative / 100f) * animationProgress

        // Draw positive segment (left)
        if (posWidth > 0) {
            segmentRect.set(barLeft, barTop, barLeft + posWidth, barTop + barHeight)
            canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, positivePaint)
        }

        // Draw neutral segment (middle)
        if (neuWidth > 0) {
            val neuLeft = barLeft + posWidth
            segmentRect.set(neuLeft, barTop, neuLeft + neuWidth, barTop + barHeight)
            canvas.drawRect(segmentRect, neutralPaint)
        }

        // Draw negative segment (right)
        if (negWidth > 0) {
            val negLeft = barLeft + posWidth + neuWidth
            segmentRect.set(negLeft, barTop, barLeft + posWidth + neuWidth + negWidth, barTop + barHeight)
            canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, negativePaint)
        }

        // Draw percentage labels on segments if wide enough
        val textY = barTop + barHeight / 2 + percentageTextSize / 3
        if (posWidth > 40f.dpToPx()) {
            canvas.drawText("${dist.positive.toInt()}%", barLeft + posWidth / 2, textY, textPaint)
        }
        if (neuWidth > 40f.dpToPx()) {
            canvas.drawText("${dist.neutral.toInt()}%", barLeft + posWidth + neuWidth / 2, textY, textPaint)
        }
        if (negWidth > 40f.dpToPx()) {
            canvas.drawText("${dist.negative.toInt()}%", barLeft + posWidth + neuWidth + negWidth / 2, textY, textPaint)
        }
    }

    private fun Float.dpToPx(): Float = this * context.resources.displayMetrics.density
}

