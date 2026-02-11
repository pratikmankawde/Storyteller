package com.dramebaz.app.data.models

/**
 * ANALYSIS-INCR-001: Settings for incremental chapter analysis workflow.
 * 
 * Controls how chapters are analyzed in batches with audio generation
 * triggered incrementally after each batch completes.
 */
data class AnalysisSettings(
    /**
     * Percentage of pages to analyze in the initial foreground pass (0-100).
     * Remaining pages are processed in background.
     * Only applies if chapter has more than [minPagesForSplit] pages.
     * Default: 50 (analyze first 50% in foreground)
     */
    val initialAnalysisPagePercent: Int = 50,

    /**
     * Minimum number of pages in a chapter to enable split processing.
     * Chapters with fewer pages are fully analyzed in foreground.
     * Default: 4
     */
    val minPagesForSplit: Int = 4,

    /**
     * Whether to trigger audio generation after each batch completes.
     * When false, audio is only generated after full chapter analysis.
     * Default: true
     */
    val enableIncrementalAudioGeneration: Boolean = true
) {
    companion object {
        /** Default analysis settings */
        val DEFAULT = AnalysisSettings()

        /** Settings for fast startup (smaller initial portion) */
        val FAST_STARTUP = AnalysisSettings(
            initialAnalysisPagePercent = 25,
            minPagesForSplit = 3,
            enableIncrementalAudioGeneration = true
        )

        /** Settings for thorough analysis (larger initial portion) */
        val THOROUGH = AnalysisSettings(
            initialAnalysisPagePercent = 75,
            minPagesForSplit = 6,
            enableIncrementalAudioGeneration = true
        )
    }

    /**
     * Calculate the number of pages to analyze in the initial pass.
     * @param totalPages Total pages in the chapter
     * @return Number of pages for initial analysis, or totalPages if no split needed
     */
    fun calculateInitialPages(totalPages: Int): Int {
        return if (totalPages > minPagesForSplit) {
            (totalPages * initialAnalysisPagePercent / 100).coerceAtLeast(1)
        } else {
            totalPages
        }
    }

    /**
     * Check if the chapter should be split for incremental processing.
     * @param totalPages Total pages in the chapter
     * @return true if chapter should be split, false for full processing
     */
    fun shouldSplitProcessing(totalPages: Int): Boolean {
        return totalPages > minPagesForSplit
    }
}

