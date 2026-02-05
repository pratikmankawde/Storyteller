package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.ai.llm.LiteRtLmEngine

/**
 * LlmModel implementation that wraps LiteRtLmEngine.
 * Uses the Adapter Pattern to make LiteRtLmEngine conform to the LlmModel interface.
 * 
 * This is the secondary model using Gemma 3n via LiteRT-LM.
 */
class LiteRtLmEngineImpl(context: Context) : LlmModel {
    
    private val liteRtLmEngine = LiteRtLmEngine(context)
    
    override suspend fun loadModel(): Boolean {
        return liteRtLmEngine.initialize()
    }
    
    override fun isModelLoaded(): Boolean {
        return liteRtLmEngine.isModelLoaded()
    }
    
    override fun release() {
        liteRtLmEngine.release()
    }
    
    override fun getExecutionProvider(): String {
        return liteRtLmEngine.getExecutionProvider()
    }
    
    override fun isUsingGpu(): Boolean {
        return liteRtLmEngine.isUsingGpu()
    }
    
    override suspend fun extractCharacterNames(text: String): List<String> {
        val profiles = liteRtLmEngine.pass1ExtractCharactersAndVoiceProfiles(text)
        return profiles.map { it.name }
    }
    
    override suspend fun extractDialogs(text: String, characterNames: List<String>): List<ExtractedDialog> {
        val dialogs = liteRtLmEngine.pass2ExtractDialogs(text, characterNames)
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
        // LiteRtLmEngine extracts profiles during pass1, so we need to run it again
        // for the specific character's context
        val profiles = liteRtLmEngine.pass1ExtractCharactersAndVoiceProfiles(context)
        val profile = profiles.find { it.name.equals(characterName, ignoreCase = true) }
        return if (profile != null) {
            Pair(profile.traits, profile.voiceProfile)
        } else {
            Pair(emptyList(), emptyMap())
        }
    }
    
    override suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse? {
        // LiteRtLmEngine doesn't have a direct chapter analysis method like Qwen3Model
        // Return null - callers should use the pass-based methods instead
        return null
    }
    
    override suspend fun extendedAnalysisJson(chapterText: String): String? {
        // LiteRtLmEngine doesn't support extended analysis
        return null
    }
    
    override suspend fun generateStory(userPrompt: String): String {
        // LiteRtLmEngine doesn't have story generation
        // Could implement using the generate() method if needed
        return ""
    }
    
    // ==================== Additional LiteRtLmEngine-specific methods ====================
    
    /**
     * Get the underlying LiteRtLmEngine for access to additional methods.
     */
    fun getUnderlyingEngine(): LiteRtLmEngine = liteRtLmEngine
    
    /**
     * Pass 1: Extract characters with full voice profiles.
     */
    suspend fun pass1ExtractCharactersAndVoiceProfiles(segmentText: String): List<LiteRtLmEngine.CharacterWithProfile> {
        return liteRtLmEngine.pass1ExtractCharactersAndVoiceProfiles(segmentText)
    }
    
    /**
     * Pass 2: Extract dialogs with speaker attribution.
     */
    suspend fun pass2ExtractDialogs(segmentText: String, characterNames: List<String>): List<LiteRtLmEngine.ExtractedDialog> {
        return liteRtLmEngine.pass2ExtractDialogs(segmentText, characterNames)
    }
    
    /**
     * Segment text for Pass 1 processing.
     */
    fun segmentTextForPass1(fullText: String): List<String> {
        return liteRtLmEngine.segmentTextForPass1(fullText)
    }
    
    /**
     * Segment text for Pass 2 processing.
     */
    fun segmentTextForPass2(fullText: String): List<String> {
        return liteRtLmEngine.segmentTextForPass2(fullText)
    }
    
    /**
     * Generate a response with custom system and user prompts.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 2048,
        temperature: Float = 0.6f
    ): String {
        return liteRtLmEngine.generate(systemPrompt, userPrompt, maxTokens, temperature)
    }
}

