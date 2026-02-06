package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Prompt definition for Key Moments Extraction.
 * 
 * Extracts significant moments for a character from chapter text.
 */
class KeyMomentsPrompt : PromptDefinition<KeyMomentsPromptInput, KeyMomentsPromptOutput> {
    
    companion object {
        private const val TAG = "KeyMomentsPrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "key_moments_v1"
    override val displayName: String = "Key Moments Extraction"
    override val purpose: String = "Extract significant moments for a character from chapter text"
    override val tokenBudget: TokenBudget = TokenBudget.KEY_MOMENTS
    override val temperature: Float = 0.2f
    
    override val systemPrompt: String = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    override fun buildUserPrompt(input: KeyMomentsPromptInput): String {
        return """Extract 2-3 key moments for "${input.characterName}" in this chapter. Key moments are significant events, decisions, revelations, or emotional scenes involving this character.
Return ONLY valid JSON:
{"moments": [{"chapter": "${input.chapterTitle}", "moment": "brief description", "significance": "why it matters"}]}

<TEXT>
${input.chapterText}
</TEXT>
Ensure the JSON is valid and contains no trailing commas."""
    }
    
    override fun prepareInput(input: KeyMomentsPromptInput): KeyMomentsPromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.chapterText.length > maxChars) {
            input.chapterText.take(maxChars)
        } else {
            input.chapterText
        }
        return input.copy(chapterText = truncatedText)
    }
    
    override fun parseResponse(response: String): KeyMomentsPromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return KeyMomentsPromptOutput("", emptyList())
            
            @Suppress("UNCHECKED_CAST")
            val momentsList = map["moments"] as? List<*> ?: return KeyMomentsPromptOutput("", emptyList())
            
            val moments = momentsList.mapNotNull { item ->
                val momentMap = item as? Map<*, *> ?: return@mapNotNull null
                KeyMomentData(
                    chapter = momentMap["chapter"]?.toString() ?: "",
                    moment = momentMap["moment"]?.toString() ?: return@mapNotNull null,
                    significance = momentMap["significance"]?.toString() ?: ""
                )
            }
            
            KeyMomentsPromptOutput("", moments)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse key moments", e)
            KeyMomentsPromptOutput("", emptyList())
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

