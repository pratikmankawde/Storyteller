package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.data.models.Foreshadowing
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Input for foreshadowing detection.
 */
data class ForeshadowingInput(
    val bookId: Long,
    val chapters: List<Pair<Int, String>>,  // (chapterIndex, chapterText)
    val maxSampleChars: Int = 1500
)

/**
 * Output from foreshadowing detection.
 */
data class ForeshadowingOutput(
    val bookId: Long,
    val foreshadowings: List<Foreshadowing>,
    val chapterCount: Int
)

/**
 * INS-002: Foreshadowing Detection Prompt
 * Detects foreshadowing elements and their payoffs/callbacks across chapters.
 */
object ForeshadowingPrompt : PromptDefinition<ForeshadowingInput, ForeshadowingOutput> {
    
    private const val TAG = "ForeshadowingPrompt"
    private val gson = Gson()
    
    override val promptId: String = "foreshadowing_detection_v1"
    override val displayName: String = "Foreshadowing Detection"
    override val purpose: String = "Detect foreshadowing elements and their payoffs across chapters"
    
    override val tokenBudget: TokenBudget = TokenBudget(
        promptTokens = 300,
        inputTokens = 2800,
        outputTokens = 900
    )
    
    override val temperature: Float = 0.2f
    
    override val systemPrompt: String = """You are a literary analyst specializing in narrative structure. Analyze the following chapters for foreshadowing elements."""
    
    override fun prepareInput(input: ForeshadowingInput): ForeshadowingInput {
        // Sample text from each chapter to fit token budget
        val maxCharsPerChapter = tokenBudget.maxInputChars / maxOf(input.chapters.size, 1)
        val sampledChapters = input.chapters.map { (index, text) ->
            index to text.take(minOf(input.maxSampleChars, maxCharsPerChapter))
        }
        return input.copy(chapters = sampledChapters)
    }
    
    override fun buildUserPrompt(input: ForeshadowingInput): String {
        val chapterTexts = input.chapters.joinToString("\n\n---\n\n") { (index, text) ->
            "CHAPTER ${index + 1}:\n$text"
        }
        
        return """Analyze these chapters for foreshadowing elements:

$chapterTexts

Identify foreshadowing elements where:
1. Setup elements: hints, symbolic objects, ominous statements, recurring motifs that suggest future events
2. Payoff elements: when the setup is revealed, resolved, or gains meaning later

Return ONLY valid JSON in this exact format:
{
  "foreshadowing": [
    {
      "setup_chapter": 1,
      "setup_text": "Brief quote or description of the setup",
      "payoff_chapter": 5,
      "payoff_text": "Brief quote or description of the payoff",
      "theme": "one or two word theme like 'death' or 'betrayal'",
      "confidence": 0.8
    }
  ]
}

If no foreshadowing is found, return: {"foreshadowing": []}"""
    }
    
    override fun parseResponse(response: String): ForeshadowingOutput {
        return try {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                AppLogger.w(TAG, "No valid JSON in response")
                return ForeshadowingOutput(0, emptyList(), 0)
            }
            
            val jsonStr = response.substring(jsonStart, jsonEnd)
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            val array = json.getAsJsonArray("foreshadowing") ?: return ForeshadowingOutput(0, emptyList(), 0)
            
            val foreshadowings = array.mapNotNull { elem ->
                val obj = elem.asJsonObject
                try {
                    Foreshadowing(
                        bookId = 0,  // Will be set by caller
                        setupChapter = obj.get("setup_chapter")?.asInt?.minus(1) ?: return@mapNotNull null,
                        setupText = obj.get("setup_text")?.asString ?: "",
                        payoffChapter = obj.get("payoff_chapter")?.asInt?.minus(1) ?: return@mapNotNull null,
                        payoffText = obj.get("payoff_text")?.asString ?: "",
                        theme = obj.get("theme")?.asString ?: "unknown",
                        confidence = obj.get("confidence")?.asFloat ?: 0.7f
                    )
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error parsing foreshadowing element", e)
                    null
                }
            }
            
            ForeshadowingOutput(0, foreshadowings, 0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing foreshadowing response", e)
            ForeshadowingOutput(0, emptyList(), 0)
        }
    }
}

