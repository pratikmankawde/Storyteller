package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass 2: Dialog Extraction with Speaker Attribution
 * 
 * Extracts dialogs from story text and attributes them to characters.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Story text segment + list of known character names
 * Output: List of dialogs with speaker, text, emotion, and intensity
 */
class DialogExtractionPass : AnalysisPass<DialogExtractionInput, DialogExtractionOutput> {
    
    companion object {
        private const val TAG = "DialogExtractionPass"
    }
    
    override val passId: String = "dialog_extraction"
    override val displayName: String = "Dialog Extraction (Pass 2)"
    
    private val gson = Gson()
    
    override suspend fun execute(
        model: LlmModel,
        input: DialogExtractionInput,
        config: PassConfig
    ): DialogExtractionOutput {
        val truncatedText = input.text.take(config.maxSegmentChars)
        
        AppLogger.d(TAG, "Executing $passId: segment ${input.segmentIndex + 1}/${input.totalSegments}, " +
                "${truncatedText.length} chars, ${input.characterNames.size} characters")
        
        var currentText = truncatedText
        var attempt = 0
        
        while (attempt < config.maxRetries) {
            attempt++
            try {
                val userPrompt = ExtractionPrompts.buildPass2ExtractDialogsPrompt(
                    currentText, 
                    input.characterNames,
                    config.maxSegmentChars
                )
                
                val response = model.generateResponse(
                    systemPrompt = ExtractionPrompts.PASS2_SYSTEM_PROMPT,
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
                
                val dialogs = parseResponse(response)
                AppLogger.d(TAG, "Extracted ${dialogs.size} dialogs")
                return DialogExtractionOutput(dialogs)
                
            } catch (e: Exception) {
                if (e.message?.contains("Max number of tokens reached", ignoreCase = true) == true) {
                    currentText = reduceText(currentText, config.tokenReductionOnRetry)
                    continue
                }
                AppLogger.e(TAG, "Error on attempt $attempt", e)
            }
        }
        
        AppLogger.w(TAG, "All attempts failed, returning empty list")
        return DialogExtractionOutput(emptyList())
    }
    
    private fun parseResponse(response: String): List<ExtractedDialog> {
        return try {
            val json = extractJsonFromResponse(response)
            
            // Try standard format: {"dialogs": [...]}
            val obj = try {
                gson.fromJson(json, Map::class.java) as? Map<*, *>
            } catch (e: Exception) { null }
            
            if (obj != null && obj.containsKey("dialogs")) {
                @Suppress("UNCHECKED_CAST")
                val dialogs = obj["dialogs"] as? List<*> ?: return emptyList()
                return dialogs.mapNotNull { parseDialogObject(it) }
            }
            
            // Fallback: array of {speaker: text} objects
            @Suppress("UNCHECKED_CAST")
            val list = try {
                gson.fromJson<List<Map<String, Any>>>(json, object : TypeToken<List<Map<String, Any>>>() {}.type)
            } catch (e: Exception) { null }
            
            list?.mapNotNull { map ->
                if (map.size != 1) return@mapNotNull null
                val (speaker, text) = map.entries.firstOrNull() ?: return@mapNotNull null
                val textStr = (text as? String)?.trim() ?: text?.toString()?.trim() ?: return@mapNotNull null
                if (textStr.isBlank()) return@mapNotNull null
                ExtractedDialog(speaker.trim(), textStr, "neutral", 0.5f)
            } ?: emptyList()
            
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse response", e)
            emptyList()
        }
    }
    
    private fun parseDialogObject(obj: Any?): ExtractedDialog? {
        val dialogMap = obj as? Map<*, *> ?: return null
        val speaker = (dialogMap["speaker"] as? String)?.trim() ?: return null
        val text = (dialogMap["text"] as? String)?.trim() ?: return null
        if (text.isBlank()) return null
        val emotion = (dialogMap["emotion"] as? String)?.trim()?.lowercase() ?: "neutral"
        val intensity = (dialogMap["intensity"] as? Number)?.toFloat() ?: 0.5f
        return ExtractedDialog(speaker, text, emotion, intensity.coerceIn(0f, 1f))
    }
    
    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()
        
        val objStart = json.indexOf('{')
        val objEnd = json.lastIndexOf('}')
        val arrStart = json.indexOf('[')
        val arrEnd = json.lastIndexOf(']')
        
        return when {
            objStart >= 0 && objEnd > objStart -> json.substring(objStart, objEnd + 1)
            arrStart >= 0 && arrEnd > arrStart -> json.substring(arrStart, arrEnd + 1)
            else -> json
        }
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

