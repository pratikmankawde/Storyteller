package com.dramebaz.app.domain.usecases

import android.content.Context
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T5.3: Global character merging – uses LLM when available (Section 2.3),
 * falls back to stub merging if LLM not available.
 */
class MergeCharactersUseCase(
    private val characterDao: CharacterDao,
    private val context: Context? = null
) {
    private val gson = Gson()
    private val tag = "MergeCharacters"

    suspend fun mergeAndSave(bookId: Long, perChapterCharacterJsonList: List<String>) = withContext(Dispatchers.IO) {
        try {
            // Try LLM-based merging first if context available
            if (context != null) {
                val llmResult = tryMergeWithLLM(perChapterCharacterJsonList)
                if (llmResult != null) {
                    saveMergedCharacters(bookId, llmResult)
                    return@withContext
                }
            }

            // Fall back to stub merging
            AppLogger.d(tag, "Using stub merging (LLM not available)")
            stubMergeAndSave(bookId, perChapterCharacterJsonList)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error in mergeAndSave", e)
            // Fallback to stub
            stubMergeAndSave(bookId, perChapterCharacterJsonList)
        }
    }

    private suspend fun tryMergeWithLLM(perChapterCharacterJsonList: List<String>): String? {
        if (context == null) return null

        return try {
            // Build prompt per Section 2.3
            val characterJsonSnippets = perChapterCharacterJsonList.joinToString("\n\n")

            val prompt = """SYSTEM:
You are a character analysis engine. You receive multiple JSON snippets describing the same characters across different chapters. Your task is to merge them into a single global character profile per character.

Rules:
1. Merge traits, removing duplicates.
2. Summarize personality in 2-3 sentences.
3. Merge voice_profile by averaging numeric fields.
4. Track key moments and relationships.
5. Output ONLY valid JSON.

USER:
Here are per-chapter character entries:

$characterJsonSnippets

Produce a merged global character list."""

            // Use Qwen to merge - try using generateStory for structured output
            val response = try {
                // Try to get structured JSON response
                val qwenModel = com.dramebaz.app.ai.llm.QwenStub
                // Use extendedAnalysisJson as it can handle structured prompts
                qwenModel.extendedAnalysisJson(prompt)
            } catch (e: Exception) {
                AppLogger.w(tag, "Error calling Qwen for merging", e)
                null
            }

            if (response != null && !response.contains("stub") && response.contains("characters")) {
                // Extract JSON from response
                val json = extractJsonFromResponse(response)
                // Validate it's proper JSON
                try {
                    gson.fromJson(json, com.google.gson.JsonObject::class.java)
                    json
                } catch (e: Exception) {
                    AppLogger.w(tag, "Invalid JSON from LLM, using stub", e)
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "LLM merging failed, using stub", e)
            null
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        // Remove markdown code blocks if present
        if (json.startsWith("```json")) {
            json = json.removePrefix("```json").trim()
        }
        if (json.startsWith("```")) {
            json = json.removePrefix("```").trim()
        }
        if (json.endsWith("```")) {
            json = json.removeSuffix("```").trim()
        }
        // Find first { and last }
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1)
        }
        return json
    }

    private suspend fun saveMergedCharacters(bookId: Long, mergedJson: String) {
        try {
            characterDao.deleteByBookId(bookId)
            val obj = gson.fromJson(mergedJson, JsonObject::class.java)
            val charactersArray = obj.getAsJsonArray("characters") ?: return

            for (i in 0 until charactersArray.size()) {
                val charObj = charactersArray[i].asJsonObject
                val name = charObj.get("name")?.asString ?: "unknown"
                val traits = charObj.getAsJsonArray("traits")?.map { it.asString }?.joinToString(",") ?: ""
                val personalitySummary = charObj.get("personality_summary")?.asString ?: ""
                val voiceProfile = charObj.get("voice_profile")?.asJsonObject
                val voiceProfileJson = if (voiceProfile != null) gson.toJson(voiceProfile) else null
                val keyMoments = charObj.getAsJsonArray("key_moments")?.map { it.asString }?.joinToString("|") ?: ""
                val relationships = charObj.getAsJsonArray("relationships")?.map { it.asString }?.joinToString("|") ?: ""
                val suggestedSpeakerId = SpeakerMatcher.suggestSpeakerId(traits, personalitySummary, name)

                characterDao.insert(
                    Character(
                        bookId = bookId,
                        name = name,
                        traits = traits,
                        personalitySummary = personalitySummary,
                        voiceProfileJson = voiceProfileJson,
                        keyMoments = keyMoments,
                        relationships = relationships,
                        speakerId = suggestedSpeakerId
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error saving merged characters", e)
            throw e
        }
    }

    private suspend fun stubMergeAndSave(bookId: Long, perChapterCharacterJsonList: List<String>) {
        characterDao.deleteByBookId(bookId)
        val byName = mutableMapOf<String, MutableList<JsonObject>>()
        for (json in perChapterCharacterJsonList) {
            try {
                val arr = gson.fromJson(json, com.google.gson.JsonArray::class.java) ?: continue
                for (i in 0 until arr.size()) {
                    val obj = arr.get(i).asJsonObject
                    val name = obj.get("name")?.asString ?: "unknown"
                    byName.getOrPut(name) { mutableListOf() }.add(obj)
                }
            } catch (_: Exception) { }
        }

        val allCharacterNames = byName.keys.toList()

        for ((name, list) in byName) {
            val merged = mergeOne(name, list, allCharacterNames)
            val suggestedSpeakerId = SpeakerMatcher.suggestSpeakerId(merged.traits, merged.summary, name)
            characterDao.insert(
                Character(
                    bookId = bookId,
                    name = name,
                    traits = merged.traits,
                    personalitySummary = merged.summary,
                    voiceProfileJson = merged.voiceProfileJson,
                    keyMoments = merged.keyMomentsJson,
                    relationships = merged.relationshipsJson,
                    speakerId = suggestedSpeakerId
                )
            )
        }
    }

    private fun mergeOne(name: String, list: List<JsonObject>, allCharacterNames: List<String>): Merged {
        val traits = list.flatMap { obj ->
            (obj.get("traits")?.asJsonArray)?.map { it.asString } ?: emptyList()
        }.distinct()
        val vps = list.mapNotNull { obj ->
            obj.get("voice_profile")?.takeIf { it.isJsonObject }?.asJsonObject
        }
        val pitch = vps.mapNotNull { it.get("pitch")?.asFloat }.average().toFloat().takeIf { vps.isNotEmpty() } ?: 1f
        val speed = vps.mapNotNull { it.get("speed")?.asFloat }.average().toFloat().takeIf { vps.isNotEmpty() } ?: 1f
        val energy = vps.mapNotNull { it.get("energy")?.asFloat }.average().toFloat().takeIf { vps.isNotEmpty() } ?: 1f
        val voiceProfileJson = """{"pitch":$pitch,"speed":$speed,"energy":$energy}"""
        val summary = "Character $name (merged from ${list.size} chapter(s))."

        // AUG-010: Collect key moments from all chapters
        val keyMoments = list.flatMap { obj ->
            obj.get("key_moments")?.asJsonArray?.mapNotNull { el ->
                try {
                    el.asJsonObject?.let { m ->
                        mapOf(
                            "chapter" to (m.get("chapter")?.asString ?: ""),
                            "moment" to (m.get("moment")?.asString ?: ""),
                            "significance" to (m.get("significance")?.asString ?: "")
                        )
                    }
                } catch (_: Exception) { null }
            } ?: emptyList()
        }.distinctBy { "${it["chapter"]}-${it["moment"]}" }
        val keyMomentsJson = gson.toJson(keyMoments)

        // AUG-011: Collect and merge relationships from all chapters
        val relationshipsMap = mutableMapOf<String, MutableMap<String, String>>()
        for (obj in list) {
            obj.get("relationships")?.asJsonArray?.forEach { el ->
                try {
                    el.asJsonObject?.let { r ->
                        val targetChar = r.get("character")?.asString ?: return@forEach
                        val relType = r.get("relationship")?.asString ?: "other"
                        val nature = r.get("nature")?.asString ?: ""
                        // Keep the most specific relationship type found
                        val existing = relationshipsMap[targetChar]
                        if (existing == null || existing["relationship"] == "other") {
                            relationshipsMap[targetChar] = mutableMapOf(
                                "character" to targetChar,
                                "relationship" to relType,
                                "nature" to nature
                            )
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        val relationshipsJson = gson.toJson(relationshipsMap.values.toList())

        return Merged(traits.joinToString(","), summary, voiceProfileJson, keyMomentsJson, relationshipsJson)
    }

    private data class Merged(
        val traits: String,
        val summary: String,
        val voiceProfileJson: String,
        val keyMomentsJson: String = "[]",
        val relationshipsJson: String = "[]"
    )

    /**
     * AUG-031: Check voice consistency for a character across chapters.
     * Returns inconsistency details if detected, null if consistent.
     */
    data class VoiceInconsistency(
        val characterName: String,
        val type: String, // "speaker_id", "voice_profile", "gender"
        val description: String,
        val severity: Float // 0.0-1.0
    )

    /**
     * AUG-031: Detect voice inconsistencies for all characters in a book.
     * Checks for: different speaker IDs, significant voice profile variations, gender mismatches.
     */
    suspend fun checkVoiceConsistency(bookId: Long, perChapterCharacterJsonList: List<String>): List<VoiceInconsistency> {
        val inconsistencies = mutableListOf<VoiceInconsistency>()
        val byName = mutableMapOf<String, MutableList<JsonObject>>()

        // Group characters by name
        for (json in perChapterCharacterJsonList) {
            try {
                val arr = gson.fromJson(json, com.google.gson.JsonArray::class.java) ?: continue
                for (i in 0 until arr.size()) {
                    val obj = arr.get(i).asJsonObject
                    val name = obj.get("name")?.asString ?: continue
                    byName.getOrPut(name.lowercase()) { mutableListOf() }.add(obj)
                }
            } catch (_: Exception) { }
        }

        // Check each character for inconsistencies
        for ((nameKey, instances) in byName) {
            if (instances.size < 2) continue
            val displayName = instances.first().get("name")?.asString ?: nameKey

            // Check gender consistency in traits
            val genders = mutableSetOf<String>()
            instances.forEach { obj ->
                val traits = obj.get("traits")?.asJsonArray?.mapNotNull { it.asString?.lowercase() } ?: emptyList()
                if (traits.any { it.contains("male") && !it.contains("female") }) genders.add("male")
                if (traits.any { it.contains("female") }) genders.add("female")
            }
            if (genders.size > 1) {
                inconsistencies.add(VoiceInconsistency(
                    characterName = displayName,
                    type = "gender",
                    description = "Gender mismatch detected across chapters (found: ${genders.joinToString(", ")})",
                    severity = 0.9f
                ))
            }

            // Check voice profile consistency
            val pitches = mutableListOf<Float>()
            val speeds = mutableListOf<Float>()
            val energies = mutableListOf<Float>()
            instances.forEach { obj ->
                obj.get("voice_profile")?.asJsonObject?.let { vp ->
                    vp.get("pitch")?.asFloat?.let { pitches.add(it) }
                    vp.get("speed")?.asFloat?.let { speeds.add(it) }
                    vp.get("energy")?.asFloat?.let { energies.add(it) }
                }
            }

            // Check for significant variations (std dev > 0.3)
            fun checkVariation(values: List<Float>, paramName: String): Boolean {
                if (values.size < 2) return false
                val mean = values.average()
                val variance = values.map { (it - mean) * (it - mean) }.average()
                val stdDev = kotlin.math.sqrt(variance)
                if (stdDev > 0.3) {
                    inconsistencies.add(VoiceInconsistency(
                        characterName = displayName,
                        type = "voice_profile",
                        description = "$paramName varies significantly across chapters (std dev: %.2f)".format(stdDev),
                        severity = (stdDev.toFloat() / 0.5f).coerceIn(0.3f, 1.0f)
                    ))
                    return true
                }
                return false
            }
            checkVariation(pitches, "Pitch")
            checkVariation(speeds, "Speed")
            checkVariation(energies, "Energy")
        }

        AppLogger.d(tag, "AUG-031: Found ${inconsistencies.size} voice inconsistencies for book $bookId")
        return inconsistencies
    }

    /**
     * AUG-031: Store inconsistency info in character's personality summary for UI display.
     */
    fun formatInconsistencyWarning(inconsistencies: List<VoiceInconsistency>): String {
        if (inconsistencies.isEmpty()) return ""
        return "⚠️ Voice inconsistencies: " + inconsistencies.joinToString("; ") { it.description }
    }
}
