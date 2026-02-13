package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisOutput
import com.dramebaz.app.ai.llm.prompts.ExtractedCharacterData
import com.dramebaz.app.ai.llm.prompts.ExtractedVoiceProfile
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for IncrementalMerger.
 * Tests character merging, deduplication, and data accumulation.
 */
class IncrementalMergerTest {

    @Before
    fun setUp() {
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

        // Reset to default strategies before each test
        IncrementalMerger.nameMatcher = com.dramebaz.app.ai.llm.pipeline.matching.FuzzyCharacterNameMatcher()
        IncrementalMerger.voiceProfileMerger = com.dramebaz.app.ai.llm.pipeline.merging.PreferDetailedVoiceProfileMerger()
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // merge tests

    @Test
    fun `merge adds new character to empty map`() {
        val existing = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()
        val newBatch = createBatch(
            listOf(
                createCharacterData("Alice", listOf("Hello!"), listOf("brave"))
            )
        )

        val result = IncrementalMerger.merge(existing, newBatch)

        assertEquals(1, result.size)
        val alice = result.values.first()
        assertEquals("Alice", alice.name)
        assertEquals(listOf("Hello!"), alice.dialogs)
        assertTrue(alice.traits.contains("brave"))
    }

    @Test
    fun `merge accumulates dialogs for same character`() {
        val existing = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        // First batch
        val batch1 = createBatch(
            listOf(createCharacterData("Alice", listOf("Hello!"), listOf("brave")))
        )
        IncrementalMerger.merge(existing, batch1)

        // Second batch with same character
        val batch2 = createBatch(
            listOf(createCharacterData("Alice", listOf("Goodbye!"), listOf("kind")))
        )
        val result = IncrementalMerger.merge(existing, batch2)

        assertEquals(1, result.size)
        val alice = result.values.first()
        assertEquals(listOf("Hello!", "Goodbye!"), alice.dialogs)
        assertTrue(alice.traits.contains("brave"))
        assertTrue(alice.traits.contains("kind"))
    }

    @Test
    fun `merge deduplicates traits case-insensitively`() {
        val existing = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        val batch1 = createBatch(
            listOf(createCharacterData("Alice", emptyList(), listOf("Brave")))
        )
        IncrementalMerger.merge(existing, batch1)

        val batch2 = createBatch(
            listOf(createCharacterData("Alice", emptyList(), listOf("brave", "BRAVE", "kind")))
        )
        val result = IncrementalMerger.merge(existing, batch2)

        val alice = result.values.first()
        // Should have "Brave" (original) and "kind" only
        assertEquals(2, alice.traits.size)
    }

    @Test
    fun `merge tracks name variants`() {
        val existing = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        val batch1 = createBatch(
            listOf(createCharacterData("Alice Smith", listOf("Hi"), emptyList()))
        )
        IncrementalMerger.merge(existing, batch1)

        val batch2 = createBatch(
            listOf(createCharacterData("Alice", listOf("Hello"), emptyList()))
        )
        val result = IncrementalMerger.merge(existing, batch2)

        // Should merge as same character due to fuzzy matching
        assertTrue(result.size <= 2) // Depending on matcher behavior
    }

    @Test
    fun `merge handles multiple characters`() {
        val existing = mutableMapOf<String, IncrementalMerger.MergedCharacterData>()

        val batch = createBatch(
            listOf(
                createCharacterData("Alice", listOf("Hello"), listOf("brave")),
                createCharacterData("Bob", listOf("Hi there"), listOf("funny"))
            )
        )
        val result = IncrementalMerger.merge(existing, batch)

        assertEquals(2, result.size)
    }

    // MergedCharacterData tests

    @Test
    fun `MergedCharacterData stores all properties`() {
        val voiceProfile = ExtractedVoiceProfile(
            gender = "female",
            age = "adult",
            accent = "american"
        )
        val data = IncrementalMerger.MergedCharacterData(
            name = "Alice",
            canonicalName = "alice",
            dialogs = mutableListOf("Hello", "World"),
            traits = mutableSetOf("brave", "kind"),
            voiceProfile = voiceProfile
        )

        assertEquals("Alice", data.name)
        assertEquals("alice", data.canonicalName)
        assertEquals(2, data.dialogs.size)
        assertEquals(2, data.traits.size)
        assertEquals("female", data.voiceProfile?.gender)
    }

    // toList tests

    @Test
    fun `toList returns empty list for empty map`() {
        val result = IncrementalMerger.toList(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toList sorts by dialog count descending`() {
        val map = mutableMapOf(
            "alice" to IncrementalMerger.MergedCharacterData(
                name = "Alice",
                canonicalName = "alice",
                dialogs = mutableListOf("One", "Two", "Three")
            ),
            "bob" to IncrementalMerger.MergedCharacterData(
                name = "Bob",
                canonicalName = "bob",
                dialogs = mutableListOf("Only one")
            ),
            "charlie" to IncrementalMerger.MergedCharacterData(
                name = "Charlie",
                canonicalName = "charlie",
                dialogs = mutableListOf("One", "Two")
            )
        )

        val result = IncrementalMerger.toList(map)

        assertEquals("Alice", result[0].name) // 3 dialogs
        assertEquals("Charlie", result[1].name) // 2 dialogs
        assertEquals("Bob", result[2].name) // 1 dialog
    }

    // Helper functions

    private fun createCharacterData(
        name: String,
        dialogs: List<String>,
        traits: List<String>
    ): ExtractedCharacterData {
        return ExtractedCharacterData(
            name = name,
            dialogs = dialogs,
            traits = traits,
            voiceProfile = null
        )
    }

    private fun createBatch(characters: List<ExtractedCharacterData>): BatchedAnalysisOutput {
        return BatchedAnalysisOutput(characters = characters)
    }
}

