package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Pass 1: Character Name Extraction
 * 
 * Extracts character names from story text. This pass is model-agnostic
 * and can work with any LlmModel implementation.
 * 
 * Input: Story text segment
 * Output: List of character names found in the text
 */
class CharacterExtractionPass : AnalysisPass<CharacterExtractionInput, CharacterExtractionOutput> {
    
    companion object {
        private const val TAG = "CharacterExtractionPass"
    }
    
    override val passId: String = "character_extraction"
    override val displayName: String = "Character Extraction (Pass 1)"
    
    private val gson = Gson()
    
    override suspend fun execute(
        model: LlmModel,
        input: CharacterExtractionInput,
        config: PassConfig
    ): CharacterExtractionOutput {
        val truncatedText = input.text.take(config.maxSegmentChars)
        
        AppLogger.d(TAG, "Executing $passId: segment ${input.segmentIndex + 1}/${input.totalSegments}, ${truncatedText.length} chars")
        
        var currentText = truncatedText
        var attempt = 0
        
        while (attempt < config.maxRetries) {
            attempt++
            try {
                val userPrompt = ExtractionPrompts.buildPass1ExtractNamesPrompt(currentText, config.maxSegmentChars)
                
                val response = model.generateResponse(
                    systemPrompt = ExtractionPrompts.PASS1_SYSTEM_PROMPT,
                    userPrompt = userPrompt,
                    maxTokens = config.maxTokens,
                    temperature = config.temperature
                )
                
                // Check for token limit error
                if (response.contains("Max number of tokens reached", ignoreCase = true)) {
                    AppLogger.w(TAG, "Token limit hit, reducing input (attempt $attempt)")
                    currentText = reduceText(currentText, config.tokenReductionOnRetry)
                    continue
                }
                
                val names = parseResponse(response)
                AppLogger.d(TAG, "Extracted ${names.size} characters: $names")
                return CharacterExtractionOutput(names)
                
            } catch (e: Exception) {
                if (e.message?.contains("Max number of tokens reached", ignoreCase = true) == true) {
                    currentText = reduceText(currentText, config.tokenReductionOnRetry)
                    continue
                }
                AppLogger.e(TAG, "Error on attempt $attempt", e)
            }
        }
        
        AppLogger.w(TAG, "All attempts failed, returning empty list")
        return CharacterExtractionOutput(emptyList())
    }
    
    private fun parseResponse(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            
            @Suppress("UNCHECKED_CAST")
            val characters = (obj["characters"] as? List<*>) ?: (obj["names"] as? List<*>)
            
            characters?.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().takeIf { it.isNotBlank() }
                    else -> (entry as? Map<*, *>)?.get("name")?.toString()?.trim()?.takeIf { it.isNotBlank() }
                }
            }?.distinctBy { it.lowercase() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse response", e)
            emptyList()
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
    
    private fun reduceText(text: String, tokenReduction: Int): String {
        val charsToRemove = tokenReduction * 4 // ~4 chars per token
        return if (text.length > charsToRemove) {
            text.dropLast(charsToRemove)
        } else {
            text.take(text.length / 2)
        }
    }
}

