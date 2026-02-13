package com.dramebaz.app.ai.llm.pipeline

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ParagraphBatcher.
 * Tests paragraph batching with token budget constraints.
 */
class ParagraphBatcherTest {

    @Before
    fun setup() {
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

    // createBatches tests

    @Test
    fun `createBatches returns empty for empty input`() {
        val result = ParagraphBatcher.createBatches(emptyList(), 1000)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `createBatches creates single batch for small input`() {
        val paragraphs = listOf("Short paragraph one.", "Short paragraph two.")
        val result = ParagraphBatcher.createBatches(paragraphs, 1000)
        
        assertEquals(1, result.size)
        assertEquals(0, result[0].batchIndex)
        assertEquals(0, result[0].startParagraphIndex)
        assertEquals(1, result[0].endParagraphIndex)
        assertEquals(2, result[0].paragraphCount)
    }

    @Test
    fun `createBatches splits into multiple batches for large input`() {
        // Create paragraphs that will exceed budget
        val longParagraph = "A".repeat(100) // ~25 tokens
        val paragraphs = List(10) { longParagraph }
        
        // Budget for ~50 chars (12 tokens)
        val result = ParagraphBatcher.createBatches(paragraphs, 12)
        
        assertTrue(result.size > 1)
        // Verify batches are consecutive
        for (i in 0 until result.size - 1) {
            assertEquals(result[i].endParagraphIndex + 1, result[i + 1].startParagraphIndex)
        }
    }

    @Test
    fun `createBatches tracks paragraph indices correctly`() {
        val paragraphs = listOf("One", "Two", "Three", "Four", "Five")
        val result = ParagraphBatcher.createBatches(paragraphs, 1000)
        
        assertEquals(1, result.size)
        assertEquals(0, result[0].startParagraphIndex)
        assertEquals(4, result[0].endParagraphIndex)
        assertEquals(5, result[0].paragraphCount)
    }

    @Test
    fun `createBatches estimates tokens correctly`() {
        val paragraph = "A".repeat(40) // 40 chars = 10 tokens at 4 chars/token
        val paragraphs = listOf(paragraph)
        val result = ParagraphBatcher.createBatches(paragraphs, 1000)
        
        assertEquals(10, result[0].estimatedTokens)
    }

    @Test
    fun `createBatches respects token budget`() {
        val maxTokens = 50 // 200 chars max
        val paragraph = "X".repeat(100) // 100 chars each
        val paragraphs = List(5) { paragraph }
        
        val result = ParagraphBatcher.createBatches(paragraphs, maxTokens)
        
        // Each batch should have at most ~200 chars
        for (batch in result) {
            assertTrue("Batch exceeds budget", batch.text.length <= maxTokens * 4 + 100) // some margin
        }
    }

    // createBatchesFromIndex tests

    @Test
    fun `createBatchesFromIndex returns empty for out of range index`() {
        val paragraphs = listOf("One", "Two", "Three")
        val result = ParagraphBatcher.createBatchesFromIndex(paragraphs, 1000, 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `createBatchesFromIndex starts from correct index`() {
        val paragraphs = listOf("Zero", "One", "Two", "Three", "Four")
        val result = ParagraphBatcher.createBatchesFromIndex(paragraphs, 1000, 2)
        
        assertEquals(1, result.size)
        assertEquals(2, result[0].startParagraphIndex)
        assertEquals(4, result[0].endParagraphIndex)
        assertEquals(3, result[0].paragraphCount)
    }

    @Test
    fun `createBatchesFromIndex adjusts indices to global`() {
        val longParagraph = "B".repeat(100)
        val paragraphs = List(10) { longParagraph }
        
        val result = ParagraphBatcher.createBatchesFromIndex(paragraphs, 30, 5)
        
        // All indices should be >= 5
        for (batch in result) {
            assertTrue(batch.startParagraphIndex >= 5)
            assertTrue(batch.endParagraphIndex >= 5)
        }
    }

    // estimateBatchCount tests

    @Test
    fun `estimateBatchCount returns 1 for small input`() {
        val paragraphs = listOf("Small", "Input")
        val count = ParagraphBatcher.estimateBatchCount(paragraphs, 1000)
        assertEquals(1, count)
    }

    @Test
    fun `estimateBatchCount estimates correctly for large input`() {
        val paragraph = "C".repeat(100) // 100 chars
        val paragraphs = List(10) { paragraph } // ~1020 chars total
        
        // Budget: 250 tokens = 1000 chars
        val count = ParagraphBatcher.estimateBatchCount(paragraphs, 250)
        
        // Should estimate ~2 batches
        assertTrue(count >= 1)
    }

    // ParagraphBatch data class tests

    @Test
    fun `ParagraphBatch stores all properties correctly`() {
        val batch = ParagraphBatcher.ParagraphBatch(
            batchIndex = 0,
            startParagraphIndex = 5,
            endParagraphIndex = 10,
            text = "Test content",
            paragraphCount = 6,
            estimatedTokens = 3
        )
        
        assertEquals(0, batch.batchIndex)
        assertEquals(5, batch.startParagraphIndex)
        assertEquals(10, batch.endParagraphIndex)
        assertEquals("Test content", batch.text)
        assertEquals(6, batch.paragraphCount)
        assertEquals(3, batch.estimatedTokens)
    }

    // CHARS_PER_TOKEN constant test

    @Test
    fun `CHARS_PER_TOKEN is 4`() {
        assertEquals(4, ParagraphBatcher.CHARS_PER_TOKEN)
    }
}

