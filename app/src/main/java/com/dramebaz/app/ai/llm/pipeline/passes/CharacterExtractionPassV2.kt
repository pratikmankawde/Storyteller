package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.pipeline.BaseExtractionPass
import com.dramebaz.app.ai.llm.prompts.CharacterExtractionPrompt
import com.dramebaz.app.ai.llm.prompts.CharacterExtractionPromptInput
import com.dramebaz.app.ai.llm.prompts.CharacterExtractionPromptOutput

/**
 * Pass 1: Character Name Extraction
 * 
 * Uses CharacterExtractionPrompt to extract character names from story text.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Story text segment
 * Output: List of character names found in the text
 */
class CharacterExtractionPassV2(
    prompt: CharacterExtractionPrompt = CharacterExtractionPrompt()
) : BaseExtractionPass<CharacterExtractionPromptInput, CharacterExtractionPromptOutput>(prompt) {
    
    override fun getDefaultOutput(): CharacterExtractionPromptOutput {
        return CharacterExtractionPromptOutput(emptyList())
    }
}

