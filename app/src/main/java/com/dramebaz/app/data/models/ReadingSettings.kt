package com.dramebaz.app.data.models

/**
 * SETTINGS-002: Display settings for reading experience.
 * Controls theme, font size, line height, and font family.
 *
 * From NovelReaderWeb settings-sheet.component.ts:
 * - Theme presets: Light, Sepia, Dark, OLED
 * - Font size: 0.8 - 2.0 (multiplier)
 * - Line height: 1.0 - 2.4
 * - Font family selection
 * - BOLD-001: Character name bolding toggle
 */
data class ReadingSettings(
    val theme: ReadingTheme = ReadingTheme.LIGHT,
    val fontSize: Float = 1.0f,
    val lineHeight: Float = 1.4f,
    val fontFamily: FontFamily = FontFamily.SERIF,
    /** BOLD-001: When enabled, character names are displayed in bold text */
    val boldCharacterNames: Boolean = true
) {
    companion object {
        const val FONT_SIZE_MIN = 0.8f
        const val FONT_SIZE_MAX = 2.0f
        const val LINE_HEIGHT_MIN = 1.0f
        const val LINE_HEIGHT_MAX = 2.4f
    }
}

/**
 * Theme presets for reading experience.
 */
enum class ReadingTheme(
    val displayName: String,
    val backgroundColor: String,
    val textColor: String
) {
    LIGHT("Light", "#FFFFFF", "#212121"),
    SEPIA("Sepia", "#F4ECD8", "#5B4636"),
    DARK("Dark", "#1E1E1E", "#E0E0E0"),
    OLED("OLED", "#000000", "#FFFFFF")
}

/**
 * Font family options for reading.
 * Based on NovelReaderWeb theme.resources.ts APP_FONTS
 *
 * Fonts are organized into categories:
 * - System fonts: Serif, Sans Serif, Monospace (always available)
 * - Custom fonts: Merriweather, Lora (downloadable from Google Fonts)
 * - Accessibility fonts: Lexend, Atkinson Hyperlegible (designed for readability)
 *
 * @param displayName User-facing name shown in settings
 * @param fontResourceName Internal resource name for loading
 * @param isAccessibilityFont Whether this font is designed for accessibility
 * @param description Brief description of the font's characteristics
 */
enum class FontFamily(
    val displayName: String,
    val fontResourceName: String,
    val isAccessibilityFont: Boolean = false,
    val description: String = ""
) {
    // System fonts (always available)
    SERIF("Serif", "serif", false, "Classic serif font"),
    SANS_SERIF("Sans Serif", "sans-serif", false, "Clean modern font"),
    MONOSPACE("Monospace", "monospace", false, "Fixed-width font"),

    // Custom fonts (Google Fonts - downloadable)
    MERRIWEATHER("Merriweather", "merriweather", false, "Classic reading font"),
    LORA("Lora", "lora", false, "Elegant novel font"),

    // Accessibility fonts (designed for readability)
    LEXEND("Lexend", "lexend", true, "Reduces visual stress"),
    ATKINSON_HYPERLEGIBLE("Atkinson", "atkinson_hyperlegible", true, "High legibility font")
}

