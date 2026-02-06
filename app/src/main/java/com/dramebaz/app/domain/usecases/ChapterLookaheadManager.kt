package com.dramebaz.app.domain.usecases

import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisInput
import com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisPass
import com.dramebaz.app.ai.llm.pipeline.ExtendedAnalysisInput
import com.dramebaz.app.ai.llm.pipeline.ExtendedAnalysisPass
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * READ-003: Chapter Lookahead Analysis
 * 
 * Pre-analyzes next chapter when user reaches 80% of current chapter.
 * Runs LLM analysis in background to generate summary, characters, dialogs.
 * Caches results for instant access when user navigates to next chapter.
 */
class ChapterLookaheadManager(
    private val scope: CoroutineScope,
    private val bookRepository: BookRepository
) {
    private val tag = "ChapterLookaheadManager"
    private val gson = Gson()
    
    companion object {
        /** Progress threshold at which to trigger lookahead (80%) */
        const val LOOKAHEAD_TRIGGER_PROGRESS = 0.8f
        
        /** Minimum chapter length to trigger analysis */
        const val MIN_CHAPTER_LENGTH = 50
    }
    
    /** Lookahead analysis state */
    sealed class LookaheadState {
        object Idle : LookaheadState()
        data class Analyzing(val chapterId: Long, val chapterTitle: String) : LookaheadState()
        data class Complete(val chapterId: Long, val chapterTitle: String) : LookaheadState()
        data class Failed(val chapterId: Long, val error: String) : LookaheadState()
    }
    
    private val _lookaheadState = MutableStateFlow<LookaheadState>(LookaheadState.Idle)
    val lookaheadState: StateFlow<LookaheadState> = _lookaheadState.asStateFlow()
    
    /** Active lookahead job */
    private var lookaheadJob: Job? = null
    
    /** Mutex for thread-safe state updates */
    private val mutex = Mutex()
    
    /** Set of chapter IDs that have been analyzed or attempted */
    private val analyzedChapters = mutableSetOf<Long>()
    
    /** Whether lookahead has been triggered for current reading session */
    private var lookaheadTriggered = false
    
    /**
     * Check if lookahead should be triggered based on reading progress.
     * 
     * @param currentProgress Current reading progress (0.0 to 1.0)
     * @param bookId Current book ID
     * @param currentChapterId Current chapter ID
     * @param enableDeepAnalysis Whether deep analysis feature is enabled
     */
    suspend fun checkAndTriggerLookahead(
        currentProgress: Float,
        bookId: Long,
        currentChapterId: Long,
        enableDeepAnalysis: Boolean
    ) = mutex.withLock {
        // Check if lookahead should trigger
        if (!enableDeepAnalysis) {
            AppLogger.d(tag, "Deep analysis disabled, skipping lookahead")
            return@withLock
        }
        
        if (lookaheadTriggered) {
            // Already triggered for this chapter
            return@withLock
        }
        
        if (currentProgress < LOOKAHEAD_TRIGGER_PROGRESS) {
            return@withLock
        }
        
        // Trigger lookahead
        lookaheadTriggered = true
        triggerLookahead(bookId, currentChapterId)
    }
    
    /**
     * Trigger lookahead analysis for the next chapter.
     */
    private fun triggerLookahead(bookId: Long, currentChapterId: Long) {
        // Cancel any previous lookahead job
        lookaheadJob?.cancel()
        
        lookaheadJob = scope.launch(Dispatchers.IO) {
            try {
                // Get all chapters for the book
                val chapters = bookRepository.chapters(bookId).first().sortedBy { it.orderIndex }
                val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
                
                if (currentIdx < 0 || currentIdx + 1 >= chapters.size) {
                    AppLogger.d(tag, "No next chapter available for lookahead")
                    return@launch
                }
                
                val nextChapter = chapters[currentIdx + 1]
                
                // Check if already analyzed
                if (analyzedChapters.contains(nextChapter.id)) {
                    AppLogger.d(tag, "Chapter ${nextChapter.id} already analyzed, skipping")
                    return@launch
                }
                
                // Check if chapter already has analysis
                if (!nextChapter.fullAnalysisJson.isNullOrBlank()) {
                    AppLogger.d(tag, "Next chapter '${nextChapter.title}' already has analysis")
                    analyzedChapters.add(nextChapter.id)
                    return@launch
                }
                
                // Check minimum length
                if (nextChapter.body.length < MIN_CHAPTER_LENGTH) {
                    AppLogger.d(tag, "Next chapter '${nextChapter.title}' too short, skipping analysis")
                    return@launch
                }
                
                // Run analysis
                analyzeChapter(bookId, nextChapter)
                
            } catch (e: Exception) {
                AppLogger.e(tag, "Error in lookahead trigger", e)
            }
        }
    }
    
    private suspend fun analyzeChapter(bookId: Long, chapter: Chapter) = withContext(Dispatchers.IO) {
        AppLogger.i(tag, "Starting lookahead analysis for '${chapter.title}'")
        _lookaheadState.value = LookaheadState.Analyzing(chapter.id, chapter.title)

        try {
            // Ensure LLM is initialized and get model
            LlmService.ensureInitialized()
            val model = LlmService.getModel()

            if (model == null) {
                AppLogger.w(tag, "LLM model not available for lookahead analysis")
                _lookaheadState.value = LookaheadState.Failed(chapter.id, "LLM model not available")
                return@withContext
            }

            // Run chapter analysis pass
            val chapterPass = ChapterAnalysisPass()
            val chapterOutput = chapterPass.execute(
                model = model,
                input = ChapterAnalysisInput(chapter.body),
                config = ChapterAnalysisPass.DEFAULT_CONFIG
            )

            // Run extended analysis pass
            val extendedPass = ExtendedAnalysisPass()
            val extendedOutput = extendedPass.execute(
                model = model,
                input = ExtendedAnalysisInput(chapter.body),
                config = ExtendedAnalysisPass.DEFAULT_CONFIG
            )

            // Save to database
            val updatedChapter = chapter.copy(
                summaryJson = gson.toJson(chapterOutput.summary),
                analysisJson = extendedOutput.rawJson,
                fullAnalysisJson = gson.toJson(chapterOutput)
            )
            bookRepository.updateChapter(updatedChapter)

            // Mark as analyzed
            analyzedChapters.add(chapter.id)

            AppLogger.i(tag, "Lookahead analysis complete for '${chapter.title}'")
            _lookaheadState.value = LookaheadState.Complete(chapter.id, chapter.title)

        } catch (e: Exception) {
            AppLogger.e(tag, "Lookahead analysis failed for '${chapter.title}'", e)
            _lookaheadState.value = LookaheadState.Failed(chapter.id, e.message ?: "Unknown error")
        }
    }

    /**
     * Called when user navigates to a new chapter.
     * Resets the lookahead trigger flag for the new chapter.
     */
    fun onChapterChanged(newChapterId: Long) {
        scope.launch {
            mutex.withLock {
                lookaheadTriggered = false
                _lookaheadState.value = LookaheadState.Idle
                AppLogger.d(tag, "Chapter changed to $newChapterId, reset lookahead trigger")
            }
        }
    }

    /**
     * Reset manager state. Called when reader closes.
     */
    fun reset() {
        lookaheadJob?.cancel()
        lookaheadJob = null
        lookaheadTriggered = false
        _lookaheadState.value = LookaheadState.Idle
        AppLogger.d(tag, "ChapterLookaheadManager reset")
    }

    /**
     * Check if a chapter has been analyzed.
     */
    fun isChapterAnalyzed(chapterId: Long): Boolean = analyzedChapters.contains(chapterId)
}

