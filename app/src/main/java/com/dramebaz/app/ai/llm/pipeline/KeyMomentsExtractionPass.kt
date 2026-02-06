package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for extracting key moments for a character.
 * Corresponds to GgufEngine.extractKeyMomentsForCharacter()
 */
class KeyMomentsExtractionPass : AnalysisPass<KeyMomentsInput, KeyMomentsOutput> {

    companion object {
        private const val TAG = "KeyMomentsExtractionPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 512, temperature = 0.2f, maxSegmentChars = 10_000)
    }

    private val gson = Gson()

    override val passId: String = "key_moments_extraction"
    override val displayName: String = "Key Moments Extraction"

    override suspend fun execute(
        model: LlmModel,
        input: KeyMomentsInput,
        config: PassConfig
    ): KeyMomentsOutput {
        AppLogger.d(TAG, "Extracting key moments for '${input.characterName}' in '${input.chapterTitle}'")

        val userPrompt = StoryPrompts.buildKeyMomentsPrompt(
            input.characterName,
            input.chapterText,
            input.chapterTitle,
            config.maxSegmentChars
        )

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.KEY_MOMENTS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response, input.characterName)
    }
    
    private fun parseResponse(response: String, characterName: String): KeyMomentsOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val momentsList = map["moments"] as? List<*> ?: emptyList<Any>()
            val moments = momentsList.mapNotNull { item ->
                val momentMap = item as? Map<*, *> ?: return@mapNotNull null
                KeyMoment(
                    chapter = momentMap["chapter"] as? String ?: "",
                    moment = momentMap["moment"] as? String ?: return@mapNotNull null,
                    significance = momentMap["significance"] as? String ?: ""
                )
            }
            
            KeyMomentsOutput(characterName, moments)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse key moments response", e)
            KeyMomentsOutput(characterName)
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for KeyMomentsExtractionPass
data class KeyMomentsInput(
    val characterName: String,
    val chapterText: String,
    val chapterTitle: String
)

data class KeyMomentsOutput(
    val characterName: String = "",
    val moments: List<KeyMoment> = emptyList()
)

data class KeyMoment(
    val chapter: String,
    val moment: String,
    val significance: String
)

