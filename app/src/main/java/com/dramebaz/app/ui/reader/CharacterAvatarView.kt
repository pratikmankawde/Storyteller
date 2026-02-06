package com.dramebaz.app.ui.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.dramebaz.app.R
import com.dramebaz.app.utils.AppLogger

/**
 * UI-003: Character Avatar Bubble View
 * Shows the currently speaking character with name and voice ID.
 * States: Idle, Speaking, Listening
 * Pulse animation on border when speaking.
 */
class CharacterAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val tag = "CharacterAvatarView"

    enum class AvatarState {
        IDLE,
        SPEAKING,
        LISTENING
    }

    // Current state
    private var currentState = AvatarState.IDLE
    private var characterName: String = ""
    private var voiceId: Int? = null

    // Paint objects
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 14f * resources.displayMetrics.density
    }

    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 11f * resources.displayMetrics.density
    }

    private val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.on_primary)
        textSize = 24f * resources.displayMetrics.density
        isFakeBoldText = true
    }

    private val initialBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }

    // Animation
    private var pulseAnimator: ValueAnimator? = null
    private var pulseScale = 1f
    private var pulseAlpha = 1f

    private val bounds = RectF()
    private val avatarCircleRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (characterName.isEmpty()) return

        val padding = 8f * resources.displayMetrics.density
        val avatarRadius = 24f * resources.displayMetrics.density
        val centerX = padding + avatarRadius
        val centerY = height / 2f

        // Draw pulse effect when speaking
        if (currentState == AvatarState.SPEAKING && pulseScale > 1f) {
            borderPaint.alpha = (pulseAlpha * 255).toInt()
            canvas.drawCircle(centerX, centerY, avatarRadius * pulseScale, borderPaint)
            borderPaint.alpha = 255
        }

        // Draw avatar circle background
        canvas.drawCircle(centerX, centerY, avatarRadius, initialBackgroundPaint)

        // Draw border (highlight when speaking)
        val borderColor = when (currentState) {
            AvatarState.SPEAKING -> ContextCompat.getColor(context, R.color.primary)
            AvatarState.LISTENING -> ContextCompat.getColor(context, R.color.accent)
            AvatarState.IDLE -> ContextCompat.getColor(context, R.color.text_secondary)
        }
        borderPaint.color = borderColor
        canvas.drawCircle(centerX, centerY, avatarRadius, borderPaint)

        // Draw initial letter in circle
        val initial = characterName.firstOrNull()?.uppercaseChar() ?: '?'
        val textY = centerY - (initialPaint.descent() + initialPaint.ascent()) / 2
        canvas.drawText(initial.toString(), centerX, textY, initialPaint)

        // Draw character name
        val nameX = padding + avatarRadius * 2 + 12f * resources.displayMetrics.density
        val nameY = height / 2f - 4f * resources.displayMetrics.density
        canvas.drawText(characterName, nameX, nameY, textPaint)

        // Draw voice ID or state
        val statusText = when {
            currentState == AvatarState.SPEAKING -> "Speaking..."
            currentState == AvatarState.LISTENING -> "Listening..."
            voiceId != null -> "Voice #$voiceId"
            else -> "Narrator"
        }
        val statusY = height / 2f + subtextPaint.textSize
        canvas.drawText(statusText, nameX, statusY, subtextPaint)
    }

    /**
     * Update the character information.
     */
    fun setCharacter(name: String, speakerId: Int? = null) {
        characterName = name
        voiceId = speakerId
        AppLogger.d(tag, "Character set: name=$name, speakerId=$speakerId")
        invalidate()
    }

    /**
     * Update the avatar state.
     */
    fun setState(state: AvatarState) {
        if (currentState == state) return
        currentState = state
        AppLogger.d(tag, "State changed: $state")

        when (state) {
            AvatarState.SPEAKING -> startPulseAnimation()
            else -> stopPulseAnimation()
        }
        invalidate()
    }

    /**
     * Start pulse animation for speaking state.
     */
    private fun startPulseAnimation() {
        stopPulseAnimation()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.3f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                pulseScale = animation.animatedValue as Float
                pulseAlpha = 1f - (pulseScale - 1f) / 0.3f * 0.8f
                invalidate()
            }
            start()
        }
    }

    /**
     * Stop pulse animation.
     */
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseScale = 1f
        pulseAlpha = 1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulseAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (200f * resources.displayMetrics.density).toInt()
        val desiredHeight = (56f * resources.displayMetrics.density).toInt()

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(desiredWidth, widthSize)
            else -> desiredWidth
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(width, height)
    }
}

