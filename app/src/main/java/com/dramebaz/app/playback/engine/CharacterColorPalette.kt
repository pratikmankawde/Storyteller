package com.dramebaz.app.playback.engine

import android.graphics.Color

/**
 * Provides unique underline colors per character based on book theme.
 * 
 * Current theme: Sci-fi (dark-saturated shades of Blue/Purple)
 * Future: Theme will be determined by book analysis.
 */
object CharacterColorPalette {
    
    /**
     * Book theme enum - determines the color palette used.
     */
    enum class BookTheme {
        SCIFI,      // Dark-saturated Blue/Purple
        FANTASY,    // Earthy greens, golds, deep reds
        ROMANCE,    // Soft pinks, roses, warm tones
        MYSTERY,    // Dark grays, deep blues, noir tones
        HORROR,     // Blood reds, sickly greens, dark purples
        ADVENTURE,  // Vibrant oranges, teals, earth tones
        DEFAULT     // Neutral palette
    }
    
    // Current theme - will be set based on book analysis later
    private var currentTheme: BookTheme = BookTheme.SCIFI
    
    // Cache of character name to color mapping
    private val characterColors = mutableMapOf<String, Int>()
    private var colorIndex = 0
    
    /**
     * Sci-fi theme palette: Dark-saturated Blue/Purple shades
     * These colors are designed to be visible as underlines on both light and dark backgrounds
     */
    private val SCIFI_PALETTE = listOf(
        Color.parseColor("#6366F1"),  // Indigo-500
        Color.parseColor("#8B5CF6"),  // Violet-500
        Color.parseColor("#A855F7"),  // Purple-500
        Color.parseColor("#3B82F6"),  // Blue-500
        Color.parseColor("#06B6D4"),  // Cyan-500
        Color.parseColor("#7C3AED"),  // Violet-600
        Color.parseColor("#4F46E5"),  // Indigo-600
        Color.parseColor("#2563EB"),  // Blue-600
        Color.parseColor("#0891B2"),  // Cyan-600
        Color.parseColor("#9333EA"),  // Purple-600
        Color.parseColor("#818CF8"),  // Indigo-400
        Color.parseColor("#A78BFA"),  // Violet-400
    )
    
    /**
     * Narrator color - slightly different to distinguish from characters
     */
    private val NARRATOR_COLOR = Color.parseColor("#94A3B8")  // Slate-400 (neutral gray-blue)
    
    /**
     * Set the current book theme.
     */
    fun setTheme(theme: BookTheme) {
        if (currentTheme != theme) {
            currentTheme = theme
            // Reset color assignments when theme changes
            characterColors.clear()
            colorIndex = 0
        }
    }
    
    /**
     * Get the current palette based on theme.
     */
    private fun getCurrentPalette(): List<Int> {
        return when (currentTheme) {
            BookTheme.SCIFI -> SCIFI_PALETTE
            // TODO: Add other theme palettes
            else -> SCIFI_PALETTE
        }
    }
    
    /**
     * Get the underline color for a character.
     * Returns a consistent color for the same character name.
     * 
     * @param characterName The name of the character (case-insensitive)
     * @return The color to use for underlining this character's text
     */
    fun getColorForCharacter(characterName: String?): Int {
        if (characterName.isNullOrBlank() || characterName.equals("Narrator", ignoreCase = true)) {
            return NARRATOR_COLOR
        }
        
        val normalizedName = characterName.lowercase().trim()
        
        return characterColors.getOrPut(normalizedName) {
            val palette = getCurrentPalette()
            val color = palette[colorIndex % palette.size]
            colorIndex++
            color
        }
    }
    
    /**
     * Reset all character color assignments.
     * Call this when starting a new book.
     */
    fun reset() {
        characterColors.clear()
        colorIndex = 0
    }
    
    /**
     * Pre-assign colors to a list of characters.
     * This ensures consistent color assignment order.
     */
    fun assignColorsToCharacters(characterNames: List<String>) {
        reset()
        characterNames.forEach { name ->
            getColorForCharacter(name)
        }
    }
    
    /**
     * Get all currently assigned character colors.
     */
    fun getAssignedColors(): Map<String, Int> = characterColors.toMap()
}

