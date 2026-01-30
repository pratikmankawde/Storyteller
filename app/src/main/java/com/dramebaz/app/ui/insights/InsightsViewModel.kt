package com.dramebaz.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dramebaz.app.data.repositories.BookRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first

/** T9.3: Load themes and vocabulary from chapter analysisJson. */
class InsightsViewModel(private val bookRepository: BookRepository) : ViewModel() {
    private val gson = Gson()
    data class Insights(val themes: String, val vocabulary: String)

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
