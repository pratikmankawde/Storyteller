package com.dramebaz.app

import android.app.Application
import com.dramebaz.app.ai.tts.SherpaTtsEngine
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.audio.PageAudioStorage
import com.dramebaz.app.data.db.createAppDatabase
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
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

    val db: AppDatabase by lazy { createAppDatabase(this) }
    val bookRepository: BookRepository by lazy {
        BookRepository(db.bookDao(), db.chapterDao())
    }
    val importBookUseCase: ImportBookUseCase by lazy { ImportBookUseCase(bookRepository) }
    val getRecapUseCase: GetRecapUseCase by lazy { GetRecapUseCase(bookRepository, this) }
    /** TTS engine; initialized on app load by SplashActivity (splash screen shown during load). */
    val ttsEngine: SherpaTtsEngine by lazy { SherpaTtsEngine(this) }
    /** Persistent storage for generated page audio (book/chapter/page) so it can be reused. */
    val pageAudioStorage: PageAudioStorage by lazy { PageAudioStorage(this) }
    /** AUG-043: Per-segment audio generator for character-specific TTS. */
    val segmentAudioGenerator: SegmentAudioGenerator by lazy {
        SegmentAudioGenerator(
            ttsEngine,
            pageAudioStorage,
            db.characterDao(),
            db.characterPageMappingDao()
        )
    }

    // Keep ttsStub for backward compatibility during transition
    @Deprecated("Use ttsEngine instead", ReplaceWith("ttsEngine"))
    val ttsStub: SherpaTtsEngine get() = ttsEngine

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "Application onCreate() - Initializing Dramebaz")
        QwenStub.setApplicationContext(applicationContext)
        // TTS is loaded by SplashActivity; LLM (ONNX) initializes lazily on first use.

        // Initialize analysis queue manager for auto-analysis after import
        AnalysisQueueManager.initialize(applicationContext, bookRepository, db)
        // AUG-043: Set segment audio generator for initial audio generation
        AnalysisQueueManager.setSegmentAudioGenerator(segmentAudioGenerator)

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
        // Avoid duplicate imports on subsequent launches.
        val existing = bookRepository.findBookByTitle(title)
        if (existing != null) {
            AppLogger.d(tag, "Book '$title' already exists, skipping seed")
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
            importBookUseCase.importFromFile(applicationContext, path, format)
            AppLogger.logPerformance(tag, "Import book '$title'", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "Successfully seeded book: $title")
        }.onFailure { e ->
            AppLogger.w(tag, "Failed to seed book '$title'", e)
        }
    }
}
