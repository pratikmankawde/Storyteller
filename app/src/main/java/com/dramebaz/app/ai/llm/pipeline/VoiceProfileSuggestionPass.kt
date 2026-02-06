package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for suggesting voice profiles for characters.
 * Corresponds to GgufEngine.suggestVoiceProfilesJson()
 */
class VoiceProfileSuggestionPass : AnalysisPass<VoiceProfileInput, VoiceProfileOutput> {

    companion object {
        private const val TAG = "VoiceProfileSuggestionPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 512, temperature = 0.2f)
    }

    private val gson = Gson()

    override val passId: String = "voice_profile_suggestion"
    override val displayName: String = "Voice Profile Suggestion"

    override suspend fun execute(
        model: LlmModel,
        input: VoiceProfileInput,
        config: PassConfig
    ): VoiceProfileOutput {
        AppLogger.d(TAG, "Suggesting voice profiles for ${input.characters.size} characters")

        // Convert characters to JSON for the prompt
        val charactersJson = gson.toJson(input.characters.map { char ->
            mapOf("name" to char.name, "traits" to char.traits)
        })

        val userPrompt = StoryPrompts.buildVoiceProfilesPrompt(charactersJson)

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.VOICE_PROFILE_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response)
    }
    
    private fun parseResponse(response: String): VoiceProfileOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            val charList = map["characters"] as? List<*> ?: emptyList<Any>()
            val profiles = charList.mapNotNull { item ->
                val charMap = item as? Map<*, *> ?: return@mapNotNull null
                val name = charMap["name"] as? String ?: return@mapNotNull null
                val voiceMap = charMap["voice_profile"] as? Map<*, *> ?: emptyMap<String, Any>()
                
                SuggestedVoiceProfile(
                    characterName = name,
                    pitch = (voiceMap["pitch"] as? Number)?.toFloat() ?: 1.0f,
                    speed = (voiceMap["speed"] as? Number)?.toFloat() ?: 1.0f,
                    energy = (voiceMap["energy"] as? Number)?.toFloat() ?: 1.0f,
                    emotionBias = (voiceMap["emotion_bias"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                        (k as? String)?.let { key -> key to (v as? Number)?.toFloat() }
                    }?.toMap()?.filterValues { it != null }?.mapValues { it.value!! } ?: emptyMap()
                )
            }
            
            VoiceProfileOutput(profiles, json)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse voice profile response", e)
            VoiceProfileOutput()
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for VoiceProfileSuggestionPass
data class VoiceProfileInput(
    val characters: List<CharacterWithTraits>
)

data class CharacterWithTraits(
    val name: String,
    val traits: List<String>
)

data class VoiceProfileOutput(
    val profiles: List<SuggestedVoiceProfile> = emptyList(),
    val rawJson: String = "{}"
)

data class SuggestedVoiceProfile(
    val characterName: String,
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
    val energy: Float = 1.0f,
    val emotionBias: Map<String, Float> = emptyMap()
)

