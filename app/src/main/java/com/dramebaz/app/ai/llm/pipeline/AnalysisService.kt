package com.dramebaz.app.ai.llm.pipeline

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.ai.llm.prompts.VoiceProfileData
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Service layer for running LLM analysis pipelines.
 * 
 * This service:
 * - Manages LLM model lifecycle
 * - Provides simple API for triggering chapter analysis
 * - Handles database persistence of results
 * - Uses MultipassPipeline for pipeline execution
 * 
 * Design Pattern: Facade Pattern - simplified interface to the analysis subsystem.
 */
class AnalysisService(
    private val context: Context,
    private val characterDao: CharacterDao
) {
    companion object {
        private const val TAG = "AnalysisService"
    }
    
    private val gson = Gson()
    private val modelMutex = Mutex()
    private var llmModel: LlmModel? = null
    
    /**
     * Result of chapter analysis.
     */
    data class ChapterAnalysisResult(
        val success: Boolean,
        val characterCount: Int,
        val dialogCount: Int,
        val durationMs: Long,
        val error: String? = null
    )
    
    /**
     * Ensure the LLM model is loaded.
     */
    suspend fun ensureModelLoaded(): Boolean = modelMutex.withLock {
        if (llmModel?.isModelLoaded() == true) return@withLock true
        
        return@withLock withContext(Dispatchers.IO) {
            try {
                llmModel = LlmModelFactory.createDefaultModel(context)
                val loaded = llmModel?.loadModel() ?: false
                if (loaded) {
                    AppLogger.i(TAG, "✅ LLM model loaded successfully")
                }
                loaded
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load LLM model", e)
                false
            }
        }
    }
    
    /**
     * Run chapter analysis pipeline.
     * 
     * @param bookId Book ID
     * @param chapterId Chapter ID
     * @param cleanedPages List of cleaned page texts (from TextCleaner)
     * @param onProgress Progress callback
     * @return Analysis result
     */
    suspend fun analyzeChapter(
        bookId: Long,
        chapterId: Long,
        cleanedPages: List<String>,
        onProgress: ((PipelineProgress) -> Unit)? = null
    ): ChapterAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Ensure model is loaded
        if (!ensureModelLoaded()) {
            return@withContext ChapterAnalysisResult(
                success = false,
                characterCount = 0,
                dialogCount = 0,
                durationMs = System.currentTimeMillis() - startTime,
                error = "Failed to load LLM model"
            )
        }
        
        val model = llmModel ?: return@withContext ChapterAnalysisResult(
            success = false,
            characterCount = 0,
            dialogCount = 0,
            durationMs = System.currentTimeMillis() - startTime,
            error = "LLM model not available"
        )
        
        try {
            // Create pipeline
            val pipeline = MultipassPipeline(context, model, "chapter_analysis")
            
            // Create initial context
            val contentHash = cleanedPages.joinToString("").hashCode()
            val initialContext = ChapterAnalysisContext(
                contentHash = contentHash,
                bookId = bookId,
                chapterId = chapterId,
                cleanedPages = cleanedPages
            )
            
            // Define pipeline steps
            val steps = listOf(
                CharacterExtractionStep(),
                DialogExtractionStep(),
                VoiceProfileStep()
            )
            
            // Execute pipeline
            val result = pipeline.execute(
                steps = steps,
                initialContext = initialContext,
                bookId = bookId,
                chapterId = chapterId,
                onProgress = onProgress
            )
            
            if (result.success) {
                // Save results to database
                saveCharactersToDatabase(bookId, result.context)
                
                ChapterAnalysisResult(
                    success = true,
                    characterCount = result.context.characters.size,
                    dialogCount = result.context.totalDialogs,
                    durationMs = result.durationMs
                )
            } else {
                ChapterAnalysisResult(
                    success = false,
                    characterCount = result.context.characters.size,
                    dialogCount = result.context.totalDialogs,
                    durationMs = result.durationMs,
                    error = result.error
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Chapter analysis failed", e)
            ChapterAnalysisResult(
                success = false,
                characterCount = 0,
                dialogCount = 0,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        }
    }
    
    /**
     * Save analyzed characters to database.
     */
    private suspend fun saveCharactersToDatabase(bookId: Long, context: ChapterAnalysisContext) {
        for ((_, charData) in context.characters) {
            saveCharacter(bookId, charData)
        }
    }

    /**
     * Save a single character to database.
     */
    private suspend fun saveCharacter(bookId: Long, charData: AccumulatedCharacterData) {
        val existing = characterDao.getByBookIdAndName(bookId, charData.name)

        val traitsStr = charData.traits.joinToString(",")
        val voiceProfileJson = charData.voiceProfile?.let { gson.toJson(it) }
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
            // Update existing character
            characterDao.update(
                existing.copy(
                    traits = if (traitsStr.isNotBlank()) traitsStr else existing.traits,
                    voiceProfileJson = voiceProfileJson ?: existing.voiceProfileJson,
                    speakerId = charData.speakerId ?: existing.speakerId,
                    dialogsJson = dialogsJson ?: existing.dialogsJson
                )
            )
            AppLogger.d(TAG, "Updated character: ${charData.name} (speakerId=${charData.speakerId})")
        } else {
            // Insert new character
            characterDao.insert(
                Character(
                    bookId = bookId,
                    name = charData.name,
                    traits = traitsStr,
                    personalitySummary = "",
                    voiceProfileJson = voiceProfileJson,
                    speakerId = charData.speakerId,
                    dialogsJson = dialogsJson
                )
            )
            AppLogger.d(TAG, "Saved new character: ${charData.name} (speakerId=${charData.speakerId})")
        }
    }

    /**
     * Release LLM resources.
     */
    suspend fun release() = modelMutex.withLock {
        llmModel?.release()
        llmModel = null
        AppLogger.d(TAG, "Released LLM resources")
    }

    /**
     * Check if model is loaded.
     */
    fun isModelLoaded(): Boolean = llmModel?.isModelLoaded() == true

    /**
     * Get execution provider (CPU/GPU).
     */
    fun getExecutionProvider(): String = llmModel?.getExecutionProvider() ?: "unknown"
}

