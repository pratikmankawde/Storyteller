package com.dramebaz.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.GetRecapUseCase
import kotlinx.coroutines.flow.first

class ReaderViewModel(
    private val bookRepository: BookRepository,
    private val getRecapUseCase: GetRecapUseCase
) : ViewModel() {
    
    // Pattern to identify actual chapter titles (not intro/preface)
    private val chapterTitlePattern = Regex(
        """(?:Chapter\s+\d+|Chapter\s+[A-Za-z]+|CHAPTER\s+\d+|Part\s+\d+|PART\s+\d+)""",
        RegexOption.IGNORE_CASE
    )
    
    suspend fun getChapter(id: Long) = bookRepository.getChapter(id)
    suspend fun getBook(id: Long) = bookRepository.getBook(id)
    
    /**
     * Get the first actual chapter ID (skipping intro/preface pages).
     * Looks for chapters with proper "Chapter X" or "Part X" titles.
     * Falls back to first chapter if no proper chapter titles found.
     */
    suspend fun firstChapterId(bookId: Long): Long? {
        val chapters = bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
        
        // Find first chapter with a proper chapter title (e.g., "Chapter 1", "Chapter One")
        val firstRealChapter = chapters.firstOrNull { chapter ->
            chapterTitlePattern.containsMatchIn(chapter.title)
        }
        
        // Fall back to first chapter if no proper chapter titles found
        return firstRealChapter?.id ?: chapters.firstOrNull()?.id
    }
    
    /**
     * Get the very first chapter (including intro/preface).
     */
    suspend fun firstChapterIdIncludingIntro(bookId: Long): Long? =
        bookRepository.chapters(bookId).first().minByOrNull { it.orderIndex }?.id

    /** T4.2: Recap paragraph for "Previously on…" (empty if first chapter). */
    suspend fun getRecapParagraph(bookId: Long, chapterId: Long): String {
        val summaries = getRecapUseCase.getLastNSummaries(bookId, chapterId, 3)
        return getRecapUseCase.compressToParagraph(summaries) // Now async with LLM support
    }

    class Factory(
        private val bookRepository: BookRepository,
        private val getRecapUseCase: GetRecapUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(bookRepository, getRecapUseCase) as T
    }
}
