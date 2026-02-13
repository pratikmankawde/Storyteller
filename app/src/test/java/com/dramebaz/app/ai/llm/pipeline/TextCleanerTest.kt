package com.dramebaz.app.ai.llm.pipeline

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TextCleaner.
 * Tests PDF text cleaning, paragraph splitting, and page mapping.
 */
class TextCleanerTest {

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

    // cleanPage tests

    @Test
    fun `cleanPage removes page numbers`() {
        val input = "Page 1\nSome content here."
        val result = TextCleaner.cleanPage(input)
        assertFalse(result.contains("Page 1"))
        assertTrue(result.contains("Some content here"))
    }

    @Test
    fun `cleanPage removes dash-wrapped page numbers`() {
        val input = "Content here\n- 42 -\nMore content"
        val result = TextCleaner.cleanPage(input)
        assertFalse(result.contains("- 42 -"))
    }

    @Test
    fun `cleanPage collapses multiple spaces`() {
        val input = "Hello    World"
        val result = TextCleaner.cleanPage(input)
        assertEquals("Hello World", result)
    }

    @Test
    fun `cleanPage collapses multiple newlines`() {
        val input = "Para 1\n\n\n\n\nPara 2"
        val result = TextCleaner.cleanPage(input)
        assertEquals("Para 1\n\nPara 2", result)
    }

    @Test
    fun `cleanPage trims each line`() {
        val input = "  Line with spaces  \n  Another line  "
        val result = TextCleaner.cleanPage(input)
        assertTrue(result.startsWith("Line"))
        assertFalse(result.contains("  Line"))
    }

    // cleanAndSplitIntoParagraphs tests

    @Test
    fun `cleanAndSplitIntoParagraphs splits on double newlines`() {
        // Note: paragraphs must be > 10 chars to pass the filter
        val pages = listOf("Paragraph one here.\n\nParagraph two here.\n\nParagraph three here.")
        val result = TextCleaner.cleanAndSplitIntoParagraphs(pages)
        assertEquals(3, result.size)
        assertTrue(result[0].contains("Paragraph one"))
        assertTrue(result[1].contains("Paragraph two"))
        assertTrue(result[2].contains("Paragraph three"))
    }

    @Test
    fun `cleanAndSplitIntoParagraphs filters short fragments`() {
        val pages = listOf("Long enough paragraph here.\n\nToo short")
        val result = TextCleaner.cleanAndSplitIntoParagraphs(pages)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("Long enough"))
    }

    @Test
    fun `cleanAndSplitIntoParagraphs handles multiple pages`() {
        val pages = listOf(
            "First page content here.",
            "Second page content here."
        )
        val result = TextCleaner.cleanAndSplitIntoParagraphs(pages)
        assertEquals(2, result.size)
    }

    @Test
    fun `cleanAndSplitIntoParagraphs returns empty for empty input`() {
        val result = TextCleaner.cleanAndSplitIntoParagraphs(emptyList())
        assertTrue(result.isEmpty())
    }

    // cleanAndSplitWithPageMapping tests

    @Test
    fun `cleanAndSplitWithPageMapping returns correct page boundaries`() {
        val pages = listOf(
            "Page 1 paragraph 1.\n\nPage 1 paragraph 2.",
            "Page 2 paragraph 1.\n\nPage 2 paragraph 2.\n\nPage 2 paragraph 3."
        )
        val (paragraphs, boundaries) = TextCleaner.cleanAndSplitWithPageMapping(pages)
        
        assertEquals(5, paragraphs.size)
        assertEquals(0, boundaries[0]) // Page 0 starts at para 0
        assertEquals(2, boundaries[1]) // Page 1 starts at para 2
        assertEquals(5, boundaries[2]) // End marker
    }

    // findPagesForParagraphRange tests

    @Test
    fun `findPagesForParagraphRange finds correct pages`() {
        val boundaries = intArrayOf(0, 3, 6, 9) // 3 pages, 3 paragraphs each
        
        // Range within first page
        assertEquals(0..0, TextCleaner.findPagesForParagraphRange(0, 2, boundaries))
        
        // Range spanning two pages
        assertEquals(0..1, TextCleaner.findPagesForParagraphRange(2, 4, boundaries))
        
        // Range in last page
        assertEquals(2..2, TextCleaner.findPagesForParagraphRange(6, 8, boundaries))
    }

    // truncateAtParagraphBoundary tests

    @Test
    fun `truncateAtParagraphBoundary returns input if under limit`() {
        val input = "Short text"
        val result = TextCleaner.truncateAtParagraphBoundary(input, 100)
        assertEquals(input, result)
    }

    @Test
    fun `truncateAtParagraphBoundary truncates at paragraph boundary`() {
        val input = "Para 1 content.\n\nPara 2 content.\n\nPara 3 content."
        val result = TextCleaner.truncateAtParagraphBoundary(input, 30)
        assertTrue(result.length <= 30)
    }

    // truncateAtSentenceBoundary tests

    @Test
    fun `truncateAtSentenceBoundary returns input if under limit`() {
        val input = "Short sentence."
        val result = TextCleaner.truncateAtSentenceBoundary(input, 50)
        assertEquals(input, result)
    }

    @Test
    fun `truncateAtSentenceBoundary truncates at sentence end`() {
        val input = "First sentence. Second sentence. Third sentence."
        val result = TextCleaner.truncateAtSentenceBoundary(input, 20)
        assertTrue(result.endsWith("."))
    }

    @Test
    fun `truncateAtSentenceBoundary handles no sentence boundary`() {
        val input = "A very long text without any sentence endings just words"
        val result = TextCleaner.truncateAtSentenceBoundary(input, 30)
        assertTrue(result.length <= 30)
    }

    // mergeParagraphs tests

    @Test
    fun `mergeParagraphs joins with double newlines`() {
        val paragraphs = listOf("Para 1", "Para 2", "Para 3")
        val result = TextCleaner.mergeParagraphs(paragraphs)
        assertEquals("Para 1\n\nPara 2\n\nPara 3", result)
    }

    @Test
    fun `mergeParagraphs handles empty list`() {
        val result = TextCleaner.mergeParagraphs(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `mergeParagraphs handles single paragraph`() {
        val result = TextCleaner.mergeParagraphs(listOf("Only one"))
        assertEquals("Only one", result)
    }
}

