package com.dramebaz.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.repositories.BookRepository
import kotlinx.coroutines.flow.first

class BookDetailViewModel(private val bookRepository: BookRepository) : ViewModel() {
    suspend fun getBook(id: Long) = bookRepository.getBook(id)
    suspend fun firstChapterId(bookId: Long): Long? =
        bookRepository.chapters(bookId).first().minByOrNull { it.orderIndex }?.id

    /**
     * FAV-001: Toggle favorite status for a book.
     * Returns the new favorite status.
     */
    suspend fun toggleFavorite(bookId: Long): Boolean {
        return bookRepository.toggleFavorite(bookId)
    }

    class Factory(private val bookRepository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BookDetailViewModel(bookRepository) as T
    }
}
