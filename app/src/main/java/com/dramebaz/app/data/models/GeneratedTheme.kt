package com.dramebaz.app.data.models

import android.graphics.Color

/**
 * THEME-001: Generated UI Theme
 * Data class representing an LLM-generated UI theme based on book mood/content.
 */
data class GeneratedTheme(
    /** The book this theme was generated for */
    val bookId: Long,
    /** Primary mood (dark_gothic, romantic, adventure, mystery, fantasy, scifi, classic) */
    val mood: String,
    /** Genre (classic_literature, modern_fiction, fantasy, scifi, romance, thriller) */
    val genre: String,
    /** Era setting (historical, contemporary, futuristic) */
    val era: String = "contemporary",
    /** Emotional tone (somber, uplifting, tense, whimsical) */
    val emotionalTone: String = "neutral",
    /** Primary background color */
    val primaryColor: Int,
    /** Secondary background color */
    val secondaryColor: Int,
    /** Accent color for buttons and highlights */
    val accentColor: Int,
    /** Text color */
    val textColor: Int,
    /** Font family name */
    val fontFamily: String,
    /** Optional ambient sound suggestion */
    val suggestedAmbientSound: String? = null,
    /** Generation timestamp */
    val generatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // ==================== Mood to Color Mappings ====================
        
        private val MOOD_COLORS = mapOf(
            "dark_gothic" to ThemeColors(
                primary = parseColor("#1A1A2E"),
                secondary = parseColor("#16213E"),
                accent = parseColor("#E94560"),
                text = parseColor("#EAEAEA")
            ),
            "romantic" to ThemeColors(
                primary = parseColor("#FFF5F5"),
                secondary = parseColor("#FFE4E1"),
                accent = parseColor("#FF69B4"),
                text = parseColor("#4A4A4A")
            ),
            "adventure" to ThemeColors(
                primary = parseColor("#2C3E50"),
                secondary = parseColor("#34495E"),
                accent = parseColor("#E67E22"),
                text = parseColor("#ECF0F1")
            ),
            "mystery" to ThemeColors(
                primary = parseColor("#1C1C1C"),
                secondary = parseColor("#2D2D2D"),
                accent = parseColor("#9B59B6"),
                text = parseColor("#D0D0D0")
            ),
            "fantasy" to ThemeColors(
                primary = parseColor("#1A1A40"),
                secondary = parseColor("#270082"),
                accent = parseColor("#7A0BC0"),
                text = parseColor("#E8E8E8")
            ),
            "scifi" to ThemeColors(
                primary = parseColor("#0D1B2A"),
                secondary = parseColor("#1B263B"),
                accent = parseColor("#00F5D4"),
                text = parseColor("#E0E1DD")
            ),
            "classic" to ThemeColors(
                primary = parseColor("#FAF3E0"),
                secondary = parseColor("#E8DCC4"),
                accent = parseColor("#8B4513"),
                text = parseColor("#2C2C2C")
            )
        )
        
        // ==================== Genre to Font Mappings ====================
        
        private val GENRE_FONTS = mapOf(
            "classic_literature" to "serif",
            "modern_fiction" to "sans-serif",
            "fantasy" to "serif",
            "scifi" to "monospace",
            "romance" to "serif",
            "thriller" to "sans-serif"
        )
        
        private fun parseColor(hex: String): Int {
            return try {
                Color.parseColor(hex)
            } catch (e: Exception) {
                Color.BLACK
            }
        }
        
        /**
         * Get colors for a given mood. Returns default (classic) if mood not found.
         */
        fun getColorsForMood(mood: String): ThemeColors {
            return MOOD_COLORS[mood.lowercase()] ?: MOOD_COLORS["classic"]!!
        }
        
        /**
         * Get font family for a given genre. Returns "serif" if genre not found.
         */
        fun getFontForGenre(genre: String): String {
            return GENRE_FONTS[genre.lowercase()] ?: "serif"
        }
        
        /**
         * Create a GeneratedTheme from LLM analysis result.
         */
        fun fromAnalysis(
            bookId: Long,
            mood: String,
            genre: String,
            era: String = "contemporary",
            emotionalTone: String = "neutral",
            ambientSound: String? = null
        ): GeneratedTheme {
            val colors = getColorsForMood(mood)
            val font = getFontForGenre(genre)
            
            return GeneratedTheme(
                bookId = bookId,
                mood = mood,
                genre = genre,
                era = era,
                emotionalTone = emotionalTone,
                primaryColor = colors.primary,
                secondaryColor = colors.secondary,
                accentColor = colors.accent,
                textColor = colors.text,
                fontFamily = font,
                suggestedAmbientSound = ambientSound
            )
        }
        
        /**
         * Create a default theme for when analysis fails.
         */
        fun createDefault(bookId: Long): GeneratedTheme {
            return fromAnalysis(bookId, "classic", "modern_fiction")
        }
    }
}

/**
 * Helper data class for color palette.
 */
data class ThemeColors(
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val text: Int
)

