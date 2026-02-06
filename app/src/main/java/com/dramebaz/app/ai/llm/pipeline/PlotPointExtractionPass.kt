package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.prompts.PlotPointInput
import com.dramebaz.app.ai.llm.prompts.PlotPointOutput
import com.dramebaz.app.ai.llm.prompts.PlotPointPrompt
import com.dramebaz.app.data.models.PlotPoint
import com.dramebaz.app.data.models.PlotPointType

/**
 * INS-005: Plot Point Extraction Pass
 * 
 * Analyzes chapters to extract major story structure elements using LLM.
 * 
 * This pass follows the new architecture:
 * - Uses PlotPointPrompt for prompt definition
 * - Extends BaseExtractionPass for common execution logic
 * - Works with any LlmModel implementation
 */
class PlotPointExtractionPass : BaseExtractionPass<PlotPointInput, PlotPointOutput>(
    PlotPointPrompt
) {
    
    companion object {
        /**
         * Generate stub plot points for testing when LLM is unavailable.
         */
        fun generateStubPlotPoints(bookId: Long, chapterCount: Int): List<PlotPoint> {
            if (chapterCount < 3) return emptyList()
            
            val points = mutableListOf<PlotPoint>()
            points.add(PlotPoint(bookId, PlotPointType.EXPOSITION, 0, "Story and characters are introduced", 0.7f))
            
            if (chapterCount >= 3) {
                points.add(PlotPoint(bookId, PlotPointType.INCITING_INCIDENT, 1, "The main conflict begins", 0.7f))
            }
            if (chapterCount >= 5) {
                points.add(PlotPoint(bookId, PlotPointType.MIDPOINT, chapterCount / 2, "A major turning point occurs", 0.6f))
            }
            if (chapterCount >= 4) {
                points.add(PlotPoint(bookId, PlotPointType.CLIMAX, chapterCount - 2, "Peak of the conflict", 0.7f))
            }
            if (chapterCount >= 2) {
                points.add(PlotPoint(bookId, PlotPointType.RESOLUTION, chapterCount - 1, "Story concludes", 0.7f))
            }
            
            return points
        }
    }
    
    override fun getDefaultOutput(): PlotPointOutput {
        return PlotPointOutput(
            bookId = 0,
            plotPoints = emptyList(),
            chapterCount = 0
        )
    }
}

