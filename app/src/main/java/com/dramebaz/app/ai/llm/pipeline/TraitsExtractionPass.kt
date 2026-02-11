package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Explicit Trait Extraction Pass (Background)
 *
 * Extracts ONLY explicitly stated traits for characters from the provided text.
 * This follows the docs/newPrompts.md specification with STRICT RULES.
 *
 * Token Budget: Prompt+Input 3000, Output 1000
 *
 * Input: Character name + aggregated context text where character appears
 * Output: List of explicitly stated traits (physical, behavioral, speech patterns, emotional states)
 */
class TraitsExtractionPass : AnalysisPass<TraitsExtractionInput, TraitsExtractionOutput> {

    companion object {
        private const val TAG = "TraitsExtractionPass"
    }

    override val passId: String = "traits_extraction"
    override val displayName: String = "Explicit Trait Extraction"

    private val gson = Gson()

    override suspend fun execute(
        model: LlmModel,
        input: TraitsExtractionInput,
        config: PassConfig
    ): TraitsExtractionOutput {
        val truncatedContext = input.contextText.take(config.maxSegmentChars)

        AppLogger.d(TAG, "Executing $passId for '${input.characterName}', ${truncatedContext.length} chars context")

        var currentContext = truncatedContext
        var attempt = 0

        while (attempt < config.maxRetries) {
            attempt++
            try {
                val userPrompt = ExtractionPrompts.buildTraitsExtractionPrompt(
                    listOf(input.characterName),
                    currentContext
                )

                val response = model.generateResponse(
                    systemPrompt = ExtractionPrompts.TRAITS_EXTRACTION_SYSTEM_PROMPT,
                    userPrompt = userPrompt,
                    maxTokens = config.maxTokens,
                    temperature = config.temperature
                )

                // Check for token limit error
                if (response.contains("Max number of tokens reached", ignoreCase = true)) {
                    AppLogger.w(TAG, "Token limit hit, reducing input (attempt $attempt)")
                    currentContext = reduceText(currentContext, config.tokenReductionOnRetry)
                    continue
                }

                val traits = parseResponse(response, input.characterName)
                AppLogger.d(TAG, "'${input.characterName}': ${traits.size} traits extracted")
                return TraitsExtractionOutput(input.characterName, traits, emptyMap())

            } catch (e: Exception) {
                if (e.message?.contains("Max number of tokens reached", ignoreCase = true) == true) {
                    currentContext = reduceText(currentContext, config.tokenReductionOnRetry)
                    continue
                }
                AppLogger.e(TAG, "Error on attempt $attempt for '${input.characterName}'", e)
            }
        }

        AppLogger.w(TAG, "All attempts failed for '${input.characterName}', returning empty")
        return TraitsExtractionOutput(input.characterName, emptyList(), emptyMap())
    }

    private fun parseResponse(response: String, characterName: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *>
                ?: return emptyList()

            // Parse new format: {"Traits": [{"CharacterName": ["trait1", "trait2"]}]}
            val traitsArray = obj["Traits"] as? List<*>
            if (traitsArray != null) {
                for (item in traitsArray) {
                    val charMap = item as? Map<*, *> ?: continue
                    // Check if this entry matches our character
                    val charTraits = charMap[characterName] as? List<*>
                        ?: charMap[characterName.lowercase()] as? List<*>
                        ?: charMap.values.firstOrNull() as? List<*>

                    if (charTraits != null) {
                        return charTraits
                            .mapNotNull { it as? String }
                            .map { it.trim() }
                            .filter { it.isNotBlank() && it.length <= 50 }
                    }
                }
            }

            // Fallback: Parse old format {"traits": ["trait1", "trait2"]}
            val traits = (obj["traits"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && it.length <= 50 }

            traits ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse response for $characterName", e)
            emptyList()
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()

        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        return if (start >= 0 && end > start) json.substring(start, end + 1) else json
    }

    private fun reduceText(text: String, tokenReduction: Int): String {
        val charsToRemove = tokenReduction * 4
        return if (text.length > charsToRemove) {
            text.dropLast(charsToRemove)
        } else {
            text.take(text.length / 2)
        }
    }
}

