package com.dramebaz.app.ai.llm.tasks

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.pipeline.PersonalityInferenceInput
import com.dramebaz.app.ai.llm.pipeline.PersonalityInferencePass
import com.dramebaz.app.ai.llm.pipeline.TraitsExtractionInput
import com.dramebaz.app.ai.llm.pipeline.TraitsExtractionPass
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson

/**
 * Background task for traits and personality extraction.
 * 
 * This task wraps TraitsExtractionPass and PersonalityInferencePass
 * into an AnalysisTask that can be run after the main 3-pass analysis.
 * 
 * Runs for a single character with their dialog context.
 */
class TraitsExtractionTask(
    val bookId: Long,
    val characterName: String,
    val dialogContext: String
) : AnalysisTask {
    
    companion object {
        private const val TAG = "TraitsExtractionTask"
        
        // Result keys
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHARACTER_NAME = "character_name"
        const val KEY_TRAITS = "traits"
        const val KEY_PERSONALITY = "personality"
    }
    
    private val gson = Gson()
    
    override val taskId: String = "traits_extraction_${bookId}_${characterName.hashCode()}"
    override val displayName: String = "Extracting traits for $characterName"
    override val estimatedDurationSeconds: Int = 30  // Short task, background-eligible
    
    override suspend fun execute(
        model: LlmModel,
        progressCallback: ((TaskProgress) -> Unit)?
    ): TaskResult {
        val startTime = System.currentTimeMillis()
        
        AppLogger.d(TAG, "Starting traits extraction for '$characterName', context=${dialogContext.length} chars")
        
        try {
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Extracting traits...",
                percent = 20,
                currentStep = 1,
                totalSteps = 2,
                stepName = "Traits Extraction"
            ))
            
            // Step 1: Extract traits
            val traitsPass = TraitsExtractionPass()
            val traitsConfig = PassConfig.PASS3_TRAITS_EXTRACTION
            val traitsInput = TraitsExtractionInput(
                characterName = characterName,
                contextText = dialogContext
            )
            val traitsOutput = traitsPass.execute(model, traitsInput, traitsConfig)
            
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Inferring personality...",
                percent = 60,
                currentStep = 2,
                totalSteps = 2,
                stepName = "Personality Inference"
            ))
            
            // Step 2: Infer personality from traits
            val personalityList = if (traitsOutput.traits.isNotEmpty()) {
                val personalityPass = PersonalityInferencePass()
                val personalityConfig = PersonalityInferencePass.DEFAULT_CONFIG
                val personalityInput = PersonalityInferenceInput(
                    characterName = characterName,
                    traits = traitsOutput.traits
                )
                val personalityOutput = personalityPass.execute(model, personalityInput, personalityConfig)
                personalityOutput.personality
            } else {
                emptyList()
            }
            
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Complete",
                percent = 100,
                currentStep = 2,
                totalSteps = 2
            ))
            
            val duration = System.currentTimeMillis() - startTime
            AppLogger.i(TAG, "Traits extraction complete for '$characterName': " +
                    "${traitsOutput.traits.size} traits, ${personalityList.size} personality aspects")
            
            return TaskResult(
                success = true,
                taskId = taskId,
                durationMs = duration,
                resultData = mapOf(
                    KEY_BOOK_ID to bookId,
                    KEY_CHARACTER_NAME to characterName,
                    KEY_TRAITS to gson.toJson(traitsOutput.traits),
                    KEY_PERSONALITY to gson.toJson(personalityList)
                )
            )
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Traits extraction failed for '$characterName'", e)
            return TaskResult(
                success = false,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }
}

