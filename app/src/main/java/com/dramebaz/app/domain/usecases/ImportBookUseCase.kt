package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.exceptions.ImportException
import com.dramebaz.app.pdf.PdfChapterDetector
import com.dramebaz.app.pdf.PdfExtractor
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.InputValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Import flow – file picker gives path; for PDF we extract text via PdfExtractor (feed text to Qwen, not raw PDF).
 * For TXT we read directly. Chapter-split by "\n\n" or "\n# " / chapter headers.
 */
class ImportBookUseCase(private val bookRepository: BookRepository) {
    private val tag = "ImportBookUseCase"

    /**
     * @param context Required for PDF text extraction; can be null for non-PDF (then file is read as text).
     */
    suspend fun importFromFile(context: Context?, filePath: String, format: String): Long = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val file = File(filePath)

        // AUG-040: Validate file before importing
        val validationResult = InputValidator.validateFileForImport(file, format)
        if (validationResult.isFailure) {
            val error = validationResult.exceptionOrNull()
            AppLogger.e(tag, "File validation failed: ${error?.message}")
            throw error as? ImportException
                ?: ImportException(error?.message ?: "File validation failed", error)
        }

        AppLogger.i(tag, "Importing book: path=$filePath, format=$format, size=${file.length()} bytes")

        val title = file.nameWithoutExtension
        val book = Book(title = title, filePath = filePath, format = format)
        val bookId = bookRepository.insertBook(book)

        // For PDF: extract text per page, detect chapter boundaries with PDF page info
        if (format.equals("pdf", ignoreCase = true) && context != null) {
            try {
                val pages = PdfExtractor(context).extractText(file)
                AppLogger.i(tag, "PDF text extracted: ${pages.size} pages")
                if (pages.isNotEmpty()) {
                    val firstPagePreview = pages[0].take(300).replace("\n", " ").trim()
                    AppLogger.d(tag, "First page preview: \"$firstPagePreview...\"")
                }
                // Use new method that preserves PDF page info
                val chaptersWithPages = PdfChapterDetector.detectChaptersWithPages(pages, title)
                AppLogger.i(tag, "PDF chapter detection complete: ${chaptersWithPages.size} chapters")
                for ((idx, ch) in chaptersWithPages.withIndex()) {
                    AppLogger.i(tag, "Chapter ${idx + 1} \"${ch.title}\": ${ch.pdfPages.size} PDF pages, ${ch.body.length} chars")
                }
                // Insert chapters with PDF page info
                val chapterList = chaptersWithPages.mapIndexed { index, ch ->
                    Chapter(
                        bookId = bookId,
                        title = ch.title,
                        body = ch.body,
                        orderIndex = index,
                        pdfPagesJson = PdfChapterDetector.pdfPagesToJson(ch.pdfPages)
                    )
                }
                bookRepository.insertChapters(chapterList)
                AppLogger.d(tag, "Inserted ${chapterList.size} chapters with PDF page info")
            } catch (e: Exception) {
                AppLogger.e(tag, "PDF extraction/detection failed", e)
                bookRepository.deleteBookWithChapters(bookId)
                throw ImportException("Failed to extract text from PDF: ${e.message}", e)
            }
        } else {
            // Non-PDF files: use text-based chapter splitting (no PDF page info)
            val body = async(Dispatchers.IO) {
                AppLogger.d(tag, "Reading file content")
                file.readText()
            }.await().also {
                AppLogger.d(tag, "File read complete: ${it.length} characters")
            }
            val chunks = async(Dispatchers.Default) {
                AppLogger.d(tag, "Splitting into chapters")
                splitChapters(body, title)
            }.await()
            AppLogger.d(tag, "Split into ${chunks.size} chapters")
            val chapterList = chunks.mapIndexed { index, (t, b) ->
                Chapter(bookId = bookId, title = t, body = b, orderIndex = index)
            }
            bookRepository.insertChapters(chapterList)
            AppLogger.i(tag, "Book imported successfully: bookId=$bookId, chapters=${chapterList.size}")
        }

        AppLogger.logPerformance(tag, "Import book '$title'", System.currentTimeMillis() - startTime)

        // AUTO-ANALYSIS: Queue only the first chapter for automatic analysis on import
        // Rest of chapters are analyzed later via lookahead (at 80% reading progress) or manual trigger
        AnalysisQueueManager.enqueueFirstChapter(bookId)
        AppLogger.i(tag, "Book $bookId queued for first-chapter analysis")

        return@withContext bookId
    }

    private fun splitChapters(fullText: String, defaultTitle: String): List<Pair<String, String>> {
        val trimmed = fullText.trim()
        if (trimmed.isEmpty()) {
            AppLogger.w(tag, "Book content is empty, creating single placeholder chapter")
            return listOf(defaultTitle to " ")
        }

        // 1) Try splitting on common chapter headers (Chapter 1, Chapter One, PART ONE, # Title, etc.)
        // Prepend newline so "Chapter 1" at start of file is also detected
        val chapterHeaderPattern = Regex(
            """\n(?=\s*(?:Chapter\s+\d+|Chapter\s+[A-Za-z\s]+|CHAPTER\s+\d+|Part\s+[One\d\s]+|PART\s+[ONE\d\s]+|#+\s+.+))""",
            RegexOption.IGNORE_CASE
        )
        val normalized = "\n$trimmed"
        val byHeader = normalized.split(chapterHeaderPattern).map { it.trim() }.filter { it.isNotBlank() }
        if (byHeader.size >= 2) {
            AppLogger.d(tag, "Split by chapter headers: ${byHeader.size} chapters")
            return byHeader.mapIndexed { i, block ->
                val firstLine = block.lines().firstOrNull()?.trim()?.take(80) ?: "Chapter ${i + 1}"
                val title = when {
                    firstLine.startsWith("#") -> firstLine.trimStart('#').trim()
                    firstLine.contains(Regex("(?i)chapter\\s+\\d+")) -> firstLine
                    firstLine.contains(Regex("(?i)part\\s+")) -> firstLine
                    else -> "Chapter ${i + 1}"
                }
                // Normalize body: collapse excessive newlines so chapter start/end are clean for LLM
                val body = block.replace(Regex("\n{3,}"), "\n\n").trim()
                title to if (body.isBlank()) " " else body
            }
        }

        // 2) Fallback: split by markdown-style "# " or double newline (paragraph breaks)
        val fallbackPattern = Regex("(\n\\s*#+\\s+)|(\n\\s*\n+)")
        val parts = trimmed.split(fallbackPattern).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            AppLogger.w(tag, "Split produced no chapters, using full text as single chapter")
            return listOf(defaultTitle to trimmed)
        }
        if (parts.size == 1) {
            return listOf(defaultTitle to trimmed)
        }
        return parts.mapIndexed { i, s ->
            val firstLine = s.lines().firstOrNull()?.trim()?.take(80) ?: "Chapter ${i + 1}"
            val title = if (firstLine.startsWith("#")) firstLine.trimStart('#').trim() else "Chapter ${i + 1}"
            title to s
        }
    }
}
