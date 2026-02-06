package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.prompts.ThemeAnalysisInput
import com.dramebaz.app.ai.llm.prompts.ThemeAnalysisOutput
import com.dramebaz.app.ai.llm.prompts.ThemeAnalysisPrompt

/**
 * THEME-001: Theme Analysis Pass
 * 
 * Uses LLM to analyze book content and generate appropriate UI themes.
 * 
 * This pass follows the new architecture:
 * - Uses ThemeAnalysisPrompt for prompt definition
 * - Extends BaseExtractionPass for common execution logic
 * - Works with any LlmModel implementation
 */
class ThemeAnalysisPass : BaseExtractionPass<ThemeAnalysisInput, ThemeAnalysisOutput>(
    ThemeAnalysisPrompt
) {
    
    companion object {
        /**
         * Heuristic fallback for theme generation when LLM is unavailable.
         */
        fun heuristicAnalysis(bookId: Long, title: String, text: String): ThemeAnalysisOutput {
            val lowerText = text.lowercase()
            val lowerTitle = title.lowercase()
            
            val mood = detectMood(lowerText, lowerTitle)
            val genre = detectGenre(lowerText, lowerTitle)
            
            return ThemeAnalysisOutput(
                bookId = bookId,
                mood = mood,
                genre = genre,
                era = "contemporary",
                emotionalTone = "neutral",
                ambientSound = null
            )
        }
        
        private fun detectMood(text: String, title: String): String {
            val combined = "$title $text"
            return when {
                combined.contains("dark") || combined.contains("shadow") ||
                combined.contains("death") || combined.contains("blood") ||
                combined.contains("vampire") || combined.contains("gothic") -> "dark_gothic"
                
                combined.contains("love") || combined.contains("heart") ||
                combined.contains("kiss") || combined.contains("romance") ||
                combined.contains("passion") -> "romantic"
                
                combined.contains("adventure") || combined.contains("quest") ||
                combined.contains("journey") || combined.contains("hero") ||
                combined.contains("treasure") || combined.contains("explore") -> "adventure"
                
                combined.contains("mystery") || combined.contains("detective") ||
                combined.contains("murder") || combined.contains("clue") ||
                combined.contains("crime") || combined.contains("secret") -> "mystery"
                
                combined.contains("magic") || combined.contains("dragon") ||
                combined.contains("wizard") || combined.contains("kingdom") ||
                combined.contains("elf") || combined.contains("sword") -> "fantasy"
                
                combined.contains("space") || combined.contains("robot") ||
                combined.contains("future") || combined.contains("alien") ||
                combined.contains("technology") || combined.contains("cyber") -> "scifi"
                
                else -> "classic"
            }
        }
        
        private fun detectGenre(text: String, title: String): String {
            val combined = "$title $text"
            return when {
                combined.contains("fantasy") || combined.contains("magic") ||
                combined.contains("dragon") || combined.contains("elf") -> "fantasy"
                
                combined.contains("space") || combined.contains("cyber") ||
                combined.contains("robot") || combined.contains("future") -> "scifi"
                
                combined.contains("love") || combined.contains("romance") ||
                combined.contains("heart") || combined.contains("passion") -> "romance"
                
                combined.contains("murder") || combined.contains("detective") ||
                combined.contains("thriller") || combined.contains("suspense") -> "thriller"
                
                combined.contains("thou") || combined.contains("hath") ||
                combined.contains("wherefore") -> "classic_literature"
                
                else -> "modern_fiction"
            }
        }
    }
    
    override fun getDefaultOutput(): ThemeAnalysisOutput {
        return ThemeAnalysisOutput(
            bookId = 0,
            mood = "classic",
            genre = "modern_fiction",
            era = "contemporary",
            emotionalTone = "neutral",
            ambientSound = null
        )
    }
}

