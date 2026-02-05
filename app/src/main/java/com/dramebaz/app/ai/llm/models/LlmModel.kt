package com.dramebaz.app.ai.llm.models

import com.dramebaz.app.ai.llm.ChapterAnalysisResponse

/**
 * Interface defining the contract for all LLM model implementations.
 * Provides a unified API for character extraction, dialog analysis, and story generation.
 * 
 * Design Pattern: Strategy Pattern - allows different LLM implementations to be used interchangeably.
 */
interface LlmModel {
    
    /**
     * Load the model. Returns true if successful.
     */
    suspend fun loadModel(): Boolean
    
    /**
     * Check if the model is loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean
    
    /**
     * Release model resources.
     */
    fun release()
    
    /**
     * Get the execution provider (e.g., "GPU (Vulkan)", "CPU", "LiteRT-LM GPU")
     */
    fun getExecutionProvider(): String
    
    /**
     * Check if model is using GPU acceleration.
     */
    fun isUsingGpu(): Boolean
    
    // ==================== Character Analysis Methods ====================
    
    /**
     * Pass-1: Extract character names from text.
     * @param text Chapter or page text to analyze
     * @return List of character names found
     */
    suspend fun extractCharacterNames(text: String): List<String>
    
    /**
     * Pass-2: Extract dialogs with speaker attribution.
     * @param text Page text to analyze
     * @param characterNames Known character names for attribution
     * @return List of extracted dialogs
     */
    suspend fun extractDialogs(text: String, characterNames: List<String>): List<ExtractedDialog>
    
    /**
     * Pass-3: Extract traits and voice profile for characters.
     * @param characterName Character to analyze
     * @param context Aggregated text where character appears
     * @return Pair of (traits list, voice profile map)
     */
    suspend fun extractTraitsAndVoiceProfile(
        characterName: String,
        context: String
    ): Pair<List<String>, Map<String, Any>>
    
    // ==================== Chapter Analysis Methods ====================
    
    /**
     * Analyze chapter for summary, characters, dialogs, and sound cues.
     */
    suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse?
    
    /**
     * Extended analysis for themes, symbols, vocabulary, foreshadowing.
     * @return JSON string with extended analysis
     */
    suspend fun extendedAnalysisJson(chapterText: String): String?
    
    // ==================== Story Generation ====================
    
    /**
     * Generate a story based on user prompt.
     */
    suspend fun generateStory(userPrompt: String): String
}

/**
 * Data class for extracted dialog entries.
 */
data class ExtractedDialog(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)

