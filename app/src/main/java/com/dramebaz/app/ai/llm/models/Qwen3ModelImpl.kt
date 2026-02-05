package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.ai.llm.Qwen3Model

/**
 * LlmModel implementation that wraps Qwen3Model.
 * Uses the Adapter Pattern to make Qwen3Model conform to the LlmModel interface.
 * 
 * This is the primary model for character analysis using Qwen3-1.7B-Q4_K_M.gguf
 * via llama.cpp JNI.
 */
class Qwen3ModelImpl(context: Context) : LlmModel {
    
    private val qwen3Model = Qwen3Model(context)
    
    override suspend fun loadModel(): Boolean {
        return qwen3Model.loadModel()
    }
    
    override fun isModelLoaded(): Boolean {
        return qwen3Model.isModelLoaded()
    }
    
    override fun release() {
        qwen3Model.release()
    }
    
    override fun getExecutionProvider(): String {
        return qwen3Model.getExecutionProvider()
    }
    
    override fun isUsingGpu(): Boolean {
        return qwen3Model.isUsingGpu()
    }
    
    override suspend fun extractCharacterNames(text: String): List<String> {
        return qwen3Model.extractCharacterNames(text)
    }
    
    override suspend fun extractDialogs(text: String, characterNames: List<String>): List<ExtractedDialog> {
        val dialogs = qwen3Model.extractDialogsFromPage(text, characterNames)
        return dialogs.map { dialog ->
            ExtractedDialog(
                speaker = dialog.speaker,
                text = dialog.text,
                emotion = dialog.emotion,
                intensity = dialog.intensity
            )
        }
    }
    
    override suspend fun extractTraitsAndVoiceProfile(
        characterName: String,
        context: String
    ): Pair<List<String>, Map<String, Any>> {
        val results = qwen3Model.pass3ExtractTraitsAndVoiceProfile(characterName, context)
        return results.firstOrNull()?.second ?: Pair(emptyList(), emptyMap())
    }
    
    override suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse? {
        return qwen3Model.analyzeChapter(chapterText)
    }
    
    override suspend fun extendedAnalysisJson(chapterText: String): String? {
        return qwen3Model.extendedAnalysisJson(chapterText)
    }
    
    override suspend fun generateStory(userPrompt: String): String {
        return qwen3Model.generateStory(userPrompt)
    }
    
    // ==================== Additional Qwen3-specific methods ====================
    // These methods are exposed for backward compatibility with existing code
    
    /**
     * Get the underlying Qwen3Model for access to additional methods.
     * Use sparingly - prefer the LlmModel interface methods.
     */
    fun getUnderlyingModel(): Qwen3Model = qwen3Model
    
    /**
     * Extract traits for a specific character.
     */
    suspend fun extractTraitsForCharacter(characterName: String, chapterText: String): List<String> {
        return qwen3Model.extractTraitsForCharacter(characterName, chapterText)
    }
    
    /**
     * Multi-character traits and voice profile extraction.
     */
    suspend fun pass3ExtractTraitsAndVoiceProfile(
        char1Name: String, char1Context: String,
        char2Name: String? = null, char2Context: String? = null,
        char3Name: String? = null, char3Context: String? = null,
        char4Name: String? = null, char4Context: String? = null
    ): List<Pair<String, Pair<List<String>, Map<String, Any>>>> {
        return qwen3Model.pass3ExtractTraitsAndVoiceProfile(
            char1Name, char1Context,
            char2Name, char2Context,
            char3Name, char3Context,
            char4Name, char4Context
        )
    }
    
    /**
     * Detect characters on a single page.
     */
    suspend fun detectCharactersOnPage(pageText: String): List<String> {
        return qwen3Model.detectCharactersOnPage(pageText)
    }
    
    /**
     * Extract key moments for a character.
     */
    suspend fun extractKeyMomentsForCharacter(
        characterName: String, 
        chapterText: String, 
        chapterTitle: String
    ): List<Map<String, String>> {
        return qwen3Model.extractKeyMomentsForCharacter(characterName, chapterText, chapterTitle)
    }
    
    /**
     * Extract relationships for a character.
     */
    suspend fun extractRelationshipsForCharacter(
        characterName: String,
        chapterText: String,
        allCharacterNames: List<String>
    ): List<Map<String, String>> {
        return qwen3Model.extractRelationshipsForCharacter(characterName, chapterText, allCharacterNames)
    }
    
    /**
     * Infer traits for a character from an excerpt.
     */
    suspend fun inferTraitsForCharacter(characterName: String, excerpt: String): List<String> {
        return qwen3Model.inferTraitsForCharacter(characterName, excerpt)
    }
    
    /**
     * Suggest voice profiles from JSON.
     */
    suspend fun suggestVoiceProfilesJson(charactersWithTraitsJson: String): String? {
        return qwen3Model.suggestVoiceProfilesJson(charactersWithTraitsJson)
    }
}

