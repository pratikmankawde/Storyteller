package com.dramebaz.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.repositories.BookRepository

class BookDetailViewModel(private val bookRepository: BookRepository) : ViewModel() {
    suspend fun getBook(id: Long) = bookRepository.getBook(id)

    /**
     * BLOB-FIX: Get first chapter ID using lightweight query to avoid CursorWindow overflow.
     */
    suspend fun firstChapterId(bookId: Long): Long? =
        bookRepository.getFirstChapterId(bookId)

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
