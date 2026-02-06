package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.prompts.ForeshadowingPrompt
import com.dramebaz.app.ai.llm.prompts.PlotPointPrompt
import com.dramebaz.app.ai.llm.prompts.ThemeAnalysisPrompt
import com.dramebaz.app.data.models.PlotPointType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Analysis Pass classes.
 * Tests pass metadata, stub generation, and fallback behavior.
 */
class AnalysisPassTest {

    @Before
    fun setup() {
        // Mock android.util.Log to prevent RuntimeException in unit tests
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // ============= ForeshadowingDetectionPass Tests =============

    @Test
    fun `ForeshadowingDetectionPass has correct pass metadata`() {
        val pass = ForeshadowingDetectionPass()
        
        assertEquals(ForeshadowingPrompt.promptId, pass.passId)
        assertEquals(ForeshadowingPrompt.displayName, pass.displayName)
    }

    @Test
    fun `ForeshadowingDetectionPass generates stub for book with enough chapters`() {
        val stubs = ForeshadowingDetectionPass.generateStubForeshadowing(bookId = 1L, chapterCount = 5)
        
        assertEquals(1, stubs.size)
        assertEquals(1L, stubs[0].bookId)
        assertEquals(0, stubs[0].setupChapter)
        assertEquals(2, stubs[0].payoffChapter) // chapterCount / 2 = 5/2 = 2
        assertEquals("mystery", stubs[0].theme)
    }

    @Test
    fun `ForeshadowingDetectionPass returns empty list for books with few chapters`() {
        val stubs = ForeshadowingDetectionPass.generateStubForeshadowing(bookId = 1L, chapterCount = 2)
        
        assertTrue(stubs.isEmpty())
    }

    @Test
    fun `ForeshadowingDetectionPass getDefaultOutput returns empty output`() {
        val pass = ForeshadowingDetectionPass()
        val defaultOutput = pass.javaClass.getDeclaredMethod("getDefaultOutput").apply {
            isAccessible = true
        }.invoke(pass) as com.dramebaz.app.ai.llm.prompts.ForeshadowingOutput
        
        assertTrue(defaultOutput.foreshadowings.isEmpty())
        assertEquals(0, defaultOutput.chapterCount)
    }

    // ============= PlotPointExtractionPass Tests =============

    @Test
    fun `PlotPointExtractionPass has correct pass metadata`() {
        val pass = PlotPointExtractionPass()
        
        assertEquals(PlotPointPrompt.promptId, pass.passId)
        assertEquals(PlotPointPrompt.displayName, pass.displayName)
    }

    @Test
    fun `PlotPointExtractionPass generates stubs for book with many chapters`() {
        val stubs = PlotPointExtractionPass.generateStubPlotPoints(bookId = 1L, chapterCount = 10)
        
        assertTrue(stubs.isNotEmpty())
        // Should include exposition, inciting incident, midpoint, climax, resolution
        val types = stubs.map { it.type }
        assertTrue(types.contains(PlotPointType.EXPOSITION))
        assertTrue(types.contains(PlotPointType.INCITING_INCIDENT))
        assertTrue(types.contains(PlotPointType.MIDPOINT))
        assertTrue(types.contains(PlotPointType.CLIMAX))
        assertTrue(types.contains(PlotPointType.RESOLUTION))
    }

    @Test
    fun `PlotPointExtractionPass generates minimal stubs for short books`() {
        val stubs = PlotPointExtractionPass.generateStubPlotPoints(bookId = 1L, chapterCount = 3)
        
        assertTrue(stubs.isNotEmpty())
        // Should at least have exposition and inciting incident
        val types = stubs.map { it.type }
        assertTrue(types.contains(PlotPointType.EXPOSITION))
        assertTrue(types.contains(PlotPointType.INCITING_INCIDENT))
    }

    @Test
    fun `PlotPointExtractionPass returns empty for very short books`() {
        val stubs = PlotPointExtractionPass.generateStubPlotPoints(bookId = 1L, chapterCount = 2)
        
        assertTrue(stubs.isEmpty())
    }

    // ============= ThemeAnalysisPass Tests =============

    @Test
    fun `ThemeAnalysisPass has correct pass metadata`() {
        val pass = ThemeAnalysisPass()
        
        assertEquals(ThemeAnalysisPrompt.promptId, pass.passId)
        assertEquals(ThemeAnalysisPrompt.displayName, pass.displayName)
    }

    @Test
    fun `ThemeAnalysisPass heuristic detects dark gothic mood`() {
        val output = ThemeAnalysisPass.heuristicAnalysis(
            bookId = 1L,
            title = "The Shadow Chronicles",
            text = "The dark castle loomed, blood dripping from ancient stones..."
        )
        
        assertEquals("dark_gothic", output.mood)
    }

    @Test
    fun `ThemeAnalysisPass heuristic detects fantasy genre`() {
        val output = ThemeAnalysisPass.heuristicAnalysis(
            bookId = 1L,
            title = "The Dragon Kingdom",
            text = "The wizard cast a magic spell as the elf watched..."
        )
        
        assertEquals("fantasy", output.mood)
        assertEquals("fantasy", output.genre)
    }

    @Test
    fun `ThemeAnalysisPass heuristic detects scifi genre`() {
        val output = ThemeAnalysisPass.heuristicAnalysis(
            bookId = 1L,
            title = "Space Odyssey",
            text = "The robot navigated through cyberspace in the future..."
        )
        
        assertEquals("scifi", output.mood)
        assertEquals("scifi", output.genre)
    }

    @Test
    fun `ThemeAnalysisPass heuristic detects romance`() {
        val output = ThemeAnalysisPass.heuristicAnalysis(
            bookId = 1L,
            title = "A Love Story",
            text = "Her heart raced as she felt passion for him..."
        )
        
        assertEquals("romantic", output.mood)
        assertEquals("romance", output.genre)
    }

    @Test
    fun `ThemeAnalysisPass heuristic returns classic for unknown content`() {
        val output = ThemeAnalysisPass.heuristicAnalysis(
            bookId = 1L,
            title = "Regular Book",
            text = "The man walked down the street and entered the building."
        )
        
        assertEquals("classic", output.mood)
        assertEquals("modern_fiction", output.genre)
    }
}

