package com.dramebaz.app

import android.app.Application
import android.content.Context
import com.dramebaz.app.ai.tts.SherpaTtsEngine
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.audio.PageAudioStorage
import com.dramebaz.app.data.db.createAppDatabase
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.data.repositories.SettingsRepository
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
import com.dramebaz.app.domain.usecases.AudioRegenerationManager
import com.dramebaz.app.domain.usecases.GetRecapUseCase
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DramebazApplication : Application() {
    private val tag = "DramebazApplication"
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val PREFS_NAME = "dramebaz_prefs"
        private const val KEY_DELETED_DEMO_BOOKS = "deleted_demo_books"

        /** Demo book titles that will be auto-seeded if found in Downloads */
        val DEMO_BOOK_TITLES = setOf("Space story", "Demo story 2")
    }

    val db: AppDatabase by lazy { createAppDatabase(this) }
    val bookRepository: BookRepository by lazy {
        BookRepository(db.bookDao(), db.chapterDao())
    }
    /** SETTINGS-001: Settings repository for managing app settings. */
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(db.appSettingsDao())
    }
    /** SUMMARY-002: BookSeriesDao for multi-book series support. */
    val bookSeriesDao by lazy { db.bookSeriesDao() }
    val importBookUseCase: ImportBookUseCase by lazy { ImportBookUseCase(bookRepository) }
    /** SUMMARY-001/SUMMARY-002: Enhanced GetRecapUseCase with time-aware and series-aware recaps */
    val getRecapUseCase: GetRecapUseCase by lazy {
        GetRecapUseCase(
            bookRepository = bookRepository,
            context = this,
            readingSessionDao = db.readingSessionDao(),
            characterDao = db.characterDao(),
            bookDao = db.bookDao()  // SUMMARY-002: For series-aware recaps
        )
    }
    /** TTS engine; initialized on app load by SplashActivity (splash screen shown during load). */
    val ttsEngine: SherpaTtsEngine by lazy { SherpaTtsEngine(this) }
    /** Persistent storage for generated page audio (book/chapter/page) so it can be reused. */
    val pageAudioStorage: PageAudioStorage by lazy { PageAudioStorage(this) }
    /** AUG-043: Per-segment audio generator for character-specific TTS.
     *  NARRATOR-002: Now uses bookDao for per-book narrator voice settings. */
    val segmentAudioGenerator: SegmentAudioGenerator by lazy {
        SegmentAudioGenerator(
            ttsEngine,
            pageAudioStorage,
            db.characterDao(),
            db.characterPageMappingDao(),
            db.bookDao()
        )
    }

    // Keep ttsStub for backward compatibility during transition
    @Deprecated("Use ttsEngine instead", ReplaceWith("ttsEngine"))
    val ttsStub: SherpaTtsEngine get() = ttsEngine

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "Application onCreate() - Initializing Dramebaz")
        LlmService.setApplicationContext(applicationContext)
        // TTS is loaded by SplashActivity; LLM (ONNX) initializes lazily on first use.

        // Initialize analysis queue manager for auto-analysis after import
        AnalysisQueueManager.initialize(applicationContext, bookRepository, db, settingsRepository)
        // AUG-043: Set segment audio generator for initial audio generation
        AnalysisQueueManager.setSegmentAudioGenerator(segmentAudioGenerator)

        // AUDIO-REGEN-001: Initialize audio regeneration manager for voice changes
        AudioRegenerationManager.initialize(
            applicationContext,
            db,
            segmentAudioGenerator,
            pageAudioStorage
        )

        // SETTINGS-001: Load settings on app startup
        appScope.launch {
            settingsRepository.loadSettings()
            // NARRATOR-003: Initialize global narrator speaker ID from settings
            val narratorSettings = settingsRepository.narratorSettings.value
            segmentAudioGenerator.setGlobalNarratorSpeakerId(narratorSettings.speakerId)
            AppLogger.d(tag, "Global narrator speaker ID initialized to: ${narratorSettings.speakerId}")
        }

        // Seed demo books so they always appear in the library if the
        // corresponding PDF files exist on the device (e.g. in Downloads).
        appScope.launch {
            AppLogger.d(tag, "Seeding demo books")
            trySeedDemoBooks()
        }

        AppLogger.i(tag, "Application initialization complete")
    }

    private suspend fun trySeedDemoBooks() {
        // Note: ImportBookUseCase uses file.nameWithoutExtension for title
        seedIfMissing(
            title = "Space story",
            path = "/storage/emulated/0/Download/Space story.pdf",
            format = "pdf"
        )
        seedIfMissing(
            title = "Demo story 2",
            path = "/storage/emulated/0/Download/Demo story 2.pdf",
            format = "pdf"
        )
    }

    private suspend fun seedIfMissing(title: String, path: String, format: String) {
        // Check if user previously deleted this demo book - don't re-seed
        if (isDemoBookDeleted(title)) {
            AppLogger.d(tag, "Book '$title' was deleted by user, skipping seed")
            return
        }
        // Avoid duplicate imports on subsequent launches.
        val existing = bookRepository.findBookByTitle(title)
        if (existing != null) {
            AppLogger.d(tag, "Book '$title' already exists, skipping seed")
            // Check if analysis is incomplete and re-trigger if needed
            if (!AnalysisQueueManager.isBookAnalyzed(existing.id)) {
                AppLogger.i(tag, "Book '$title' exists but analysis incomplete, re-triggering analysis")
                AnalysisQueueManager.enqueueBook(existing.id)
            }
            return
        }
        // Skip seed when file is not on device (e.g. demo PDF not in Downloads) to avoid error logs.
        if (!File(path).exists()) {
            AppLogger.d(tag, "Seed file not found, skipping: $path")
            return
        }
        AppLogger.d(tag, "Attempting to seed book: title=$title, path=$path, format=$format")
        runCatching {
            val startTime = System.currentTimeMillis()
            val bookId = importBookUseCase.importFromFile(applicationContext, path, format)
            AppLogger.logPerformance(tag, "Import book '$title'", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "Successfully seeded book: $title (bookId=$bookId)")

            // Auto-trigger analysis for demo books
            if (bookId > 0) {
                AppLogger.i(tag, "Triggering analysis for demo book '$title' (bookId=$bookId)")
                AnalysisQueueManager.enqueueBook(bookId)
            }
        }.onFailure { e ->
            AppLogger.w(tag, "Failed to seed book '$title'", e)
        }
    }

    /**
     * Check if a demo book was previously deleted by the user.
     */
    private fun isDemoBookDeleted(title: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedBooks = prefs.getStringSet(KEY_DELETED_DEMO_BOOKS, emptySet()) ?: emptySet()
        return title in deletedBooks
    }

    /**
     * Mark a demo book as deleted so it won't be re-seeded on next app launch.
     * Called from LibraryViewModel when user deletes a demo book.
     */
    fun markDemoBookAsDeleted(title: String) {
        if (title !in DEMO_BOOK_TITLES) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedBooks = prefs.getStringSet(KEY_DELETED_DEMO_BOOKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        deletedBooks.add(title)
        prefs.edit().putStringSet(KEY_DELETED_DEMO_BOOKS, deletedBooks).apply()
        AppLogger.d(tag, "Marked demo book '$title' as deleted")
    }
}
