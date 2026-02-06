package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.pipeline.BaseExtractionPass
import com.dramebaz.app.ai.llm.prompts.TraitsExtractionPrompt
import com.dramebaz.app.ai.llm.prompts.TraitsExtractionPromptInput
import com.dramebaz.app.ai.llm.prompts.TraitsExtractionPromptOutput

/**
 * Traits Extraction Pass
 * 
 * Uses TraitsExtractionPrompt to extract character traits from context.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Character name + context text
 * Output: List of traits for the character
 */
class TraitsExtractionPassV2(
    prompt: TraitsExtractionPrompt = TraitsExtractionPrompt()
) : BaseExtractionPass<TraitsExtractionPromptInput, TraitsExtractionPromptOutput>(prompt) {
    
    override fun getDefaultOutput(): TraitsExtractionPromptOutput {
        return TraitsExtractionPromptOutput("", emptyList())
    }
}

