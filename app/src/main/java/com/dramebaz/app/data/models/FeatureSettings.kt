package com.dramebaz.app.data.models

/**
 * SETTINGS-003: Feature toggles for resource-intensive AI features.
 * Allows users to enable/disable features based on device capability.
 *
 * From NovelReaderWeb settings-sheet.component.ts:
 * - Smart Casting: High CPU voice processing
 * - Generative Visuals: Slow image generation
 * - Deep Analysis: Background LLM analysis
 *
 * INCREMENTAL-001: Added incrementalAnalysisPagePercent for partial page processing.
 */
data class FeatureSettings(
    val enableSmartCasting: Boolean = true,
    val enableGenerativeVisuals: Boolean = false,
    val enableDeepAnalysis: Boolean = true,
    val enableEmotionModifiers: Boolean = true,
    val enableKaraokeHighlight: Boolean = true,
    /**
     * INCREMENTAL-001: Percentage of pages to analyze initially in foreground (25-100).
     * When < 100, the remaining pages are processed in background.
     * Default 50 means first 50% of pages are analyzed immediately, rest in background.
     */
    val incrementalAnalysisPagePercent: Int = 50
) {
    companion object {
        /** Minimum pages required for incremental processing */
        const val MIN_PAGES_FOR_INCREMENTAL = 4

        /** Default settings for high-end devices */
        val HIGH_END = FeatureSettings(
            enableSmartCasting = true,
            enableGenerativeVisuals = true,
            enableDeepAnalysis = true,
            enableEmotionModifiers = true,
            enableKaraokeHighlight = true,
            incrementalAnalysisPagePercent = 50
        )

        /** Default settings for mid-range devices */
        val MID_RANGE = FeatureSettings(
            enableSmartCasting = true,
            enableGenerativeVisuals = false,
            enableDeepAnalysis = true,
            enableEmotionModifiers = true,
            enableKaraokeHighlight = true,
            incrementalAnalysisPagePercent = 50
        )

        /** Battery saver / low-end device settings */
        val BATTERY_SAVER = FeatureSettings(
            enableSmartCasting = false,
            enableGenerativeVisuals = false,
            enableDeepAnalysis = false,
            enableEmotionModifiers = false,
            enableKaraokeHighlight = true,
            incrementalAnalysisPagePercent = 100  // No incremental processing to save battery
        )
    }
}

