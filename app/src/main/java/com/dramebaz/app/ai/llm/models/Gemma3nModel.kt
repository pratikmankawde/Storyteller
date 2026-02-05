package com.dramebaz.app.ai.llm.models

import android.content.Context
import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.ai.llm.LiteRtLmEngine
import com.dramebaz.app.utils.AppLogger

/**
 * Gemma 3n model implementation wrapping LiteRtLmEngine.
 * Uses the Gemma 3n E2B Lite model via LiteRT-LM for 2-pass character analysis.
 * 
 * Design Pattern: Adapter Pattern - wraps LiteRtLmEngine to implement LlmModel interface.
 * 
 * Model: gemma-3n-E2B-it-int4.litertlm (path from config)
 * 
 * Two-Pass Workflow:
 * - Pass 1: Extract character names + complete voice profiles (~3000 tokens per segment)
 * - Pass 2: Extract dialogs with speaker attribution (~1500 tokens per segment)
 */
class Gemma3nModel(private val context: Context) : LlmModel {
    companion object {
        private const val TAG = "Gemma3nModel"
    }

    private val liteRtLmEngine = LiteRtLmEngine(context)

    override suspend fun loadModel(): Boolean {
        AppLogger.i(TAG, "Loading Gemma 3n model via LiteRT-LM...")
        val success = liteRtLmEngine.initialize()
        if (success) {
            AppLogger.i(TAG, "Gemma 3n model loaded successfully: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "Failed to load Gemma 3n model")
        }
        return success
    }

    override fun isModelLoaded(): Boolean = liteRtLmEngine.isModelLoaded()

    override fun release() {
        liteRtLmEngine.release()
    }

    override fun getExecutionProvider(): String = liteRtLmEngine.getExecutionProvider()

    override fun isUsingGpu(): Boolean = liteRtLmEngine.isUsingGpu()

    // ==================== Character Analysis Methods ====================

    override suspend fun extractCharacterNames(text: String): List<String> {
        // Pass-1: Extract characters and voice profiles, then return just the names
        val segments = liteRtLmEngine.segmentTextForPass1(text)
        val allNames = mutableSetOf<String>()
        
        for (segment in segments) {
            val characters = liteRtLmEngine.pass1ExtractCharactersAndVoiceProfiles(segment)
            allNames.addAll(characters.map { it.name })
        }
        
        return allNames.toList()
    }

    override suspend fun extractDialogs(text: String, characterNames: List<String>): List<ExtractedDialog> {
        // Pass-2: Extract dialogs with speaker attribution
        val segments = liteRtLmEngine.segmentTextForPass2(text)
        val allDialogs = mutableListOf<ExtractedDialog>()
        
        for (segment in segments) {
            val dialogs = liteRtLmEngine.pass2ExtractDialogs(segment, characterNames)
            allDialogs.addAll(dialogs.map { d ->
                ExtractedDialog(
                    speaker = d.speaker,
                    text = d.text,
                    emotion = d.emotion,
                    intensity = d.intensity
                )
            })
        }
        
        return allDialogs
    }

    override suspend fun extractTraitsAndVoiceProfile(
        characterName: String,
        context: String
    ): Pair<List<String>, Map<String, Any>> {
        // For Gemma 2-pass workflow, traits and voice profiles are extracted in Pass-1
        // This method is used to re-extract for a single character if needed
        val characters = liteRtLmEngine.pass1ExtractCharactersAndVoiceProfiles(context)
        val character = characters.find { it.name.equals(characterName, ignoreCase = true) }
        return if (character != null) {
            Pair(character.traits, character.voiceProfile)
        } else {
            Pair(emptyList(), emptyMap())
        }
    }

    // ==================== Chapter Analysis Methods ====================

    override suspend fun analyzeChapter(chapterText: String): ChapterAnalysisResponse? {
        // Gemma 3n is optimized for 2-pass character extraction, not full chapter analysis
        // For full chapter analysis, use Qwen3Model or return null to fall back to stub
        AppLogger.w(TAG, "analyzeChapter not optimized for Gemma 3n, returning null")
        return null
    }

    override suspend fun extendedAnalysisJson(chapterText: String): String? {
        // Extended analysis not supported by Gemma 2-pass workflow
        AppLogger.w(TAG, "extendedAnalysisJson not supported for Gemma 3n, returning null")
        return null
    }

    // ==================== Story Generation ====================

    override suspend fun generateStory(userPrompt: String): String {
        // Story generation not supported by Gemma 3n model
        AppLogger.w(TAG, "generateStory not supported for Gemma 3n, returning empty")
        return ""
    }

    // ==================== Gemma-Specific Methods ====================

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
     * Segment text for Pass 1 analysis.
     */
    fun segmentTextForPass1(fullText: String): List<String> {
        return liteRtLmEngine.segmentTextForPass1(fullText)
    }

    /**
     * Segment text for Pass 2 analysis.
     */
    fun segmentTextForPass2(fullText: String): List<String> {
        return liteRtLmEngine.segmentTextForPass2(fullText)
    }
}

