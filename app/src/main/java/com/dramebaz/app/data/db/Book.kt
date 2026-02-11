package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Analysis state for a book's LLM processing.
 */
enum class AnalysisState {
    /** Analysis not yet started */
    PENDING,
    /** Analysis in progress */
    ANALYZING,
    /** All chapters analyzed successfully */
    COMPLETED,
    /** Analysis failed (can retry) */
    FAILED,
    /** Analysis cancelled by user */
    CANCELLED
}

/**
 * Book entity representing an imported book.
 * SUMMARY-002: Added seriesId and seriesOrder for multi-book series support.
 * AUTO-ANALYSIS: Added analysis state fields for progressive book activation.
 * LIBRARY-001: Added library organization fields (favorite, lastReadAt, finished, progress).
 */
@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = BookSeries::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("seriesId"), Index("isFavorite"), Index("lastReadAt"), Index("isFinished")]
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val format: String, // "pdf" | "epub" | "txt"
    val createdAt: Long = System.currentTimeMillis(),
    /** SUMMARY-002: Series this book belongs to (null if standalone) */
    val seriesId: Long? = null,
    /** SUMMARY-002: Order of this book within the series (1-based) */
    val seriesOrder: Int? = null,

    // ============ AUTO-ANALYSIS: Progressive activation fields ============
    /** Current analysis state */
    val analysisStatus: String = AnalysisState.PENDING.name,
    /** Analysis progress percentage (0-100) */
    val analysisProgress: Int = 0,
    /** Total number of chapters to analyze */
    val totalChaptersToAnalyze: Int = 0,
    /** Number of chapters that have been analyzed */
    val analyzedChapterCount: Int = 0,
    /** Current analysis message for UI display */
    val analysisMessage: String? = null,

    // ============ LIBRARY-001: Library organization fields ============
    /** Whether this book is marked as favorite */
    val isFavorite: Boolean = false,
    /** Timestamp of when this book was last read (null if never read) */
    val lastReadAt: Long? = null,
    /** Whether this book has been finished/completed */
    val isFinished: Boolean = false,
    /** Reading progress (0.0 to 1.0) */
    val readingProgress: Float = 0f,

    // ============ NARRATOR-002: Per-book narrator voice settings ============
    /** Speaker ID for narrator voice (model-dependent), null means use default */
    val narratorSpeakerId: Int? = null,
    /** Narrator speech speed (0.5-2.0), null means use default 0.9 */
    val narratorSpeed: Float? = null,
	    /** Narrator speech energy/intensity (0.5-1.5), null means use default 1.0 */
	    val narratorEnergy: Float? = null,

	    // ============ COVER-001: Book cover & genre metadata ============
	    /** Optional path to an embedded cover image extracted from the book file (if available). */
	    val embeddedCoverPath: String? = null,
	    /** Optional path to a genre-based placeholder cover image in assets when no embedded cover exists. */
	    val placeholderCoverPath: String? = null,
	    /** Detected primary genre for this book (e.g., fantasy, scifi, romance). */
	    val detectedGenre: String? = null
) {
    /** Get the analysis state as enum */
    fun getAnalysisState(): AnalysisState = try {
        AnalysisState.valueOf(analysisStatus)
    } catch (e: Exception) {
        AnalysisState.PENDING
    }

    /** Check if book is fully active (all chapters analyzed) */
    fun isFullyActive(): Boolean = getAnalysisState() == AnalysisState.COMPLETED

    /** Check if book is partially active (at least one chapter analyzed) */
    fun isPartiallyActive(): Boolean = analyzedChapterCount > 0

    /** Check if book is inactive (no chapters analyzed yet) */
    fun isInactive(): Boolean = analyzedChapterCount == 0 && getAnalysisState() != AnalysisState.COMPLETED
}
