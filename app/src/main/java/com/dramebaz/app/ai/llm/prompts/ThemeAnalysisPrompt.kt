package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Input for theme analysis.
 */
data class ThemeAnalysisInput(
    val bookId: Long,
    val title: String,
    val firstChapterText: String,
    val maxSampleChars: Int = 3000
)

/**
 * Output from theme analysis.
 */
data class ThemeAnalysisOutput(
    val bookId: Long,
    val mood: String,
    val genre: String,
    val era: String,
    val emotionalTone: String,
    val ambientSound: String?
)

/**
 * THEME-001: Theme Analysis Prompt
 * Uses LLM to analyze book content and generate appropriate UI themes.
 */
object ThemeAnalysisPrompt : PromptDefinition<ThemeAnalysisInput, ThemeAnalysisOutput> {
    
    private const val TAG = "ThemeAnalysisPrompt"
    private val gson = Gson()
    
    override val promptId: String = "theme_analysis_v1"
    override val displayName: String = "Theme Analysis"
    override val purpose: String = "Analyze book content to determine mood, genre, and UI theme"
    
    override val tokenBudget: TokenBudget = TokenBudget(
        promptTokens = 250,
        inputTokens = 3000,
        outputTokens = 200
    )
    
    override val temperature: Float = 0.2f
    
    override val systemPrompt: String = """You are a literary analyst specializing in genre and mood classification. Analyze the book content to determine its mood, genre, and atmosphere."""
    
    override fun prepareInput(input: ThemeAnalysisInput): ThemeAnalysisInput {
        val sampledText = input.firstChapterText.take(input.maxSampleChars)
        return input.copy(firstChapterText = sampledText)
    }
    
    override fun buildUserPrompt(input: ThemeAnalysisInput): String {
        return """Analyze this book content and determine its mood and atmosphere:

Title: ${input.title}
First Chapter Sample:
${input.firstChapterText}

Determine:
1. Primary mood (dark_gothic, romantic, adventure, mystery, fantasy, scifi, classic)
2. Genre (classic_literature, modern_fiction, fantasy, scifi, romance, thriller)
3. Era setting (historical, contemporary, futuristic)
4. Emotional tone (somber, uplifting, tense, whimsical)

Return JSON:
{
  "mood": "dark_gothic",
  "genre": "thriller",
  "era": "contemporary",
  "emotional_tone": "tense",
  "suggested_ambient_sound": "rain"
}"""
    }
    
    override fun parseResponse(response: String): ThemeAnalysisOutput {
        return try {
            val jsonStr = extractJson(response) ?: return getDefaultOutput()
            
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            ThemeAnalysisOutput(
                bookId = 0,  // Will be set by caller
                mood = json.get("mood")?.asString ?: "classic",
                genre = json.get("genre")?.asString ?: "modern_fiction",
                era = json.get("era")?.asString ?: "contemporary",
                emotionalTone = json.get("emotional_tone")?.asString ?: "neutral",
                ambientSound = json.get("suggested_ambient_sound")?.asString
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "JSON parse error: ${e.message}")
            getDefaultOutput()
        }
    }
    
    private fun extractJson(text: String): String? {
        val startIdx = text.indexOf('{')
        val endIdx = text.lastIndexOf('}')
        return if (startIdx >= 0 && endIdx > startIdx) {
            text.substring(startIdx, endIdx + 1)
        } else null
    }
    
    private fun getDefaultOutput(): ThemeAnalysisOutput {
        return ThemeAnalysisOutput(
            bookId = 0,
            mood = "classic",
            genre = "modern_fiction",
            era = "contemporary",
            emotionalTone = "neutral",
            ambientSound = null
        )
    }
}

