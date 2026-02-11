package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Prompt definition for Pass-2: Dialog Extraction.
 * 
 * Extracts dialogs and attributes them to characters.
 * Token Budget: Prompt+Input 1800, Output 2200
 */
class DialogExtractionPrompt : PromptDefinition<DialogExtractionPromptInput, DialogExtractionPromptOutput> {
    
    companion object {
        private const val TAG = "DialogExtractionPrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "dialog_extraction_v1"
    override val displayName: String = "Dialog Extraction"
    override val purpose: String = "Extract dialogs and attribute them to characters"
    override val tokenBudget: TokenBudget = TokenBudget.PASS2_DIALOG_EXTRACTION
    override val temperature: Float = 0.15f
    
    override val systemPrompt: String = """You are a dialog extraction engine. Read the text sequentially and extract all the dialogs of the given characters in the story excerpt."""
    
    override fun buildUserPrompt(input: DialogExtractionPromptInput): String {
        val characterNamesStr = input.characterNames.joinToString(", ")
        
        return """Extract all dialogs for: $characterNamesStr

OUTPUT FORMAT (valid JSON array):
[{"<character_name>": "<dialog_text>"}]

Example:
[{"Harry": "I'm not going back"}, {"Hermione": "We need to study"}, {"Harry": "Later"}]

TEXT:
${input.text}"""
    }
    
    override fun prepareInput(input: DialogExtractionPromptInput): DialogExtractionPromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.text.length > maxChars) {
            input.text.take(maxChars)
        } else {
            input.text
        }
        return input.copy(text = truncatedText)
    }
    
    override fun parseResponse(response: String): DialogExtractionPromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            AppLogger.d(TAG, "Raw response (first 500 chars): ${response.take(500)}")
            AppLogger.d(TAG, "Extracted JSON (first 500 chars): ${json.take(500)}")
            val dialogs = mutableListOf<ExtractedDialogData>()

            // Try standard format: {"dialogs": [...]}
            val obj = try {
                gson.fromJson(json, Map::class.java) as? Map<*, *>
            } catch (e: Exception) { null }

            if (obj != null && obj.containsKey("dialogs")) {
                @Suppress("UNCHECKED_CAST")
                val dialogList = obj["dialogs"] as? List<*> ?: return DialogExtractionPromptOutput(emptyList())
                dialogList.mapNotNullTo(dialogs) { parseDialogObject(it) }
                AppLogger.d(TAG, "Parsed ${dialogs.size} dialogs from 'dialogs' key format")
                return DialogExtractionPromptOutput(dialogs)
            }

            // Fallback: array of {speaker: text} objects
            @Suppress("UNCHECKED_CAST")
            val list = try {
                gson.fromJson<List<Map<String, Any>>>(json, object : TypeToken<List<Map<String, Any>>>() {}.type)
            } catch (e: Exception) { null }

            AppLogger.d(TAG, "Fallback: parsed list has ${list?.size ?: 0} items")

            list?.forEach { map ->
                AppLogger.d(TAG, "Processing map with ${map.size} entries: ${map.keys}")
                if (map.size == 1) {
                    val (speaker, text) = map.entries.firstOrNull() ?: return@forEach
                    val textStr = (text as? String)?.trim() ?: text?.toString()?.trim() ?: return@forEach
                    if (textStr.isNotBlank()) {
                        dialogs.add(ExtractedDialogData(speaker.trim(), textStr, "neutral", 0.5f))
                        AppLogger.d(TAG, "Added dialog: speaker='$speaker', text='${textStr.take(50)}...'")
                    }
                }
            }

            AppLogger.d(TAG, "Total dialogs parsed: ${dialogs.size}")
            DialogExtractionPromptOutput(dialogs)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse response", e)
            DialogExtractionPromptOutput(emptyList())
        }
    }
    
    private fun parseDialogObject(obj: Any?): ExtractedDialogData? {
        val dialogMap = obj as? Map<*, *> ?: return null
        val speaker = (dialogMap["speaker"] as? String)?.trim() ?: return null
        val text = (dialogMap["text"] as? String)?.trim() ?: return null
        if (text.isBlank()) return null
        val emotion = (dialogMap["emotion"] as? String)?.trim()?.lowercase() ?: "neutral"
        val intensity = (dialogMap["intensity"] as? Number)?.toFloat() ?: 0.5f
        return ExtractedDialogData(speaker, text, emotion, intensity.coerceIn(0f, 1f))
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

        // For dialog extraction, we expect an array format [{"speaker": "text"}, ...]
        // Prefer array extraction when '[' comes before or at the same position as '{'
        return when {
            arrStart >= 0 && arrEnd > arrStart && (objStart < 0 || arrStart <= objStart) ->
                json.substring(arrStart, arrEnd + 1)
            objStart >= 0 && objEnd > objStart ->
                json.substring(objStart, objEnd + 1)
            else -> json
        }
    }
}

