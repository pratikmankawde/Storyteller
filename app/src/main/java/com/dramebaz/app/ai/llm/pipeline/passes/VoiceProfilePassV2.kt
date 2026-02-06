package com.dramebaz.app.ai.llm.pipeline.passes

import com.dramebaz.app.ai.llm.pipeline.BaseExtractionPass
import com.dramebaz.app.ai.llm.prompts.VoiceProfilePrompt
import com.dramebaz.app.ai.llm.prompts.VoiceProfilePromptInput
import com.dramebaz.app.ai.llm.prompts.VoiceProfilePromptOutput

/**
 * Pass 3: Voice Profile Suggestion
 * 
 * Uses VoiceProfilePrompt to suggest TTS voice profiles for characters.
 * This pass is model-agnostic and can work with any LlmModel implementation.
 * 
 * Input: Character names + dialog context
 * Output: Voice profiles for each character
 */
class VoiceProfilePassV2(
    prompt: VoiceProfilePrompt = VoiceProfilePrompt()
) : BaseExtractionPass<VoiceProfilePromptInput, VoiceProfilePromptOutput>(prompt) {
    
    override fun getDefaultOutput(): VoiceProfilePromptOutput {
        return VoiceProfilePromptOutput(emptyList())
    }
}

