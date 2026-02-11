package com.dramebaz.app.domain.usecases

import android.content.Context
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
            // Use programmatic merging - LLM is overkill for data aggregation
            AppLogger.d(tag, "Merging ${perChapterCharacterJsonList.size} chapter character lists")
            programmaticMergeAndSave(bookId, perChapterCharacterJsonList)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error in mergeAndSave", e)
        }
    }

    /**
     * Programmatic character merging - combines character data from multiple chapters
     * by merging traits, averaging voice profiles, and collecting key moments/relationships.
     */
    private suspend fun programmaticMergeAndSave(bookId: Long, perChapterCharacterJsonList: List<String>) {
        // Get existing characters to preserve dialogsJson
        val existingChars = characterDao.getByBookIdDirect(bookId)
        val existingByName = existingChars.associateBy { it.name.lowercase() }

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
            val existing = existingByName[name.lowercase()]

            if (existing != null) {
                // Update existing character, preserving dialogsJson and user-set speakerId
                val suggestedSpeakerId = existing.speakerId ?: SpeakerMatcher.suggestSpeakerId(merged.traits, merged.summary, name)
                characterDao.update(
                    existing.copy(
                        traits = if (merged.traits.isNotBlank()) merged.traits else existing.traits,
                        personalitySummary = if (merged.summary.isNotBlank()) merged.summary else existing.personalitySummary,
                        voiceProfileJson = merged.voiceProfileJson.takeIf { it != "{\"pitch\":1.0,\"speed\":1.0,\"energy\":1.0}" } ?: existing.voiceProfileJson,
                        keyMoments = if (merged.keyMomentsJson != "[]") merged.keyMomentsJson else existing.keyMoments,
                        relationships = if (merged.relationshipsJson != "[]") merged.relationshipsJson else existing.relationships,
                        speakerId = suggestedSpeakerId
                        // dialogsJson is preserved from existing
                    )
                )
            } else {
                // Insert new character - try to get dialogsJson from existing if available
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
                        speakerId = suggestedSpeakerId,
                        dialogsJson = existingByName[name.lowercase()]?.dialogsJson
                    )
                )
            }
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
