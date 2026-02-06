package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.pipeline.BaseExtractionPass
import com.dramebaz.app.ai.llm.prompts.KeyMomentsPrompt
import com.dramebaz.app.ai.llm.prompts.KeyMomentsPromptInput
import com.dramebaz.app.ai.llm.prompts.KeyMomentsPromptOutput

/**
 * Key Moments Extraction Pass
 * 
 * Uses KeyMomentsPrompt to extract significant moments for a character.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Character name + chapter text + chapter title
 * Output: List of key moments for the character
 */
class KeyMomentsPassV2(
    prompt: KeyMomentsPrompt = KeyMomentsPrompt()
) : BaseExtractionPass<KeyMomentsPromptInput, KeyMomentsPromptOutput>(prompt) {
    
    override fun getDefaultOutput(): KeyMomentsPromptOutput {
        return KeyMomentsPromptOutput("", emptyList())
    }
}

