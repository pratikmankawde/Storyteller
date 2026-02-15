package com.dramebaz.app.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.ChapterSummary
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow

class CharactersViewModel(private val db: AppDatabase) : ViewModel() {
    private val gson = Gson()

    fun characters(bookId: Long): Flow<List<com.dramebaz.app.data.db.Character>> =
        db.characterDao().getByBookId(bookId)

    /**
     * CHARACTER-002: Observe chapter changes for real-time dialog count updates.
     * Returns a Flow that emits when chapters are updated (e.g., after analysis).
     * BLOB-FIX: Uses lightweight summaries to avoid CursorWindow overflow.
     */
    fun observeChapterChanges(bookId: Long): Flow<List<ChapterSummary>> =
        db.chapterDao().getSummariesByBookId(bookId)

    /**
     * Get dialog counts by speaker name from all chapter analyses.
     * Returns a map of speaker name (lowercase) to dialog count.
     * BLOB-FIX: Uses lightweight projection to avoid CursorWindow overflow.
     */
    suspend fun getDialogCountsBySpeaker(bookId: Long): Map<String, Int> {
        // Use lightweight projection that only loads fullAnalysisJson
        val analyses = db.chapterDao().getAnalysesOnly(bookId)
        val dialogCounts = mutableMapOf<String, Int>()

        for (analysis in analyses) {
            val analysisJson = analysis.fullAnalysisJson ?: continue
            try {
                val parsed = gson.fromJson(analysisJson, ChapterAnalysisResponse::class.java)
                parsed.dialogs?.forEach { dialog ->
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
