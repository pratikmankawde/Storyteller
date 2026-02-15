package com.dramebaz.app.pdf

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Detects chapter boundaries from PDF page text (my_prompt.md workflow).
 * A chapter starts on a new page when the page starts with a chapter title/header.
 * Store start and end pages per chapter: scan from first chapter start to find second
 * chapter start; page before second start = end of first chapter. Repeat to end of novel.
 */
object PdfChapterDetector {
    private val tag = "PdfChapterDetector"
    private val gson = Gson()

    /**
     * Data class representing a single PDF page with its original page number.
     * Used for storing in Chapter.pdfPagesJson.
     */
    data class PdfPageInfo(
        val pdfPage: Int,  // 1-based original PDF page number
        val text: String   // Text content of this page
    )

    /**
     * Result of chapter detection containing title, body (joined text), and individual PDF pages.
     */
    data class ChapterWithPages(
        val title: String,
        val body: String,               // Joined text for backward compatibility
        val pdfPages: List<PdfPageInfo> // Individual PDF pages with page numbers
    )

    /** Patterns for "this page is the start of a chapter" (first non-empty line). */
    private val chapterStartPattern = Regex(
        """^\s*(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+(?:One|Two|Three|Four|Five|\d+))\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val chapterTitleLine = Regex(
        """(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+(?:One|Two|Three|Four|Five|\d+)|#+\s*(.+))""",
        RegexOption.IGNORE_CASE
    )

    /** Expanded section keywords - includes story structure, front matter, and back matter */
    private val sectionKeywords = listOf(
        // Story structure
        "Prologue", "Epilogue", "Foreword", "Introduction", "Preface", "Afterword",
        // Front matter
        "Contents", "Table of Contents", "Dedication", "Acknowledgments", "Acknowledgements",
        "About the Author", "Author's Note",
        // Back matter
        "Glossary", "Appendix", "Appendices", "Index", "Bibliography", "References",
        "Notes", "Endnotes", "Further Reading"
    )

    /** Regex pattern for section keywords */
    private val sectionPattern = Regex(
        """^\s*(?:${sectionKeywords.joinToString("|") { Regex.escape(it) }})\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Pattern to strip decorative characters (allows detecting "◆ CHAPTER ONE ◆" etc.) */
    private val decorativeChars = Regex("""^[^a-zA-Z0-9#]+|[^a-zA-Z0-9]+$""")

    /**
     * Strip decorative characters from text for pattern matching.
     * Handles cases like "◆ CHAPTER ONE ◆" -> "CHAPTER ONE"
     */
    private fun stripDecorative(text: String): String {
        return decorativeChars.replace(text.trim(), "")
    }

    /**
     * Check if a first line looks like a chapter start.
     * @param firstLine The first non-blank line of the page
     * @return Pair of (isChapterStart, reason) for debugging
     */
    private fun isChapterStart(firstLine: String): Pair<Boolean, String> {
        if (firstLine.isBlank()) return false to "empty"

        // Strip decorative characters for matching (handles "◆ CHAPTER ONE ◆")
        val coreText = stripDecorative(firstLine)
        if (coreText.isBlank()) return false to "only_decorative"

        // Check chapter/part patterns
        if (chapterStartPattern.matches(coreText)) {
            return true to "chapter_pattern"
        }

        // Additional chapter keyword patterns
        if (coreText.matches(Regex("""Chapter\s+.+""", RegexOption.IGNORE_CASE))) {
            return true to "chapter_keyword"
        }
        if (coreText.matches(Regex("""PART\s+(?:ONE|TWO|THREE|FOUR|FIVE|\d+).*""", RegexOption.IGNORE_CASE))) {
            return true to "part_keyword"
        }

        // Markdown headers
        if (coreText.matches(Regex("""#+\s+.+"""))) {
            return true to "markdown_header"
        }

        // Section keywords (Prologue, Epilogue, TOC, etc.)
        if (sectionPattern.matches(coreText)) {
            return true to "section_keyword"
        }

        // Numbered titles like "1. The Beginning" or "1) Chapter Title"
        // Be more strict: require title text after number, not just any content
        if (coreText.matches(Regex("""^\d{1,3}[.)]\s+[A-Z].*""", RegexOption.IGNORE_CASE))) {
            return true to "numbered_title"
        }

        return false to "no_match"
    }

    /**
     * Find chapter start indices from pages.
     * @param pages List of page texts
     * @param treatFirstPageAsChapter If true, always add page 0 as chapter start
     * @return List of page indices where chapters start
     */
    private fun findChapterStarts(pages: List<String>, treatFirstPageAsChapter: Boolean = true): List<Int> {
        val chapterStarts = mutableListOf<Int>()

        for (i in pages.indices) {
            val firstLine = pages[i].lines().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""
            val (isStart, reason) = isChapterStart(firstLine)

            if (i == 0 && treatFirstPageAsChapter) {
                // Only add first page if it has content (not just a blank title page)
                if (pages[i].trim().length > 50) {
                    chapterStarts.add(0)
                    AppLogger.d(tag, "Page 0 added as chapter start (has content)")
                } else {
                    AppLogger.d(tag, "Page 0 skipped (appears to be title/blank page)")
                }
            } else if (isStart) {
                chapterStarts.add(i)
                AppLogger.d(tag, "Chapter start at page $i: reason=$reason, firstLine=\"${firstLine.take(60)}\"")
            } else if (i < 20) {
                // Debug log for first 20 pages to help diagnose detection issues
                AppLogger.d(tag, "Page $i not a chapter start: reason=$reason, firstLine=\"${firstLine.take(60)}\"")
            }
        }

        // Ensure we have at least one chapter start
        if (chapterStarts.isEmpty()) chapterStarts.add(0)
        // Add sentinel for end of book
        if (chapterStarts.last() < pages.size) chapterStarts.add(pages.size)

        return chapterStarts
    }

    /**
     * Extract chapter title from the first line of a page.
     */
    private fun extractChapterTitle(firstLine: String, chapterIndex: Int, startPage: Int): String {
        val coreText = stripDecorative(firstLine)

        return when {
            // Markdown header
            firstLine.startsWith("#") -> firstLine.trimStart('#').trim()
            // Chapter/Part title
            chapterTitleLine.find(coreText) != null -> coreText
            // Section keyword (use as-is)
            sectionPattern.matches(coreText) -> coreText.trim()
            // First chunk without chapter title - use generic name
            chapterIndex == 0 && startPage == 0 -> "Introduction"
            // Fallback
            else -> "Section ${chapterIndex + 1}"
        }
    }

    /**
     * Detect chapter boundaries from a list of PDF page texts.
     * @param pages List of page texts (one string per page).
     * @param defaultTitle Fallback book/chapter title if no headers found.
     * @return List of (chapterTitle, chapterBody) where body is joined pages for that chapter range.
     */
    fun detectChaptersFromPages(pages: List<String>, defaultTitle: String): List<Pair<String, String>> {
        if (pages.isEmpty()) {
            AppLogger.w(tag, "No pages provided")
            return listOf(defaultTitle to " ")
        }

        // Use shared helper to find chapter boundaries
        val chapterStarts = findChapterStarts(pages)
        AppLogger.i(tag, "Chapter boundaries: ${chapterStarts.size - 1} chapters, start pages=$chapterStarts")

        val result = mutableListOf<Pair<String, String>>()
        for (k in 0 until chapterStarts.size - 1) {
            val startPage = chapterStarts[k]
            val endPage = chapterStarts[k + 1] // exclusive
            if (startPage >= endPage) {
                AppLogger.w(tag, "Skipping invalid chapter range: start=$startPage end=$endPage")
                continue
            }
            val rangePages = pages.subList(startPage, endPage)
            // Trim each page and join so chapter start/end are clean; collapse excessive newlines
            val rawBody = rangePages.joinToString("\n\n") { it.trim() }.trim()
            val body = rawBody.replace(Regex("\n{3,}"), "\n\n").trim()
            val firstPageText = rangePages.firstOrNull() ?: ""
            val titleLine = firstPageText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

            val title = extractChapterTitle(titleLine, k, startPage)
            val finalBody = if (body.isBlank()) " " else body
            result.add(title to finalBody)
            AppLogger.i(tag, "Chapter ${k + 1}: PDF pages $startPage–${endPage - 1} (${endPage - startPage} pages), title=\"$title\", bodyLen=${finalBody.length}")
        }
        return result
    }

    /**
     * Detect chapter boundaries with preserved PDF page info.
     * Returns ChapterWithPages containing individual PDF pages with their original page numbers.
     * @param pages List of page texts (one string per page, 0-indexed).
     * @param defaultTitle Fallback book/chapter title if no headers found.
     * @return List of ChapterWithPages with title, joined body, and individual PDF pages.
     */
    fun detectChaptersWithPages(pages: List<String>, defaultTitle: String): List<ChapterWithPages> {
        if (pages.isEmpty()) {
            AppLogger.w(tag, "No pages provided")
            return listOf(ChapterWithPages(defaultTitle, " ", emptyList()))
        }

        // Use shared helper to find chapter boundaries
        val chapterStarts = findChapterStarts(pages)
        AppLogger.i(tag, "Chapter boundaries (with pages): ${chapterStarts.size - 1} chapters, start pages=$chapterStarts")

        val result = mutableListOf<ChapterWithPages>()
        for (k in 0 until chapterStarts.size - 1) {
            val startPage = chapterStarts[k]
            val endPage = chapterStarts[k + 1]
            if (startPage >= endPage) continue

            // Build PDF pages list with 1-based page numbers
            val pdfPages = (startPage until endPage).map { pageIndex ->
                PdfPageInfo(
                    pdfPage = pageIndex + 1,  // Convert 0-based to 1-based
                    text = pages[pageIndex].trim()
                )
            }

            val rangePages = pages.subList(startPage, endPage)
            val rawBody = rangePages.joinToString("\n\n") { it.trim() }.trim()
            val body = rawBody.replace(Regex("\n{3,}"), "\n\n").trim()
            val firstPageText = rangePages.firstOrNull() ?: ""
            val titleLine = firstPageText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

            val title = extractChapterTitle(titleLine, k, startPage)
            val finalBody = if (body.isBlank()) " " else body
            result.add(ChapterWithPages(title, finalBody, pdfPages))
            AppLogger.i(tag, "Chapter ${k + 1}: PDF pages ${startPage + 1}–$endPage (${pdfPages.size} pages), title=\"$title\"")
        }
        return result
    }

    /**
     * Convert PDF pages list to JSON string for storage.
     */
    fun pdfPagesToJson(pdfPages: List<PdfPageInfo>): String = gson.toJson(pdfPages)

    /**
     * Parse PDF pages JSON back to list.
     */
    fun pdfPagesFromJson(json: String?): List<PdfPageInfo> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson(json, Array<PdfPageInfo>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to parse pdfPagesJson", e)
            emptyList()
        }
    }
}
