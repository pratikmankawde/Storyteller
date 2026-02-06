package com.dramebaz.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.dramebaz.app.R

/**
 * UI-006: Loading Shimmer Effect.
 * 
 * Custom View that creates a shimmer effect using LinearGradient animation.
 * Use this to replace ProgressDialog for LLM analysis loading states.
 * The shimmer implies "reading" rather than generic loading.
 * 
 * From NovelReaderWeb docs/UI.md - "LoadingShimmer"
 */
class ShimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val ANIMATION_DURATION = 1500L
        private const val SHIMMER_ANGLE = 20f
    }

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shimmerGradient: LinearGradient? = null
    private val gradientMatrix = Matrix()
    private var translateX = 0f
    
    private val cornerRadius = 8f * resources.displayMetrics.density
    private val lineRect = RectF()
    
    // Colors for shimmer effect
    private val baseColor = ContextCompat.getColor(context, R.color.shimmer_base)
    private val highlightColor = ContextCompat.getColor(context, R.color.shimmer_highlight)
    
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShimmerGradient()
    }
    
    private fun updateShimmerGradient() {
        if (width <= 0 || height <= 0) return
        
        // Create gradient that spans the view width * 2 for sweep effect
        val gradientWidth = width * 2f
        
        shimmerGradient = LinearGradient(
            0f, 0f,
            gradientWidth, 0f,
            intArrayOf(baseColor, highlightColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.4f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerPaint.shader = shimmerGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width <= 0 || height <= 0) return
        
        // Calculate translation for shimmer sweep
        val translationWidth = width * 2f
        val dx = -width + (translateX * translationWidth)
        
        gradientMatrix.reset()
        gradientMatrix.postRotate(SHIMMER_ANGLE, width / 2f, height / 2f)
        gradientMatrix.postTranslate(dx, 0f)
        shimmerGradient?.setLocalMatrix(gradientMatrix)
        
        // Draw rounded rectangle as the shimmer line
        lineRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(lineRect, cornerRadius, cornerRadius, shimmerPaint)
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
}

