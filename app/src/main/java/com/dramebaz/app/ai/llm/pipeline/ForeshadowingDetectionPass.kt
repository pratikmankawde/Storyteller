package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.prompts.ForeshadowingInput
import com.dramebaz.app.ai.llm.prompts.ForeshadowingOutput
import com.dramebaz.app.ai.llm.prompts.ForeshadowingPrompt
import com.dramebaz.app.data.models.Foreshadowing

/**
 * INS-002: Foreshadowing Detection Pass
 * 
 * Detects foreshadowing elements and their payoffs/callbacks across chapters
 * using LLM analysis.
 * 
 * This pass follows the new architecture:
 * - Uses ForeshadowingPrompt for prompt definition
 * - Extends BaseExtractionPass for common execution logic
 * - Works with any LlmModel implementation
 */
class ForeshadowingDetectionPass : BaseExtractionPass<ForeshadowingInput, ForeshadowingOutput>(
    ForeshadowingPrompt
) {
    
    companion object {
        /**
         * Generate stub foreshadowing for testing when LLM is unavailable.
         */
        fun generateStubForeshadowing(bookId: Long, chapterCount: Int): List<Foreshadowing> {
            if (chapterCount < 3) return emptyList()
            return listOf(
                Foreshadowing(
                    bookId = bookId,
                    setupChapter = 0,
                    setupText = "A mysterious object is mentioned in passing",
                    payoffChapter = chapterCount / 2,
                    payoffText = "The object's significance is revealed",
                    theme = "mystery",
                    confidence = 0.7f
                )
            )
        }
    }
    
    override fun getDefaultOutput(): ForeshadowingOutput {
        return ForeshadowingOutput(
            bookId = 0,
            foreshadowings = emptyList(),
            chapterCount = 0
        )
    }
}

