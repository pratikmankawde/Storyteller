package com.dramebaz.app.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.db.AppDatabase

class CharacterDetailViewModel(private val db: AppDatabase) : ViewModel() {
    suspend fun getCharacter(id: Long) = db.characterDao().getById(id)

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharacterDetailViewModel(db) as T
    }
}
