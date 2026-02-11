package com.dramebaz.app.ui.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.data.audio.PageAudioStorage
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.ReadingSessionDao
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import com.dramebaz.app.domain.exceptions.ImportException
import com.dramebaz.app.ai.llm.services.AnalysisForegroundService
import com.dramebaz.app.ai.llm.tasks.ChapterAnalysisTask
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LibraryViewModel"

class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val importUseCase: ImportBookUseCase,
    private val pageAudioStorage: PageAudioStorage,
    private val readingSessionDao: ReadingSessionDao,
    private val appContext: Context
) : ViewModel() {

    val books: StateFlow<List<Book>> = bookRepository.allBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importError = MutableSharedFlow<String>()
    val importError: SharedFlow<String> = _importError.asSharedFlow()

    private val _isImporting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isImporting: kotlinx.coroutines.flow.StateFlow<Boolean> = _isImporting.asStateFlow()

    /**
     * Delete a book and all its associated data:
     * - Cancel any running analysis jobs for this book
     * - Audio files (page audio and segment audio)
     * - Analysis checkpoints (Gemma and 3-pass)
     * - Reading session (if it references this book)
     * - Database records (chapters, characters, bookmarks, etc. via CASCADE)
     */
    /**
     * FAV-001: Toggle favorite status for a book.
     * Returns the new favorite status.
     */
    fun toggleFavorite(book: Book, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val newStatus = bookRepository.toggleFavorite(book.id)
            withContext(Dispatchers.Main) {
                onComplete(newStatus)
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = book.id
            AppLogger.i(TAG, "Deleting book $bookId: ${book.title}")

            // 0. Cancel any running analysis jobs for this book
            AnalysisQueueManager.cancelAnalysisForBook(bookId)
            AnalysisForegroundService.cancelForBook(appContext, bookId)
            AppLogger.d(TAG, "Cancelled analysis jobs for book $bookId")

            // 1. Delete audio files
            val audioDeleted = pageAudioStorage.deleteAudioForBook(bookId)
            AppLogger.d(TAG, "Deleted $audioDeleted audio files for book $bookId")

            // 2. Delete analysis checkpoints
            val checkpointsDeleted = ChapterAnalysisTask.deleteCheckpointsForBook(appContext, bookId)
            AppLogger.d(TAG, "Deleted $checkpointsDeleted analysis checkpoints")

            // 3. Clear reading session if it references this book
            val session = readingSessionDao.get()
            if (session?.bookId == bookId) {
                readingSessionDao.clear()
                AppLogger.d(TAG, "Cleared reading session for book $bookId")
            }

            // 4. Delete from database (CASCADE handles chapters, characters, etc.)
            bookRepository.deleteBookWithChapters(bookId)

            // 5. Mark demo books as deleted so they don't get re-seeded on next app launch
            if (book.title in DramebazApplication.DEMO_BOOK_TITLES) {
                (appContext.applicationContext as? DramebazApplication)?.markDemoBookAsDeleted(book.title)
            }

            // 6. Clear from cancelled set now that deletion is complete
            AnalysisQueueManager.clearCancelledBook(bookId)
            AppLogger.i(TAG, "Book $bookId deleted successfully")
        }
    }

    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                val stream = context.contentResolver.openInputStream(uri) ?: run {
                    _isImporting.value = false
                    return@launch
                }
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
            val bookId = importUseCase.importFromFile(context, file.absolutePath, when {
                name.endsWith(".pdf", true) -> "pdf"
                name.endsWith(".epub", true) -> "epub"
                else -> "txt"
            })
                // Auto-analyze first chapter on book load for quick initial insights
                AnalysisQueueManager.enqueueFirstChapter(bookId)
                _isImporting.value = false
            } catch (e: ImportException) {
                _isImporting.value = false
                _importError.emit(e.message ?: "Import failed")
            } catch (e: Exception) {
                _isImporting.value = false
                _importError.emit("Import failed: ${e.message}")
            }
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
        private val app: DramebazApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val importUseCase = ImportBookUseCase(app.bookRepository)
            return LibraryViewModel(
                bookRepository = app.bookRepository,
                importUseCase = importUseCase,
                pageAudioStorage = app.pageAudioStorage,
                readingSessionDao = app.db.readingSessionDao(),
                appContext = app.applicationContext
            ) as T
        }
    }
}
