package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.pipeline.BaseExtractionPass
import com.dramebaz.app.ai.llm.prompts.RelationshipsPrompt
import com.dramebaz.app.ai.llm.prompts.RelationshipsPromptInput
import com.dramebaz.app.ai.llm.prompts.RelationshipsPromptOutput

/**
 * Relationships Extraction Pass
 * 
 * Uses RelationshipsPrompt to extract relationships between characters.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Character name + chapter text + other character names
 * Output: List of relationships for the character
 */
class RelationshipsPassV2(
    prompt: RelationshipsPrompt = RelationshipsPrompt()
) : BaseExtractionPass<RelationshipsPromptInput, RelationshipsPromptOutput>(prompt) {
    
    override fun getDefaultOutput(): RelationshipsPromptOutput {
        return RelationshipsPromptOutput("", emptyList())
    }
}

