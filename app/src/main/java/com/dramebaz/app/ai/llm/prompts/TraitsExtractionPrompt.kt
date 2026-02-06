package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Prompt definition for Traits Extraction.
 * 
 * Extracts character traits from context text.
 */
class TraitsExtractionPrompt : PromptDefinition<TraitsExtractionPromptInput, TraitsExtractionPromptOutput> {
    
    companion object {
        private const val TAG = "TraitsExtractionPrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "traits_extraction_v1"
    override val displayName: String = "Traits Extraction"
    override val purpose: String = "Extract character traits from story context"
    override val tokenBudget: TokenBudget = TokenBudget.TRAITS_EXTRACTION
    override val temperature: Float = 0.1f
    
    override val systemPrompt: String = """You are an extraction engine. Your only job is to output valid JSON. Do not add commentary."""
    
    override fun buildUserPrompt(input: TraitsExtractionPromptInput): String {
        return """Infer voice-relevant traits for "${input.characterName}" from the excerpt (gender, age, accent when inferable). Return ONLY valid JSON:
{"traits": ["trait1", "trait2"]}

<EXCERPT>
${input.contextText}
</EXCERPT>
Ensure the JSON is valid and contains no trailing commas."""
    }
    
    override fun prepareInput(input: TraitsExtractionPromptInput): TraitsExtractionPromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedContext = if (input.contextText.length > maxChars) {
            input.contextText.take(maxChars)
        } else {
            input.contextText
        }
        return input.copy(contextText = truncatedContext)
    }
    
    override fun parseResponse(response: String): TraitsExtractionPromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return TraitsExtractionPromptOutput("", emptyList())
            
            @Suppress("UNCHECKED_CAST")
            val traits = (map["traits"] as? List<*>)?.mapNotNull { it?.toString()?.trim() } ?: emptyList()
            
            TraitsExtractionPromptOutput("", traits)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse traits", e)
            TraitsExtractionPromptOutput("", emptyList())
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

