package com.dramebaz.app.ui.insights

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * INS-001: Enhanced Emotional Arc Visualization
 * Custom view that displays an interactive line graph showing emotional journey
 * across chapters with intensity scores and mood indicators.
 */
class EmotionalArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Data classes
    data class EmotionalDataPoint(
        val chapterIndex: Int,
        val chapterTitle: String,
        val dominantEmotion: String,
        val intensity: Float,  // 1.0 to 10.0
        val secondaryEmotions: List<String> = emptyList()
    )

    // Emotion colors as per task spec
    private val emotionColors = mapOf(
        "joy" to Color.parseColor("#FFD700"),
        "happy" to Color.parseColor("#FFD700"),
        "happiness" to Color.parseColor("#FFD700"),
        "sadness" to Color.parseColor("#4169E1"),
        "sad" to Color.parseColor("#4169E1"),
        "melancholy" to Color.parseColor("#4169E1"),
        "anger" to Color.parseColor("#FF4500"),
        "angry" to Color.parseColor("#FF4500"),
        "rage" to Color.parseColor("#FF4500"),
        "fear" to Color.parseColor("#800080"),
        "scared" to Color.parseColor("#800080"),
        "anxiety" to Color.parseColor("#800080"),
        "surprise" to Color.parseColor("#00CED1"),
        "curious" to Color.parseColor("#00CED1"),
        "curiosity" to Color.parseColor("#00CED1"),
        "neutral" to Color.parseColor("#808080"),
        "tension" to Color.parseColor("#FF9800"),
        "suspense" to Color.parseColor("#FF9800"),
        "love" to Color.parseColor("#E91E63"),
        "romance" to Color.parseColor("#E91E63"),
        "peace" to Color.parseColor("#8BC34A"),
        "calm" to Color.parseColor("#8BC34A"),
        "resolution" to Color.parseColor("#8BC34A")
    )

    // Data
    private var dataPoints = listOf<EmotionalDataPoint>()
    private var animationProgress = 1f

    // Dimensions
    private val paddingLeft = 48f.dpToPx()
    private val paddingRight = 24f.dpToPx()
    private val paddingTop = 24f.dpToPx()
    private val paddingBottom = 48f.dpToPx()
    private val pointRadius = 8f.dpToPx()
    private val touchRadius = 24f.dpToPx()

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f.dpToPx()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dpToPx()
        color = Color.WHITE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#E0E0E0")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.dpToPx()
        color = Color.parseColor("#757575")
        textAlign = Paint.Align.CENTER
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.dpToPx()
        color = Color.parseColor("#757575")
        textAlign = Paint.Align.RIGHT
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#424242")
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f.dpToPx()
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    // Interaction
    private var selectedPointIndex = -1
    var onChapterClickListener: ((chapterIndex: Int) -> Unit)? = null

    // Computed points for drawing/touch
    private val computedPoints = mutableListOf<PointF>()

    private fun Float.dpToPx(): Float = this * context.resources.displayMetrics.density

    fun setData(points: List<EmotionalDataPoint>, animate: Boolean = true) {
        dataPoints = points
        computedPoints.clear()
        selectedPointIndex = -1
        if (animate && points.isNotEmpty()) {
            animationProgress = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animationProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            animationProgress = 1f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = 200f.dpToPx().toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun getXForChapter(index: Int): Float {
        if (dataPoints.size <= 1) return paddingLeft + (width - paddingLeft - paddingRight) / 2
        val graphWidth = width - paddingLeft - paddingRight
        return paddingLeft + (index.toFloat() / (dataPoints.size - 1)) * graphWidth
    }

    private fun getYForIntensity(intensity: Float): Float {
        val graphHeight = height - paddingTop - paddingBottom
        val normalizedIntensity = ((intensity - 1f) / 9f).coerceIn(0f, 1f)
        return height - paddingBottom - normalizedIntensity * graphHeight * animationProgress
    }

    private fun getEmotionColor(emotion: String): Int =
        emotionColors[emotion.lowercase()] ?: emotionColors["neutral"]!!

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) {
            drawEmptyState(canvas)
            return
        }
        drawGrid(canvas)
        drawAxisLabels(canvas)
        computePoints()
        drawBezierCurves(canvas)
        drawDataPoints(canvas)
        drawTooltip(canvas)
    }

    private fun drawEmptyState(canvas: Canvas) {
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("No emotional data available", width / 2f, height / 2f, labelPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val graphHeight = height - paddingTop - paddingBottom
        // Horizontal grid lines for intensity levels (2, 4, 6, 8, 10)
        for (i in listOf(2, 4, 6, 8, 10)) {
            val y = getYForIntensity(i.toFloat())
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint)
        }
    }

    private fun drawAxisLabels(canvas: Canvas) {
        val graphHeight = height - paddingTop - paddingBottom
        // Y-axis labels (intensity)
        for (i in listOf(2, 4, 6, 8, 10)) {
            val y = getYForIntensity(i.toFloat())
            canvas.drawText(i.toString(), paddingLeft - 8f, y + 4f, axisLabelPaint)
        }
        // X-axis labels (chapter numbers)
        labelPaint.textAlign = Paint.Align.CENTER
        for ((idx, point) in dataPoints.withIndex()) {
            val x = getXForChapter(idx)
            val label = "Ch${point.chapterIndex + 1}"
            canvas.drawText(label, x, height - 8f.dpToPx(), labelPaint)
        }
    }

    private fun computePoints() {
        computedPoints.clear()
        for ((idx, point) in dataPoints.withIndex()) {
            computedPoints.add(PointF(getXForChapter(idx), getYForIntensity(point.intensity)))
        }
    }

    private fun drawBezierCurves(canvas: Canvas) {
        if (computedPoints.size < 2) return
        val path = Path()
        path.moveTo(computedPoints[0].x, computedPoints[0].y)

        for (i in 0 until computedPoints.size - 1) {
            val p0 = computedPoints[i]
            val p1 = computedPoints[i + 1]
            // Control points for smooth bezier curve
            val midX = (p0.x + p1.x) / 2
            path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            // Draw segment with color gradient approximation (use start point color)
            linePaint.color = getEmotionColor(dataPoints[i].dominantEmotion)
            val segmentPath = Path()
            segmentPath.moveTo(p0.x, p0.y)
            segmentPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            canvas.drawPath(segmentPath, linePaint)
        }
    }

    private fun drawDataPoints(canvas: Canvas) {
        for ((idx, point) in computedPoints.withIndex()) {
            val color = getEmotionColor(dataPoints[idx].dominantEmotion)
            pointPaint.color = color
            val radius = if (idx == selectedPointIndex) pointRadius * 1.5f else pointRadius
            canvas.drawCircle(point.x, point.y, radius, pointPaint)
            canvas.drawCircle(point.x, point.y, radius, pointStrokePaint)
        }
    }

    private fun drawTooltip(canvas: Canvas) {
        if (selectedPointIndex < 0 || selectedPointIndex >= dataPoints.size) return
        val dataPoint = dataPoints[selectedPointIndex]
        val point = computedPoints[selectedPointIndex]
        val text = "${dataPoint.chapterTitle}\n${dataPoint.dominantEmotion}: ${dataPoint.intensity}"
        val lines = text.split("\n")
        val lineHeight = tooltipTextPaint.textSize * 1.2f
        val tooltipHeight = lineHeight * lines.size + 16f.dpToPx()
        val tooltipWidth = lines.maxOf { tooltipTextPaint.measureText(it) } + 24f.dpToPx()
        var tooltipX = point.x - tooltipWidth / 2
        var tooltipY = point.y - pointRadius * 2 - tooltipHeight
        // Clamp to view bounds
        if (tooltipX < 4f) tooltipX = 4f
        if (tooltipX + tooltipWidth > width - 4f) tooltipX = width - 4f - tooltipWidth
        if (tooltipY < 4f) tooltipY = point.y + pointRadius * 2
        val rect = RectF(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight)
        canvas.drawRoundRect(rect, 8f.dpToPx(), 8f.dpToPx(), tooltipPaint)
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, rect.centerX(), rect.top + 12f.dpToPx() + i * lineHeight, tooltipTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val touchedIndex = findTouchedPoint(event.x, event.y)
                if (touchedIndex != selectedPointIndex) {
                    selectedPointIndex = touchedIndex
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (selectedPointIndex >= 0) {
                    onChapterClickListener?.invoke(dataPoints[selectedPointIndex].chapterIndex)
                }
                selectedPointIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selectedPointIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTouchedPoint(x: Float, y: Float): Int {
        for ((idx, point) in computedPoints.withIndex()) {
            val dx = x - point.x
            val dy = y - point.y
            if (dx * dx + dy * dy <= touchRadius * touchRadius) return idx
        }
        return -1
    }
}

