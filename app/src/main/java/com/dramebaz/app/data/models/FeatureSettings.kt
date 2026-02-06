package com.dramebaz.app.data.models

/**
 * SETTINGS-003: Feature toggles for resource-intensive AI features.
 * Allows users to enable/disable features based on device capability.
 * 
 * From NovelReaderWeb settings-sheet.component.ts:
 * - Smart Casting: High CPU voice processing
 * - Generative Visuals: Slow image generation
 * - Deep Analysis: Background LLM analysis
 */
data class FeatureSettings(
    val enableSmartCasting: Boolean = true,
    val enableGenerativeVisuals: Boolean = false,
    val enableDeepAnalysis: Boolean = true,
    val enableEmotionModifiers: Boolean = true,
    val enableKaraokeHighlight: Boolean = true
) {
    companion object {
        /** Default settings for high-end devices */
        val HIGH_END = FeatureSettings(
            enableSmartCasting = true,
            enableGenerativeVisuals = true,
            enableDeepAnalysis = true,
            enableEmotionModifiers = true,
            enableKaraokeHighlight = true
        )
        
        /** Default settings for mid-range devices */
        val MID_RANGE = FeatureSettings(
            enableSmartCasting = true,
            enableGenerativeVisuals = false,
            enableDeepAnalysis = true,
            enableEmotionModifiers = true,
            enableKaraokeHighlight = true
        )
        
        /** Battery saver / low-end device settings */
        val BATTERY_SAVER = FeatureSettings(
            enableSmartCasting = false,
            enableGenerativeVisuals = false,
            enableDeepAnalysis = false,
            enableEmotionModifiers = false,
            enableKaraokeHighlight = true
        )
    }
}

