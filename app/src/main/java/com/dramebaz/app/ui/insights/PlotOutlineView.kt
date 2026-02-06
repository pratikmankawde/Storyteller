package com.dramebaz.app.ui.insights

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.dramebaz.app.data.models.PlotPoint
import com.dramebaz.app.data.models.PlotPointType

/**
 * INS-005: Plot Outline View
 * Custom View displaying the story arc as a timeline with plot point markers.
 */
class PlotOutlineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var plotPoints: List<PlotPoint> = emptyList()
    private var totalChapters: Int = 1
    private var animationProgress = 0f
    private var onPlotPointClick: ((PlotPoint) -> Unit)? = null

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#6200EE")
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.parseColor("#333333")
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        color = Color.parseColor("#666666")
        textAlign = Paint.Align.CENTER
    }

    private val arcPath = Path()
    private val markerRects = mutableListOf<Pair<RectF, PlotPoint>>()

    // Colors for different plot point types
    private val typeColors = mapOf(
        PlotPointType.EXPOSITION to Color.parseColor("#4CAF50"),
        PlotPointType.INCITING_INCIDENT to Color.parseColor("#2196F3"),
        PlotPointType.RISING_ACTION to Color.parseColor("#FF9800"),
        PlotPointType.MIDPOINT to Color.parseColor("#9C27B0"),
        PlotPointType.CLIMAX to Color.parseColor("#F44336"),
        PlotPointType.FALLING_ACTION to Color.parseColor("#FF5722"),
        PlotPointType.RESOLUTION to Color.parseColor("#009688")
    )

    fun setPlotPoints(points: List<PlotPoint>, chapters: Int) {
        plotPoints = points.sortedBy { it.type.order }
        totalChapters = chapters.coerceAtLeast(1)
        animateIn()
    }

    fun setOnPlotPointClickListener(listener: (PlotPoint) -> Unit) {
        onPlotPointClick = listener
    }

    private fun animateIn() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                animationProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plotPoints.isEmpty()) return

        val padding = 40f
        val w = width - padding * 2
        val h = height - padding * 2
        markerRects.clear()

        // Draw the story arc curve (a gentle hill shape)
        arcPath.reset()
        val arcHeight = h * 0.5f
        arcPath.moveTo(padding, height - padding)

        // Create bezier curve for story arc
        val cp1x = padding + w * 0.3f
        val cp1y = height - padding - arcHeight * 0.3f
        val cp2x = padding + w * 0.5f
        val cp2y = height - padding - arcHeight  // Peak at midpoint/climax
        val cp3x = padding + w * 0.7f
        val cp3y = height - padding - arcHeight * 0.3f

        arcPath.cubicTo(cp1x, cp1y, cp2x, cp2y, padding + w * 0.5f, height - padding - arcHeight)
        arcPath.cubicTo(cp3x, cp3y, padding + w * 0.9f, height - padding - arcHeight * 0.1f, padding + w, height - padding)

        // Draw arc with animation
        val pathMeasure = android.graphics.PathMeasure(arcPath, false)
        val animatedPath = Path()
        pathMeasure.getSegment(0f, pathMeasure.length * animationProgress, animatedPath, true)
        canvas.drawPath(animatedPath, arcPaint)

        // Draw plot point markers
        plotPoints.forEach { point ->
            val x = getXForPlotType(point.type, padding, w)
            val y = getYForPlotType(point.type, padding, h, arcHeight)
            val color = typeColors[point.type] ?: Color.GRAY

            // Draw marker circle
            markerPaint.color = color
            markerPaint.alpha = (255 * animationProgress).toInt()
            val radius = 16f * animationProgress
            canvas.drawCircle(x, y, radius, markerPaint)

            // Draw label below
            labelPaint.alpha = (255 * animationProgress).toInt()
            canvas.drawText(point.type.displayName, x, y + radius + 30f, labelPaint)

            // Store for click detection
            val rect = RectF(x - 30f, y - 30f, x + 30f, y + 30f)
            markerRects.add(rect to point)
        }
    }

    private fun getXForPlotType(type: PlotPointType, padding: Float, width: Float): Float {
        return padding + width * when (type) {
            PlotPointType.EXPOSITION -> 0.05f
            PlotPointType.INCITING_INCIDENT -> 0.15f
            PlotPointType.RISING_ACTION -> 0.35f
            PlotPointType.MIDPOINT -> 0.5f
            PlotPointType.CLIMAX -> 0.65f
            PlotPointType.FALLING_ACTION -> 0.8f
            PlotPointType.RESOLUTION -> 0.95f
        }
    }

    private fun getYForPlotType(type: PlotPointType, padding: Float, height: Float, arcHeight: Float): Float {
        val baseY = this.height - padding
        return baseY - arcHeight * when (type) {
            PlotPointType.EXPOSITION -> 0.1f
            PlotPointType.INCITING_INCIDENT -> 0.25f
            PlotPointType.RISING_ACTION -> 0.6f
            PlotPointType.MIDPOINT -> 0.85f
            PlotPointType.CLIMAX -> 1.0f
            PlotPointType.FALLING_ACTION -> 0.5f
            PlotPointType.RESOLUTION -> 0.15f
        }
    }
}

