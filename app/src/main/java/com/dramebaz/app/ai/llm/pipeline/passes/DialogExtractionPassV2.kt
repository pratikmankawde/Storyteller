package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.pipeline.BaseExtractionPass
import com.dramebaz.app.ai.llm.prompts.DialogExtractionPrompt
import com.dramebaz.app.ai.llm.prompts.DialogExtractionPromptInput
import com.dramebaz.app.ai.llm.prompts.DialogExtractionPromptOutput

/**
 * Pass 2: Dialog Extraction with Speaker Attribution
 * 
 * Uses DialogExtractionPrompt to extract dialogs and attribute them to characters.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Story text segment + list of known character names
 * Output: List of dialogs with speaker, text, emotion, and intensity
 */
class DialogExtractionPassV2(
    prompt: DialogExtractionPrompt = DialogExtractionPrompt()
) : BaseExtractionPass<DialogExtractionPromptInput, DialogExtractionPromptOutput>(prompt) {
    
    override fun getDefaultOutput(): DialogExtractionPromptOutput {
        return DialogExtractionPromptOutput(emptyList())
    }
}

