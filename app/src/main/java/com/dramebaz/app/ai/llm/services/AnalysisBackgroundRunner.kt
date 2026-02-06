package com.dramebaz.app.ai.llm.services

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.tasks.AnalysisTask
import com.dramebaz.app.ai.llm.tasks.TaskProgress
import com.dramebaz.app.ai.llm.tasks.TaskResult
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background runner for analysis tasks.
 * 
 * This is a simple coroutine-based runner with:
 * - NO wake lock (CPU may sleep)
 * - NO notification
 * - Suitable for short tasks or when app is in foreground
 * 
 * NOT an Android Service - just a class that runs coroutines in the background.
 * 
 * Use for tasks with estimatedDurationSeconds < 60.
 */
class AnalysisBackgroundRunner(
    private val modelProvider: suspend () -> LlmModel?
) {
    
    companion object {
        private const val TAG = "AnalysisBackgroundRunner"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var currentJob: Job? = null
    private var currentTaskId: String? = null
    
    /**
     * Execute a task asynchronously.
     * 
     * @param task The task to execute
     * @param onProgress Progress callback (called on IO thread)
     * @param onComplete Completion callback (called on IO thread)
     */
    fun executeAsync(
        task: AnalysisTask,
        onProgress: ((TaskProgress) -> Unit)? = null,
        onComplete: (TaskResult) -> Unit
    ) {
        scope.launch {
            val result = execute(task, onProgress)
            onComplete(result)
        }
    }
    
    /**
     * Execute a task and wait for result.
     * 
     * @param task The task to execute
     * @param onProgress Progress callback
     * @return Task result
     */
    suspend fun execute(
        task: AnalysisTask,
        onProgress: ((TaskProgress) -> Unit)? = null
    ): TaskResult = mutex.withLock {
        val startTime = System.currentTimeMillis()
        currentTaskId = task.taskId
        
        AppLogger.i(TAG, "Starting task: ${task.displayName} (${task.taskId})")
        
        return@withLock try {
            // Get the LLM model
            val model = modelProvider()
            if (model == null || !model.isModelLoaded()) {
                AppLogger.e(TAG, "LLM model not available")
                return@withLock TaskResult(
                    success = false,
                    taskId = task.taskId,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = "LLM model not available"
                )
            }
            
            // Execute the task
            val result = task.execute(model, onProgress)
            
            AppLogger.i(TAG, "Task completed: ${task.displayName} " +
                    "(success=${result.success}, duration=${result.durationMs}ms)")
            
            result
            
        } catch (e: CancellationException) {
            AppLogger.w(TAG, "Task cancelled: ${task.displayName}")
            TaskResult(
                success = false,
                taskId = task.taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = "Cancelled"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Task failed: ${task.displayName}", e)
            TaskResult(
                success = false,
                taskId = task.taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        } finally {
            currentTaskId = null
        }
    }
    
    /** Check if a task is currently running */
    fun isRunning(): Boolean = mutex.isLocked
    
    /** Get the ID of the currently running task */
    fun getCurrentTaskId(): String? = currentTaskId
    
    /** Cancel the current task */
    fun cancelCurrentTask() {
        currentJob?.cancel()
        currentJob = null
    }
    
    /** Shutdown the runner and release resources */
    fun shutdown() {
        scope.cancel()
    }
}

