package com.dramebaz.app.playback.engine

import android.text.TextPaint
import android.text.style.CharacterStyle
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
 * Alternative implementation using a more reliable approach.
 * Uses the newer TextPaint underlineColor API (API 29+) with fallback.
 */
class ColoredUnderlineSpanCompat(
    private val color: Int,
    private val strokeWidth: Float = 3f
) : CharacterStyle(), UpdateAppearance {
    
    override fun updateDrawState(tp: TextPaint) {
        // For API 29+, we can use underlineColor directly
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tp.isUnderlineText = true
            tp.underlineColor = color
            tp.underlineThickness = strokeWidth
        } else {
            // Fallback: just enable underline with text color
            // The color won't be custom, but at least we have an underline
            tp.isUnderlineText = true
        }
    }
}

