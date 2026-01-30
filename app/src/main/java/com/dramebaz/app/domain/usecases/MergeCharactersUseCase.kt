package com.dramebaz.app.domain.usecases

import android.content.Context
import android.util.Log
import com.dramebaz.app.ai.llm.QwenStub
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
            Log.d(tag, "Using stub merging (LLM not available)")
            stubMergeAndSave(bookId, perChapterCharacterJsonList)
        } catch (e: Exception) {
            Log.e(tag, "Error in mergeAndSave", e)
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
                Log.w(tag, "Error calling Qwen for merging", e)
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
                    Log.w(tag, "Invalid JSON from LLM, using stub", e)
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(tag, "LLM merging failed, using stub", e)
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
            Log.e(tag, "Error saving merged characters", e)
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
        for ((name, list) in byName) {
            val merged = mergeOne(name, list)
            val suggestedSpeakerId = SpeakerMatcher.suggestSpeakerId(merged.traits, merged.summary, name)
            characterDao.insert(
                Character(
                    bookId = bookId,
                    name = name,
                    traits = merged.traits,
                    personalitySummary = merged.summary,
                    voiceProfileJson = merged.voiceProfileJson,
                    keyMoments = "",
                    relationships = "",
                    speakerId = suggestedSpeakerId
                )
            )
        }
    }

    private fun mergeOne(name: String, list: List<JsonObject>): Merged {
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
        return Merged(traits.joinToString(","), summary, voiceProfileJson)
    }

    private data class Merged(val traits: String, val summary: String, val voiceProfileJson: String)
}
