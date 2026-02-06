package com.dramebaz.app.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.dramebaz.app.R

/**
 * UI-002: VoiceWaveform Visualizer.
 * 
 * Custom View that displays audio waveform visualization using cubic bezier curves
 * based on PCM amplitude (RMS). Shows smooth animated waveform during TTS playback.
 * 
 * From NovelReaderWeb docs/UI.md - "VoiceWaveform"
 */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val ANIMATION_DURATION = 100L
        private const val NUM_BARS = 32  // Number of amplitude bars
        private const val MIN_AMPLITUDE = 0.05f  // Minimum bar height (5% of max)
        private const val MAX_AMPLITUDE = 1.0f
        private const val SMOOTHING_FACTOR = 0.3f  // Interpolation factor for smooth transitions
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val wavePath = Path()
    
    // Current amplitude values for each bar
    private val amplitudes = FloatArray(NUM_BARS) { MIN_AMPLITUDE }
    private val targetAmplitudes = FloatArray(NUM_BARS) { MIN_AMPLITUDE }
    
    // Bar dimensions
    private var barWidth = 0f
    private var barSpacing = 0f
    private var maxBarHeight = 0f
    
    private var isAnimating = false
    private var animationPhase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ANIMATION_DURATION
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationPhase = it.animatedValue as Float
            smoothAmplitudes()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateBarDimensions()
    }

    private fun calculateBarDimensions() {
        if (width <= 0 || height <= 0) return
        
        // Each bar takes 2/3 of the slot, with 1/3 spacing
        val totalSlotWidth = width.toFloat() / NUM_BARS
        barWidth = totalSlotWidth * 0.7f
        barSpacing = totalSlotWidth * 0.3f
        maxBarHeight = height.toFloat() * 0.9f  // 90% of height max
    }

    private fun smoothAmplitudes() {
        for (i in amplitudes.indices) {
            amplitudes[i] = amplitudes[i] + (targetAmplitudes[i] - amplitudes[i]) * SMOOTHING_FACTOR
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width <= 0 || height <= 0) return
        
        val centerY = height / 2f
        
        // Draw bars as rounded rectangles with mirrored top/bottom
        for (i in 0 until NUM_BARS) {
            val amplitude = amplitudes[i].coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
            val barHeight = amplitude * maxBarHeight
            val halfHeight = barHeight / 2f
            
            val left = i * (barWidth + barSpacing) + barSpacing / 2f
            val right = left + barWidth
            val top = centerY - halfHeight
            val bottom = centerY + halfHeight
            
            // Draw rounded rectangle bar
            val cornerRadius = barWidth / 2f
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, wavePaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Don't start automatically - wait for setAmplitude calls
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    /**
     * Update amplitude data from audio buffer.
     * @param rmsAmplitude Normalized amplitude value (0.0 to 1.0)
     */
    fun setAmplitude(rmsAmplitude: Float) {
        // Shift all amplitudes left and add new one at the end
        for (i in 0 until NUM_BARS - 1) {
            targetAmplitudes[i] = targetAmplitudes[i + 1]
        }
        targetAmplitudes[NUM_BARS - 1] = rmsAmplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
        
        // Start animation if not already running
        if (!isAnimating) {
            startAnimation()
        }
    }

    /**
     * Set multiple amplitude values at once (for bulk updates).
     * @param values Array of normalized amplitude values (0.0 to 1.0)
     */
    fun setAmplitudes(values: FloatArray) {
        val count = minOf(values.size, NUM_BARS)
        for (i in 0 until count) {
            targetAmplitudes[i] = values[i].coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
        }

        if (!isAnimating) {
            startAnimation()
        }
    }

    /**
     * Start the waveform animation.
     */
    fun startAnimation() {
        if (!animator.isRunning) {
            isAnimating = true
            animator.start()
        }
    }

    /**
     * Stop the waveform animation.
     */
    fun stopAnimation() {
        animator.cancel()
        isAnimating = false
    }

    /**
     * Reset all amplitudes to minimum (idle state).
     */
    fun reset() {
        for (i in amplitudes.indices) {
            amplitudes[i] = MIN_AMPLITUDE
            targetAmplitudes[i] = MIN_AMPLITUDE
        }
        invalidate()
    }

    /**
     * Set the waveform color.
     */
    fun setWaveColor(color: Int) {
        wavePaint.color = color
        invalidate()
    }

    /**
     * Set the waveform color from resource.
     */
    fun setWaveColorResource(colorResId: Int) {
        wavePaint.color = ContextCompat.getColor(context, colorResId)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (isAnimating) {
                startAnimation()
            }
        } else {
            stopAnimation()
        }
    }

    /**
     * Generate simulated amplitude data for demo/preview.
     * Creates a natural-looking wave pattern.
     */
    fun simulateWave() {
        val time = System.currentTimeMillis() / 1000.0
        for (i in 0 until NUM_BARS) {
            // Create multiple sine waves at different frequencies for natural look
            val wave1 = kotlin.math.sin(time * 3.0 + i * 0.3).toFloat()
            val wave2 = kotlin.math.sin(time * 5.0 + i * 0.5).toFloat() * 0.5f
            val wave3 = kotlin.math.sin(time * 7.0 + i * 0.2).toFloat() * 0.25f
            val combined = (wave1 + wave2 + wave3) / 1.75f  // Normalize
            targetAmplitudes[i] = (combined + 1f) / 2f * 0.8f + 0.1f  // Map to 0.1-0.9
        }

        if (!isAnimating) {
            startAnimation()
        }
    }
}

