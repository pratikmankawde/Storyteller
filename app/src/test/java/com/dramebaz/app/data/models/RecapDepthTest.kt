package com.dramebaz.app.data.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SUMMARY-001: RecapDepth and TimeAwareRecapResult.
 * Tests the time-based recap depth calculation and result formatting.
 */
class RecapDepthTest {

    // ==================== RecapDepth.fromDaysSinceLastRead Tests ====================

    @Test
    fun `fromDaysSinceLastRead returns BRIEF for less than 1 day`() {
        assertEquals(RecapDepth.BRIEF, RecapDepth.fromDaysSinceLastRead(0f))
        assertEquals(RecapDepth.BRIEF, RecapDepth.fromDaysSinceLastRead(0.5f))
        assertEquals(RecapDepth.BRIEF, RecapDepth.fromDaysSinceLastRead(0.99f))
    }

    @Test
    fun `fromDaysSinceLastRead returns MEDIUM for 1 to 7 days`() {
        assertEquals(RecapDepth.MEDIUM, RecapDepth.fromDaysSinceLastRead(1f))
        assertEquals(RecapDepth.MEDIUM, RecapDepth.fromDaysSinceLastRead(3f))
        assertEquals(RecapDepth.MEDIUM, RecapDepth.fromDaysSinceLastRead(7f))
    }

    @Test
    fun `fromDaysSinceLastRead returns DETAILED for more than 7 days`() {
        assertEquals(RecapDepth.DETAILED, RecapDepth.fromDaysSinceLastRead(7.1f))
        assertEquals(RecapDepth.DETAILED, RecapDepth.fromDaysSinceLastRead(14f))
        assertEquals(RecapDepth.DETAILED, RecapDepth.fromDaysSinceLastRead(30f))
        assertEquals(RecapDepth.DETAILED, RecapDepth.fromDaysSinceLastRead(365f))
    }

    // ==================== RecapDepth Properties Tests ====================

    @Test
    fun `BRIEF has correct properties`() {
        val depth = RecapDepth.BRIEF
        assertEquals(1, depth.chapterCount)
        assertFalse(depth.includeCharacters)
        assertEquals("Quick Recap", depth.displayName)
    }

    @Test
    fun `MEDIUM has correct properties`() {
        val depth = RecapDepth.MEDIUM
        assertEquals(3, depth.chapterCount)
        assertFalse(depth.includeCharacters)
        assertEquals("Standard Recap", depth.displayName)
    }

    @Test
    fun `DETAILED has correct properties`() {
        val depth = RecapDepth.DETAILED
        assertEquals(5, depth.chapterCount)
        assertTrue(depth.includeCharacters)
        assertEquals("Full Recap", depth.displayName)
    }

    // ==================== TimeAwareRecapResult Tests ====================

    @Test
    fun `TimeAwareRecapResult isFirstRead returns true for negative days`() {
        val result = TimeAwareRecapResult(
            recapText = "Test recap",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = -1f,
            chapterCount = 1
        )
        assertTrue(result.isFirstRead)
    }

    @Test
    fun `TimeAwareRecapResult isFirstRead returns false for positive days`() {
        val result = TimeAwareRecapResult(
            recapText = "Test recap",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = 0.5f,
            chapterCount = 1
        )
        assertFalse(result.isFirstRead)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for first read`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = -1f,
            chapterCount = 0
        )
        assertEquals("First time reading", result.timeSinceLastReadText)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for earlier today`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.BRIEF,
            daysSinceLastRead = 0.5f,
            chapterCount = 1
        )
        assertEquals("Read earlier today", result.timeSinceLastReadText)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for yesterday`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.MEDIUM,
            daysSinceLastRead = 1.5f,
            chapterCount = 3
        )
        assertEquals("Read yesterday", result.timeSinceLastReadText)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for days ago`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.MEDIUM,
            daysSinceLastRead = 5f,
            chapterCount = 3
        )
        assertEquals("5 days ago", result.timeSinceLastReadText)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for about a week`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.DETAILED,
            daysSinceLastRead = 10f,
            chapterCount = 5
        )
        assertEquals("About a week ago", result.timeSinceLastReadText)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for weeks ago`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.DETAILED,
            daysSinceLastRead = 21f,
            chapterCount = 5
        )
        assertEquals("3 weeks ago", result.timeSinceLastReadText)
    }

    @Test
    fun `timeSinceLastReadText returns correct text for months ago`() {
        val result = TimeAwareRecapResult(
            recapText = "Test",
            depth = RecapDepth.DETAILED,
            daysSinceLastRead = 60f,
            chapterCount = 5
        )
        assertEquals("2 months ago", result.timeSinceLastReadText)
    }

    // ==================== CharacterReminder Tests ====================

    @Test
    fun `CharacterReminder stores values correctly`() {
        val reminder = CharacterReminder(
            name = "John",
            description = "The protagonist",
            traits = listOf("brave", "kind")
        )
        assertEquals("John", reminder.name)
        assertEquals("The protagonist", reminder.description)
        assertEquals(2, reminder.traits.size)
    }
}

