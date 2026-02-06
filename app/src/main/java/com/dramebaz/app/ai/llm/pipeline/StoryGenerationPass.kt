package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger

/**
 * Pass for generating stories from prompts.
 * Corresponds to GgufEngine.generateStory()
 */
class StoryGenerationPass : AnalysisPass<StoryGenerationInput, StoryGenerationOutput> {

    companion object {
        private const val TAG = "StoryGenerationPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 2048, temperature = 0.7f)
    }

    override val passId: String = "story_generation"
    override val displayName: String = "Story Generation"

    override suspend fun execute(
        model: LlmModel,
        input: StoryGenerationInput,
        config: PassConfig
    ): StoryGenerationOutput {
        AppLogger.d(TAG, "Generating story from prompt (length=${input.userPrompt.length})")

        val userPrompt = StoryPrompts.buildStoryPrompt(input.userPrompt)

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.STORY_GENERATION_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )

        // Clean up response - remove any markdown code blocks
        val cleanedStory = response
            .replace(Regex("```[\\w]*\\n"), "")
            .replace(Regex("```"), "")
            .trim()

        return StoryGenerationOutput(
            story = cleanedStory,
            wordCount = cleanedStory.split(Regex("\\s+")).size
        )
    }
}

// Data classes for StoryGenerationPass
data class StoryGenerationInput(
    val userPrompt: String
)

data class StoryGenerationOutput(
    val story: String = "",
    val wordCount: Int = 0
)

