package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.StoryPrompts
import com.dramebaz.app.utils.AppLogger

/**
 * Pass for remixing existing stories.
 * Corresponds to GgufEngine.remixStory()
 */
class StoryRemixPass : AnalysisPass<StoryRemixInput, StoryRemixOutput> {

    companion object {
        private const val TAG = "StoryRemixPass"
        val DEFAULT_CONFIG = PassConfig(maxTokens = 2048, temperature = 0.7f, maxSegmentChars = 5000)
    }

    override val passId: String = "story_remix"
    override val displayName: String = "Story Remix"

    override suspend fun execute(
        model: LlmModel,
        input: StoryRemixInput,
        config: PassConfig
    ): StoryRemixOutput {
        AppLogger.d(TAG, "Remixing story (original=${input.sourceStory.length} chars, instruction=${input.remixInstruction})")

        val userPrompt = StoryPrompts.buildRemixPrompt(
            input.remixInstruction,
            input.sourceStory,
            config.maxSegmentChars
        )

        val response = model.generateResponse(
            systemPrompt = StoryPrompts.STORY_REMIX_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = config.maxTokens,
            temperature = config.temperature
        )

        // Clean up response - remove any markdown code blocks
        val cleanedStory = response
            .replace(Regex("```[\\w]*\\n"), "")
            .replace(Regex("```"), "")
            .trim()

        return StoryRemixOutput(
            remixedStory = cleanedStory,
            wordCount = cleanedStory.split(Regex("\\s+")).size,
            originalWordCount = input.sourceStory.split(Regex("\\s+")).size
        )
    }
}

// Data classes for StoryRemixPass
data class StoryRemixInput(
    val remixInstruction: String,
    val sourceStory: String
)

data class StoryRemixOutput(
    val remixedStory: String = "",
    val wordCount: Int = 0,
    val originalWordCount: Int = 0
)

