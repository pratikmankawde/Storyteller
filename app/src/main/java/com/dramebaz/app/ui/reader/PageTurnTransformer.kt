package com.dramebaz.app.ui.reader

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * 3D page turning transformer for ViewPager2.
 * Creates a realistic book page turning effect with depth and perspective.
 */
class PageTurnTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val pageWidth = page.width.toFloat()
        val pageHeight = page.height.toFloat()
        
        // Enable hardware acceleration for smoother 3D transforms
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        
        when {
            position < -1f -> {
                // Page is completely off-screen to the left
                page.alpha = 0f
                page.visibility = View.INVISIBLE
            }
            position <= 0f -> {
                // Page is being scrolled to the left (going out) - current page
                page.visibility = View.VISIBLE
                page.alpha = 1f + position
                
                // Apply 3D rotation for realistic page turning effect
                // More rotation as the page turns further
                val rotation = position * 45f // Max 45 degrees rotation for more dramatic effect
                page.rotationY = rotation
                
                // Set pivot point at the right edge (spine of the book)
                page.pivotX = pageWidth
                page.pivotY = pageHeight / 2f
                
                // Slight scale down as page turns
                val scale = 1f + position * 0.05f
                page.scaleX = scale
                page.scaleY = scale
                
                // Add depth with translation
                page.translationX = position * pageWidth * 0.3f
                
                // Elevation for depth effect
                page.elevation = (1f + position) * 12f
            }
            position <= 1f -> {
                // Page is being scrolled to the right (coming in) - next page
                page.visibility = View.VISIBLE
                page.alpha = 1f - abs(position)
                
                // Apply 3D rotation from the left edge
                val rotation = position * 45f
                page.rotationY = rotation
                
                // Set pivot point at the left edge
                page.pivotX = 0f
                page.pivotY = pageHeight / 2f
                
                // Scale effect as page comes in
                val scale = 1f - abs(position) * 0.05f
                page.scaleX = scale
                page.scaleY = scale
                
                // Translation for depth
                page.translationX = -position * pageWidth * 0.3f
                
                // Elevation for depth effect
                page.elevation = (1f - abs(position)) * 12f
            }
            else -> {
                // Page is completely off-screen to the right
                page.alpha = 0f
                page.visibility = View.INVISIBLE
            }
        }
        
        // Add camera distance for better 3D perspective
        page.cameraDistance = pageWidth * 2
    }
}
