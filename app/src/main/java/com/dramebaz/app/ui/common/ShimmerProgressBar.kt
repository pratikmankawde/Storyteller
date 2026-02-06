package com.dramebaz.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.dramebaz.app.R
import kotlin.math.roundToInt

/**
 * LIBRARY-001: Shimmer Progress Bar.
 * 
 * Custom View that displays a progress bar with a shimmer effect.
 * Used on book cards to show reading progress with a visually appealing animation.
 */
class ShimmerProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val ANIMATION_DURATION = 2000L  // Slower animation for subtler effect
        private const val DEFAULT_HEIGHT_DP = 6f
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shimmerGradient: LinearGradient? = null
    private val gradientMatrix = Matrix()
    private var translateX = 0f
    
    private val cornerRadius = 3f * resources.displayMetrics.density
    private val progressRect = RectF()
    private val trackRect = RectF()
    
    // Progress value (0.0 to 1.0)
    private var _progress: Float = 0f
    var progress: Float
        get() = _progress
        set(value) {
            _progress = value.coerceIn(0f, 1f)
            invalidate()
        }
    
    // Colors
    private val primaryColor = ContextCompat.getColor(context, R.color.primary)
    private val trackColor = ContextCompat.getColor(context, R.color.surface_variant)
    // Create a lighter shade of primary color for subtle shimmer effect
    private val shimmerHighlight = blendColors(primaryColor, 0xFFFFFFFF.toInt(), 0.35f)
    
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ANIMATION_DURATION
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            translateX = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        trackPaint.color = trackColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (DEFAULT_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShimmerGradient()
    }
    
    private fun updateShimmerGradient() {
        if (width <= 0 || height <= 0) return

        // Create gradient for shimmer sweep over the progress portion
        // Narrower highlight band (0.45-0.55) for subtler effect
        val gradientWidth = width.toFloat()

        shimmerGradient = LinearGradient(
            0f, 0f,
            gradientWidth, 0f,
            intArrayOf(primaryColor, primaryColor, shimmerHighlight, primaryColor, primaryColor),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        progressPaint.shader = shimmerGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width <= 0 || height <= 0) return
        
        // Draw track (background)
        trackRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)
        
        // Draw progress with shimmer
        if (_progress > 0f) {
            val progressWidth = width * _progress
            progressRect.set(0f, 0f, progressWidth, height.toFloat())
            
            // Apply shimmer translation
            val translationWidth = width.toFloat()
            val dx = -width / 2f + (translateX * translationWidth)
            
            gradientMatrix.reset()
            gradientMatrix.postTranslate(dx, 0f)
            shimmerGradient?.setLocalMatrix(gradientMatrix)
            
            canvas.save()
            canvas.clipRect(progressRect)
            canvas.drawRoundRect(progressRect, cornerRadius, cornerRadius, progressPaint)
            canvas.restore()
        }
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmer()
    }
    
    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }
    
    fun startShimmer() {
        if (!animator.isRunning) {
            animator.start()
        }
    }
    
    fun stopShimmer() {
        animator.cancel()
    }
    
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startShimmer()
        } else {
            stopShimmer()
        }
    }

    /**
     * Blend two colors together.
     * @param color1 First color
     * @param color2 Second color
     * @param ratio Blend ratio (0.0 = all color1, 1.0 = all color2)
     * @return Blended color
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val a = (Color.alpha(color1) * inverseRatio + Color.alpha(color2) * ratio).roundToInt()
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).roundToInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).roundToInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).roundToInt()
        return Color.argb(a, r, g, b)
    }
}

