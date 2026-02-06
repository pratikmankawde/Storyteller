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
import com.dramebaz.app.data.models.Foreshadowing

/**
 * INS-002: Foreshadowing Timeline View
 * Custom view that displays foreshadowing elements as connected arcs
 * between setup and payoff chapters.
 */
class ForeshadowingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var foreshadowings = listOf<Foreshadowing>()
    private var totalChapters = 0
    private var animationProgress = 1f

    // Dimensions
    private val paddingHorizontal = 24f.dpToPx()
    private val paddingVertical = 16f.dpToPx()
    private val nodeRadius = 6f.dpToPx()
    private val touchRadius = 20f.dpToPx()
    private val timelineY get() = height - paddingVertical - 24f.dpToPx()

    // Paints
    private val timelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dpToPx()
        color = Color.parseColor("#E0E0E0")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.dpToPx()
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f.dpToPx()
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f.dpToPx()
        color = Color.parseColor("#757575")
        textAlign = Paint.Align.CENTER
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#424242")
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.dpToPx()
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    // Interaction
    private var selectedIndex = -1
    var onForeshadowingClickListener: ((Foreshadowing) -> Unit)? = null

    // Computed positions
    private val setupPoints = mutableListOf<PointF>()
    private val payoffPoints = mutableListOf<PointF>()

    private fun Float.dpToPx(): Float = this * context.resources.displayMetrics.density

    fun setData(items: List<Foreshadowing>, chapters: Int, animate: Boolean = true) {
        foreshadowings = items
        totalChapters = chapters
        selectedIndex = -1
        if (animate && items.isNotEmpty()) {
            animationProgress = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600L
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
        val desiredHeight = 120f.dpToPx().toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun getXForChapter(chapter: Int): Float {
        if (totalChapters <= 1) return width / 2f
        val graphWidth = width - 2 * paddingHorizontal
        return paddingHorizontal + (chapter.toFloat() / (totalChapters - 1)) * graphWidth
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (foreshadowings.isEmpty() || totalChapters == 0) {
            drawEmptyState(canvas)
            return
        }
        computePoints()
        drawTimeline(canvas)
        drawArcs(canvas)
        drawNodes(canvas)
        drawTooltip(canvas)
    }

    private fun drawEmptyState(canvas: Canvas) {
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("No foreshadowing detected", width / 2f, height / 2f, labelPaint)
    }

    private fun computePoints() {
        setupPoints.clear()
        payoffPoints.clear()
        for (f in foreshadowings) {
            setupPoints.add(PointF(getXForChapter(f.setupChapter), timelineY))
            payoffPoints.add(PointF(getXForChapter(f.payoffChapter), timelineY))
        }
    }

    private fun drawTimeline(canvas: Canvas) {
        canvas.drawLine(paddingHorizontal, timelineY, width - paddingHorizontal, timelineY, timelinePaint)
        // Chapter markers
        for (i in 0 until totalChapters) {
            val x = getXForChapter(i)
            canvas.drawCircle(x, timelineY, 3f.dpToPx(), timelinePaint)
            canvas.drawText("${i + 1}", x, timelineY + 14f.dpToPx(), labelPaint)
        }
    }

    private fun drawArcs(canvas: Canvas) {
        val arcPath = Path()
        for ((idx, f) in foreshadowings.withIndex()) {
            val startX = setupPoints[idx].x
            val endX = payoffPoints[idx].x
            val distance = kotlin.math.abs(endX - startX)
            val arcHeight = (distance * 0.4f).coerceIn(20f.dpToPx(), (height - paddingVertical * 2 - 30f.dpToPx()))
            arcPaint.color = f.getThemeColor()
            arcPaint.alpha = (255 * animationProgress).toInt()
            arcPath.reset()
            arcPath.moveTo(startX, timelineY)
            val midX = (startX + endX) / 2
            arcPath.quadTo(midX, timelineY - arcHeight * animationProgress, endX, timelineY)
            canvas.drawPath(arcPath, arcPaint)
        }
    }

    private fun drawNodes(canvas: Canvas) {
        for ((idx, f) in foreshadowings.withIndex()) {
            nodePaint.color = f.getThemeColor()
            val radius = if (idx == selectedIndex) nodeRadius * 1.4f else nodeRadius
            // Setup node (circle)
            canvas.drawCircle(setupPoints[idx].x, setupPoints[idx].y, radius, nodePaint)
            canvas.drawCircle(setupPoints[idx].x, setupPoints[idx].y, radius, nodeStrokePaint)
            // Payoff node (diamond shape via rotation)
            canvas.drawCircle(payoffPoints[idx].x, payoffPoints[idx].y, radius, nodePaint)
            canvas.drawCircle(payoffPoints[idx].x, payoffPoints[idx].y, radius, nodeStrokePaint)
        }
    }

    private fun drawTooltip(canvas: Canvas) {
        if (selectedIndex < 0 || selectedIndex >= foreshadowings.size) return
        val f = foreshadowings[selectedIndex]
        val text = "Ch${f.setupChapter + 1} → Ch${f.payoffChapter + 1}: ${f.theme}"
        val tooltipWidth = tooltipTextPaint.measureText(text) + 20f.dpToPx()
        val tooltipHeight = 28f.dpToPx()
        val midX = (setupPoints[selectedIndex].x + payoffPoints[selectedIndex].x) / 2
        var tooltipX = midX - tooltipWidth / 2
        val tooltipY = timelineY - 50f.dpToPx() - tooltipHeight
        if (tooltipX < 4f) tooltipX = 4f
        if (tooltipX + tooltipWidth > width - 4f) tooltipX = width - 4f - tooltipWidth
        val rect = RectF(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight)
        canvas.drawRoundRect(rect, 6f.dpToPx(), 6f.dpToPx(), tooltipPaint)
        canvas.drawText(text, rect.centerX(), rect.centerY() + 4f.dpToPx(), tooltipTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val touched = findTouchedArc(event.x, event.y)
                if (touched != selectedIndex) {
                    selectedIndex = touched
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (selectedIndex >= 0) {
                    onForeshadowingClickListener?.invoke(foreshadowings[selectedIndex])
                }
                selectedIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selectedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTouchedArc(x: Float, y: Float): Int {
        // Check if touch is near any setup or payoff node
        for ((idx, _) in foreshadowings.withIndex()) {
            val setupDist = kotlin.math.hypot((x - setupPoints[idx].x).toDouble(), (y - setupPoints[idx].y).toDouble())
            val payoffDist = kotlin.math.hypot((x - payoffPoints[idx].x).toDouble(), (y - payoffPoints[idx].y).toDouble())
            if (setupDist <= touchRadius || payoffDist <= touchRadius) return idx
        }
        return -1
    }
}

