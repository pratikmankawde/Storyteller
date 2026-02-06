package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for detecting character names on a page.
 * Corresponds to GgufEngine.detectCharactersOnPage()
 */
class CharacterDetectionPass : AnalysisPass<CharacterDetectionInput, CharacterDetectionOutput> {

    companion object {
        private const val TAG = "CharacterDetectionPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 256, temperature = 0.1f, maxSegmentChars = 10_000)
    }

    private val gson = Gson()

    override val passId: String = "character_detection"
    override val displayName: String = "Character Detection"

    override suspend fun execute(
        model: LlmModel,
        input: CharacterDetectionInput,
        config: PassConfig
    ): CharacterDetectionOutput {
        AppLogger.d(TAG, "Detecting characters on page (text=${input.pageText.length} chars)")

        val userPrompt = StoryPrompts.buildDetectCharactersPrompt(input.pageText, config.maxSegmentChars)

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.DETECT_CHARACTERS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response)
    }
    
    private fun parseResponse(response: String): CharacterDetectionOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val names = (map["names"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            
            CharacterDetectionOutput(names)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse character detection response", e)
            CharacterDetectionOutput()
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for CharacterDetectionPass
data class CharacterDetectionInput(
    val pageText: String
)

data class CharacterDetectionOutput(
    val characterNames: List<String> = emptyList()
)

