package com.dramebaz.app.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.data.db.AppDatabase
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow

class CharactersViewModel(private val db: AppDatabase) : ViewModel() {
    private val gson = Gson()

    fun characters(bookId: Long): Flow<List<com.dramebaz.app.data.db.Character>> =
        db.characterDao().getByBookId(bookId)

    /**
     * Get dialog counts by speaker name from all chapter analyses.
     * Returns a map of speaker name (lowercase) to dialog count.
     */
    suspend fun getDialogCountsBySpeaker(bookId: Long): Map<String, Int> {
        val chapters = db.chapterDao().getChaptersList(bookId)
        val dialogCounts = mutableMapOf<String, Int>()

        for (chapter in chapters) {
            val analysisJson = chapter.fullAnalysisJson ?: continue
            try {
                val analysis = gson.fromJson(analysisJson, ChapterAnalysisResponse::class.java)
                analysis.dialogs?.forEach { dialog ->
                    val speaker = dialog.speaker.lowercase()
                    dialogCounts[speaker] = (dialogCounts[speaker] ?: 0) + 1
                }
            } catch (e: Exception) {
                // Skip chapters with invalid JSON
            }
        }

        return dialogCounts
    }

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CharactersViewModel(db) as T
    }
}
