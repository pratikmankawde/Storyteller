package com.dramebaz.app.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

class CharactersViewModel(private val db: AppDatabase) : ViewModel() {
    fun characters(bookId: Long): Flow<List<com.dramebaz.app.data.db.Character>> =
        db.characterDao().getByBookId(bookId)

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharactersViewModel(db) as T
    }
}
