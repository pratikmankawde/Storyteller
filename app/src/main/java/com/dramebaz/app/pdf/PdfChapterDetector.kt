package com.dramebaz.app.pdf

import com.dramebaz.app.utils.AppLogger

/**
 * Detects chapter boundaries from PDF page text (my_prompt.md workflow).
 * A chapter starts on a new page when the page starts with a chapter title/header.
 * Store start and end pages per chapter: scan from first chapter start to find second
 * chapter start; page before second start = end of first chapter. Repeat to end of novel.
 */
object PdfChapterDetector {
    private val tag = "PdfChapterDetector"

    /** Patterns for "this page is the start of a chapter" (first non-empty line). */
    private val chapterStartPattern = Regex(
        """^\s*(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+[One\d\s]+|PART\s+[ONE\d\s]+|#+\s*.+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val chapterTitleLine = Regex(
        """(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+[One\d\s]+|PART\s+[ONE\d\s]+|#+\s*(.+))""",
        RegexOption.IGNORE_CASE
    )

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

        // Find indices where a new chapter starts (page starts with chapter header or is first page)
        val chapterStarts = mutableListOf<Int>()
        for (i in pages.indices) {
            val firstLine = pages[i].lines().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""
            val isFirstPage = i == 0
            // Chapter starts: "Chapter 1", "Chapter One", "PART ONE", "# Title", "Prologue", "Epilogue", "1. Title"
            val looksLikeChapterStart = firstLine.isNotBlank() && (
                chapterStartPattern.containsMatchIn(firstLine) ||
                firstLine.matches(Regex("""^\s*Chapter\s+.+""", RegexOption.IGNORE_CASE)) ||
                firstLine.matches(Regex("""^\s*#+\s+.+""")) ||
                firstLine.matches(Regex("""^\s*PART\s+[ONE\d\s]+""", RegexOption.IGNORE_CASE)) ||
                firstLine.matches(Regex("""^\s*(?:Prologue|Epilogue|Foreword|Introduction)\s*$""", RegexOption.IGNORE_CASE)) ||
                firstLine.matches(Regex("""^\s*\d+[.)]\s+.+""")) // "1. Title" or "1) Title"
            )
            if (isFirstPage) {
                chapterStarts.add(0)
            } else if (looksLikeChapterStart) {
                chapterStarts.add(i)
                AppLogger.d(tag, "Chapter start at PDF page $i: firstLine=\"${firstLine.take(80)}\"")
            }
        }

        if (chapterStarts.isEmpty()) chapterStarts.add(0)
        if (chapterStarts.last() < pages.size) chapterStarts.add(pages.size)

        // Log chapter boundaries at INFO so they appear in on-device logcat
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
            
            // Determine title - use proper chapter title if found, otherwise label as Introduction/Preface
            val title = when {
                titleLine.startsWith("#") -> titleLine.trimStart('#').trim()
                chapterTitleLine.find(titleLine) != null -> titleLine
                titleLine.matches(Regex("""^\s*(?:Prologue|Epilogue|Foreword|Introduction)\s*$""", RegexOption.IGNORE_CASE)) -> titleLine.trim()
                k == 0 && startPage == 0 -> "Introduction" // First chunk without chapter title is intro
                else -> "Section ${k + 1}"
            }
            // Only add non-empty chapter body so LLM receives valid content
            val finalBody = if (body.isBlank()) " " else body
            result.add(title to finalBody)
            // INFO so on-device logcat shows chapter boundary page numbers
            AppLogger.i(tag, "Chapter ${k + 1}: PDF pages $startPage–${endPage - 1} (${endPage - startPage} pages), title=\"$title\", bodyLen=${finalBody.length}")
        }
        return result
    }
}
