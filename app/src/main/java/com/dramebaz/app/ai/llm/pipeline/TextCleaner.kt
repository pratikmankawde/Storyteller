package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.utils.AppLogger

/**
 * Utility for cleaning and preparing PDF-extracted text for LLM analysis.
 * 
 * Responsibilities:
 * 1. Remove headers, footers, and page numbers
 * 2. Break text at paragraph boundaries
 * 3. Clean repeating spaces and newlines
 * 4. Organize text as clean paragraphs
 */
object TextCleaner {
    private const val TAG = "TextCleaner"
    
    // Common header/footer patterns to remove
    private val HEADER_FOOTER_PATTERNS = listOf(
        // Page numbers: "Page 1", "- 1 -", "1", "[1]", "(1)"
        Regex("""^\s*(?:Page\s+)?\d+\s*$""", RegexOption.MULTILINE),
        Regex("""^\s*-\s*\d+\s*-\s*$""", RegexOption.MULTILINE),
        Regex("""^\s*\[\d+\]\s*$""", RegexOption.MULTILINE),
        Regex("""^\s*\(\d+\)\s*$""", RegexOption.MULTILINE),
        // Common headers: "Chapter X", book titles (all caps lines at start)
        Regex("""^\s*[A-Z][A-Z\s]{10,}\s*$""", RegexOption.MULTILINE), // All caps lines > 10 chars
        // Copyright notices
        Regex("""^\s*(?:Copyright|©|All rights reserved).*$""", RegexOption.IGNORE_CASE),
        // Running headers (short lines at page boundaries)
        Regex("""^\s*.{1,30}\s*\n\s*\d+\s*$""", RegexOption.MULTILINE)
    )
    
    // Sentence-ending punctuation
    private val SENTENCE_END = Regex("""[.!?]["']?\s*$""")
    
    /**
     * Clean a single page of PDF text.
     * Removes headers, footers, page numbers, and normalizes whitespace.
     */
    fun cleanPage(pageText: String): String {
        var cleaned = pageText.trim()
        
        // Remove header/footer patterns
        for (pattern in HEADER_FOOTER_PATTERNS) {
            cleaned = pattern.replace(cleaned, "")
        }
        
        // Normalize whitespace: collapse multiple spaces to single space
        cleaned = cleaned.replace(Regex(""" {2,}"""), " ")
        
        // Normalize newlines: collapse 3+ newlines to 2 (paragraph break)
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")
        
        // Remove leading/trailing whitespace from each line
        cleaned = cleaned.lines()
            .map { it.trim() }
            .joinToString("\n")
        
        return cleaned.trim()
    }
    
    /**
     * Clean and merge multiple pages into organized paragraphs.
     * Returns a list of clean paragraphs.
     */
    fun cleanAndSplitIntoParagraphs(pages: List<String>): List<String> {
        // Clean each page
        val cleanedPages = pages.map { cleanPage(it) }
        
        // Join all pages with paragraph breaks
        val fullText = cleanedPages.joinToString("\n\n")
        
        // Split into paragraphs (double newline or more)
        val rawParagraphs = fullText.split(Regex("""\n\s*\n"""))
        
        // Clean and filter paragraphs
        val paragraphs = rawParagraphs
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.length > 10 } // Filter out very short fragments
        
        AppLogger.d(TAG, "Cleaned ${pages.size} pages into ${paragraphs.size} paragraphs")
        return paragraphs
    }
    
    /**
     * Clean a single page and return as organized paragraphs.
     */
    fun cleanPageIntoParagraphs(pageText: String): List<String> {
        val cleaned = cleanPage(pageText)

        // Split into paragraphs
        val rawParagraphs = cleaned.split(Regex("""\n\s*\n"""))

        return rawParagraphs
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.length > 10 }
    }

    /**
     * INCREMENTAL-001: Clean and split pages into paragraphs while tracking page-to-paragraph mapping.
     * Returns a pair of (paragraphs, pageBoundaries) where pageBoundaries[i] is the paragraph index
     * where page i starts.
     *
     * @param pages List of raw page texts
     * @return Pair of (all paragraphs, page boundaries array where pageBoundaries[i] = first paragraph index of page i)
     */
    fun cleanAndSplitWithPageMapping(pages: List<String>): Pair<List<String>, IntArray> {
        val allParagraphs = mutableListOf<String>()
        val pageBoundaries = IntArray(pages.size + 1) // +1 for end marker

        for ((pageIndex, pageText) in pages.withIndex()) {
            pageBoundaries[pageIndex] = allParagraphs.size
            val pageParagraphs = cleanPageIntoParagraphs(pageText)
            allParagraphs.addAll(pageParagraphs)
        }
        pageBoundaries[pages.size] = allParagraphs.size // End marker

        AppLogger.d(TAG, "Split ${pages.size} pages into ${allParagraphs.size} paragraphs with page mapping")
        return Pair(allParagraphs, pageBoundaries)
    }

    /**
     * INCREMENTAL-001: Find which pages are covered by a paragraph range.
     *
     * @param startParagraphIndex Start paragraph index (inclusive)
     * @param endParagraphIndex End paragraph index (inclusive)
     * @param pageBoundaries Page boundary array from cleanAndSplitWithPageMapping
     * @return IntRange of page indices (0-based) covered by the paragraph range
     */
    fun findPagesForParagraphRange(
        startParagraphIndex: Int,
        endParagraphIndex: Int,
        pageBoundaries: IntArray
    ): IntRange {
        var startPage = 0
        var endPage = 0

        // Find start page
        for (i in 0 until pageBoundaries.size - 1) {
            if (pageBoundaries[i] <= startParagraphIndex && startParagraphIndex < pageBoundaries[i + 1]) {
                startPage = i
                break
            }
            // Handle case where startParagraphIndex >= last boundary
            if (startParagraphIndex >= pageBoundaries[i]) {
                startPage = i
            }
        }

        // Find end page
        for (i in 0 until pageBoundaries.size - 1) {
            if (pageBoundaries[i] <= endParagraphIndex && endParagraphIndex < pageBoundaries[i + 1]) {
                endPage = i
                break
            }
            if (endParagraphIndex >= pageBoundaries[i]) {
                endPage = i
            }
        }

        return startPage..endPage
    }
    
    /**
     * Merge paragraphs back into a single text block.
     */
    fun mergeParagraphs(paragraphs: List<String>): String {
        return paragraphs.joinToString("\n\n")
    }
    
    /**
     * Truncate text at a paragraph boundary to fit within character limit.
     * Tries to fill at least 90% of the budget.
     */
    fun truncateAtParagraphBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        
        val paragraphs = text.split(Regex("""\n\s*\n"""))
        val result = StringBuilder()
        val targetMin = (maxChars * 0.9).toInt()
        
        for (para in paragraphs) {
            val nextLength = result.length + para.length + 2 // +2 for \n\n
            if (nextLength > maxChars) {
                // If we haven't reached 90% target, try to add partial paragraph
                if (result.length < targetMin) {
                    val remaining = maxChars - result.length - 2
                    if (remaining > 50) {
                        val truncated = truncateAtSentenceBoundary(para, remaining)
                        if (result.isNotEmpty()) result.append("\n\n")
                        result.append(truncated)
                    }
                }
                break
            }
            if (result.isNotEmpty()) result.append("\n\n")
            result.append(para)
        }
        
        return result.toString()
    }
    
    /**
     * Truncate text at a sentence boundary.
     */
    fun truncateAtSentenceBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        
        // Find the last sentence end before maxChars
        val truncated = text.take(maxChars)
        val lastSentenceEnd = SENTENCE_END.findAll(truncated)
            .lastOrNull()?.range?.last
        
        return if (lastSentenceEnd != null && lastSentenceEnd > maxChars * 0.5) {
            text.take(lastSentenceEnd + 1)
        } else {
            // Fallback: truncate at last space
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > maxChars * 0.5) {
                text.take(lastSpace)
            } else {
                truncated
            }
        }
    }
}

