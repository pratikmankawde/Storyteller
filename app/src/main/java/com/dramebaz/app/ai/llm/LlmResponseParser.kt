package com.dramebaz.app.ai.llm

import com.dramebaz.app.ai.llm.models.ExtractedDialog
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.ProsodyHints
import com.dramebaz.app.data.models.ScenePrompt
import com.dramebaz.app.data.models.SoundCueModel
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Parses LLM responses into structured data objects.
 * 
 * Design Pattern: Single Responsibility - this class only handles parsing,
 * extracted from LlmService to reduce class size.
 * 
 * Handles:
 * - Chapter analysis responses
 * - Character names and traits
 * - Dialog extraction
 * - Key moments and relationships
 * - Scene prompts
 */
object LlmResponseParser {
    private const val TAG = "LlmResponseParser"
    private val gson = Gson()

    /**
     * Extract JSON from LLM response (handles markdown code blocks and extra text).
     */
    fun extractJsonFromResponse(response: String): String {
        var text = response.trim()
        // Remove markdown code block wrapper if present
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json").trim()
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```").trim()
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }
        // Find JSON object bounds
        val jsonStart = text.indexOf("{")
        val jsonEnd = text.lastIndexOf("}") + 1
        if (jsonStart < 0 || jsonEnd <= jsonStart) return ""
        return text.substring(jsonStart, jsonEnd)
    }

    /**
     * Parse LLM response for chapter analysis into ChapterAnalysisResponse.
     */
    fun parseAnalysisResponse(response: String): ChapterAnalysisResponse? {
        return try {
            val json = extractJsonFromResponse(response)
            if (json.isEmpty()) {
                AppLogger.w(TAG, "parseAnalysisResponse: extractJsonFromResponse returned empty string")
                AppLogger.w(TAG, "parseAnalysisResponse: raw response was: ${response.take(500)}")
                return null
            }
            AppLogger.d(TAG, "parseAnalysisResponse: Extracted JSON length=${json.length}")

            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *>
            if (obj == null) {
                AppLogger.w(TAG, "parseAnalysisResponse: gson.fromJson returned null for: ${json.take(300)}")
                return null
            }

            val result = ChapterAnalysisResponse(
                chapterSummary = parseChapterSummary(obj["chapter_summary"]),
                characters = parseCharacters(obj["characters"]),
                dialogs = parseDialogs(obj["dialogs"]),
                soundCues = parseSoundCues(obj["sound_cues"])
            )
            AppLogger.d(TAG, "parseAnalysisResponse: Created ChapterAnalysisResponse with ${result.characters?.size ?: 0} chars, ${result.dialogs?.size ?: 0} dialogs")
            result
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse analysis response: ${e.message}")
            AppLogger.w(TAG, "parseAnalysisResponse: Exception parsing: ${response.take(500)}")
            null
        }
    }

    /**
     * Parse LLM response for extended analysis, returning JSON string.
     */
    fun parseExtendedAnalysisResponse(response: String): String? {
        return try {
            val json = extractJsonFromResponse(response)
            if (json.isEmpty()) return null
            // Validate JSON by parsing
            gson.fromJson(json, Map::class.java) ?: return null
            json
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse extended analysis response: ${e.message}")
            null
        }
    }

    fun parseChapterSummary(obj: Any?): ChapterSummary? {
        if (obj !is Map<*, *>) return null
        return ChapterSummary(
            title = obj["title"] as? String ?: "Chapter",
            shortSummary = obj["short_summary"] as? String ?: "",
            mainEvents = (obj["main_events"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            emotionalArc = (obj["emotional_arc"] as? List<*>)?.mapNotNull {
                if (it is Map<*, *>) EmotionalSegment(
                    segment = it["segment"] as? String ?: "",
                    emotion = it["emotion"] as? String ?: "neutral",
                    intensity = (it["intensity"] as? Number)?.toFloat() ?: 0.5f
                ) else null
            } ?: emptyList()
        )
    }

    fun parseCharacters(obj: Any?): List<CharacterStub>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val vp = (it["voice_profile"] as? Map<*, *>)?.mapKeys { (k, _) -> k.toString() }
                    ?.mapValues { (_, v) ->
                        when (v) {
                            is Number -> v.toDouble() as Any
                            is String -> (v.toString().toDoubleOrNull() ?: 1.0) as Any
                            else -> 1.0 as Any
                        }
                    }
                CharacterStub(
                    name = it["name"] as? String ?: "Unknown",
                    traits = (it["traits"] as? List<*>)?.mapNotNull { t -> t as? String },
                    voiceProfile = vp
                )
            } else null
        }
    }

    fun parseDialogs(obj: Any?): List<Dialog>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>) {
                val p = it["prosody"] as? Map<*, *>
                val prosody = p?.let { pr ->
                    ProsodyHints(
                        pitchVariation = pr["pitch_variation"] as? String ?: "normal",
                        speed = pr["speed"] as? String ?: "normal",
                        stressPattern = pr["stress_pattern"] as? String ?: ""
                    )
                }
                val confidence = (it["confidence"] as? Number)?.toFloat() ?: 1.0f
                Dialog(
                    speaker = it["speaker"] as? String ?: it["character"] as? String ?: "unknown",
                    dialog = it["dialog"] as? String ?: it["text"] as? String ?: "",
                    emotion = it["emotion"] as? String ?: "neutral",
                    intensity = (it["intensity"] as? Number)?.toFloat() ?: 0.5f,
                    prosody = prosody,
                    confidence = confidence
                )
            } else null
        }
    }

    fun parseSoundCues(obj: Any?): List<SoundCueModel>? {
        if (obj !is List<*>) return null
        return obj.mapNotNull {
            if (it is Map<*, *>)
                SoundCueModel(
                    event = it["event"] as? String ?: "effect",
                    soundPrompt = it["sound_prompt"] as? String ?: "",
                    duration = (it["duration"] as? Number)?.toFloat() ?: 2f,
                    category = it["category"] as? String ?: "effect"
                )
            else null
        }
    }

    /**
     * Parse character names from LLM response.
     * Expected format: {"characters": ["Name1", "Name2"]} or {"names": ["Name1", "Name2"]}
     */
    fun parseCharacterNamesFromResponse(response: String): List<String> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val names = (map["characters"] as? List<*>) ?: (map["names"] as? List<*>)
            names?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse character names from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse dialogs from LLM response.
     * Expected format: {"dialogs": [{"speaker": "...", "text": "...", "emotion": "...", "intensity": 0.5}]}
     */
    fun parseDialogsFromResponse(response: String): List<ExtractedDialog> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val dialogs = map["dialogs"] as? List<*> ?: return emptyList()
            dialogs.mapNotNull { item ->
                if (item is Map<*, *>) {
                    ExtractedDialog(
                        speaker = item["speaker"] as? String ?: "Unknown",
                        text = item["text"] as? String ?: "",
                        emotion = item["emotion"] as? String ?: "neutral",
                        intensity = (item["intensity"] as? Number)?.toFloat() ?: 0.5f
                    )
                } else null
            }.filter { it.text.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse dialogs from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse traits from LLM response.
     * Expected format: {"traits": ["trait1", "trait2"]} or full pass3 format
     */
    fun parseTraitsFromResponse(response: String): List<String> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val traits = map["traits"] as? List<*>
            traits?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse traits from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse voice profile from LLM response for pass3.
     * Expected format: {"character": "...", "traits": [...], "voice_profile": {...}}
     */
    fun parsePass3Response(response: String, characterName: String): Pair<List<String>, Map<String, Any>>? {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val traits = (map["traits"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val voiceProfile = (map["voice_profile"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                ?.mapValues { it.value as Any } ?: emptyMap()
            if (traits.isNotEmpty() || voiceProfile.isNotEmpty()) {
                Pair(traits, voiceProfile)
            } else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse pass3 response for $characterName: ${e.message}")
            null
        }
    }

    /**
     * Parse key moments from LLM response.
     * Expected format: {"moments": [{"chapter": "...", "moment": "...", "significance": "..."}]}
     */
    fun parseKeyMomentsFromResponse(response: String): List<Map<String, String>> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val moments = map["moments"] as? List<*> ?: return emptyList()
            moments.mapNotNull { item ->
                if (item is Map<*, *>) {
                    mapOf(
                        "chapter" to (item["chapter"] as? String ?: ""),
                        "moment" to (item["moment"] as? String ?: ""),
                        "significance" to (item["significance"] as? String ?: "")
                    )
                } else null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse key moments from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse relationships from LLM response.
     * Expected format: {"relationships": [{"character": "...", "relationship": "...", "nature": "..."}]}
     */
    fun parseRelationshipsFromResponse(response: String): List<Map<String, String>> {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val relationships = map["relationships"] as? List<*> ?: return emptyList()
            relationships.mapNotNull { item ->
                if (item is Map<*, *>) {
                    mapOf(
                        "character" to (item["character"] as? String ?: ""),
                        "relationship" to (item["relationship"] as? String ?: ""),
                        "nature" to (item["nature"] as? String ?: "")
                    )
                } else null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse relationships from response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse scene prompt from LLM response.
     */
    fun parseScenePromptFromResponse(response: String, mood: String?): ScenePrompt? {
        return try {
            val cleanedResponse = extractJsonFromResponse(response)
            val map = gson.fromJson(cleanedResponse, Map::class.java)
            val prompt = map["prompt"] as? String ?: return null
            val style = map["style"] as? String ?: "realistic"
            val detectedMood = map["mood"] as? String ?: mood ?: "neutral"
            ScenePrompt(
                prompt = prompt,
                style = style,
                mood = detectedMood
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse scene prompt from response: ${e.message}")
            null
        }
    }
}
