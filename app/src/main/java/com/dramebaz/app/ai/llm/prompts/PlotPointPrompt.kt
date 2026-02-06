package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.data.models.PlotPoint
import com.dramebaz.app.data.models.PlotPointType
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Input for plot point extraction.
 */
data class PlotPointInput(
    val bookId: Long,
    val chapters: List<Pair<Int, String>>,  // (chapterIndex, chapterText)
    val maxSampleChars: Int = 1500
)

/**
 * Output from plot point extraction.
 */
data class PlotPointOutput(
    val bookId: Long,
    val plotPoints: List<PlotPoint>,
    val chapterCount: Int
)

/**
 * INS-005: Plot Point Extraction Prompt
 * Analyzes chapters to extract major story structure elements.
 */
object PlotPointPrompt : PromptDefinition<PlotPointInput, PlotPointOutput> {
    
    private const val TAG = "PlotPointPrompt"
    private val gson = Gson()
    
    override val promptId: String = "plot_point_extraction_v1"
    override val displayName: String = "Plot Point Extraction"
    override val purpose: String = "Extract major story structure elements from chapters"
    
    override val tokenBudget: TokenBudget = TokenBudget(
        promptTokens = 350,
        inputTokens = 2700,
        outputTokens = 900
    )
    
    override val temperature: Float = 0.2f
    
    override val systemPrompt: String = """You are a literary analyst specializing in narrative structure. Analyze the following chapters to identify the major plot points."""
    
    override fun prepareInput(input: PlotPointInput): PlotPointInput {
        val maxCharsPerChapter = tokenBudget.maxInputChars / maxOf(input.chapters.size, 1)
        val sampledChapters = input.chapters.map { (index, text) ->
            index to text.take(minOf(input.maxSampleChars, maxCharsPerChapter))
        }
        return input.copy(chapters = sampledChapters)
    }
    
    override fun buildUserPrompt(input: PlotPointInput): String {
        val chapterSummaries = input.chapters.joinToString("\n\n---\n\n") { (index, text) ->
            "CHAPTER ${index + 1}:\n$text"
        }
        
        return """Analyze these chapters and identify the major plot points:

$chapterSummaries

Identify where these story elements occur:
1. Exposition - introduction of setting, characters, and initial situation
2. Inciting Incident - the event that sets the main conflict in motion
3. Rising Action - events building tension toward the climax
4. Midpoint - a turning point that changes the story's direction
5. Climax - the peak of conflict and tension
6. Falling Action - events after the climax
7. Resolution - final outcome and closure

Return JSON array:
[
  {"type": "Exposition", "chapter": 1, "description": "Brief description", "confidence": 0.9},
  {"type": "Inciting Incident", "chapter": 2, "description": "Brief description", "confidence": 0.85}
]

Only include plot points you can identify with confidence. Return ONLY the JSON array."""
    }
    
    override fun parseResponse(response: String): PlotPointOutput {
        return try {
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return PlotPointOutput(0, emptyList(), 0)
            }
            
            val jsonArray = response.substring(jsonStart, jsonEnd)
            val plotPointsList = mutableListOf<PlotPoint>()
            
            val parsed = gson.fromJson(jsonArray, List::class.java) as? List<*>
            parsed?.forEach { item ->
                if (item is Map<*, *>) {
                    val type = (item["type"] as? String)?.let { PlotPointType.fromString(it) }
                    val chapter = (item["chapter"] as? Number)?.toInt()?.minus(1) ?: 0
                    val description = item["description"] as? String ?: ""
                    val confidence = (item["confidence"] as? Number)?.toFloat() ?: 0.8f
                    
                    if (type != null && description.isNotBlank()) {
                        plotPointsList.add(PlotPoint(0, type, chapter.coerceAtLeast(0), description, confidence))
                    }
                }
            }
            
            PlotPointOutput(0, plotPointsList.sortedBy { it.type.order }, 0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing plot points response", e)
            PlotPointOutput(0, emptyList(), 0)
        }
    }
}

