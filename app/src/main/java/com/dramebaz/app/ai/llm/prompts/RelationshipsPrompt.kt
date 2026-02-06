package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Prompt definition for Relationships Extraction.
 * 
 * Extracts relationships between a character and other characters.
 */
class RelationshipsPrompt : PromptDefinition<RelationshipsPromptInput, RelationshipsPromptOutput> {
    
    companion object {
        private const val TAG = "RelationshipsPrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "relationships_v1"
    override val displayName: String = "Relationships Extraction"
    override val purpose: String = "Extract relationships between characters"
    override val tokenBudget: TokenBudget = TokenBudget.RELATIONSHIPS
    override val temperature: Float = 0.15f
    
    override val systemPrompt: String = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    override fun buildUserPrompt(input: RelationshipsPromptInput): String {
        val otherNames = input.otherCharacters
            .filter { !it.equals(input.characterName, ignoreCase = true) }
            .take(20)
            .joinToString(", ")
        
        return """Extract relationships between "${input.characterName}" and other characters: $otherNames
Relationship types: family, friend, enemy, romantic, professional, other.
Return ONLY valid JSON:
{"relationships": [{"character": "other character name", "relationship": "type", "nature": "brief description"}]}

<TEXT>
${input.chapterText}
</TEXT>
Ensure the JSON is valid and contains no trailing commas."""
    }
    
    override fun prepareInput(input: RelationshipsPromptInput): RelationshipsPromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.chapterText.length > maxChars) {
            input.chapterText.take(maxChars)
        } else {
            input.chapterText
        }
        return input.copy(chapterText = truncatedText)
    }
    
    override fun parseResponse(response: String): RelationshipsPromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return RelationshipsPromptOutput("", emptyList())
            
            @Suppress("UNCHECKED_CAST")
            val relationshipsList = map["relationships"] as? List<*> ?: return RelationshipsPromptOutput("", emptyList())
            
            val relationships = relationshipsList.mapNotNull { item ->
                val relMap = item as? Map<*, *> ?: return@mapNotNull null
                RelationshipData(
                    character = relMap["character"]?.toString() ?: return@mapNotNull null,
                    relationship = relMap["relationship"]?.toString() ?: "other",
                    nature = relMap["nature"]?.toString() ?: ""
                )
            }
            
            RelationshipsPromptOutput("", relationships)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse relationships", e)
            RelationshipsPromptOutput("", emptyList())
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()
        
        val objStart = json.indexOf('{')
        val objEnd = json.lastIndexOf('}')
        
        return if (objStart >= 0 && objEnd > objStart) {
            json.substring(objStart, objEnd + 1)
        } else {
            json
        }
    }
}

