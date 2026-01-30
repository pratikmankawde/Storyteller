package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * T4.2: Recap generator – fetch last N chapter summaries, optional "Previously on…" paragraph.
 */
class GetRecapUseCase(
    private val bookRepository: BookRepository,
    private val context: Context? = null
) {
    private val gson = Gson()
    private val tag = "GetRecapUseCase"

    suspend fun getLastNSummaries(bookId: Long, currentChapterId: Long, n: Int = 3): List<String> {
        val chapters = bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }.takeIf { it >= 0 } ?: 0
        val start = (currentIndex - n).coerceAtLeast(0)
        return chapters.subList(start, currentIndex).mapNotNull { parseSummary(it) }
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

                val llmResult = QwenStub.generateStory(prompt)
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
}
