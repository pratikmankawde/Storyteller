package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.CharacterStub
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.executor.AnalysisExecutor
import com.dramebaz.app.ai.llm.pipeline.AccumulatedCharacterData
import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.pipeline.TextCleaner
import com.dramebaz.app.ai.llm.pipeline.ThemeAnalysisPass
import com.dramebaz.app.ai.llm.prompts.ThemeAnalysisInput
import com.dramebaz.app.ai.llm.services.AnalysisForegroundService
import com.dramebaz.app.ai.llm.tasks.BatchedChapterAnalysisTask
import com.dramebaz.app.ai.llm.tasks.ChapterAnalysisTask
import com.dramebaz.app.ai.llm.tasks.TraitsExtractionTask
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.db.AnalysisState as BookAnalysisState
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.FeatureSettings
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.data.repositories.SettingsRepository
import com.dramebaz.app.domain.theme.GenreCoverMapper
import com.dramebaz.app.pdf.PdfChapterDetector
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Book Analysis Workflow encapsulates all the business logic for analyzing a book.
 *
 * This includes:
 * - First chapter analysis (quick preview during import)
 * - Full book analysis (all chapters)
 * - Single chapter analysis
 * - Character merging
 * - Audio generation
 * - Traits extraction
 *
 * The workflow uses AnalysisForegroundService for wake lock and notification,
 * but does NOT manage job queues - that's the QueueManager's responsibility.
 */
class BookAnalysisWorkflow(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val tag = "BookAnalysisWorkflow"
    private val gson = Gson()

    // Status tracking per book
    private val _analysisStatus = MutableStateFlow<Map<Long, AnalysisStatus>>(emptyMap())
    val analysisStatus: StateFlow<Map<Long, AnalysisStatus>> = _analysisStatus.asStateFlow()

    // External dependency (can be set after construction)
    var segmentAudioGenerator: SegmentAudioGenerator? = null
        set(value) {
            field = value
            // Initialize incremental audio enqueuer when generator is set
            value?.let {
                incrementalAudioEnqueuer = IncrementalAudioEnqueuer(it, CoroutineScope(Dispatchers.IO))
            }
        }

    // INCREMENTAL-002: Enqueuer for audio generation after each batch
    private var incrementalAudioEnqueuer: IncrementalAudioEnqueuer? = null

    // Cancellation tracking
    private val cancelledBookIds = mutableSetOf<Long>()

    @Volatile
    private var isPaused = false

    // Executor for LLM tasks
    private var analysisExecutor: AnalysisExecutor? = null

    enum class AnalysisState { PENDING, ANALYZING, COMPLETE, FAILED }

    data class AnalysisStatus(
        val state: AnalysisState,
        val progress: Int = 0, // 0-100
        val message: String = "",
        val analyzedChapters: Int = 0,
        val totalChapters: Int = 0
    )

    /**
     * Callback interface for workflow progress updates.
     */
    interface ProgressCallback {
        fun onProgress(bookId: Long, status: AnalysisStatus)
    }

    /**
     * Analyze only the first chapter of a book.
     * Provides quick initial analysis on book load without processing the entire book.
     *
     * @param bookId The book to analyze
     * @param progressCallback Optional callback for progress updates
     * @return true if analysis was successful
     */
    suspend fun analyzeFirstChapter(
        bookId: Long,
        progressCallback: ProgressCallback? = null
    ): Boolean {
        if (isBookCancelled(bookId)) return false

        AppLogger.i(tag, "Starting first-chapter analysis for book $bookId")

        // Start foreground service for wake lock and notification
        AnalysisForegroundService.startSimple(context, "Analyzing first chapter...")

        try {
            val chapters = bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
            val totalChapters = chapters.size

            if (chapters.isEmpty()) {
                val failedStatus = AnalysisStatus(AnalysisState.FAILED, 0, "No chapters found")
                updateStatus(bookId, failedStatus)
                progressCallback?.onProgress(bookId, failedStatus)
                bookRepository.markAnalysisFailed(bookId, "No chapters found")
                return false
            }

            // Initialize analysis state in book entity
            bookRepository.initializeAnalysisState(bookId, totalChapters)

            val firstChapter = chapters.first()

            // Skip if already analyzed or too short
            if (!firstChapter.fullAnalysisJson.isNullOrBlank()) {
                AppLogger.d(tag, "Book $bookId first chapter already analyzed")
                val completeStatus = AnalysisStatus(AnalysisState.COMPLETE, 100, "First chapter analyzed", 1, totalChapters)
                updateStatus(bookId, completeStatus)
                progressCallback?.onProgress(bookId, completeStatus)
                // Update database to prevent re-analysis on next app launch
                bookRepository.updateAnalysisProgress(
                    bookId = bookId,
                    state = BookAnalysisState.COMPLETED,
                    progress = 100,
                    analyzedCount = 1,
                    totalChapters = totalChapters,
                    message = "First chapter analyzed"
                )
                return true
            }

            if (firstChapter.body.length <= 50) {
                AppLogger.d(tag, "Book $bookId first chapter too short, skipping analysis")
                val shortStatus = AnalysisStatus(AnalysisState.COMPLETE, 0, "First chapter too short", 0, totalChapters)
                updateStatus(bookId, shortStatus)
                progressCallback?.onProgress(bookId, shortStatus)
                // Update database to prevent re-analysis on next app launch
                bookRepository.updateAnalysisProgress(
                    bookId = bookId,
                    state = BookAnalysisState.COMPLETED,
                    progress = 100,
                    analyzedCount = 0,
                    totalChapters = totalChapters,
                    message = "First chapter too short"
                )
                return true
            }

            val chapterLabel = firstChapter.title.ifBlank { "Chapter 1" }
            AppLogger.i(tag, "FIRST-CHAPTER-ANALYSIS: Starting for book $bookId - $chapterLabel")

            val status = AnalysisStatus(AnalysisState.ANALYZING, 0, "Analyzing $chapterLabel (1/$totalChapters)", 0, totalChapters)
            updateStatus(bookId, status)
            progressCallback?.onProgress(bookId, status)

            // Update foreground notification
            AnalysisForegroundService.updateProgress(context, "Analyzing $chapterLabel", 0)

            // Update book entity progress
            bookRepository.updateAnalysisProgress(
                bookId = bookId,
                state = BookAnalysisState.ANALYZING,
                progress = 0,
                analyzedCount = 0,
                totalChapters = totalChapters,
                message = "Analyzing $chapterLabel"
            )

            // INCREMENTAL-001: Use batched analysis with incremental audio generation
            val success = analyzeFirstChapterIncremental(
                bookId = bookId,
                chapter = firstChapter,
                totalChapters = totalChapters,
                progressCallback = progressCallback
            )

            if (!success || isBookCancelled(bookId) || isPaused) {
                AppLogger.i(tag, "First chapter analysis interrupted for book $bookId")
                return false
            }

            // COVER-001: Trigger background book-level details analysis (genre + placeholder cover).
            val sampleText = getPage1Text(firstChapter)
            if (!sampleText.isNullOrBlank()) {
                val analysisTitle = firstChapter.title.ifBlank { "Chapter 1" }
                triggerBackgroundBookDetailsAnalysis(
                    bookId = bookId,
                    title = analysisTitle,
                    sampleText = sampleText
                )
            }

            // Mark as partially complete (1 chapter done)
            val completeStatus = AnalysisStatus(AnalysisState.COMPLETE, 100, "First chapter analyzed", 1, totalChapters)
            updateStatus(bookId, completeStatus)
            progressCallback?.onProgress(bookId, completeStatus) // Forward completion to queue manager
            bookRepository.updateAnalysisProgress(
                bookId = bookId,
                state = BookAnalysisState.COMPLETED,
                progress = 100,
                analyzedCount = 1,
                totalChapters = totalChapters,
                message = "First chapter analyzed"
            )
            AppLogger.i(tag, "FIRST-CHAPTER-ANALYSIS: Complete for book $bookId")
            return true

        } finally {
            // Stop foreground service
            AnalysisForegroundService.stop(context)
        }
    }

    /**
     * Analyze all chapters in a book (for full book analysis).
     * Typically called after first-chapter analysis completes successfully.
     *
     * @param bookId The book to analyze
     * @param progressCallback Optional callback for progress updates
     * @return true if all chapters analyzed successfully
     */
    suspend fun analyzeAllChapters(
        bookId: Long,
        progressCallback: ProgressCallback? = null
    ): Boolean {
        if (isBookCancelled(bookId)) return false

        AppLogger.i(tag, "Starting full book analysis for book $bookId")

        val chapters = bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
        val totalChapters = chapters.size

        if (chapters.isEmpty()) {
            val failedStatus = AnalysisStatus(AnalysisState.FAILED, 0, "No chapters found")
            updateStatus(bookId, failedStatus)
            progressCallback?.onProgress(bookId, failedStatus)
            bookRepository.markAnalysisFailed(bookId, "No chapters found")
            return false
        }

        // Initialize analysis state in book entity
        bookRepository.initializeAnalysisState(bookId, totalChapters)

        // Filter to only chapters needing analysis
        val chaptersToAnalyze = chapters.filter {
            it.fullAnalysisJson.isNullOrBlank() && it.body.length > 50
        }

        if (chaptersToAnalyze.isEmpty()) {
            val alreadyCompleteStatus = AnalysisStatus(AnalysisState.COMPLETE, 100, "All chapters already analyzed", totalChapters, totalChapters)
            updateStatus(bookId, alreadyCompleteStatus)
            progressCallback?.onProgress(bookId, alreadyCompleteStatus)
            bookRepository.markAnalysisComplete(bookId, totalChapters)
            AppLogger.d(tag, "Book $bookId all chapters already analyzed")
            return true
        }

        AppLogger.i(tag, "AUTO-ANALYSIS: Starting for book $bookId, ${chaptersToAnalyze.size}/${totalChapters} chapters need analysis")
        val startStatus = AnalysisStatus(AnalysisState.ANALYZING, 0, "Starting analysis...", 0, totalChapters)
        updateStatus(bookId, startStatus)
        progressCallback?.onProgress(bookId, startStatus)

        var analyzedCount = totalChapters - chaptersToAnalyze.size // Count pre-analyzed chapters

        for ((index, chapter) in chaptersToAnalyze.withIndex()) {
            // Check if paused or cancelled
            if (isPaused || isBookCancelled(bookId)) {
                AppLogger.i(tag, "Analysis interrupted for book $bookId at chapter ${index + 1}/${chaptersToAnalyze.size}")
                return false
            }

            val chapterLabel = chapter.title.ifBlank { "Chapter ${chapters.indexOf(chapter) + 1}" }
            val overallProgress = ((index.toFloat() / chaptersToAnalyze.size) * 100).toInt()

            val status = AnalysisStatus(
                AnalysisState.ANALYZING,
                overallProgress,
                "Analyzing $chapterLabel (${index + 1}/${chaptersToAnalyze.size})",
                analyzedCount,
                totalChapters
            )
            updateStatus(bookId, status)
            progressCallback?.onProgress(bookId, status)

            // Update foreground notification
            AnalysisForegroundService.updateProgress(context, "Analyzing $chapterLabel", overallProgress)

            // Update book entity progress
            bookRepository.updateAnalysisProgress(
                bookId = bookId,
                state = BookAnalysisState.ANALYZING,
                progress = overallProgress,
                analyzedCount = analyzedCount,
                totalChapters = totalChapters,
                message = "Analyzing $chapterLabel"
            )

            // Analyze this chapter
            val success = analyzeChapter(bookId, chapter, chapters.indexOf(chapter), totalChapters, progressCallback)

            if (success) {
                analyzedCount++
                AppLogger.i(tag, "AUTO-ANALYSIS: Completed chapter ${analyzedCount}/$totalChapters for book $bookId")
            }
        }

        // Check if cancelled after loop
        if (isBookCancelled(bookId) || isPaused) {
            AppLogger.i(tag, "Analysis interrupted for book $bookId after chapter loop")
            return false
        }

        // Mark analysis complete
        val finalProgress = ((analyzedCount.toFloat() / totalChapters) * 100).toInt()
        val completeStatus = AnalysisStatus(AnalysisState.COMPLETE, finalProgress, "Analysis complete", analyzedCount, totalChapters)
        updateStatus(bookId, completeStatus)
        progressCallback?.onProgress(bookId, completeStatus) // Forward completion to queue manager
        bookRepository.markAnalysisComplete(bookId, totalChapters)
        AppLogger.i(tag, "AUTO-ANALYSIS: Complete for book $bookId. Analyzed $analyzedCount/$totalChapters chapters")

        // Global character merge after all chapters analyzed
        if (analyzedCount > 1 && !isBookCancelled(bookId) && !isPaused) {
            performGlobalCharacterMerge(bookId)
        }

        return true
    }

    /**
     * INCREMENTAL-001: Analyze first chapter with incremental processing and audio generation.
     *
     * If chapter has more than MIN_PAGES_FOR_INCREMENTAL pages:
     * 1. Process first 50% of pages in foreground with incremental audio generation
     * 2. Queue remaining 50% for background processing
     *
     * Audio generation is triggered after EACH batch completes via onBatchComplete callback.
     * Summary generation only happens after 100% of chapter is analyzed.
     */
    private suspend fun analyzeFirstChapterIncremental(
        bookId: Long,
        chapter: Chapter,
        totalChapters: Int,
        progressCallback: ProgressCallback?
    ): Boolean {
        val analysisStartTime = System.currentTimeMillis()
        val chapterLabel = chapter.title.ifBlank { "Chapter 1" }

        // Prepare cleaned pages
        val cleanedPages = prepareChapterPages(chapter)
        if (cleanedPages.isEmpty()) {
            AppLogger.w(tag, "No cleaned pages for chapter: ${chapter.title}")
            return false
        }

        val totalPages = cleanedPages.size
        AppLogger.i(tag, "INCREMENTAL-001: $totalPages pages prepared for chapter '$chapterLabel'")

        // Get incremental analysis percentage from settings
        val incrementalPercent = settingsRepository.featureSettings.value.incrementalAnalysisPagePercent
        AppLogger.d(tag, "INCREMENTAL-001: Using incrementalAnalysisPagePercent=$incrementalPercent% from settings")

        // Determine if we should use incremental processing
        val shouldSplit = totalPages > FeatureSettings.MIN_PAGES_FOR_INCREMENTAL && incrementalPercent < 100
        val initialPageCount = if (shouldSplit) {
            // Process configured percentage of pages initially
            (totalPages * incrementalPercent / 100).coerceAtLeast(1)
        } else {
            totalPages  // Process all pages if below threshold or percent is 100%
        }

        AppLogger.i(tag, "INCREMENTAL-001: Processing $initialPageCount/$totalPages pages initially ($incrementalPercent%, split=$shouldSplit)")

        // Create batch complete callback for incremental audio generation
        val audioCallback = incrementalAudioEnqueuer?.createBatchCompleteCallback(bookId, chapter.id)
            ?: { _: BatchedChapterAnalysisTask.BatchCompleteData -> }

        // Create BatchedChapterAnalysisTask with maxPagesToProcess for partial analysis
        val task = BatchedChapterAnalysisTask(
            bookId = bookId,
            chapterId = chapter.id,
            chapterTitle = chapterLabel,
            rawPages = cleanedPages,
            cacheDir = context.cacheDir,
            maxPagesToProcess = if (shouldSplit) initialPageCount else null,
            onBatchComplete = audioCallback
        )

        // Execute with progress tracking
        val model = LlmService.getModel()
        if (model == null) {
            AppLogger.e(tag, "LLM model not available for analysis")
            return false
        }

        val result = task.execute(model) { progress ->
            val status = AnalysisStatus(
                AnalysisState.ANALYZING,
                progress.percent / 2,  // First 50% of overall progress for initial pages
                "${progress.message} ($chapterLabel)",
                0,
                totalChapters
            )
            updateStatus(bookId, status)
            progressCallback?.onProgress(bookId, status)
            AnalysisForegroundService.updateProgress(context, progress.message, progress.percent / 2)
        }

        if (!result.success || isBookCancelled(bookId) || isPaused) {
            AppLogger.w(tag, "Initial analysis failed or was cancelled: ${result.error}")
            return false
        }

        // Handle results from initial analysis
        val isPartial = result.resultData[BatchedChapterAnalysisTask.KEY_IS_PARTIAL] as? Boolean ?: false
        val pagesProcessed = result.resultData[BatchedChapterAnalysisTask.KEY_PAGES_PROCESSED] as? Int ?: totalPages

        AppLogger.i(tag, "INCREMENTAL-001: Initial analysis complete. Pages: $pagesProcessed/$totalPages, partial=$isPartial")

        // Save partial results to database
        if (!saveIncrementalResults(bookId, chapter, result.resultData)) {
            AppLogger.w(tag, "Failed to save incremental results")
        }

        // If we split processing, queue the remaining pages for background analysis
        if (shouldSplit && pagesProcessed < totalPages) {
            AppLogger.i(tag, "INCREMENTAL-001: Queuing remaining ${totalPages - pagesProcessed} pages for background processing")
            queueRemainingPagesForBackground(bookId, chapter.id, cleanedPages, pagesProcessed)
        }

        val duration = System.currentTimeMillis() - analysisStartTime
        AppLogger.i(tag, "✅ INCREMENTAL-001: First chapter incremental analysis complete in ${duration}ms")

        return true
    }

    /**
     * Save incremental analysis results to database.
     */
    private suspend fun saveIncrementalResults(
        bookId: Long,
        chapter: Chapter,
        resultData: Map<String, Any>
    ): Boolean {
        try {
            val charactersJson = resultData[BatchedChapterAnalysisTask.KEY_CHARACTERS] as? String ?: return false

            val characterType = object : TypeToken<List<AccumulatedCharacterData>>() {}.type
            val characterDataList: List<AccumulatedCharacterData> = gson.fromJson(charactersJson, characterType)

            // Persist characters with dialogs
            if (characterDataList.isNotEmpty()) {
                persistCharactersWithDialogs(bookId, characterDataList)
            }

            // Create partial analysis response (no summary yet - that comes after 100% analysis)
            val characters = characterDataList.map { charData ->
                CharacterStub(
                    name = charData.name,
                    traits = charData.traits.takeIf { it.isNotEmpty() },
                    voiceProfile = charData.voiceProfile?.let { profile ->
                        mapOf(
                            "pitch" to profile.pitch,
                            "speed" to profile.speed,
                            "energy" to profile.energy,
                            "gender" to profile.gender
                        )
                    }
                )
            }

            val dialogs = characterDataList.flatMap { charData ->
                charData.dialogs.map { dialogWithPage ->
                    Dialog(
                        speaker = charData.name,
                        dialog = dialogWithPage.text,
                        emotion = dialogWithPage.emotion,
                        intensity = dialogWithPage.intensity
                    )
                }
            }

            // Save partial analysis (summary will be added after 100% completion)
            val partialResponse = ChapterAnalysisResponse(
                chapterSummary = null,  // No summary yet
                characters = characters,
                dialogs = dialogs,
                soundCues = emptyList()
            )

            bookRepository.updateChapter(chapter.copy(
                fullAnalysisJson = gson.toJson(partialResponse)
            ))

            // Merge characters
            mergeChapterCharacters(bookId, characters)

            AppLogger.i(tag, "Saved incremental results: ${characters.size} characters, ${dialogs.size} dialogs")
            return true

        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to save incremental results", e)
            return false
        }
    }

    /**
     * Queue remaining pages for background analysis.
     * This creates a background job to analyze the remaining portion of the chapter.
     */
    private fun queueRemainingPagesForBackground(
        bookId: Long,
        chapterId: Long,
        allPages: List<String>,
        startFromPage: Int
    ) {
        // TODO: Implement background job queue for remaining pages
        // For now, log that we would queue it
        AppLogger.i(tag, "INCREMENTAL-001: Would queue pages $startFromPage-${allPages.size - 1} for background processing")

        // The AnalysisQueueManager could be extended to support partial chapter analysis jobs
        // For now, the remaining analysis happens as part of full book analysis later
    }

    /**
     * Analyze a single chapter using the modular multi-pass workflow.
     */
    private suspend fun analyzeChapter(
        bookId: Long,
        chapter: Chapter,
        chapterIndex: Int,
        totalChapters: Int,
        progressCallback: ProgressCallback?
    ): Boolean {
        AppLogger.i(tag, "🔄 Analyzing chapter: ${chapter.title} (${chapter.body.length} chars)")
        val analysisStartTime = System.currentTimeMillis()

        // Step 1: Prepare cleaned pages
        val cleanedPages = prepareChapterPages(chapter)
        if (cleanedPages.isEmpty()) {
            AppLogger.w(tag, "No cleaned pages for chapter: ${chapter.title}")
            return false
        }
        AppLogger.d(tag, "Prepared ${cleanedPages.size} cleaned pages for analysis")

        // Step 2: Create and execute ChapterAnalysisTask with step completion callback
        val task = ChapterAnalysisTask(
            bookId = bookId,
            chapterId = chapter.id,
            cleanedPages = cleanedPages,
            chapterTitle = chapter.title,
            appContext = context,  // Enable checkpoint persistence
            onStepCompleted = { stepIndex, stepName, characters ->
                // Save characters to database after each step
                saveCharactersAfterStep(bookId, stepIndex, stepName, characters)
            }
        )

        val executor = getOrCreateExecutor()
        val chapterLabel = chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" }

        val result = executor.execute(
            task = task,
            options = AnalysisExecutor.ExecutionOptions(
                forceBackground = true,
                autoPersist = false
            ),
            onProgress = { progress ->
                val status = AnalysisStatus(
                    AnalysisState.ANALYZING,
                    progress.percent,
                    "${progress.message} (${progress.currentStep}/${progress.totalSteps})",
                    chapterIndex,
                    totalChapters
                )
                updateStatus(bookId, status)
                progressCallback?.onProgress(bookId, status)

                // Update notification
                AnalysisForegroundService.updateProgress(context, "${progress.message} - $chapterLabel", progress.percent)

                // Update book entity in database (skip final 100% - workflow handles completion state)
                if (progress.percent < 100) {
                    CoroutineScope(Dispatchers.IO).launch {
                        bookRepository.updateAnalysisProgress(
                            bookId = bookId,
                            state = BookAnalysisState.ANALYZING,
                            progress = progress.percent,
                            analyzedCount = chapterIndex,
                            totalChapters = totalChapters,
                            message = progress.message
                        )
                    }
                }
            }
        )

        // Check cancellation
        if (isBookCancelled(bookId) || isPaused) {
            AppLogger.i(tag, "Analysis interrupted for book $bookId")
            return false
        }

        if (!result.success) {
            AppLogger.w(tag, "Chapter analysis task failed: ${result.error}")
            return false
        }

        // Step 3: Handle results
        val analysisResult = handleChapterAnalysisResult(bookId, chapter, result.resultData, chapterIndex)

        if (analysisResult != null) {
            // Save analysis results
            bookRepository.updateChapter(chapter.copy(
                summaryJson = gson.toJson(analysisResult.first.chapterSummary),
                fullAnalysisJson = gson.toJson(analysisResult.first)
            ))

            // FIRST: Save full character data (with dialogs and speakerId) to database
            if (!isBookCancelled(bookId) && !isPaused) {
                val characterDataList = analysisResult.second
                if (characterDataList.isNotEmpty()) {
                    persistCharactersWithDialogs(bookId, characterDataList)
                }
            }

            // THEN: Merge characters (this updates traits/voice profiles without deleting dialogs)
            if (!isBookCancelled(bookId) && !isPaused) {
                mergeChapterCharacters(bookId, analysisResult.first.characters)
            }

            // Report 100% now that data is saved to database
            val completeStatus = AnalysisStatus(
                AnalysisState.ANALYZING, // Still in overall analyzing state for multi-chapter books
                100,
                "Chapter saved - $chapterLabel",
                chapterIndex + 1,
                totalChapters
            )
            updateStatus(bookId, completeStatus)
            progressCallback?.onProgress(bookId, completeStatus)

            // Generate audio for first page of first chapter only
            if (chapterIndex == 0 && !isBookCancelled(bookId) && !isPaused) {
                try {
                    generateInitialAudio(bookId, chapter, analysisResult.first.dialogs)
                } catch (e: Exception) {
                    AppLogger.w(tag, "Initial audio generation failed", e)
                }
            }

            val duration = System.currentTimeMillis() - analysisStartTime
            AppLogger.i(tag, "✅ Chapter analysis complete: ${chapter.title}, " +
                    "characters=${analysisResult.first.characters?.size ?: 0}, " +
                    "dialogs=${analysisResult.first.dialogs?.size ?: 0}, " +
                    "duration=${duration}ms")

            // Trigger background traits extraction
            if (!isBookCancelled(bookId) && !isPaused) {
                val characterDataList = analysisResult.second
                if (characterDataList.isNotEmpty()) {
                    triggerBackgroundTraitsExtraction(bookId, chapter.id, characterDataList, executor)
                }
            }

            return true
        }

        return false
    }

    // ==================== Helper Methods ====================

    private fun getOrCreateExecutor(): AnalysisExecutor {
        return analysisExecutor ?: AnalysisExecutor(context).also { analysisExecutor = it }
    }

    /**
     * Prepare chapter text by cleaning and splitting into pages.
     */
    private fun prepareChapterPages(chapter: Chapter): List<String> {
        // Try PDF pages first
        if (!chapter.pdfPagesJson.isNullOrBlank()) {
            try {
                val pdfPages = PdfChapterDetector.pdfPagesFromJson(chapter.pdfPagesJson)
                if (pdfPages.isNotEmpty()) {
                    return pdfPages.map { TextCleaner.cleanPage(it.text) }
                        .filter { it.length > 50 }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed to parse PDF pages, falling back to chapter body", e)
            }
        }

        // Fallback: split chapter body into ~10K char segments
        val cleanedText = TextCleaner.cleanPage(chapter.body)
        if (cleanedText.length <= 10000) {
            return listOf(cleanedText)
        }

        // Split at paragraph boundaries
        val paragraphs = TextCleaner.cleanAndSplitIntoParagraphs(listOf(chapter.body))
        val pages = mutableListOf<String>()
        var currentPage = StringBuilder()

        for (para in paragraphs) {
            if (currentPage.length + para.length > 10000 && currentPage.isNotEmpty()) {
                pages.add(currentPage.toString())
                currentPage = StringBuilder()
            }
            if (currentPage.isNotEmpty()) currentPage.append("\n\n")
            currentPage.append(para)
        }
        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toString())
        }

        return pages
    }

    /**
     * Handle the result from ChapterAnalysisTask and convert to ChapterAnalysisResponse.
     */
    private fun handleChapterAnalysisResult(
        bookId: Long,
        chapter: Chapter,
        resultData: Map<String, Any>,
        chapterIndex: Int
    ): Pair<ChapterAnalysisResponse, List<AccumulatedCharacterData>>? {
        try {
            val charactersJson = resultData[ChapterAnalysisTask.KEY_CHARACTERS] as? String
                ?: return null

            val characterType = object : TypeToken<List<AccumulatedCharacterData>>() {}.type
            val characterDataList: List<AccumulatedCharacterData> = gson.fromJson(charactersJson, characterType)

            // Convert to CharacterStub list
            val characters = characterDataList.map { charData ->
                CharacterStub(
                    name = charData.name,
                    traits = charData.traits.takeIf { it.isNotEmpty() },
                    voiceProfile = charData.voiceProfile?.let { profile ->
                        mapOf(
                            "pitch" to profile.pitch,
                            "speed" to profile.speed,
                            "energy" to profile.energy,
                            "gender" to profile.gender,
                            "age" to profile.age,
                            "tone" to profile.tone,
                            "accent" to profile.accent
                        )
                    }
                )
            }

            // Extract dialogs from character data
            val dialogs = characterDataList.flatMap { charData ->
                charData.dialogs.map { dialogWithPage ->
                    Dialog(
                        speaker = charData.name,
                        dialog = dialogWithPage.text,
                        emotion = dialogWithPage.emotion,
                        intensity = dialogWithPage.intensity
                    )
                }
            }

            // Create chapter summary
            val chapterSummary = ChapterSummary(
                title = chapter.title,
                shortSummary = "Chapter with ${characters.size} characters and ${dialogs.size} dialogs",
                mainEvents = emptyList(),
                emotionalArc = emptyList()
            )

            val response = ChapterAnalysisResponse(
                chapterSummary = chapterSummary,
                characters = characters,
                dialogs = dialogs,
                soundCues = emptyList()
            )

            return Pair(response, characterDataList)
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to parse chapter analysis result", e)
            return null
        }
    }

    /**
     * Merge characters from a single chapter.
     */
    private suspend fun mergeChapterCharacters(bookId: Long, characters: List<CharacterStub>?) {
        if (characters.isNullOrEmpty()) return

        try {
            MergeCharactersUseCase(database.characterDao(), context)
                .mergeAndSave(bookId, listOf(gson.toJson(characters)))
        } catch (e: Exception) {
            AppLogger.w(tag, "Character merge failed", e)
        }
    }

    /**
     * Persist characters with full dialog data to the database.
     * Called after each chapter analysis to save dialogs immediately.
     */
    private suspend fun persistCharactersWithDialogs(bookId: Long, characterDataList: List<AccumulatedCharacterData>) {
        try {
            val characterDao = database.characterDao()
            val existingChars = characterDao.getByBookIdDirect(bookId)
            val existingByName = existingChars.associateBy { it.name.lowercase() }

            for (charData in characterDataList) {
                val dialogsJson = if (charData.dialogs.isNotEmpty()) {
                    gson.toJson(charData.dialogs.map { d ->
                        mapOf(
                            "pageNumber" to d.pageNumber,
                            "text" to d.text,
                            "emotion" to d.emotion,
                            "intensity" to d.intensity
                        )
                    })
                } else null

                val existing = existingByName[charData.name.lowercase()]

                if (existing != null) {
                    // Update existing character with new dialogs (merge dialogs)
                    val mergedDialogsJson = mergeDialogsJson(existing.dialogsJson, dialogsJson)
                    characterDao.update(
                        existing.copy(
                            dialogsJson = mergedDialogsJson,
                            speakerId = charData.speakerId ?: existing.speakerId,
                            voiceProfileJson = charData.voiceProfile?.let { vp ->
                                gson.toJson(mapOf(
                                    "pitch" to vp.pitch,
                                    "speed" to vp.speed,
                                    "energy" to vp.energy,
                                    "gender" to vp.gender,
                                    "age" to vp.age,
                                    "tone" to vp.tone
                                ))
                            } ?: existing.voiceProfileJson
                        )
                    )
                } else {
                    // Insert new character - convert traits list to comma-separated string
                    characterDao.insert(
                        com.dramebaz.app.data.db.Character(
                            bookId = bookId,
                            name = charData.name,
                            traits = charData.traits.joinToString(","),
                            dialogsJson = dialogsJson,
                            speakerId = charData.speakerId,
                            voiceProfileJson = charData.voiceProfile?.let { vp ->
                                gson.toJson(mapOf(
                                    "pitch" to vp.pitch,
                                    "speed" to vp.speed,
                                    "energy" to vp.energy,
                                    "gender" to vp.gender,
                                    "age" to vp.age,
                                    "tone" to vp.tone
                                ))
                            }
                        )
                    )
                }
            }
            AppLogger.i(tag, "Persisted ${characterDataList.size} characters with dialogs for book $bookId")
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to persist characters with dialogs", e)
        }
    }

    /**
     * Save characters to database after each pipeline step completes.
     * Called by ChapterAnalysisTask callback after each step.
     * @param bookId Book ID
     * @param stepIndex 0=CharacterExtraction, 1=DialogExtraction, 2=VoiceProfile
     * @param stepName Human-readable step name
     * @param characters Current accumulated character data
     */
    private fun saveCharactersAfterStep(
        bookId: Long,
        stepIndex: Int,
        stepName: String,
        characters: Map<String, AccumulatedCharacterData>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val characterDao = database.characterDao()
                val existingChars = characterDao.getByBookIdDirect(bookId)
                val existingByName = existingChars.associateBy { it.name.lowercase() }

                AppLogger.i(tag, "Saving ${characters.size} characters after step $stepIndex ($stepName)")

                for ((_, charData) in characters) {
                    val existing = existingByName[charData.name.lowercase()]

                    when (stepIndex) {
                        0 -> {
                            // Step 0: CharacterExtraction - insert character names (traits may be empty)
                            if (existing == null) {
                                characterDao.insert(
                                    com.dramebaz.app.data.db.Character(
                                        bookId = bookId,
                                        name = charData.name,
                                        traits = charData.traits.joinToString(",")
                                    )
                                )
                                AppLogger.d(tag, "Inserted new character: ${charData.name}")
                            }
                        }
                        1 -> {
                            // Step 1: DialogExtraction - update dialogs
                            val dialogsJson = if (charData.dialogs.isNotEmpty()) {
                                gson.toJson(charData.dialogs.map { d ->
                                    mapOf(
                                        "pageNumber" to d.pageNumber,
                                        "text" to d.text,
                                        "emotion" to d.emotion,
                                        "intensity" to d.intensity
                                    )
                                })
                            } else null

                            if (existing != null) {
                                val mergedDialogsJson = mergeDialogsJson(existing.dialogsJson, dialogsJson)
                                characterDao.update(existing.copy(dialogsJson = mergedDialogsJson))
                                AppLogger.d(tag, "Updated dialogs for: ${charData.name} (${charData.dialogs.size} dialogs)")
                            } else {
                                // Character should exist from step 0, but insert if missing
                                characterDao.insert(
                                    com.dramebaz.app.data.db.Character(
                                        bookId = bookId,
                                        name = charData.name,
                                        traits = charData.traits.joinToString(","),
                                        dialogsJson = dialogsJson
                                    )
                                )
                            }
                        }
                        2 -> {
                            // Step 2: VoiceProfile - update voice profile and speaker ID
                            if (existing != null) {
                                characterDao.update(
                                    existing.copy(
                                        speakerId = charData.speakerId ?: existing.speakerId,
                                        voiceProfileJson = charData.voiceProfile?.let { vp ->
                                            gson.toJson(mapOf(
                                                "pitch" to vp.pitch,
                                                "speed" to vp.speed,
                                                "energy" to vp.energy,
                                                "gender" to vp.gender,
                                                "age" to vp.age,
                                                "tone" to vp.tone
                                            ))
                                        } ?: existing.voiceProfileJson
                                    )
                                )
                                AppLogger.d(tag, "Updated voice profile for: ${charData.name}")
                            } else {
                                // Character should exist from step 0, but insert if missing
                                characterDao.insert(
                                    com.dramebaz.app.data.db.Character(
                                        bookId = bookId,
                                        name = charData.name,
                                        traits = charData.traits.joinToString(","),
                                        speakerId = charData.speakerId,
                                        voiceProfileJson = charData.voiceProfile?.let { vp ->
                                            gson.toJson(mapOf(
                                                "pitch" to vp.pitch,
                                                "speed" to vp.speed,
                                                "energy" to vp.energy,
                                                "gender" to vp.gender,
                                                "age" to vp.age,
                                                "tone" to vp.tone
                                            ))
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
                AppLogger.i(tag, "Step $stepIndex ($stepName) save completed for book $bookId")
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed to save characters after step $stepIndex ($stepName)", e)
            }
        }
    }

    /**
     * Merge two dialogsJson strings, combining dialogs from both.
     */
    private fun mergeDialogsJson(existingJson: String?, newJson: String?): String? {
        if (existingJson.isNullOrBlank() && newJson.isNullOrBlank()) return null
        if (existingJson.isNullOrBlank()) return newJson
        if (newJson.isNullOrBlank()) return existingJson

        return try {
            val existingList = gson.fromJson<List<Map<String, Any>>>(
                existingJson,
                object : TypeToken<List<Map<String, Any>>>() {}.type
            ) ?: emptyList()
            val newList = gson.fromJson<List<Map<String, Any>>>(
                newJson,
                object : TypeToken<List<Map<String, Any>>>() {}.type
            ) ?: emptyList()

            // Combine both lists (could add deduplication here if needed)
            gson.toJson(existingList + newList)
        } catch (e: Exception) {
            newJson ?: existingJson
        }
    }

    /**
     * Perform global character merge after all chapters analyzed.
     */
    private suspend fun performGlobalCharacterMerge(bookId: Long) {
        try {
            // BLOB-FIX: Use lightweight projection - only need fullAnalysisJson
            val allChapters = bookRepository.getChaptersWithAnalysis(bookId)
            val characterJsonList = allChapters.mapNotNull { chapter ->
                chapter.fullAnalysisJson?.let { json ->
                    try {
                        val analysis = gson.fromJson(json, ChapterAnalysisResponse::class.java)
                        analysis.characters?.let { chars -> gson.toJson(chars) }
                    } catch (e: Exception) { null }
                }
            }
            if (characterJsonList.size > 1) {
                AppLogger.d(tag, "Global character merge: combining from ${characterJsonList.size} chapters")
                MergeCharactersUseCase(database.characterDao(), context)
                    .mergeAndSave(bookId, characterJsonList)
                AppLogger.i(tag, "Global character merge complete for book $bookId")
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Global character merge failed", e)
        }
    }

    /**
     * Generate audio for page 1 of chapter 1.
     */
    private suspend fun generateInitialAudio(bookId: Long, chapter: Chapter, dialogs: List<Dialog>?) {
        val generator = segmentAudioGenerator ?: return

        val page1Text = getPage1Text(chapter) ?: return
        AppLogger.i(tag, "Generating initial audio for page 1 of book $bookId")

        val startTime = System.currentTimeMillis()
        val page1Dialogs = generator.getDialogsForPage(page1Text, dialogs)
        val generatedFiles = generator.generatePageAudio(
            bookId = bookId,
            chapterId = chapter.id,
            pageNumber = 1,
            pageText = page1Text,
            dialogs = page1Dialogs
        )

        AppLogger.logPerformance(tag, "Initial audio generation (${generatedFiles.size} segments)",
            System.currentTimeMillis() - startTime)
    }

    private fun getPage1Text(chapter: Chapter): String? {
        if (!chapter.pdfPagesJson.isNullOrBlank()) {
            try {
                val pdfPages = PdfChapterDetector.pdfPagesFromJson(chapter.pdfPagesJson)
                if (pdfPages.isNotEmpty()) return pdfPages[0].text
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed to parse PDF pages", e)
            }
        }

        val body = chapter.body.trim()
        if (body.isBlank()) return null
        return if (body.length <= 3000) body else body.substring(0, 3000)
    }

    /**
     * COVER-001: Trigger background book-level details analysis (genre + placeholder cover).
     *
     * Uses ThemeAnalysisPass with LLM when available, falling back to heuristicAnalysis.
     * Resulting genre and mapped cover path are persisted via BookRepository, but only
     * when the book has no embedded cover (enforced in repository/DAO layer).
     */
    private fun triggerBackgroundBookDetailsAnalysis(
        bookId: Long,
        title: String,
        sampleText: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isBookCancelled(bookId) || isPaused) {
                    AppLogger.i(tag, "COVER-001: Skipping book details analysis for book $bookId (cancelled/paused)")
                    return@launch
                }

                val text = sampleText.takeIf { it.isNotBlank() } ?: return@launch

                val output = try {
                    val model = LlmService.getModel()
                    if (model != null) {
                        val pass = ThemeAnalysisPass()
                        val input = ThemeAnalysisInput(
                            bookId = bookId,
                            title = title,
                            firstChapterText = text
                        )
                        pass.execute(model, input, PassConfig())
                    } else {
                        ThemeAnalysisPass.heuristicAnalysis(bookId, title, text)
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "COVER-001: ThemeAnalysisPass failed, falling back to heuristics for book $bookId", e)
                    ThemeAnalysisPass.heuristicAnalysis(bookId, title, text)
                }

                if (isBookCancelled(bookId) || isPaused) {
                    AppLogger.i(tag, "COVER-001: Book $bookId cancelled/paused after theme analysis, not updating cover")
                    return@launch
                }

                val genre = output.genre
                val coverPath = GenreCoverMapper.mapGenreToCoverPath(genre)

                try {
                    bookRepository.updateGenreAndPlaceholderCover(
                        bookId = bookId,
                        genre = genre,
                        coverPath = coverPath
                    )
                    AppLogger.d(tag, "COVER-001: Updated book $bookId with genre='$genre', cover='$coverPath'")
                } catch (e: Exception) {
                    AppLogger.w(tag, "COVER-001: Failed to update genre/placeholder cover for book $bookId", e)
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "COVER-001: Background book details analysis failed for book $bookId", e)
            }
        }
    }

    /**
     * Trigger background traits extraction for characters.
     */
    private fun triggerBackgroundTraitsExtraction(
        bookId: Long,
        chapterId: Long,
        characterDataList: List<AccumulatedCharacterData>,
        executor: AnalysisExecutor
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            AppLogger.d(tag, "🔄 Starting background traits extraction for ${characterDataList.size} characters")

            for (charData in characterDataList) {
                if (isBookCancelled(bookId) || isPaused) return@launch

                val dialogContext = charData.dialogs.take(10).joinToString("\n") {
                    "${charData.name}: \"${it.text}\""
                }

                if (dialogContext.length < 50) continue

                try {
                    val traitsTask = TraitsExtractionTask(
                        bookId = bookId,
                        characterName = charData.name,
                        dialogContext = dialogContext
                    )

                    val result = executor.execute(
                        task = traitsTask,
                        options = AnalysisExecutor.ExecutionOptions(forceBackground = true, autoPersist = false)
                    )

                    if (result.success) {
                        val traitsJson = result.resultData[TraitsExtractionTask.KEY_TRAITS] as? String
                        val personalityJson = result.resultData[TraitsExtractionTask.KEY_PERSONALITY] as? String

                        val traits: List<String> = if (traitsJson != null) {
                            gson.fromJson(traitsJson, object : TypeToken<List<String>>() {}.type)
                        } else emptyList()

                        val personality: List<String> = if (personalityJson != null) {
                            gson.fromJson(personalityJson, object : TypeToken<List<String>>() {}.type)
                        } else emptyList()

                        if (traits.isNotEmpty()) {
                            updateCharacterWithTraitsAndPersonality(bookId, charData.name, traits, personality)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "Failed to extract traits for ${charData.name}", e)
                }
            }

            AppLogger.d(tag, "✅ Background traits extraction complete for book $bookId")
        }
    }

    /**
     * Update a character with extracted traits and personality.
     */
    private suspend fun updateCharacterWithTraitsAndPersonality(
        bookId: Long,
        characterName: String,
        traits: List<String>,
        personality: List<String>
    ) {
        val characterDao = database.characterDao()

        try {
            val existing = characterDao.getByBookIdAndName(bookId, characterName)
            if (existing != null) {
                val existingTraits = existing.traits.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val mergedTraits = (existingTraits + traits).distinct()
                val personalitySummary = if (personality.isNotEmpty()) {
                    personality.joinToString(". ")
                } else {
                    existing.personalitySummary
                }

                characterDao.update(existing.copy(
                    traits = mergedTraits.joinToString(","),
                    personalitySummary = personalitySummary
                ))
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to update character with traits: $characterName", e)
        }
    }

    // ==================== Status and Control Methods ====================

    private fun updateStatus(bookId: Long, status: AnalysisStatus) {
        _analysisStatus.value = _analysisStatus.value + (bookId to status)
    }

    fun getBookStatus(bookId: Long): AnalysisStatus? = _analysisStatus.value[bookId]

    /**
     * Cancel analysis for a specific book.
     */
    fun cancelForBook(bookId: Long) {
        cancelledBookIds.add(bookId)
        _analysisStatus.value = _analysisStatus.value - bookId
        AppLogger.i(tag, "Cancelled analysis for book $bookId")
    }

    fun isBookCancelled(bookId: Long): Boolean = cancelledBookIds.contains(bookId)

    fun clearCancelledBook(bookId: Long) {
        cancelledBookIds.remove(bookId)
    }

    /**
     * Pause all analysis (e.g., for LLM settings change).
     */
    fun pause() {
        isPaused = true
        AppLogger.i(tag, "⏸️ Workflow paused")
    }

    /**
     * Resume analysis after pause.
     */
    fun resume() {
        isPaused = false
        AppLogger.i(tag, "▶️ Workflow resumed")
    }

    fun isPaused(): Boolean = isPaused

    /**
     * Check if a book is partially analyzed (first chapter done).
     */
    suspend fun isBookPartiallyAnalyzed(bookId: Long): Boolean {
        // BLOB-FIX: Use lightweight projection with isAnalyzed flag
        val chapters = bookRepository.chapterSummariesList(bookId).sortedBy { it.orderIndex }
        return chapters.firstOrNull()?.isAnalyzed == true
    }

    /**
     * Check if a book is fully analyzed.
     */
    suspend fun isBookAnalyzed(bookId: Long): Boolean {
        // BLOB-FIX: Use lightweight projection with isAnalyzed flag
        val chapters = bookRepository.chapterSummariesList(bookId)
        return chapters.isNotEmpty() && chapters.all { it.isAnalyzed }
    }
}
