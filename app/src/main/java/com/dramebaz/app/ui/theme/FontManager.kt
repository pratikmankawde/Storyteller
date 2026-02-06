package com.dramebaz.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.dramebaz.app.R
import com.dramebaz.app.data.models.FontFamily
import com.dramebaz.app.utils.AppLogger

/**
 * FontManager - Manages loading and caching of custom fonts.
 * 
 * Handles both system fonts (Serif, Sans Serif, Monospace) and
 * custom fonts loaded from resources (Merriweather, Lora, etc.).
 * 
 * Uses ResourcesCompat for backward compatibility and proper
 * handling of downloadable fonts from Google Fonts.
 */
object FontManager {
    private const val TAG = "FontManager"
    
    // Cache of loaded typefaces
    private val typefaceCache = mutableMapOf<String, Typeface>()
    
    /**
     * Get a Typeface for the given FontFamily enum.
     * Uses cached typeface if available, otherwise loads from resources.
     * 
     * @param context Application context for loading resources
     * @param fontFamily The FontFamily enum value
     * @return Typeface for the font, or system default if loading fails
     */
    fun getTypeface(context: Context, fontFamily: FontFamily): Typeface {
        return getTypeface(context, fontFamily.fontResourceName)
    }
    
    /**
     * Get a Typeface for the given font resource name.
     * Uses cached typeface if available, otherwise loads from resources.
     * 
     * @param context Application context for loading resources
     * @param fontResourceName The font resource name (e.g., "merriweather", "serif")
     * @return Typeface for the font, or system default if loading fails
     */
    fun getTypeface(context: Context, fontResourceName: String): Typeface {
        // Check cache first
        typefaceCache[fontResourceName]?.let { return it }
        
        val typeface = loadTypeface(context, fontResourceName)
        typefaceCache[fontResourceName] = typeface
        return typeface
    }
    
    /**
     * Load a typeface from resources or return system font.
     */
    private fun loadTypeface(context: Context, fontResourceName: String): Typeface {
        return when (fontResourceName.lowercase()) {
            // System fonts - always available
            "serif" -> Typeface.SERIF
            "sans-serif" -> Typeface.SANS_SERIF
            "monospace" -> Typeface.MONOSPACE
            
            // Custom fonts - load from resources
            "merriweather" -> loadFontResource(context, R.font.merriweather)
            "lora" -> loadFontResource(context, R.font.lora)
            "lexend" -> loadFontResource(context, R.font.lexend)
            "atkinson_hyperlegible" -> loadFontResource(context, R.font.atkinson_hyperlegible)
            
            // Fallback to default
            else -> {
                AppLogger.w(TAG, "Unknown font: $fontResourceName, using default")
                Typeface.DEFAULT
            }
        }
    }
    
    /**
     * Load a font from resources with error handling.
     * Falls back to system serif if loading fails.
     */
    private fun loadFontResource(context: Context, fontResId: Int): Typeface {
        return try {
            ResourcesCompat.getFont(context, fontResId) ?: run {
                AppLogger.w(TAG, "Font resource $fontResId returned null, using fallback")
                Typeface.SERIF
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load font resource $fontResId", e)
            Typeface.SERIF
        }
    }
    
    /**
     * Preload all custom fonts for faster access later.
     * Call this during app initialization.
     */
    fun preloadFonts(context: Context) {
        AppLogger.d(TAG, "Preloading custom fonts...")
        FontFamily.entries.forEach { fontFamily ->
            try {
                getTypeface(context, fontFamily)
                AppLogger.d(TAG, "Preloaded font: ${fontFamily.displayName}")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to preload font: ${fontFamily.displayName}", e)
            }
        }
        AppLogger.d(TAG, "Font preloading complete. Cached ${typefaceCache.size} fonts.")
    }
    
    /**
     * Clear the typeface cache.
     * Useful for memory management or when fonts need to be reloaded.
     */
    fun clearCache() {
        typefaceCache.clear()
        AppLogger.d(TAG, "Font cache cleared")
    }
    
    /**
     * Get all available font families.
     * @return List of all FontFamily enum values
     */
    fun getAvailableFonts(): List<FontFamily> = FontFamily.entries.toList()
    
    /**
     * Get accessibility-focused fonts.
     * @return List of FontFamily values designed for accessibility
     */
    fun getAccessibilityFonts(): List<FontFamily> = 
        FontFamily.entries.filter { it.isAccessibilityFont }
}

