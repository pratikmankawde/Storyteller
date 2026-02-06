package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Prompt definition for Pass-1: Character Name Extraction.
 * 
 * Extracts character names from story text.
 * Token Budget: Prompt+Input 3500, Output 100
 */
class CharacterExtractionPrompt : PromptDefinition<CharacterExtractionPromptInput, CharacterExtractionPromptOutput> {
    
    companion object {
        private const val TAG = "CharacterExtractionPrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "character_extraction_v1"
    override val displayName: String = "Character Name Extraction"
    override val purpose: String = "Extract character names from story text"
    override val tokenBudget: TokenBudget = TokenBudget.PASS1_CHARACTER_EXTRACTION
    override val temperature: Float = 0.1f
    
    override val systemPrompt: String = """You are a character name extraction engine. Extract ONLY character names that appear in the provided story text."""
    
    override fun buildUserPrompt(input: CharacterExtractionPromptInput): String {
        return """OUTPUT FORMAT (valid JSON only):
{"characters": ["Name1", "Name2", "Name3"]}

TEXT:
${input.text}"""
    }
    
    override fun prepareInput(input: CharacterExtractionPromptInput): CharacterExtractionPromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.text.length > maxChars) {
            input.text.take(maxChars)
        } else {
            input.text
        }
        return input.copy(text = truncatedText)
    }
    
    override fun parseResponse(response: String): CharacterExtractionPromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return CharacterExtractionPromptOutput(emptyList())
            
            @Suppress("UNCHECKED_CAST")
            val characters = (obj["characters"] as? List<*>) ?: (obj["names"] as? List<*>)
            
            val names = characters?.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().takeIf { it.isNotBlank() }
                    else -> (entry as? Map<*, *>)?.get("name")?.toString()?.trim()?.takeIf { it.isNotBlank() }
                }
            }?.distinctBy { it.lowercase() } ?: emptyList()
            
            CharacterExtractionPromptOutput(names)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse response", e)
            CharacterExtractionPromptOutput(emptyList())
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

