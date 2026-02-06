package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for inferring character traits from an excerpt.
 * Corresponds to GgufEngine.inferTraitsForCharacter()
 */
class InferTraitsPass : AnalysisPass<InferTraitsInput, InferTraitsOutput> {

    companion object {
        private const val TAG = "InferTraitsPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 256, temperature = 0.15f, maxSegmentChars = 5000)
    }

    private val gson = Gson()

    override val passId: String = "infer_traits"
    override val displayName: String = "Infer Traits"

    override suspend fun execute(
        model: LlmModel,
        input: InferTraitsInput,
        config: PassConfig
    ): InferTraitsOutput {
        AppLogger.d(TAG, "Inferring traits for '${input.characterName}' (excerpt=${input.excerpt.length} chars)")

        val userPrompt = StoryPrompts.buildInferTraitsPrompt(input.characterName, input.excerpt)

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.INFER_TRAITS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response, input.characterName)
    }
    
    private fun parseResponse(response: String, characterName: String): InferTraitsOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val traits = (map["traits"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            
            InferTraitsOutput(characterName, traits)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse infer traits response", e)
            InferTraitsOutput(characterName)
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for InferTraitsPass
data class InferTraitsInput(
    val characterName: String,
    val excerpt: String
)

data class InferTraitsOutput(
    val characterName: String = "",
    val traits: List<String> = emptyList()
)

