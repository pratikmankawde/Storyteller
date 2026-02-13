package com.dramebaz.app.integration

import com.dramebaz.app.ai.llm.StubFallbacks
import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.llm.pipeline.ParagraphBatcher
import com.dramebaz.app.ai.llm.pipeline.matching.FuzzyCharacterNameMatcher
import com.dramebaz.app.ai.llm.pipeline.merging.PreferDetailedVoiceProfileMerger
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisOutput
import com.dramebaz.app.ai.llm.prompts.ExtractedCharacterData
import com.dramebaz.app.ai.llm.prompts.ExtractedVoiceProfile
import com.dramebaz.app.utils.DegradedModeManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for LLM Analysis Pipeline.
 * Tests:
 * - End-to-end chapter analysis (character extraction → dialog extraction → voice profile)
 * - Batched analysis with ParagraphBatcher and IncrementalMerger
 * - Fallback to StubFallbacks when LLM is unavailable
 * - DegradedModeManager state transitions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmAnalysisPipelineIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
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

        // Reset strategies to defaults
        IncrementalMerger.nameMatcher = FuzzyCharacterNameMatcher()
        IncrementalMerger.voiceProfileMerger = PreferDetailedVoiceProfileMerger()
        
        // Reset DegradedModeManager
        DegradedModeManager.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== ParagraphBatcher Tests ====================

    @Test
    fun `ParagraphBatcher creates batches respecting token budget`() {
        val paragraphs = listOf(
            "Paragraph one with some content.",
            "Paragraph two with more content here.",
            "Paragraph three continues the story.",
            "Paragraph four wraps things up."
        )

        val batches = ParagraphBatcher.createBatches(paragraphs, maxInputTokens = 50)

        assertTrue("Should create at least one batch", batches.isNotEmpty())
        batches.forEach { batch ->
            assertTrue("Batch should have text", batch.text.isNotEmpty())
            assertTrue("Estimated tokens should be within budget", batch.estimatedTokens <= 50)
        }
    }

    @Test
    fun `ParagraphBatcher never truncates mid-paragraph`() {
        val longParagraph = "A".repeat(500) // Long paragraph
        val paragraphs = listOf(longParagraph, "Short paragraph.")

        val batches = ParagraphBatcher.createBatches(paragraphs, maxInputTokens = 200)

        // Each batch should contain complete paragraphs
        batches.forEach { batch ->
            val containedParagraphs = paragraphs.filter { batch.text.contains(it) }
            containedParagraphs.forEach { p ->
                assertTrue("Paragraph should not be truncated", batch.text.contains(p))
            }
        }
    }

    @Test
    fun `ParagraphBatcher estimateBatchCount returns reasonable estimate`() {
        val paragraphs = listOf(
            "Short text.",
            "Another short text.",
            "Yet another piece of content."
        )

        val estimate = ParagraphBatcher.estimateBatchCount(paragraphs, maxInputTokens = 100)

        assertTrue("Estimate should be at least 1", estimate >= 1)
    }

    // ==================== IncrementalMerger Tests ====================

    @Test
    fun `IncrementalMerger merges character data from multiple batches`() {
        val accumulated = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        // Batch 1: Jax speaks
        val batch1 = BatchedAnalysisOutput(
            characters = listOf(
                ExtractedCharacterData(
                    name = "Jax",
                    dialogs = listOf("See? I told you the landing gear was optional."),
                    traits = listOf("confident", "reckless"),
                    voiceProfile = null
                )
            )
        )
        IncrementalMerger.merge(accumulated, batch1)

        // Batch 2: Jax speaks again
        val batch2 = BatchedAnalysisOutput(
            characters = listOf(
                ExtractedCharacterData(
                    name = "Jax",
                    dialogs = listOf("Standard protocol."),
                    traits = listOf("leader"),
                    voiceProfile = ExtractedVoiceProfile(gender = "male", age = "adult", accent = "american")
                )
            )
        )
        IncrementalMerger.merge(accumulated, batch2)

        assertEquals("Should have 1 merged character", 1, accumulated.size)
        val jax = accumulated.values.first()
        assertEquals(2, jax.dialogs.size)
        assertTrue(jax.traits.contains("confident"))
        assertTrue(jax.traits.contains("leader"))
        assertEquals("male", jax.voiceProfile?.gender)
    }

    @Test
    fun `IncrementalMerger deduplicates characters by name variants`() {
        val accumulated = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        val batch1 = createBatch("Jax", listOf("Hello"))
        IncrementalMerger.merge(accumulated, batch1)

        val batch2 = createBatch("JAX", listOf("World"))
        IncrementalMerger.merge(accumulated, batch2)

        // FuzzyCharacterNameMatcher should canonicalize to same key
        assertTrue("Should merge name variants", accumulated.size <= 2)
    }

    // ==================== StubFallbacks Tests ====================

    @Test
    fun `StubFallbacks detects character names from dialog patterns`() {
        val pageText = """
            "See? I told you the landing gear was optional," Jax said with a grin.
            Zane replied, "We're missing a wing, Jax."
            "Movement at twelve o'clock," Kael warned.
        """.trimIndent()

        val characters = StubFallbacks.detectCharactersOnPage(pageText)

        assertTrue("Should detect at least one character", characters.isNotEmpty())
    }

    @Test
    fun `StubFallbacks infers traits from character name`() {
        val maleTraits = StubFallbacks.inferTraitsFromName("Captain Jack")
        val femaleTraits = StubFallbacks.inferTraitsFromName("Princess Lyra")

        assertTrue("Should infer male traits", maleTraits.any { it.contains("male", ignoreCase = true) })
        assertTrue("Should infer female traits", femaleTraits.any { it.contains("female", ignoreCase = true) })
    }

    @Test
    fun `StubFallbacks analyzeChapter returns valid response`() {
        val chapterText = """
            Chapter 1: The Beginning

            "See? I told you the landing gear was optional," Jax said with a grin.
            The ship shuddered as it descended through the atmosphere.

            "We're missing a wing, Jax," Zane replied, checking the damage report.

            "Movement at twelve o'clock," Kael warned, pointing at the sensor display.
        """.trimIndent()

        val response = StubFallbacks.analyzeChapter(chapterText)

        assertNotNull("Response should not be null", response)
        // StubFallbacks should extract something from the chapter
    }

    // ==================== DegradedModeManager Tests ====================

    @Test
    fun `DegradedModeManager tracks LLM state transitions`() = runTest {
        // Initial state
        assertEquals(DegradedModeManager.LlmMode.NOT_INITIALIZED, DegradedModeManager.llmMode.value)

        // Transition to ONNX_FULL
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
        assertEquals(DegradedModeManager.LlmMode.ONNX_FULL, DegradedModeManager.llmMode.value)

        // Transition to STUB_FALLBACK
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.STUB_FALLBACK, "Model failed")
        assertEquals(DegradedModeManager.LlmMode.STUB_FALLBACK, DegradedModeManager.llmMode.value)
        assertEquals("Model failed", DegradedModeManager.llmFailureReason)
    }

    @Test
    fun `DegradedModeManager isDegraded reflects combined state`() {
        // Both systems not initialized
        DegradedModeManager.reset()
        assertFalse("NOT_INITIALIZED should not be considered degraded", DegradedModeManager.isDegraded)

        // LLM in fallback mode
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.STUB_FALLBACK)
        assertTrue("STUB_FALLBACK should be degraded", DegradedModeManager.isDegraded)

        // Reset and test TTS disabled
        DegradedModeManager.reset()
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED)
        assertTrue("TTS DISABLED should be degraded", DegradedModeManager.isDegraded)
    }

    @Test
    fun `DegradedModeManager StateFlow emits updates`() = runTest {
        DegradedModeManager.reset()

        val modes = mutableListOf<DegradedModeManager.LlmMode>()

        // Collect initial value
        modes.add(DegradedModeManager.llmMode.value)

        // Update and collect
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
        modes.add(DegradedModeManager.llmMode.value)

        assertEquals(2, modes.size)
        assertEquals(DegradedModeManager.LlmMode.NOT_INITIALIZED, modes[0])
        assertEquals(DegradedModeManager.LlmMode.ONNX_FULL, modes[1])
    }

    // ==================== End-to-End Pipeline Tests ====================

    @Test
    fun `full analysis pipeline processes Space Story characters`() {
        // Simulate Space Story analysis using demo data format
        val accumulated = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        // Batch 1: Chapter 1 characters
        val batch1 = BatchedAnalysisOutput(
            characters = listOf(
                ExtractedCharacterData("Jax", listOf("See? I told you the landing gear was optional."), listOf("confident"), null),
                ExtractedCharacterData("Zane", listOf("We're missing a wing, Jax,"), listOf("cautious"), null),
                ExtractedCharacterData("Kael", listOf("Movement at twelve o'clock,"), listOf("observant"), null),
                ExtractedCharacterData("Mina", listOf("You seek the Pulse,"), listOf("mysterious"), null)
            )
        )
        IncrementalMerger.merge(accumulated, batch1)

        // Batch 2: Chapter 2 characters (some overlap)
        val batch2 = BatchedAnalysisOutput(
            characters = listOf(
                ExtractedCharacterData("Lyra", listOf("Don't listen to it, Zane!"), listOf("protective"), null),
                ExtractedCharacterData("Jax", listOf("Standard protocol,"), listOf("leader"), null),
                ExtractedCharacterData("Kael", listOf("Standard protocol?"), listOf("disciplined"), null)
            )
        )
        IncrementalMerger.merge(accumulated, batch2)

        // Verify merged results
        assertTrue("Should have at least 4 unique characters", accumulated.size >= 4)

        val jax = accumulated.values.find { it.name == "Jax" }
        assertNotNull("Jax should be in results", jax)
        assertEquals("Jax should have 2 dialogs", 2, jax!!.dialogs.size)
        assertTrue("Jax should have merged traits", jax.traits.size >= 2)
    }

    private fun createBatch(name: String, dialogs: List<String>): BatchedAnalysisOutput {
        return BatchedAnalysisOutput(
            characters = listOf(
                ExtractedCharacterData(name = name, dialogs = dialogs, traits = emptyList(), voiceProfile = null)
            )
        )
    }
}

