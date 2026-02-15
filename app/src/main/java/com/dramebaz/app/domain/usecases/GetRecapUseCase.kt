package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.BookDao
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.db.ChapterWithAnalysis
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.data.db.ReadingSession
import com.dramebaz.app.data.db.ReadingSessionDao
import com.dramebaz.app.data.models.CharacterReminder
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.RecapDepth
import com.dramebaz.app.data.models.SeriesRecapResult
import com.dramebaz.app.data.models.TimeAwareRecapResult
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * T4.2: Recap generator – fetch last N chapter summaries, optional "Previously on…" paragraph.
 * SUMMARY-001: Enhanced with time-aware recaps based on days since last read.
 * SUMMARY-002: Enhanced with multi-book series cross-reference.
 */
class GetRecapUseCase(
    private val bookRepository: BookRepository,
    private val context: Context? = null,
    private val readingSessionDao: ReadingSessionDao? = null,
    private val characterDao: CharacterDao? = null,
    private val bookDao: BookDao? = null  // SUMMARY-002: For series-aware recaps
) {
    private val gson = Gson()
    private val tag = "GetRecapUseCase"

    suspend fun getLastNSummaries(bookId: Long, currentChapterId: Long, n: Int = 3): List<String> {
        // BLOB-FIX: Use lightweight projection that excludes body field
        val chapters = bookRepository.getChaptersWithAnalysis(bookId).sortedBy { it.orderIndex }
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.takeIf { it >= 0 } ?: 0
        val start = (currentIndex - n).coerceAtLeast(0)
        return chapters.subList(start, currentIndex).mapNotNull { parseSummaryFromAnalysis(it) }
    }

    /** 
     * T4.2.2: Compress last N summaries into a "Previously on…" paragraph.
     * Uses LLM if available, otherwise falls back to simple concatenation.
     */
    suspend fun compressToParagraph(summaries: List<String>): String = withContext(Dispatchers.IO) {
        if (summaries.isEmpty()) return@withContext ""
        
        // Try LLM compression if context is available
        if (context != null) {
            try {
                val prompt = """SYSTEM:
You are a story recap generator. Your task is to create a concise "Previously on..." paragraph that summarizes the key events from multiple chapter summaries.

Rules:
1. Keep it under 200 words
2. Focus on the most important plot points and character developments
3. Write in a narrative style that flows naturally
4. Use past tense
5. Connect the events smoothly

USER:
Here are the chapter summaries to compress:

${summaries.joinToString("\n\n")}

Create a "Previously on..." paragraph that summarizes these events."""

                val llmResult = LlmService.generateStory(prompt)
                if (llmResult.isNotBlank() && !llmResult.contains("stub") && !llmResult.contains("This is a stub")) {
                    // Extract the recap paragraph (remove any prompt text)
                    val recap = llmResult.lines()
                        .filter { it.isNotBlank() && !it.contains("SYSTEM:") && !it.contains("USER:") }
                        .joinToString(" ")
                        .take(500)
                    
                    if (recap.isNotBlank()) {
                        AppLogger.d(tag, "Generated LLM recap: ${recap.length} characters")
                        return@withContext "Previously: $recap"
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "LLM compression failed, using fallback", e)
            }
        }
        
        // Fallback: simple concatenation
        val fallback = summaries.joinToString(" ") { it.take(120) }.take(500)
        "Previously: $fallback${if (summaries.size > 1) "…" else ""}"
    }

    private fun parseSummary(chapter: Chapter): String? {
        val json = chapter.summaryJson ?: return null
        return try {
            gson.fromJson(json, ChapterSummary::class.java)?.shortSummary ?: json.take(100)
        } catch (_: Exception) {
            json.take(100)
        }
    }

    /**
     * BLOB-FIX: Parse summary from lightweight ChapterWithAnalysis projection.
     */
    private fun parseSummaryFromAnalysis(chapter: ChapterWithAnalysis): String? {
        val json = chapter.summaryJson ?: return null
        return try {
            gson.fromJson(json, ChapterSummary::class.java)?.shortSummary ?: json.take(100)
        } catch (_: Exception) {
            json.take(100)
        }
    }

    // ==================== SUMMARY-001: Time-Aware Recaps ====================

    /**
     * SUMMARY-001: Generate a time-aware recap based on how long since user last read.
     *
     * @param bookId The book ID
     * @param currentChapterId The current chapter ID
     * @return TimeAwareRecapResult with recap text, depth, and optional character reminders
     */
    suspend fun getTimeAwareRecap(
        bookId: Long,
        currentChapterId: Long
    ): TimeAwareRecapResult = withContext(Dispatchers.IO) {
        // Get last reading session for this book
        val session = readingSessionDao?.getCurrent()
        val daysSinceLastRead = calculateDaysSinceLastRead(session, bookId)

        // Determine recap depth based on time
        val depth = if (daysSinceLastRead < 0) {
            RecapDepth.BRIEF // First time reading
        } else {
            RecapDepth.fromDaysSinceLastRead(daysSinceLastRead)
        }

        AppLogger.d(tag, "SUMMARY-001: Days since last read: $daysSinceLastRead, depth: ${depth.name}")

        // Get summaries based on depth
        val summaries = getLastNSummaries(bookId, currentChapterId, depth.chapterCount)

        // Generate recap text
        val recapText = if (summaries.isNotEmpty()) {
            compressToParagraph(summaries)
        } else {
            ""
        }

        // Get character reminders for detailed recaps
        val characterReminders = if (depth.includeCharacters && characterDao != null) {
            getCharacterReminders(bookId)
        } else {
            emptyList()
        }

        TimeAwareRecapResult(
            recapText = recapText,
            depth = depth,
            daysSinceLastRead = daysSinceLastRead,
            characterReminders = characterReminders,
            chapterCount = summaries.size
        )
    }

    /**
     * Calculate days since last read for a specific book.
     * Returns -1 if no previous session exists (first time reading).
     */
    private fun calculateDaysSinceLastRead(session: ReadingSession?, bookId: Long): Float {
        if (session == null || session.bookId != bookId) {
            return -1f // First time reading this book
        }

        val lastReadMs = session.lastReadTimestamp
        val nowMs = System.currentTimeMillis()
        val diffMs = nowMs - lastReadMs

        return TimeUnit.MILLISECONDS.toDays(diffMs).toFloat() +
               (TimeUnit.MILLISECONDS.toHours(diffMs) % 24) / 24f
    }

    /**
     * Get character reminders for long absences.
     * Returns top characters with their traits for quick reference.
     */
    private suspend fun getCharacterReminders(bookId: Long): List<CharacterReminder> {
        if (characterDao == null) return emptyList()

        return try {
            val characters = characterDao.getByBookId(bookId).first()
            characters.take(5).map { character ->
                CharacterReminder(
                    name = character.name,
                    description = character.personalitySummary.ifBlank {
                        "A character in the story"
                    },
                    traits = parseTraits(character.traits)
                )
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to get character reminders", e)
            emptyList()
        }
    }

    /**
     * Parse traits from JSON array or comma-separated string.
     */
    private fun parseTraits(traitsStr: String): List<String> {
        if (traitsStr.isBlank()) return emptyList()

        return try {
            // Try parsing as JSON array first
            if (traitsStr.startsWith("[")) {
                gson.fromJson(traitsStr, Array<String>::class.java)?.toList() ?: emptyList()
            } else {
                // Fallback to comma-separated
                traitsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        } catch (e: Exception) {
            traitsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    /**
     * SUMMARY-001: Generate recap with character context for detailed recaps.
     * Includes character names and brief descriptions in the LLM prompt.
     */
    suspend fun compressToParagraphWithCharacters(
        summaries: List<String>,
        characters: List<CharacterReminder>
    ): String = withContext(Dispatchers.IO) {
        if (summaries.isEmpty()) return@withContext ""

        val characterContext = if (characters.isNotEmpty()) {
            val charList = characters.joinToString("\n") {
                "- ${it.name}: ${it.description}"
            }
            "\n\nKey characters:\n$charList"
        } else {
            ""
        }

        if (context != null) {
            try {
                val prompt = """<|im_start|>system
You are a story recap generator. Create a "Previously on..." paragraph that summarizes key events and reminds the reader of important characters.
<|im_end|>
<|im_start|>user
Chapter summaries:
${summaries.joinToString("\n\n")}
$characterContext

Create a concise recap (under 250 words) that:
1. Summarizes the main plot points
2. Mentions key characters naturally
3. Uses past tense and flows smoothly
<|im_end|>
<|im_start|>assistant
Previously:"""

                val llmResult = LlmService.generateRawText(prompt)
                if (llmResult.isNotBlank() && !llmResult.contains("stub")) {
                    val recap = llmResult.lines()
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .take(600)

                    if (recap.isNotBlank()) {
                        AppLogger.d(tag, "Generated detailed LLM recap: ${recap.length} chars")
                        return@withContext "Previously: $recap"
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "LLM detailed recap failed, using fallback", e)
            }
        }

        // Fallback
        compressToParagraph(summaries)
    }

    // ==================== SUMMARY-002: Multi-Book Series Cross-Reference ====================

    /**
     * SUMMARY-002: Generate a series-aware recap that includes events from previous books.
     *
     * @param bookId The current book ID
     * @param currentChapterId The current chapter ID
     * @return SeriesRecapResult with current book recap and optionally previous book summary
     */
    suspend fun getSeriesAwareRecap(
        bookId: Long,
        currentChapterId: Long
    ): SeriesRecapResult = withContext(Dispatchers.IO) {
        // Get the current book's time-aware recap
        val currentRecap = getTimeAwareRecap(bookId, currentChapterId)

        // Check if book is part of a series
        if (bookDao == null) {
            AppLogger.d(tag, "SUMMARY-002: No bookDao available, returning basic recap")
            return@withContext SeriesRecapResult(currentBookRecap = currentRecap)
        }

        val book = bookDao.getById(bookId)
        if (book == null || book.seriesId == null) {
            AppLogger.d(tag, "SUMMARY-002: Book is not part of a series")
            return@withContext SeriesRecapResult(currentBookRecap = currentRecap)
        }

        // Get series info
        val seriesOrder = book.seriesOrder ?: 1
        if (seriesOrder <= 1) {
            AppLogger.d(tag, "SUMMARY-002: This is the first book in the series, no previous book recap")
            return@withContext SeriesRecapResult(
                currentBookRecap = currentRecap,
                isPartOfSeries = true,
                bookNumberInSeries = seriesOrder
            )
        }

        // Find the previous book in the series
        val previousBook = findPreviousBookInSeries(book.seriesId, seriesOrder - 1)
        if (previousBook == null) {
            AppLogger.d(tag, "SUMMARY-002: Could not find previous book in series")
            return@withContext SeriesRecapResult(
                currentBookRecap = currentRecap,
                isPartOfSeries = true,
                bookNumberInSeries = seriesOrder
            )
        }

        // Generate summary of the previous book
        val previousSummary = generatePreviousBookSummary(previousBook.id)

        AppLogger.d(tag, "SUMMARY-002: Generated series-aware recap with previous book: ${previousBook.title}")

        SeriesRecapResult(
            currentBookRecap = currentRecap,
            previousBookSummary = previousSummary,
            previousBookTitle = previousBook.title,
            isPartOfSeries = true,
            bookNumberInSeries = seriesOrder
        )
    }

    /**
     * Find a book in the series by its order.
     */
    private suspend fun findPreviousBookInSeries(seriesId: Long, targetOrder: Int): Book? {
        if (bookDao == null) return null

        return try {
            val allBooks = bookRepository.allBooks().first()
            allBooks.find { it.seriesId == seriesId && it.seriesOrder == targetOrder }
        } catch (e: Exception) {
            AppLogger.w(tag, "Error finding previous book in series", e)
            null
        }
    }

    /**
     * Generate a summary of the previous book's key events.
     */
    private suspend fun generatePreviousBookSummary(previousBookId: Long): String? {
        return try {
            // BLOB-FIX: Use lightweight projection that excludes body field
            val chapters = bookRepository.getChaptersWithAnalysis(previousBookId).sortedBy { it.orderIndex }

            // Collect all chapter summaries
            val summaries = chapters.mapNotNull { parseSummaryFromAnalysis(it) }

            if (summaries.isEmpty()) {
                return "The previous book has not been analyzed yet."
            }

            // Generate a compressed summary of key events
            if (context != null) {
                try {
                    val prompt = """<|im_start|>system
You are a story recap generator. Create a brief "Previously in the series..." summary that reminds readers of key events from the previous book.
<|im_end|>
<|im_start|>user
Here are the chapter summaries from the previous book:

${summaries.take(10).joinToString("\n\n")}

Create a concise summary (under 150 words) of the major plot points, character developments, and how the previous book ended. This will help readers remember what happened before starting the current book.
<|im_end|>
<|im_start|>assistant
Previously in the series:"""

                    val llmResult = LlmService.generateRawText(prompt)
                    if (llmResult.isNotBlank() && !llmResult.contains("stub")) {
                        val recap = llmResult.lines()
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .take(400)

                        if (recap.isNotBlank()) {
                            AppLogger.d(tag, "Generated previous book summary: ${recap.length} chars")
                            return "Previously in the series: $recap"
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "LLM previous book summary failed, using fallback", e)
                }
            }

            // Fallback: simple concatenation of last few summaries
            val fallback = summaries.takeLast(3).joinToString(" ") { it.take(100) }.take(300)
            "Previously in the series: $fallback..."
        } catch (e: Exception) {
            AppLogger.w(tag, "Error generating previous book summary", e)
            null
        }
    }
}
