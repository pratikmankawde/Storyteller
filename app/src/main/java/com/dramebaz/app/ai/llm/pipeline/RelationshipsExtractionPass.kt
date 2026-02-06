package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for extracting character relationships.
 * Corresponds to GgufEngine.extractRelationshipsForCharacter()
 */
class RelationshipsExtractionPass : AnalysisPass<RelationshipsInput, RelationshipsOutput> {

    companion object {
        private const val TAG = "RelationshipsExtractionPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 512, temperature = 0.2f, maxSegmentChars = 10_000)
    }

    private val gson = Gson()

    override val passId: String = "relationships_extraction"
    override val displayName: String = "Relationships Extraction"

    override suspend fun execute(
        model: LlmModel,
        input: RelationshipsInput,
        config: PassConfig
    ): RelationshipsOutput {
        AppLogger.d(TAG, "Extracting relationships for '${input.characterName}' with ${input.otherCharacters.size} others")

        val userPrompt = StoryPrompts.buildRelationshipsPrompt(
            input.characterName,
            input.chapterText,
            input.otherCharacters,
            config.maxSegmentChars
        )

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.RELATIONSHIPS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response, input.characterName)
    }
    
    private fun parseResponse(response: String, characterName: String): RelationshipsOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val relList = map["relationships"] as? List<*> ?: emptyList<Any>()
            val relationships = relList.mapNotNull { item ->
                val relMap = item as? Map<*, *> ?: return@mapNotNull null
                CharacterRelationship(
                    character = relMap["character"] as? String ?: return@mapNotNull null,
                    relationship = relMap["relationship"] as? String ?: "other",
                    nature = relMap["nature"] as? String ?: ""
                )
            }
            
            RelationshipsOutput(characterName, relationships)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse relationships response", e)
            RelationshipsOutput(characterName)
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for RelationshipsExtractionPass
data class RelationshipsInput(
    val characterName: String,
    val chapterText: String,
    val otherCharacters: List<String>
)

data class RelationshipsOutput(
    val characterName: String = "",
    val relationships: List<CharacterRelationship> = emptyList()
)

data class CharacterRelationship(
    val character: String,
    val relationship: String,  // family, friend, enemy, romantic, professional, other
    val nature: String  // brief description
)

