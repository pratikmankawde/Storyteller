package com.dramebaz.app.ai.llm.tasks

import com.dramebaz.app.ai.llm.models.LlmModel

/**
 * Interface for analysis tasks that can be executed by the analysis services.
 * 
 * Tasks are self-contained units of work that:
 * - Have a unique ID and display name
 * - Estimate their duration (for foreground/background decision)
 * - Execute using an LLM model
 * - Report progress via callbacks
 * - Return results in a standardized format
 * 
 * Design Pattern: Command Pattern - encapsulates the analysis workflow as an object.
 */
interface AnalysisTask {
    /** Unique identifier for this task instance */
    val taskId: String
    
    /** Human-readable name for UI display */
    val displayName: String
    
    /** Estimated duration in seconds (used to decide foreground vs background) */
    val estimatedDurationSeconds: Int
    
    /**
     * Execute the task.
     * 
     * @param model The LLM model to use for inference
     * @param progressCallback Optional callback for progress updates
     * @return Task result with success/failure, duration, and result data
     */
    suspend fun execute(
        model: LlmModel,
        progressCallback: ((TaskProgress) -> Unit)?
    ): TaskResult
}

/**
 * Progress update during task execution.
 */
data class TaskProgress(
    /** Task ID this progress belongs to */
    val taskId: String,
    /** Human-readable progress message */
    val message: String,
    /** Progress percentage (0-100) */
    val percent: Int,
    /** Current step number (optional) */
    val currentStep: Int = 0,
    /** Total number of steps (optional) */
    val totalSteps: Int = 0,
    /** Name of current step (optional) */
    val stepName: String? = null
)

/**
 * Result of task execution.
 */
data class TaskResult(
    /** Whether the task completed successfully */
    val success: Boolean,
    /** Task ID this result belongs to */
    val taskId: String,
    /** Total execution time in milliseconds */
    val durationMs: Long,
    /** Result data (varies by task type) */
    val resultData: Map<String, Any> = emptyMap(),
    /** Error message if failed */
    val error: String? = null
)

