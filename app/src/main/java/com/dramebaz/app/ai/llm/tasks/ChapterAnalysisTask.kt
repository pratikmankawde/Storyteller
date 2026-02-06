package com.dramebaz.app.ai.llm.tasks

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisContext
import com.dramebaz.app.ai.llm.pipeline.CharacterExtractionStep
import com.dramebaz.app.ai.llm.pipeline.DialogExtractionStep
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.pipeline.VoiceProfileStep
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Analysis task for chapter-level character, dialog, and voice profile extraction.
 * 
 * Wraps the 3-pass chapter analysis workflow (Character Extraction, Dialog Extraction,
 * Voice Profile Suggestion) into an AnalysisTask that can be run by either
 * AnalysisBackgroundRunner or AnalysisForegroundService.
 * 
 * NOT tied to any specific service - pure pipeline logic.
 */
class ChapterAnalysisTask(
    val bookId: Long,
    val chapterId: Long,
    val cleanedPages: List<String>,
    val chapterTitle: String = "Chapter Analysis"
) : AnalysisTask {
    
    companion object {
        private const val TAG = "ChapterAnalysisTask"
        
        // Result keys for resultData map
        const val KEY_CHARACTERS = "characters"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHARACTER_COUNT = "character_count"
        const val KEY_DIALOG_COUNT = "dialog_count"
    }
    
    private val gson = Gson()
    
    override val taskId: String = "chapter_analysis_${bookId}_${chapterId}"
    
    override val displayName: String = chapterTitle
    
    // Estimate: ~30 seconds per page for full 3-pass analysis
    override val estimatedDurationSeconds: Int = (cleanedPages.size * 30).coerceAtLeast(120)
    
    override suspend fun execute(
        model: LlmModel,
        progressCallback: ((TaskProgress) -> Unit)?
    ): TaskResult {
        val startTime = System.currentTimeMillis()
        
        AppLogger.i(TAG, "Starting chapter analysis: bookId=$bookId, chapterId=$chapterId, " +
                "pages=${cleanedPages.size}")
        
        try {
            // Create initial context
            val contentHash = cleanedPages.joinToString("").hashCode()
            var context = ChapterAnalysisContext(
                contentHash = contentHash,
                bookId = bookId,
                chapterId = chapterId,
                cleanedPages = cleanedPages
            )
            
            val steps = listOf(
                CharacterExtractionStep(),
                DialogExtractionStep(),
                VoiceProfileStep()
            )
            
            val config = PassConfig()
            
            // Execute each step
            for ((index, step) in steps.withIndex()) {
                progressCallback?.invoke(TaskProgress(
                    taskId = taskId,
                    message = "Executing ${step.name}...",
                    percent = (index * 100) / steps.size,
                    currentStep = index + 1,
                    totalSteps = steps.size,
                    stepName = step.name
                ))
                
                AppLogger.d(TAG, "Executing step ${index + 1}/${steps.size}: ${step.name}")
                context = step.execute(model, context, config)
            }
            
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Analysis complete",
                percent = 100,
                currentStep = steps.size,
                totalSteps = steps.size
            ))
            
            val duration = System.currentTimeMillis() - startTime
            AppLogger.i(TAG, "Chapter analysis completed in ${duration}ms: " +
                    "${context.characters.size} characters, ${context.totalDialogs} dialogs")
            
            // Build result data
            val resultData = buildResultData(context)
            
            return TaskResult(
                success = true,
                taskId = taskId,
                durationMs = duration,
                resultData = resultData
            )
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Chapter analysis failed", e)
            return TaskResult(
                success = false,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    private fun buildResultData(context: ChapterAnalysisContext): Map<String, Any> {
        // Serialize characters to JSON for cross-process compatibility
        val charactersJson = gson.toJson(context.characters.values.toList())
        
        return mapOf(
            KEY_BOOK_ID to bookId,
            KEY_CHAPTER_ID to chapterId,
            KEY_CHARACTERS to charactersJson,
            KEY_CHARACTER_COUNT to context.characters.size,
            KEY_DIALOG_COUNT to context.totalDialogs
        )
    }
}

