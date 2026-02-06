package com.dramebaz.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.data.repositories.BookRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first

/** T9.3: Load themes and vocabulary from chapter analysisJson. Extended analysis is lazy-loaded when Insights is opened. */
class InsightsViewModel(private val bookRepository: BookRepository) : ViewModel() {
    private val gson = Gson()
    data class Insights(val themes: String, val vocabulary: String)

    /**
     * If any chapter has fullAnalysisJson but null analysisJson, run extended analysis for the first such chapter
     * and save. Call this when Insights tab is opened so themes/vocabulary appear without blocking reader.
     */
    suspend fun ensureExtendedAnalysisForFirstNeeding(bookId: Long): Boolean {
        val chapters = bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
        val ch = chapters.firstOrNull { it.fullAnalysisJson != null && it.body.length > 50 && it.analysisJson.isNullOrBlank() } ?: return false
        val extendedJson = LlmService.extendedAnalysisJson(ch.body)
        if (extendedJson.isNullOrBlank()) return false
        bookRepository.updateChapter(ch.copy(analysisJson = extendedJson))
        return true
    }

    suspend fun insightsForBook(bookId: Long): Insights {
        val chapters = bookRepository.chapters(bookId).first()
        val themes = mutableSetOf<String>()
        val vocab = mutableListOf<String>()
        for (ch in chapters) {
            ch.analysisJson?.let { json ->
                try {
                    val obj = gson.fromJson(json, JsonObject::class.java) ?: return@let
                    obj.getAsJsonArray("themes")?.forEach { themes.add(it.asString) }
                    obj.getAsJsonArray("vocabulary")?.forEach { el ->
                        (el as? JsonObject)?.get("word")?.asString?.let { vocab.add(it) }
                    }
                } catch (_: Exception) { }
            }
        }
        return Insights(themes.joinToString(", ").ifEmpty { "" }, vocab.distinct().take(20).joinToString(", "))
    }

    class Factory(private val bookRepository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InsightsViewModel(bookRepository) as T
    }
}
