package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Post-analysis summary prompt for chapter analysis.
 * 
 * Extracts high-level chapter information:
 * - Chapter summary (2-3 sentences)
 * - Main themes
 * - Genre indicators
 * - Key events
 * 
 * Token Budget: Input = 6000 tokens (~24000 chars), Output = 500 tokens (~2000 chars)
 */
class ChapterSummaryPrompt : PromptDefinition<ChapterSummaryInput, ChapterSummaryOutput> {
    
    companion object {
        private const val TAG = "ChapterSummaryPrompt"
        
        /** Token budget for chapter summary */
        val CHAPTER_SUMMARY_BUDGET = TokenBudget(
            promptTokens = 200,     // System + user prompt template
            inputTokens = 6000,     // Space for chapter text (~24000 chars)
            outputTokens = 500      // Output JSON (~2000 chars)
        )
    }
    
    private val gson = Gson()
    
    override val promptId: String = "chapter_summary_v1"
    override val displayName: String = "Chapter Summary"
    override val purpose: String = "Extract chapter summary, themes, and genre"
    override val tokenBudget: TokenBudget = CHAPTER_SUMMARY_BUDGET
    override val temperature: Float = 0.2f
    
    override val systemPrompt: String = """You are a literary analysis engine. Analyze chapters and extract summaries, themes, and genre indicators. Output valid JSON only."""
    
    override fun buildUserPrompt(input: ChapterSummaryInput): String {
        return """Analyze this chapter and extract:
1. summary: 2-3 sentence plot summary
2. themes: List of main themes (e.g., "redemption", "love", "betrayal")
3. genre: Primary genre (fantasy, romance, mystery, thriller, etc.)
4. mood: Overall mood (dark, lighthearted, tense, melancholic, etc.)
5. key_events: List of 3-5 significant plot events

Output: Valid JSON, No commentary
{"summary":"...","themes":["..."],"genre":"...","mood":"...","key_events":["event1","event2"]}

Chapter Title: ${input.chapterTitle}

Chapter Text:
${input.chapterText}"""
    }
    
    override fun prepareInput(input: ChapterSummaryInput): ChapterSummaryInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedText = if (input.chapterText.length > maxChars) {
            // For summary, we want to keep beginning and end
            val halfMax = maxChars / 2
            val beginning = input.chapterText.take(halfMax)
            val ending = input.chapterText.takeLast(halfMax)
            "$beginning\n\n[...middle section omitted...]\n\n$ending"
        } else {
            input.chapterText
        }
        return input.copy(chapterText = truncatedText)
    }
    
    override fun parseResponse(response: String): ChapterSummaryOutput {
        return try {
            val json = extractJsonFromResponse(response)
            AppLogger.d(TAG, "Extracted JSON: ${json.take(500)}")
            
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            
            if (map == null) {
                AppLogger.w(TAG, "Failed to parse JSON")
                return ChapterSummaryOutput()
            }
            
            @Suppress("UNCHECKED_CAST")
            val themes = (map["themes"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            
            @Suppress("UNCHECKED_CAST")
            val keyEvents = (map["key_events"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            
            ChapterSummaryOutput(
                summary = map["summary"]?.toString() ?: "",
                themes = themes,
                genre = map["genre"]?.toString() ?: "",
                mood = map["mood"]?.toString() ?: "",
                keyEvents = keyEvents
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse chapter summary response", e)
            ChapterSummaryOutput()
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

// ==================== Input/Output Data Classes ====================

/** Input for chapter summary prompt */
data class ChapterSummaryInput(
    val chapterTitle: String,
    val chapterText: String
)

/** Output from chapter summary prompt */
data class ChapterSummaryOutput(
    val summary: String = "",
    val themes: List<String> = emptyList(),
    val genre: String = "",
    val mood: String = "",
    val keyEvents: List<String> = emptyList()
)

