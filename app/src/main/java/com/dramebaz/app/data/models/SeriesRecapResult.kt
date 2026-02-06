package com.dramebaz.app.data.models

/**
 * SUMMARY-002: Result of a series-aware recap, including events from previous books.
 */
data class SeriesRecapResult(
    /** The standard recap for the current book */
    val currentBookRecap: TimeAwareRecapResult,
    /** Summary of key events from previous book in series (if applicable) */
    val previousBookSummary: String? = null,
    /** Title of the previous book (for context) */
    val previousBookTitle: String? = null,
    /** Series name (if book is part of a series) */
    val seriesName: String? = null,
    /** Current book's position in the series (1-based) */
    val bookNumberInSeries: Int? = null,
    /** Whether this book is part of a series */
    val isPartOfSeries: Boolean = false
)

