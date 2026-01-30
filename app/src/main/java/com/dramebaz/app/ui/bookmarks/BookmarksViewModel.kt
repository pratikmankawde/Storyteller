package com.dramebaz.app.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

class BookmarksViewModel(private val db: AppDatabase) : ViewModel() {
    fun bookmarks(bookId: Long): Flow<List<com.dramebaz.app.data.db.Bookmark>> =
        db.bookmarkDao().getByBookId(bookId)

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookmarksViewModel(db) as T
    }
}
