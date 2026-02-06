package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pass for generating image prompts from scene text (VIS-001).
 * Corresponds to GgufEngine.generateScenePrompt()
 */
class ScenePromptGenerationPass : AnalysisPass<ScenePromptInput, ScenePromptOutput> {

    companion object {
        private const val TAG = "ScenePromptGenerationPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 512, temperature = 0.3f, maxSegmentChars = 2000)

        private const val SYSTEM_PROMPT = """You are an image prompt generator. Extract visual elements from the scene and create a detailed image generation prompt. Output ONLY valid JSON."""
        private const val JSON_VALIDITY_REMINDER = "\nEnsure the JSON is valid and contains no trailing commas."
    }

    private val gson = Gson()

    override val passId: String = "scene_prompt_generation"
    override val displayName: String = "Scene Prompt Generation"

    override suspend fun execute(
        model: LlmModel,
        input: ScenePromptInput,
        config: PassConfig
    ): ScenePromptOutput {
        AppLogger.d(TAG, "Generating scene prompt (text=${input.sceneText.length} chars)")

        val userPrompt = buildPrompt(input, config.maxSegmentChars)

        val response = model.generateResponse(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )
        return parseResponse(response, input.sceneText)
    }
    
    private fun buildPrompt(input: ScenePromptInput, maxChars: Int): String {
        val text = input.sceneText.take(maxChars)
        val moodHint = input.mood?.let { "Mood: $it\n" } ?: ""
        val charHint = if (input.characters.isNotEmpty()) "Characters present: ${input.characters.joinToString(", ")}\n" else ""
        
        return """Analyze this scene and create an image generation prompt for Stable Diffusion or similar models.

${moodHint}${charHint}
<SCENE>
$text
</SCENE>

Extract visual elements and return JSON:
{"prompt": "detailed description of the scene for image generation", "negative_prompt": "elements to avoid", "style": "art style suggestion", "mood": "detected mood", "setting": "location/environment", "time_of_day": "morning/afternoon/evening/night/unknown", "characters": ["list of characters visible"]}

Focus on: visual composition, lighting, colors, environment details, character descriptions if present.
$JSON_VALIDITY_REMINDER"""
    }
    
    private fun parseResponse(response: String, originalText: String): ScenePromptOutput {
        return try {
            val json = extractJsonFromResponse(response)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type) ?: emptyMap()
            
            ScenePromptOutput(
                prompt = map["prompt"] as? String ?: createFallbackPrompt(originalText),
                negativePrompt = map["negative_prompt"] as? String ?: "blurry, low quality, distorted",
                style = map["style"] as? String ?: "detailed digital illustration",
                mood = map["mood"] as? String ?: "neutral",
                setting = map["setting"] as? String ?: "",
                timeOfDay = map["time_of_day"] as? String ?: "unknown",
                characters = (map["characters"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse scene prompt response", e)
            createFallbackOutput(originalText)
        }
    }
    
    private fun createFallbackPrompt(text: String): String {
        val sentences = text.split(Regex("[.!?]")).filter { it.isNotBlank() }.take(3)
        return "Scene: ${sentences.joinToString(". ") { it.trim() }}"
    }
    
    private fun createFallbackOutput(text: String): ScenePromptOutput {
        val words = text.lowercase()
        val mood = when {
            words.contains("dark") || words.contains("fear") -> "dark"
            words.contains("bright") || words.contains("happy") -> "bright"
            words.contains("sad") || words.contains("grief") -> "melancholy"
            else -> "neutral"
        }
        val timeOfDay = when {
            words.contains("morning") || words.contains("dawn") -> "morning"
            words.contains("evening") || words.contains("sunset") -> "evening"
            words.contains("night") || words.contains("moon") -> "night"
            else -> "unknown"
        }
        return ScenePromptOutput(
            prompt = createFallbackPrompt(text),
            mood = mood,
            timeOfDay = timeOfDay,
            style = "detailed digital illustration"
        )
    }
    
    private fun extractJsonFromResponse(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else "{}"
    }
}

// Data classes for ScenePromptGenerationPass
data class ScenePromptInput(
    val sceneText: String,
    val mood: String? = null,
    val characters: List<String> = emptyList()
)

data class ScenePromptOutput(
    val prompt: String = "",
    val negativePrompt: String = "blurry, low quality, distorted",
    val style: String = "detailed digital illustration",
    val mood: String = "neutral",
    val setting: String = "",
    val timeOfDay: String = "unknown",
    val characters: List<String> = emptyList()
)

