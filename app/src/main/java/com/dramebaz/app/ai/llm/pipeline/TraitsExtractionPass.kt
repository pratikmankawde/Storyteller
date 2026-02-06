package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Pass 3: Traits and Voice Profile Extraction
 * 
 * Extracts character traits and suggests TTS voice profile based on context.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Character name + aggregated context text where character appears
 * Output: Traits list + voice profile map for TTS
 */
class TraitsExtractionPass : AnalysisPass<TraitsExtractionInput, TraitsExtractionOutput> {
    
    companion object {
        private const val TAG = "TraitsExtractionPass"
    }
    
    override val passId: String = "traits_extraction"
    override val displayName: String = "Traits & Voice Profile (Pass 3)"
    
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
                val userPrompt = ExtractionPrompts.buildPass3TraitsPrompt(input.characterName, currentContext)
                
                val response = model.generateResponse(
                    systemPrompt = ExtractionPrompts.PASS3_SYSTEM_PROMPT,
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
                
                val (traits, voiceProfile) = parseResponse(response, input.characterName)
                AppLogger.d(TAG, "'${input.characterName}': ${traits.size} traits, profile=$voiceProfile")
                return TraitsExtractionOutput(input.characterName, traits, voiceProfile)
                
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
    
    private fun parseResponse(response: String, characterName: String): Pair<List<String>, Map<String, Any>> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *>
                ?: return Pair(emptyList(), emptyMap())
            
            // Parse traits
            val traits = (obj["traits"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && it.length <= 50 }
                ?: emptyList()
            
            // Parse voice profile
            val voiceProfile = mutableMapOf<String, Any>()
            val profileObj = obj["voice_profile"] as? Map<*, *>
            
            if (profileObj != null) {
                profileObj["gender"]?.let { voiceProfile["gender"] = it }
                profileObj["age"]?.let { voiceProfile["age"] = it }
                profileObj["tone"]?.let { voiceProfile["tone"] = it }
                profileObj["accent"]?.let { voiceProfile["accent"] = it }
                profileObj["speaker_id"]?.let { voiceProfile["speaker_id"] = it }
                voiceProfile["pitch"] = (profileObj["pitch"] as? Number)?.toDouble() ?: 1.0
                voiceProfile["speed"] = (profileObj["speed"] as? Number)?.toDouble() ?: 1.0
                voiceProfile["energy"] = (profileObj["energy"] as? Number)?.toDouble() ?: 1.0
            }
            
            Pair(traits, voiceProfile)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse response for $characterName", e)
            Pair(emptyList(), emptyMap())
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

