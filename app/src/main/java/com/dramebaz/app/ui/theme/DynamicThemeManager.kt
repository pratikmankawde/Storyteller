package com.dramebaz.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.dramebaz.app.data.models.GeneratedTheme
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * THEME-001: Dynamic Theme Manager
 * Applies generated themes to UI elements in the reader and other views.
 * 
 * Responsibilities:
 * - Apply color scheme to views
 * - Apply font family to text views
 * - Cache current theme per book
 * - Provide theme persistence via SharedPreferences
 */
class DynamicThemeManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DynamicThemeManager"
        private const val PREFS_NAME = "theme_prefs"
        private const val KEY_THEME_PREFIX = "book_theme_"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Cache of applied themes
    private val themeCache = mutableMapOf<Long, GeneratedTheme>()
    
    /**
     * Apply a theme to a root view and all child text views.
     */
    fun applyTheme(theme: GeneratedTheme, rootView: View) {
        AppLogger.d(TAG, "Applying theme: mood=${theme.mood}, genre=${theme.genre}")
        
        // Apply background color
        rootView.setBackgroundColor(theme.primaryColor)
        
        // Apply font and text colors to all text views
        applyToTextViews(rootView, theme)
        
        // Cache the theme
        themeCache[theme.bookId] = theme
    }
    
    /**
     * Apply theme to a specific container (e.g., reader content area).
     */
    fun applyToContainer(theme: GeneratedTheme, container: View) {
        container.setBackgroundColor(theme.secondaryColor)
        applyToTextViews(container, theme)
    }
    
    /**
     * Recursively apply theme to all TextViews in a view hierarchy.
     */
    private fun applyToTextViews(view: View, theme: GeneratedTheme) {
        if (view is TextView) {
            view.setTextColor(theme.textColor)
            view.typeface = getTypeface(theme.fontFamily)
        }
        
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToTextViews(view.getChildAt(i), theme)
            }
        }
    }
    
    /**
     * Get a Typeface for the given font family name.
     * Uses FontManager for proper loading of custom fonts.
     */
    private fun getTypeface(fontFamily: String): Typeface {
        return FontManager.getTypeface(context, fontFamily)
    }
    
    /**
     * Save a generated theme for a book.
     */
    fun saveTheme(theme: GeneratedTheme) {
        val json = gson.toJson(theme)
        prefs.edit()
            .putString("$KEY_THEME_PREFIX${theme.bookId}", json)
            .apply()
        themeCache[theme.bookId] = theme
        AppLogger.d(TAG, "Saved theme for book ${theme.bookId}")
    }
    
    /**
     * Load a saved theme for a book.
     * @return GeneratedTheme if found, null otherwise
     */
    fun loadTheme(bookId: Long): GeneratedTheme? {
        // Check cache first
        themeCache[bookId]?.let { return it }
        
        // Load from preferences
        val json = prefs.getString("$KEY_THEME_PREFIX$bookId", null) ?: return null
        return try {
            val theme = gson.fromJson(json, GeneratedTheme::class.java)
            themeCache[bookId] = theme
            theme
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load theme for book $bookId", e)
            null
        }
    }
    
    /**
     * Check if a theme exists for a book.
     */
    fun hasTheme(bookId: Long): Boolean {
        return themeCache.containsKey(bookId) || 
               prefs.contains("$KEY_THEME_PREFIX$bookId")
    }
    
    /**
     * Clear cached theme for a book.
     */
    fun clearTheme(bookId: Long) {
        themeCache.remove(bookId)
        prefs.edit()
            .remove("$KEY_THEME_PREFIX$bookId")
            .apply()
    }
    
    /**
     * Get the accent color for buttons and highlights.
     */
    fun getAccentColor(bookId: Long): Int {
        return loadTheme(bookId)?.accentColor ?: android.graphics.Color.parseColor("#2196F3")
    }
    
    /**
     * Get a ColorDrawable for the primary color.
     */
    fun getPrimaryDrawable(bookId: Long): ColorDrawable {
        val color = loadTheme(bookId)?.primaryColor ?: android.graphics.Color.WHITE
        return ColorDrawable(color)
    }
}

