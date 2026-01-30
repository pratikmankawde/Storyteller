package com.dramebaz.app.ui.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val importUseCase: ImportBookUseCase
) : ViewModel() {

    val books: StateFlow<List<Book>> = bookRepository.allBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Delete a book and all its chapters from the library.
     */
    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.deleteBookWithChapters(book.id)
        }
    }

    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri) ?: return@launch
            // Use display name from URI so the loaded book shows the correct title (e.g. "Space story" not "import_123")
            val displayName = getDisplayName(context, uri)
            val ext = when {
                displayName.endsWith(".pdf", true) -> "pdf"
                displayName.endsWith(".epub", true) -> "epub"
                else -> context.contentResolver.getType(uri)?.split("/")?.lastOrNull() ?: "txt"
            }
            val name = if (displayName.isNotBlank()) {
                // Sanitize: keep only safe filename chars; fallback to import_ timestamp if empty
                val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                if (safe.isNotBlank()) safe else "import_${System.currentTimeMillis()}.$ext"
            } else {
                "import_${System.currentTimeMillis()}.$ext"
            }
            val file = File(context.filesDir, name)
            file.outputStream().use { stream.copyTo(it) }
            importUseCase.importFromFile(context, file.absolutePath, when {
                name.endsWith(".pdf", true) -> "pdf"
                name.endsWith(".epub", true) -> "epub"
                else -> "txt"
            })
        }
    }

    private fun getDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)?.trim()
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        // Fallback 1: document ID on Android 8+ is sometimes the full path (e.g. "raw:/storage/.../Download/My book.pdf")
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val lastSlash = docId.lastIndexOf('/')
            if (lastSlash >= 0 && lastSlash < docId.length - 1) {
                val raw = docId.substring(lastSlash + 1).trim()
                val name = Uri.decode(raw)
                if (name.isNotBlank()) return name
            }
        }
        // Fallback 2: last path segment of URI if it looks like a filename
        val last = uri.lastPathSegment ?: return ""
        val decoded = Uri.decode(last)
        return if (decoded.contains(".") && decoded.length in 2..200) decoded else ""
    }

    class Factory(
        private val bookRepository: BookRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val importUseCase = ImportBookUseCase(bookRepository)
            return LibraryViewModel(bookRepository, importUseCase) as T
        }
    }
}
