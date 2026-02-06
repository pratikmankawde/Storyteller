package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.AnalysisPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for chapter analysis - extracts summary, characters, dialogs, and sound cues.
 * Corresponds to GgufEngine.analyzeChapter()
 */
class ChapterAnalysisPass : AnalysisPass<ChapterAnalysisInput, ChapterAnalysisOutput> {

    companion object {
        private const val TAG = "ChapterAnalysisPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 768, temperature = 0.15f, maxSegmentChars = 10_000)
    }

    private val gson = Gson()

    override val passId: String = "chapter_analysis"
    override val displayName: String = "Chapter Analysis"

    override suspend fun execute(
        model: LlmModel,
        input: ChapterAnalysisInput,
        config: PassConfig
    ): ChapterAnalysisOutput {
        AppLogger.d(TAG, "Executing chapter analysis (text=${input.chapterText.length} chars)")

        val userPrompt = AnalysisPrompts.buildAnalysisPrompt(input.chapterText, config.maxSegmentChars)

        val response = model.generateResponse(
            systemPrompt = AnalysisPrompts.ANALYSIS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response)
    }
    
    private fun parseResponse(response: String): ChapterAnalysisOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val summary = parseChapterSummary(map["chapter_summary"])
            val characters = parseCharacters(map["characters"])
            val dialogs = parseDialogs(map["dialogs"])
            val soundCues = parseSoundCues(map["sound_cues"])
            
            ChapterAnalysisOutput(summary, characters, dialogs, soundCues)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse chapter analysis response", e)
            ChapterAnalysisOutput()
        }
    }
    
    private fun parseChapterSummary(data: Any?): ChapterSummary {
        val map = data as? Map<*, *> ?: return ChapterSummary()
        return ChapterSummary(
            title = map["title"] as? String ?: "",
            shortSummary = map["short_summary"] as? String ?: "",
            mainEvents = (map["main_events"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            emotionalArc = parseEmotionalArc(map["emotional_arc"])
        )
    }
    
    private fun parseEmotionalArc(data: Any?): List<EmotionalArcSegment> {
        val list = data as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            EmotionalArcSegment(
                segment = map["segment"] as? String ?: "unknown",
                emotion = map["emotion"] as? String ?: "neutral",
                intensity = (map["intensity"] as? Number)?.toFloat() ?: 0.5f
            )
        }
    }
    
    private fun parseCharacters(data: Any?): List<ExtractedCharacter> {
        val list = data as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            ExtractedCharacter(
                name = name,
                traits = (map["traits"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                voiceProfile = (map["voice_profile"] as? Map<*, *>)?.mapNotNull { (k, v) -> 
                    (k as? String)?.let { key -> key to v } 
                }?.toMap() ?: emptyMap()
            )
        }
    }
    
    private fun parseDialogs(data: Any?): List<ExtractedDialog> {
        val list = data as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            ExtractedDialog(
                speaker = map["speaker"] as? String ?: "Unknown",
                text = map["dialog"] as? String ?: map["text"] as? String ?: "",
                emotion = map["emotion"] as? String ?: "neutral",
                intensity = (map["intensity"] as? Number)?.toFloat() ?: 0.5f
            )
        }
    }
    
    private fun parseSoundCues(data: Any?): List<SoundCue> {
        val list = data as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            SoundCue(
                event = map["event"] as? String ?: "",
                soundPrompt = map["sound_prompt"] as? String ?: "",
                duration = (map["duration"] as? Number)?.toFloat() ?: 1.0f,
                category = map["category"] as? String ?: "effect"
            )
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for ChapterAnalysisPass
data class ChapterAnalysisInput(val chapterText: String)

data class ChapterAnalysisOutput(
    val summary: ChapterSummary = ChapterSummary(),
    val characters: List<ExtractedCharacter> = emptyList(),
    val dialogs: List<ExtractedDialog> = emptyList(),
    val soundCues: List<SoundCue> = emptyList()
)

data class ChapterSummary(
    val title: String = "",
    val shortSummary: String = "",
    val mainEvents: List<String> = emptyList(),
    val emotionalArc: List<EmotionalArcSegment> = emptyList()
)

data class EmotionalArcSegment(val segment: String, val emotion: String, val intensity: Float)
data class ExtractedCharacter(val name: String, val traits: List<String>, val voiceProfile: Map<String, Any?>)
data class SoundCue(val event: String, val soundPrompt: String, val duration: Float, val category: String)

