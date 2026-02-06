package com.dramebaz.app.data.models

import com.google.gson.annotations.SerializedName

/**
 * INS-002: Foreshadowing Detection
 * Data class representing a foreshadowing element detected in a book.
 * Links setup elements (hints, symbolic objects, ominous statements)
 * to their payoff elements (resolutions, callbacks) across chapters.
 */
data class Foreshadowing(
    val id: Long = 0,
    val bookId: Long,
    @SerializedName("setup_chapter") val setupChapter: Int,
    @SerializedName("setup_text") val setupText: String,
    @SerializedName("payoff_chapter") val payoffChapter: Int,
    @SerializedName("payoff_text") val payoffText: String,
    val theme: String,
    /** Confidence score from LLM (0.0 - 1.0) */
    val confidence: Float = 0.8f
) {
    /**
     * Returns a brief description for display in UI.
     */
    fun getBriefDescription(): String {
        val setupPreview = setupText.take(50) + if (setupText.length > 50) "..." else ""
        return "Ch${setupChapter + 1} → Ch${payoffChapter + 1}: $theme"
    }
    
    /**
     * Returns the color for this foreshadowing link based on theme.
     */
    fun getThemeColor(): Int {
        return when (theme.lowercase()) {
            "death", "mortality" -> 0xFF800080.toInt() // Purple
            "love", "romance" -> 0xFFE91E63.toInt() // Pink
            "betrayal", "deception" -> 0xFFFF4500.toInt() // Red-Orange
            "mystery", "secrets" -> 0xFF4169E1.toInt() // Royal Blue
            "hope", "redemption" -> 0xFF4CAF50.toInt() // Green
            "fate", "destiny" -> 0xFF9C27B0.toInt() // Deep Purple
            "time" -> 0xFF00BCD4.toInt() // Cyan
            "nature", "environment" -> 0xFF8BC34A.toInt() // Light Green
            "power", "ambition" -> 0xFFFF9800.toInt() // Orange
            else -> 0xFF757575.toInt() // Gray
        }
    }
}

/**
 * Container for multiple foreshadowing elements detected in a book.
 */
data class ForeshadowingResult(
    val bookId: Long,
    val foreshadowings: List<Foreshadowing>,
    val analyzedChapters: Int,
    val detectedAt: Long = System.currentTimeMillis()
)

