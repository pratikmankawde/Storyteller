package com.dramebaz.app.ai.llm.workflows

import android.content.Context

/**
 * Base interface for character analysis workflows.
 * 
 * Different LLM models require different analysis approaches:
 * - Qwen3 model uses a 3-pass workflow (names → dialogs → traits/voice)
 * - Gemma 3n model uses a 2-pass workflow (characters+profiles → dialogs)
 * 
 * Design Pattern: Strategy Pattern - allows different workflow implementations
 * to be used interchangeably based on the available model.
 */
interface AnalysisWorkflow {
    
    /**
     * Number of passes in this workflow.
     */
    val passCount: Int
    
    /**
     * Name of this workflow for logging.
     */
    val workflowName: String
    
    /**
     * Initialize the workflow and underlying model.
     * @param context Android context for model loading
     * @return true if initialization succeeded
     */
    suspend fun initialize(context: Context): Boolean
    
    /**
     * Check if the workflow is ready for analysis.
     */
    fun isReady(): Boolean
    
    /**
     * Release resources.
     */
    fun release()
    
    /**
     * Get the execution provider (e.g., "GPU (Vulkan)", "CPU", "LiteRT-LM GPU")
     */
    fun getExecutionProvider(): String
    
    /**
     * Analyze a chapter of text using this workflow.
     * 
     * @param bookId Book ID for database operations
     * @param chapterText Full chapter text to analyze
     * @param chapterIndex Current chapter index (0-based)
     * @param totalChapters Total chapters in book
     * @param onProgress Progress callback with message
     * @param onCharacterProcessed Callback when a character is fully processed
     * @return List of analyzed characters
     */
    suspend fun analyzeChapter(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int = 0,
        totalChapters: Int = 1,
        onProgress: ((String) -> Unit)? = null,
        onCharacterProcessed: ((String) -> Unit)? = null
    ): List<CharacterAnalysisResult>
}

/**
 * Result of character analysis containing all extracted information.
 */
data class CharacterAnalysisResult(
    val name: String,
    val traits: List<String>,
    val voiceProfile: Map<String, Any>,
    val dialogs: List<DialogEntry> = emptyList(),
    val speakerId: Int = -1
)

/**
 * Extracted dialog entry with speaker attribution.
 */
data class DialogEntry(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)

