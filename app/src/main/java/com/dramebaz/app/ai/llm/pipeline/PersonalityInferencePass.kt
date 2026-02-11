package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Personality Inference Pass (Background)
 *
 * Infers personality characteristics based ONLY on the extracted traits.
 * This follows the docs/newPrompts.md specification with STRICT RULES.
 *
 * Token Budget: Prompt+Input 3000, Output 1000
 *
 * Input: Character name + list of extracted traits
 * Output: 3-5 synthesized personality points grounded in evidence
 */
class PersonalityInferencePass : AnalysisPass<PersonalityInferenceInput, PersonalityInferenceOutput> {

    companion object {
        private const val TAG = "PersonalityInferencePass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 256, temperature = 0.15f)
    }

    private val gson = Gson()

    override val passId: String = "personality_inference"
    override val displayName: String = "Personality Inference"

    override suspend fun execute(
        model: LlmModel,
        input: PersonalityInferenceInput,
        config: PassConfig
    ): PersonalityInferenceOutput {
        AppLogger.d(TAG, "Inferring personality for '${input.characterName}' from ${input.traits.size} traits")

        // Handle empty/insufficient traits case early
        if (input.traits.isEmpty()) {
            AppLogger.d(TAG, "'${input.characterName}' has no traits, returning minimal inference")
            return PersonalityInferenceOutput(
                input.characterName,
                listOf("minor character", "limited information")
            )
        }

        val userPrompt = ExtractionPrompts.buildPersonalityInferencePrompt(
            input.characterName,
            input.traits
        )

        val response = model.generateResponse(
            systemPrompt = ExtractionPrompts.PERSONALITY_INFERENCE_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response, input.characterName)
    }

    private fun parseResponse(response: String, characterName: String): PersonalityInferenceOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()

            // Parse personality array, limit to 5 points max as per spec
            val personality = (map["personality"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.take(5)
                ?: emptyList()

            // If parsing returns empty, provide minimal inference
            if (personality.isEmpty()) {
                return PersonalityInferenceOutput(
                    characterName,
                    listOf("minor character", "limited information")
                )
            }

            PersonalityInferenceOutput(characterName, personality)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse personality inference response", e)
            PersonalityInferenceOutput(
                characterName,
                listOf("minor character", "limited information")
            )
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()

        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        return if (start >= 0 && end > start) json.substring(start, end + 1) else "{}"
    }
}

// Data classes for PersonalityInferencePass
data class PersonalityInferenceInput(
    val characterName: String,
    val traits: List<String>
)

data class PersonalityInferenceOutput(
    val characterName: String = "",
    val personality: List<String> = emptyList()
)

