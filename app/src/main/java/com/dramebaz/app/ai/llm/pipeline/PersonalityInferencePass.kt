package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for inferring personality traits from extracted traits.
 * Corresponds to GgufEngine.inferPersonalityFromTraits()
 */
class PersonalityInferencePass : AnalysisPass<PersonalityInferenceInput, PersonalityInferenceOutput> {

    companion object {
        private const val TAG = "PersonalityInferencePass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 256, temperature = 0.15f)

        private const val SYSTEM_PROMPT = "You are a character personality analyzer. Given traits, infer deeper personality characteristics. Output valid JSON only."
        private const val JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."
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

        val userPrompt = buildPrompt(input)

        val response = model.generateResponse(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response, input.characterName)
    }
    
    private fun buildPrompt(input: PersonalityInferenceInput): String {
        val traitsJson = gson.toJson(input.traits)
        return """CHARACTER: "${input.characterName}"
OBSERVED TRAITS: $traitsJson

Based on these observed traits, infer deeper personality characteristics:
- Communication style (verbose/concise, formal/casual)
- Emotional tendencies (optimistic/pessimistic, calm/anxious)
- Social behavior (introverted/extroverted, leader/follower)
- Core motivations (what drives this character)

Return ONLY valid JSON:
{"personality": ["trait1", "trait2", "trait3", "trait4"]}
$JSON_VALIDITY_REMINDER"""
    }
    
    private fun parseResponse(response: String, characterName: String): PersonalityInferenceOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val personality = (map["personality"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            
            PersonalityInferenceOutput(characterName, personality)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse personality inference response", e)
            PersonalityInferenceOutput(characterName)
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
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

