package com.dramebaz.app.data.models

/**
 * INS-005: Plot Outline Extraction
 * Data class representing a major story structure element.
 */
data class PlotPoint(
    /** The book this plot point belongs to */
    val bookId: Long,
    /** Type of plot point (Exposition, Rising Action, Climax, etc.) */
    val type: PlotPointType,
    /** Chapter where this plot point occurs (0-indexed) */
    val chapterIndex: Int,
    /** Brief description of what happens at this point */
    val description: String,
    /** Confidence score (0.0-1.0) */
    val confidence: Float = 1.0f
)

/**
 * Types of plot points in a standard story arc (Freytag's Pyramid)
 */
enum class PlotPointType(val displayName: String, val order: Int) {
    EXPOSITION("Exposition", 0),
    INCITING_INCIDENT("Inciting Incident", 1),
    RISING_ACTION("Rising Action", 2),
    MIDPOINT("Midpoint", 3),
    CLIMAX("Climax", 4),
    FALLING_ACTION("Falling Action", 5),
    RESOLUTION("Resolution", 6);

    companion object {
        fun fromString(value: String): PlotPointType? {
            return entries.find {
                it.name.equals(value.replace(" ", "_"), ignoreCase = true) ||
                it.displayName.equals(value, ignoreCase = true)
            }
        }
    }
}

/**
 * Result of plot outline extraction containing all plot points for a book.
 */
data class PlotOutlineResult(
    val bookId: Long,
    val plotPoints: List<PlotPoint>,
    val totalChapters: Int
) {
    /** Get plot points sorted by chapter order */
    val sortedByChapter: List<PlotPoint>
        get() = plotPoints.sortedBy { it.chapterIndex }

    /** Get plot points sorted by story arc order */
    val sortedByArc: List<PlotPoint>
        get() = plotPoints.sortedBy { it.type.order }
}

