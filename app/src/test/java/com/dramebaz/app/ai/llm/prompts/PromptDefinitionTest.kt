package com.dramebaz.app.ai.llm.prompts

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PromptDefinition implementations.
 * Tests prompt building, response parsing, and token budget handling.
 */
class PromptDefinitionTest {

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

    // ============= ForeshadowingPrompt Tests =============

    @Test
    fun `ForeshadowingPrompt has correct metadata`() {
        assertEquals("foreshadowing_detection_v1", ForeshadowingPrompt.promptId)
        assertEquals("Foreshadowing Detection", ForeshadowingPrompt.displayName)
        assertTrue(ForeshadowingPrompt.tokenBudget.totalTokens <= 4096)
    }

    @Test
    fun `ForeshadowingPrompt builds valid user prompt`() {
        val input = ForeshadowingInput(
            bookId = 1L,
            chapters = listOf(
                0 to "Chapter 1: The dark key was found...",
                1 to "Chapter 2: The door remained locked..."
            )
        )
        
        val prompt = ForeshadowingPrompt.buildUserPrompt(input)
        
        assertTrue(prompt.contains("CHAPTER 1"))
        assertTrue(prompt.contains("CHAPTER 2"))
        assertTrue(prompt.contains("foreshadowing"))
        assertTrue(prompt.contains("JSON"))
    }

    @Test
    fun `ForeshadowingPrompt parses valid JSON response`() {
        val response = """
            Here is the analysis:
            {
              "foreshadowing": [
                {
                  "setup_chapter": 1,
                  "setup_text": "The dark key was found",
                  "payoff_chapter": 3,
                  "payoff_text": "The key unlocked the mystery",
                  "theme": "mystery",
                  "confidence": 0.85
                }
              ]
            }
        """.trimIndent()
        
        val output = ForeshadowingPrompt.parseResponse(response)
        
        assertEquals(1, output.foreshadowings.size)
        assertEquals(0, output.foreshadowings[0].setupChapter) // 1-indexed to 0-indexed
        assertEquals(2, output.foreshadowings[0].payoffChapter) // 3-indexed to 2-indexed
        assertEquals("mystery", output.foreshadowings[0].theme)
        assertEquals(0.85f, output.foreshadowings[0].confidence, 0.01f)
    }

    @Test
    fun `ForeshadowingPrompt handles empty foreshadowing array`() {
        val response = """{"foreshadowing": []}"""
        
        val output = ForeshadowingPrompt.parseResponse(response)
        
        assertTrue(output.foreshadowings.isEmpty())
    }

    @Test
    fun `ForeshadowingPrompt handles invalid JSON gracefully`() {
        val response = "Not valid JSON at all"
        
        val output = ForeshadowingPrompt.parseResponse(response)
        
        assertTrue(output.foreshadowings.isEmpty())
    }

    @Test
    fun `ForeshadowingPrompt prepares input within token budget`() {
        val longText = "A".repeat(50000)
        val input = ForeshadowingInput(
            bookId = 1L,
            chapters = listOf(0 to longText, 1 to longText)
        )
        
        val prepared = ForeshadowingPrompt.prepareInput(input)
        
        val totalChars = prepared.chapters.sumOf { it.second.length }
        assertTrue(totalChars <= ForeshadowingPrompt.tokenBudget.maxInputChars)
    }

    // ============= PlotPointPrompt Tests =============

    @Test
    fun `PlotPointPrompt has correct metadata`() {
        assertEquals("plot_point_extraction_v1", PlotPointPrompt.promptId)
        assertEquals("Plot Point Extraction", PlotPointPrompt.displayName)
        assertTrue(PlotPointPrompt.tokenBudget.totalTokens <= 4096)
    }

    @Test
    fun `PlotPointPrompt builds valid user prompt`() {
        val input = PlotPointInput(
            bookId = 1L,
            chapters = listOf(0 to "Once upon a time...", 1 to "The adventure begins...")
        )
        
        val prompt = PlotPointPrompt.buildUserPrompt(input)
        
        assertTrue(prompt.contains("CHAPTER 1"))
        assertTrue(prompt.contains("Exposition"))
        assertTrue(prompt.contains("Climax"))
        assertTrue(prompt.contains("JSON"))
    }

    @Test
    fun `PlotPointPrompt parses valid JSON response`() {
        val response = """
            [
              {"type": "Exposition", "chapter": 1, "description": "Story begins", "confidence": 0.9},
              {"type": "Climax", "chapter": 5, "description": "The battle", "confidence": 0.85}
            ]
        """.trimIndent()
        
        val output = PlotPointPrompt.parseResponse(response)

        assertEquals(2, output.plotPoints.size)
        assertEquals(0, output.plotPoints[0].chapterIndex) // 1-indexed to 0-indexed
    }

    @Test
    fun `PlotPointPrompt handles empty array`() {
        val response = """[]"""
        
        val output = PlotPointPrompt.parseResponse(response)
        
        assertTrue(output.plotPoints.isEmpty())
    }

    // ============= ThemeAnalysisPrompt Tests =============

    @Test
    fun `ThemeAnalysisPrompt has correct metadata`() {
        assertEquals("theme_analysis_v1", ThemeAnalysisPrompt.promptId)
        assertEquals("Theme Analysis", ThemeAnalysisPrompt.displayName)
        assertTrue(ThemeAnalysisPrompt.tokenBudget.totalTokens <= 4096)
    }

    @Test
    fun `ThemeAnalysisPrompt builds valid user prompt`() {
        val input = ThemeAnalysisInput(
            bookId = 1L,
            title = "Dark Shadows",
            firstChapterText = "The night was dark and stormy..."
        )

        val prompt = ThemeAnalysisPrompt.buildUserPrompt(input)

        assertTrue(prompt.contains("Dark Shadows"))
        assertTrue(prompt.contains("mood"))
        assertTrue(prompt.contains("genre"))
        assertTrue(prompt.contains("JSON"))
    }

    @Test
    fun `ThemeAnalysisPrompt parses valid JSON response`() {
        val response = """
            {
              "mood": "dark_gothic",
              "genre": "thriller",
              "era": "contemporary",
              "emotional_tone": "tense",
              "suggested_ambient_sound": "rain"
            }
        """.trimIndent()

        val output = ThemeAnalysisPrompt.parseResponse(response)

        assertEquals("dark_gothic", output.mood)
        assertEquals("thriller", output.genre)
        assertEquals("contemporary", output.era)
        assertEquals("tense", output.emotionalTone)
        assertEquals("rain", output.ambientSound)
    }

    @Test
    fun `ThemeAnalysisPrompt returns defaults for invalid JSON`() {
        val response = "Not valid JSON"

        val output = ThemeAnalysisPrompt.parseResponse(response)

        assertEquals("classic", output.mood)
        assertEquals("modern_fiction", output.genre)
        assertEquals("contemporary", output.era)
    }

    @Test
    fun `ThemeAnalysisPrompt prepares input within token budget`() {
        val longText = "A".repeat(50000)
        val input = ThemeAnalysisInput(
            bookId = 1L,
            title = "Test Book",
            firstChapterText = longText
        )

        val prepared = ThemeAnalysisPrompt.prepareInput(input)

        assertTrue(prepared.firstChapterText.length <= input.maxSampleChars)
    }

    // ============= TokenBudget Tests =============

    @Test
    fun `TokenBudget calculates max chars correctly`() {
        val budget = TokenBudget(
            promptTokens = 100,
            inputTokens = 1000,
            outputTokens = 500
        )

        assertEquals(1600, budget.totalTokens)
        assertEquals(4000, budget.maxInputChars) // 1000 * 4
        assertEquals(2000, budget.maxOutputChars) // 500 * 4
    }

    @Test
    fun `TokenBudget preset values are within limits`() {
        assertTrue(TokenBudget.PASS1_CHARACTER_EXTRACTION.totalTokens <= 4096)
        assertTrue(TokenBudget.PASS2_DIALOG_EXTRACTION.totalTokens <= 4096)
        assertTrue(TokenBudget.PASS3_VOICE_PROFILE.totalTokens <= 4096)
        assertTrue(TokenBudget.TRAITS_EXTRACTION.totalTokens <= 4096)
        assertTrue(TokenBudget.KEY_MOMENTS.totalTokens <= 4096)
        assertTrue(TokenBudget.RELATIONSHIPS.totalTokens <= 4096)
    }
}

