package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AnalysisSettings.
 * Tests incremental analysis configuration and page calculations.
 */
class AnalysisSettingsTest {

    // Default values tests

    @Test
    fun `DEFAULT has expected values`() {
        val settings = AnalysisSettings.DEFAULT
        
        assertEquals(50, settings.initialAnalysisPagePercent)
        assertEquals(4, settings.minPagesForSplit)
        assertTrue(settings.enableIncrementalAudioGeneration)
    }

    @Test
    fun `FAST_STARTUP has smaller initial percentage`() {
        val settings = AnalysisSettings.FAST_STARTUP
        
        assertEquals(25, settings.initialAnalysisPagePercent)
        assertEquals(3, settings.minPagesForSplit)
        assertTrue(settings.enableIncrementalAudioGeneration)
    }

    @Test
    fun `THOROUGH has larger initial percentage`() {
        val settings = AnalysisSettings.THOROUGH
        
        assertEquals(75, settings.initialAnalysisPagePercent)
        assertEquals(6, settings.minPagesForSplit)
        assertTrue(settings.enableIncrementalAudioGeneration)
    }

    // calculateInitialPages tests

    @Test
    fun `calculateInitialPages returns total for small chapters`() {
        val settings = AnalysisSettings(minPagesForSplit = 4)
        
        assertEquals(3, settings.calculateInitialPages(3))
        assertEquals(4, settings.calculateInitialPages(4))
    }

    @Test
    fun `calculateInitialPages returns percentage for large chapters`() {
        val settings = AnalysisSettings(
            initialAnalysisPagePercent = 50,
            minPagesForSplit = 4
        )
        
        // 10 pages with 50% should give 5
        assertEquals(5, settings.calculateInitialPages(10))
        // 20 pages with 50% should give 10
        assertEquals(10, settings.calculateInitialPages(20))
    }

    @Test
    fun `calculateInitialPages returns at least 1 for large chapters`() {
        val settings = AnalysisSettings(
            initialAnalysisPagePercent = 1, // Very low percentage
            minPagesForSplit = 4
        )
        
        // Even with 1%, should return at least 1
        assertEquals(1, settings.calculateInitialPages(10))
    }

    @Test
    fun `calculateInitialPages respects different percentages`() {
        val settings25 = AnalysisSettings(initialAnalysisPagePercent = 25, minPagesForSplit = 4)
        val settings75 = AnalysisSettings(initialAnalysisPagePercent = 75, minPagesForSplit = 4)
        
        // 20 pages
        assertEquals(5, settings25.calculateInitialPages(20))  // 25% of 20 = 5
        assertEquals(15, settings75.calculateInitialPages(20)) // 75% of 20 = 15
    }

    // shouldSplitProcessing tests

    @Test
    fun `shouldSplitProcessing returns false for small chapters`() {
        val settings = AnalysisSettings(minPagesForSplit = 4)
        
        assertFalse(settings.shouldSplitProcessing(1))
        assertFalse(settings.shouldSplitProcessing(2))
        assertFalse(settings.shouldSplitProcessing(3))
        assertFalse(settings.shouldSplitProcessing(4))
    }

    @Test
    fun `shouldSplitProcessing returns true for large chapters`() {
        val settings = AnalysisSettings(minPagesForSplit = 4)
        
        assertTrue(settings.shouldSplitProcessing(5))
        assertTrue(settings.shouldSplitProcessing(10))
        assertTrue(settings.shouldSplitProcessing(100))
    }

    @Test
    fun `shouldSplitProcessing respects minPagesForSplit`() {
        val settingsLow = AnalysisSettings(minPagesForSplit = 2)
        val settingsHigh = AnalysisSettings(minPagesForSplit = 10)
        
        // 5 pages
        assertTrue(settingsLow.shouldSplitProcessing(5))   // 5 > 2
        assertFalse(settingsHigh.shouldSplitProcessing(5)) // 5 <= 10
    }

    // Custom settings tests

    @Test
    fun `custom settings can disable incremental audio`() {
        val settings = AnalysisSettings(enableIncrementalAudioGeneration = false)
        
        assertFalse(settings.enableIncrementalAudioGeneration)
    }

    @Test
    fun `data class equality works correctly`() {
        val settings1 = AnalysisSettings(50, 4, true)
        val settings2 = AnalysisSettings(50, 4, true)
        val settings3 = AnalysisSettings(50, 4, false)
        
        assertEquals(settings1, settings2)
        assertNotEquals(settings1, settings3)
    }

    @Test
    fun `data class copy works correctly`() {
        val original = AnalysisSettings.DEFAULT
        val modified = original.copy(initialAnalysisPagePercent = 30)
        
        assertEquals(30, modified.initialAnalysisPagePercent)
        assertEquals(original.minPagesForSplit, modified.minPagesForSplit)
        assertEquals(original.enableIncrementalAudioGeneration, modified.enableIncrementalAudioGeneration)
    }

    // Edge cases

    @Test
    fun `calculateInitialPages handles zero pages`() {
        val settings = AnalysisSettings.DEFAULT
        
        // 0 pages should return 0 (full processing)
        assertEquals(0, settings.calculateInitialPages(0))
    }

    @Test
    fun `calculateInitialPages handles single page`() {
        val settings = AnalysisSettings(minPagesForSplit = 4)
        
        assertEquals(1, settings.calculateInitialPages(1))
    }
}

