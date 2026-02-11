package com.dramebaz.app.ai.llm.tasks

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.BatchedAnalysisCheckpoint
import com.dramebaz.app.ai.llm.pipeline.BatchedPipelineConfig
import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.llm.pipeline.ParagraphBatcher
import com.dramebaz.app.ai.llm.pipeline.TextCleaner
import com.dramebaz.app.ai.llm.pipeline.checkpoint.FileCheckpointManager
import com.dramebaz.app.ai.llm.pipeline.conversion.ResultConverterUtil
import com.dramebaz.app.ai.llm.pipeline.passes.BatchedChapterAnalysisPass
import com.dramebaz.app.ai.llm.pipeline.toCheckpoint
import com.dramebaz.app.ai.llm.pipeline.toMerged
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisInput
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import java.io.File

/**
 * Task implementation for batched chapter analysis.
 *
 * Features:
 * - Processes paragraphs in batches to reduce LLM calls (~8-10 instead of 66)
 * - Supports checkpoint/resume from any paragraph
 * - Incrementally merges results across batches
 * - Saves checkpoint after each batch completion
 * - Coexists with existing 3-pass pipeline (ChapterAnalysisTask)
 * - INCREMENTAL-001: Supports partial page processing and page-to-paragraph tracking
 *
 * Uses:
 * - FileCheckpointManager: For checkpoint persistence (Repository Pattern)
 * - CharacterAnalysisBatchProcessor: For batch processing (Template Method Pattern)
 * - ResultConverterUtil: For result conversion (Adapter Pattern)
 */
class BatchedChapterAnalysisTask(
    val bookId: Long,
    val chapterId: Long,
    private val chapterTitle: String,
    private val rawPages: List<String>,
    private val cacheDir: File,
    /**
     * INCREMENTAL-001: Maximum number of pages to process (null = all pages).
     * When set, analysis stops after processing paragraphs from the first maxPagesToProcess pages.
     */
    private val maxPagesToProcess: Int? = null,
    /**
     * INCREMENTAL-001: Enhanced callback with page range data for incremental audio generation.
     * Called after each batch completes with page range information.
     */
    private val onBatchComplete: suspend (batchData: BatchCompleteData) -> Unit = { _ -> }
) : AnalysisTask {

    companion object {
        private const val TAG = "BatchedChapterAnalysisTask"

        // Result keys for resultData map
        const val KEY_CHARACTERS = "characters"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHARACTER_COUNT = "character_count"
        const val KEY_DIALOG_COUNT = "dialog_count"
        const val KEY_BATCH_COUNT = "batch_count"
        const val KEY_RESUMED = "resumed_from_checkpoint"
        const val KEY_PAGES_PROCESSED = "pages_processed"
        const val KEY_TOTAL_PAGES = "total_pages"
        const val KEY_IS_PARTIAL = "is_partial"
    }

    /**
     * INCREMENTAL-001: Data class for enhanced batch completion callback.
     */
    data class BatchCompleteData(
        val batchIndex: Int,
        val totalBatches: Int,
        val characters: List<IncrementalMerger.MergedCharacterData>,
        /** Page range covered by this batch (0-based, inclusive) */
        val pageRange: IntRange,
        /** Raw page texts for the pages in this batch */
        val pageTexts: Map<Int, String>,
        /** True if this is the final batch of the (possibly partial) analysis */
        val isFinalBatch: Boolean,
        /** Total pages in chapter */
        val totalPages: Int,
        /** Pages processed so far (cumulative) */
        val pagesProcessedSoFar: Int
    )

    private val gson = Gson()
    private val checkpointManager = FileCheckpointManager(cacheDir)
    private val pass = BatchedChapterAnalysisPass()

    override val taskId: String = "batched_chapter_analysis_${bookId}_${chapterId}"
    override val displayName: String = "Batched Analysis: $chapterTitle"

    override val estimatedDurationSeconds: Int
        get() {
            // Estimate: 30 seconds per batch average
            val paragraphCount = rawPages.sumOf { TextCleaner.cleanPageIntoParagraphs(it).size }
            val estimatedBatches = ParagraphBatcher.estimateBatchCount(
                List(paragraphCount) { "x".repeat(200) },  // Rough estimate
                BatchedPipelineConfig.INPUT_TOKENS
            )
            return estimatedBatches * 30
        }

    override suspend fun execute(
        model: LlmModel,
        progressCallback: ((TaskProgress) -> Unit)?
    ): TaskResult {
        val startTime = System.currentTimeMillis()

        try {
            val isPartial = maxPagesToProcess != null && maxPagesToProcess < rawPages.size
            AppLogger.i(TAG, "Starting batched analysis for chapter '$chapterTitle' " +
                    "(bookId=$bookId, chapterId=$chapterId, partial=$isPartial, maxPages=$maxPagesToProcess)")

            progressCallback?.invoke(TaskProgress(taskId, "Preparing paragraphs...", 0))

            // INCREMENTAL-001: Determine pages to process
            val pagesToProcess = if (maxPagesToProcess != null) {
                rawPages.take(maxPagesToProcess)
            } else {
                rawPages
            }
            val totalPages = rawPages.size
            val pagesProcessed = pagesToProcess.size

            // Step 1: Clean and split into paragraphs WITH page mapping
            val (paragraphs, pageBoundaries) = TextCleaner.cleanAndSplitWithPageMapping(pagesToProcess)
            AppLogger.i(TAG, "Cleaned $pagesProcessed pages into ${paragraphs.size} paragraphs (partial=$isPartial)")

            if (paragraphs.isEmpty()) {
                AppLogger.w(TAG, "No paragraphs to analyze")
                return TaskResult(
                    success = false,
                    taskId = taskId,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = "No paragraphs to analyze"
                )
            }

            // Step 2: Compute content hash for checkpoint validation
            val contentHash = FileCheckpointManager.computeContentHash(paragraphs)

            // Step 3: Check for existing checkpoint using CheckpointManager (Repository Pattern)
            val checkpoint = checkpointManager.load(bookId, chapterId, contentHash)
            var resumedFromCheckpoint = false
            var startParagraphIndex = 0
            val accumulatedData: MutableMap<String, IncrementalMerger.MergedCharacterData> = mutableMapOf()

            if (checkpoint != null && !checkpoint.isComplete()) {
                // Resume from checkpoint
                startParagraphIndex = checkpoint.getResumeIndex()
                checkpoint.accumulatedCharacters.forEach { charData ->
                    accumulatedData[charData.canonicalName] = charData.toMerged()
                }
                resumedFromCheckpoint = true
                AppLogger.i(TAG, "Resuming from checkpoint: paragraph $startParagraphIndex/${paragraphs.size} " +
                        "(${checkpoint.getProgressPercent()}% complete)")
                progressCallback?.invoke(TaskProgress(taskId, "Resuming from checkpoint...", checkpoint.getProgressPercent()))
            }

            // Step 4: Create batches from remaining paragraphs
            val batches = ParagraphBatcher.createBatchesFromIndex(
                paragraphs = paragraphs,
                maxInputTokens = BatchedPipelineConfig.INPUT_TOKENS,
                startFromParagraphIndex = startParagraphIndex
            )

            val totalBatches = batches.size
            AppLogger.i(TAG, "Created $totalBatches batches from paragraph $startParagraphIndex")

            // Track cumulative pages processed for callback
            var maxPageProcessedSoFar = 0

            // Step 5: Process each batch
            for ((index, batch) in batches.withIndex()) {
                val progressPercent = ((index.toFloat() / totalBatches) * 100).toInt()
                progressCallback?.invoke(TaskProgress(
                    taskId = taskId,
                    message = "Processing batch ${index + 1}/$totalBatches",
                    percent = progressPercent,
                    currentStep = index + 1,
                    totalSteps = totalBatches,
                    stepName = "Batch ${index + 1}"
                ))

                AppLogger.d(TAG, "Processing batch ${batch.batchIndex}: paras ${batch.startParagraphIndex}-${batch.endParagraphIndex}")

                // Execute LLM call
                val input = BatchedAnalysisInput(
                    text = batch.text,
                    batchIndex = batch.batchIndex,
                    totalBatches = totalBatches
                )

                val output = pass.execute(model, input, BatchedChapterAnalysisPass.CONFIG)

                // Merge results
                IncrementalMerger.merge(accumulatedData, output)

                // INCREMENTAL-001: Calculate page range for this batch
                val pageRange = TextCleaner.findPagesForParagraphRange(
                    batch.startParagraphIndex,
                    batch.endParagraphIndex,
                    pageBoundaries
                )
                maxPageProcessedSoFar = maxOf(maxPageProcessedSoFar, pageRange.last + 1)

                // Build page texts map for the pages in this batch
                val pageTexts = pageRange.associateWith { pageIndex ->
                    if (pageIndex < pagesToProcess.size) pagesToProcess[pageIndex] else ""
                }

                // Save checkpoint using CheckpointManager (Repository Pattern)
                val checkpointData = BatchedAnalysisCheckpoint(
                    bookId = bookId,
                    chapterId = chapterId,
                    contentHash = contentHash,
                    lastProcessedParagraphIndex = batch.endParagraphIndex,
                    totalParagraphs = paragraphs.size,
                    batchesCompleted = batch.batchIndex + 1,
                    totalBatches = totalBatches,
                    timestamp = System.currentTimeMillis(),
                    accumulatedCharacters = accumulatedData.values.map { it.toCheckpoint() }
                )
                checkpointManager.save(checkpointData)

                // INCREMENTAL-001: Notify callback with enhanced batch data
                val batchData = BatchCompleteData(
                    batchIndex = batch.batchIndex,
                    totalBatches = totalBatches,
                    characters = IncrementalMerger.toList(accumulatedData),
                    pageRange = pageRange,
                    pageTexts = pageTexts,
                    isFinalBatch = index == batches.size - 1,
                    totalPages = totalPages,
                    pagesProcessedSoFar = maxPageProcessedSoFar
                )
                onBatchComplete(batchData)

                AppLogger.i(TAG, "Batch ${batch.batchIndex} complete: ${accumulatedData.size} characters, pages ${pageRange.first}-${pageRange.last}")
            }

            // Step 6: Clean up checkpoint on completion (only if processing all pages)
            if (!isPartial) {
                checkpointManager.delete(bookId, chapterId)
            }

            progressCallback?.invoke(TaskProgress(taskId, "Analysis complete", 100))

            val characters = IncrementalMerger.toList(accumulatedData)
            val stats = ResultConverterUtil.extractStats(characters)

            AppLogger.i(TAG, "Analysis complete: ${stats.characterCount} characters, ${stats.dialogCount} dialogs " +
                    "in $totalBatches batches (pages: $pagesProcessed/$totalPages)")

            // Use ResultConverterUtil (Adapter Pattern) for result conversion
            return TaskResult(
                success = true,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                resultData = mapOf(
                    KEY_CHARACTERS to gson.toJson(ResultConverterUtil.toAccumulatedFormat(characters)),
                    KEY_BOOK_ID to bookId,
                    KEY_CHAPTER_ID to chapterId,
                    KEY_CHARACTER_COUNT to stats.characterCount,
                    KEY_DIALOG_COUNT to stats.dialogCount,
                    KEY_BATCH_COUNT to totalBatches,
                    KEY_RESUMED to resumedFromCheckpoint,
                    KEY_PAGES_PROCESSED to pagesProcessed,
                    KEY_TOTAL_PAGES to totalPages,
                    KEY_IS_PARTIAL to isPartial
                )
            )

        } catch (e: Exception) {
            AppLogger.e(TAG, "Batched analysis failed", e)
            return TaskResult(
                success = false,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }
}

