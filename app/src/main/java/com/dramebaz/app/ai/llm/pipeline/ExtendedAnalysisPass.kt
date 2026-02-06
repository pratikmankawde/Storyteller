package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.AnalysisPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for extended analysis - extracts themes, symbols, vocabulary, foreshadowing.
 * Corresponds to GgufEngine.extendedAnalysisJson()
 */
class ExtendedAnalysisPass : AnalysisPass<ExtendedAnalysisInput, ExtendedAnalysisOutput> {

    companion object {
        private const val TAG = "ExtendedAnalysisPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 1024, temperature = 0.15f, maxSegmentChars = 10_000)
    }

    private val gson = Gson()

    override val passId: String = "extended_analysis"
    override val displayName: String = "Extended Analysis"

    override suspend fun execute(
        model: LlmModel,
        input: ExtendedAnalysisInput,
        config: PassConfig
    ): ExtendedAnalysisOutput {
        AppLogger.d(TAG, "Executing extended analysis (text=${input.chapterText.length} chars)")

        val userPrompt = AnalysisPrompts.buildExtendedAnalysisPrompt(input.chapterText, config.maxSegmentChars)

        val response = model.generateResponse(
            systemPrompt = AnalysisPrompts.EXTENDED_ANALYSIS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response)
    }
    
    private fun parseResponse(response: String): ExtendedAnalysisOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            ExtendedAnalysisOutput(
                themes = (map["themes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                symbols = (map["symbols"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                foreshadowing = (map["foreshadowing"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                vocabulary = parseVocabulary(map["vocabulary"]),
                rawJson = json
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse extended analysis response", e)
            ExtendedAnalysisOutput()
        }
    }
    
    private fun parseVocabulary(data: Any?): List<VocabularyEntry> {
        val list = data as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            VocabularyEntry(
                word = map["word"] as? String ?: return@mapNotNull null,
                definition = map["definition"] as? String ?: ""
            )
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for ExtendedAnalysisPass
data class ExtendedAnalysisInput(val chapterText: String)

data class ExtendedAnalysisOutput(
    val themes: List<String> = emptyList(),
    val symbols: List<String> = emptyList(),
    val foreshadowing: List<String> = emptyList(),
    val vocabulary: List<VocabularyEntry> = emptyList(),
    val rawJson: String = "{}"
)

data class VocabularyEntry(val word: String, val definition: String)

