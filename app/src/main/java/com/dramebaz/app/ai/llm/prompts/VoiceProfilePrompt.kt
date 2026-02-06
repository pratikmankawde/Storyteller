package com.dramebaz.app.ai.llm.prompts

import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Prompt definition for Pass-3: Voice Profile Suggestion.
 * 
 * Suggests voice profiles for characters based on their dialogs.
 * Token Budget: Prompt+Input 2500, Output 1500
 */
class VoiceProfilePrompt : PromptDefinition<VoiceProfilePromptInput, VoiceProfilePromptOutput> {
    
    companion object {
        private const val TAG = "VoiceProfilePrompt"
    }
    
    private val gson = Gson()
    
    override val promptId: String = "voice_profile_v1"
    override val displayName: String = "Voice Profile Suggestion"
    override val purpose: String = "Suggest TTS voice profiles for characters based on their dialogs"
    override val tokenBudget: TokenBudget = TokenBudget.PASS3_VOICE_PROFILE
    override val temperature: Float = 0.2f
    
    override val systemPrompt: String = """You are a voice casting director. Suggest a voice profile for characters based ONLY on their depiction in the story."""
    
    override fun buildUserPrompt(input: VoiceProfilePromptInput): String {
        val characterNamesStr = input.characterNames.joinToString(", ")
        
        return """Suggest voice profiles for: $characterNamesStr

VOICE PROFILE PARAMETERS (all values 0.5-1.5):
- pitch: 0.5=very low, 1.0=normal, 1.5=very high
- speed: 0.5=very slow, 1.0=normal, 1.5=very fast
- energy: 0.5=calm/subdued, 1.0=normal, 1.5=very energetic

OUTPUT FORMAT (valid JSON):
{
  "characters": [
    {
      "name": "<character_name>",
      "gender": "male|female",
      "age": "child|young|middle-aged|elderly",
      "tone": "brief description",
      "accent": "neutral|british|southern|etc",
      "voice_profile": {
        "pitch": 1.0,
        "speed": 1.0,
        "energy": 1.0,
        "emotion_bias": {"neutral": 0.5, "happy": 0.2}
      }
    }
  ]
}

DIALOGS/CONTEXT:
${input.dialogContext}"""
    }
    
    override fun prepareInput(input: VoiceProfilePromptInput): VoiceProfilePromptInput {
        val maxChars = tokenBudget.maxInputChars
        val truncatedContext = if (input.dialogContext.length > maxChars) {
            input.dialogContext.take(maxChars)
        } else {
            input.dialogContext
        }
        return input.copy(dialogContext = truncatedContext)
    }
    
    override fun parseResponse(response: String): VoiceProfilePromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return VoiceProfilePromptOutput(emptyList())
            
            @Suppress("UNCHECKED_CAST")
            val characters = map["characters"] as? List<*> ?: return VoiceProfilePromptOutput(emptyList())
            
            val profiles = characters.mapNotNull { item ->
                val charMap = item as? Map<*, *> ?: return@mapNotNull null
                val name = charMap["name"]?.toString() ?: return@mapNotNull null
                val voiceMap = charMap["voice_profile"] as? Map<*, *> ?: emptyMap<String, Any>()
                
                @Suppress("UNCHECKED_CAST")
                val emotionBias = (voiceMap["emotion_bias"] as? Map<*, *>)
                    ?.mapNotNull { (k, v) ->
                        val key = k?.toString() ?: return@mapNotNull null
                        val value = (v as? Number)?.toFloat() ?: return@mapNotNull null
                        key to value
                    }?.toMap() ?: emptyMap()
                
                VoiceProfileData(
                    characterName = name,
                    gender = charMap["gender"]?.toString() ?: "male",
                    age = charMap["age"]?.toString() ?: "adult",
                    tone = charMap["tone"]?.toString() ?: "",
                    accent = charMap["accent"]?.toString() ?: "neutral",
                    pitch = (voiceMap["pitch"] as? Number)?.toFloat() ?: 1.0f,
                    speed = (voiceMap["speed"] as? Number)?.toFloat() ?: 1.0f,
                    energy = (voiceMap["energy"] as? Number)?.toFloat() ?: 1.0f,
                    emotionBias = emotionBias
                )
            }
            
            VoiceProfilePromptOutput(profiles)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse voice profiles", e)
            VoiceProfilePromptOutput(emptyList())
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

