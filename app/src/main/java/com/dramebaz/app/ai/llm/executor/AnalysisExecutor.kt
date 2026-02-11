package com.dramebaz.app.ai.llm.executor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LlmModelHolder
import com.dramebaz.app.ai.llm.services.AnalysisBackgroundRunner
import com.dramebaz.app.ai.llm.services.AnalysisForegroundService
import com.dramebaz.app.ai.llm.tasks.AnalysisTask
import com.dramebaz.app.ai.llm.tasks.TaskProgress
import com.dramebaz.app.ai.llm.tasks.TaskResult
import com.dramebaz.app.ai.llm.persisters.TaskResultPersister
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * High-level executor for running analysis tasks.
 * 
 * This class:
 * - Chooses between foreground and background execution based on task characteristics
 * - Manages LLM model lifecycle
 * - Collects results and handles database persistence
 * 
 * Design Pattern: Facade Pattern - simplified interface to the analysis subsystem.
 * 
 * Architecture:
 * ```
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    AnalysisExecutor                          │
 * │  - Chooses foreground vs background based on estimated time  │
 * │  - Manages LLM model lifecycle                               │
 * │  - Handles result persistence via TaskResultPersister        │
 * └────────────────────────┬────────────────────────────────────┘
 *                          │
 *          ┌───────────────┴───────────────┐
 *          ▼                               ▼
 * ┌─────────────────────┐      ┌─────────────────────┐
 * │ AnalysisBackground  │      │ AnalysisForeground  │
 * │     Runner          │      │     Service         │
 * │ (< 60s tasks)       │      │ (>= 60s tasks)      │
 * └─────────────────────┘      └─────────────────────┘
 * ```
 */
class AnalysisExecutor(
    private val context: Context,
    private val resultPersister: TaskResultPersister? = null
) {
    companion object {
        private const val TAG = "AnalysisExecutor"

        /** Duration threshold for choosing foreground vs background (seconds) */
        private const val FOREGROUND_THRESHOLD_SECONDS = 60
    }

    private val modelMutex = Mutex()
    private var llmModel: LlmModel? = null
    private var backgroundRunner: AnalysisBackgroundRunner? = null

    // Listener for model switch notifications
    private val modelSwitchListener: () -> Unit = {
        AppLogger.i(TAG, "Model switch notification received - clearing cached model reference")
        llmModel = null
    }

    init {
        // Register to receive model switch notifications
        LlmModelHolder.addSwitchListener(modelSwitchListener)
    }
    
    /**
     * Execution result returned to callers.
     */
    data class ExecutionResult(
        val success: Boolean,
        val taskId: String,
        val durationMs: Long,
        val resultData: Map<String, Any> = emptyMap(),
        val error: String? = null,
        val persistedCount: Int = 0
    )
    
    /**
     * Options for task execution.
     */
    data class ExecutionOptions(
        /** Force foreground execution regardless of estimated duration */
        val forceForeground: Boolean = false,
        /** Force background execution (may fail for long tasks) */
        val forceBackground: Boolean = false,
        /** Should persist results to database automatically */
        val autoPersist: Boolean = true
    )
    
    /**
     * Execute a task, automatically choosing foreground or background execution.
     * 
     * @param task The task to execute
     * @param options Execution options
     * @param onProgress Progress callback
     * @return Execution result
     */
    suspend fun execute(
        task: AnalysisTask,
        options: ExecutionOptions = ExecutionOptions(),
        onProgress: ((TaskProgress) -> Unit)? = null
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Ensure model is loaded
        val model = ensureModelLoaded()
        if (model == null) {
            return@withContext ExecutionResult(
                success = false,
                taskId = task.taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = "Failed to load LLM model"
            )
        }
        
        // Decide execution mode
        val useForeground = when {
            options.forceForeground -> true
            options.forceBackground -> false
            else -> task.estimatedDurationSeconds > FOREGROUND_THRESHOLD_SECONDS
        }
        
        AppLogger.i(TAG, "Executing task ${task.taskId} " +
                "(foreground=$useForeground, estimated=${task.estimatedDurationSeconds}s)")
        
        val result = if (useForeground) {
            executeForeground(task, model, onProgress)
        } else {
            executeBackground(task, model, onProgress)
        }
        
        // Persist results if enabled
        var persistedCount = 0
        if (options.autoPersist && result.success && resultPersister != null) {
            persistedCount = resultPersister.persist(result.resultData)
            AppLogger.d(TAG, "Persisted $persistedCount items")
        }
        
        ExecutionResult(
            success = result.success,
            taskId = result.taskId,
            durationMs = result.durationMs,
            resultData = result.resultData,
            error = result.error,
            persistedCount = persistedCount
        )
    }
    
    /**
     * Execute a task in background (simple coroutine, no wake lock).
     */
    private suspend fun executeBackground(
        task: AnalysisTask,
        model: LlmModel,
        onProgress: ((TaskProgress) -> Unit)?
    ): TaskResult {
        val runner = backgroundRunner ?: AnalysisBackgroundRunner { llmModel }
        backgroundRunner = runner
        return runner.execute(task, onProgress)
    }

    /**
     * Execute a task in foreground (Android service with wake lock and notification).
     */
    private suspend fun executeForeground(
        task: AnalysisTask,
        model: LlmModel,
        onProgress: ((TaskProgress) -> Unit)?
    ): TaskResult = suspendCancellableCoroutine { continuation ->
        // Register receiver for completion broadcast
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    AnalysisForegroundService.ACTION_PROGRESS -> {
                        val progress = TaskProgress(
                            taskId = intent.getStringExtra(AnalysisForegroundService.EXTRA_TASK_ID) ?: "",
                            message = intent.getStringExtra(AnalysisForegroundService.EXTRA_PROGRESS_MESSAGE) ?: "",
                            percent = intent.getIntExtra(AnalysisForegroundService.EXTRA_PROGRESS_PERCENT, 0)
                        )
                        onProgress?.invoke(progress)
                    }
                    AnalysisForegroundService.ACTION_COMPLETE -> {
                        context.unregisterReceiver(this)

                        val success = intent.getBooleanExtra(AnalysisForegroundService.EXTRA_SUCCESS, false)
                        val error = intent.getStringExtra(AnalysisForegroundService.EXTRA_ERROR_MESSAGE)
                        @Suppress("UNCHECKED_CAST")
                        val resultData = intent.getSerializableExtra(AnalysisForegroundService.EXTRA_RESULT_DATA)
                            as? HashMap<String, Any> ?: emptyMap()

                        if (continuation.isActive) {
                            continuation.resume(TaskResult(
                                success = success,
                                taskId = task.taskId,
                                durationMs = 0, // Duration tracked by service
                                resultData = resultData,
                                error = error
                            ))
                        }
                    }
                }
            }
        }

        // Register receiver
        val filter = IntentFilter().apply {
            addAction(AnalysisForegroundService.ACTION_PROGRESS)
            addAction(AnalysisForegroundService.ACTION_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // Start foreground service
        AnalysisForegroundService.start(context, task, model)

        // Handle cancellation
        continuation.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) { /* ignore */ }
            AnalysisForegroundService.cancel(context)
        }
    }

    /**
     * Ensure the LLM model is loaded and ready.
     * Uses the singleton LlmModelHolder to avoid multiple model instances in GPU memory.
     */
    private suspend fun ensureModelLoaded(): LlmModel? = modelMutex.withLock {
        // Check if we have a cached reference that's still valid
        llmModel?.let {
            if (it.isModelLoaded()) return@withLock it
        }

        AppLogger.i(TAG, "Getting shared LLM model from LlmModelHolder...")

        // Use the singleton model holder - this prevents multiple GPU memory allocations
        val model = LlmModelHolder.getOrLoadModel(context)
        if (model == null) {
            AppLogger.e(TAG, "Failed to get LLM model from holder")
            return@withLock null
        }

        // Cache reference locally
        llmModel = model
        AppLogger.i(TAG, "Using shared LLM model: ${model.getExecutionProvider()}")
        return@withLock model
    }

    /**
     * Check if the LLM model is currently loaded.
     */
    fun isModelLoaded(): Boolean = llmModel?.isModelLoaded() == true

    /**
     * Release local resources.
     * Note: Does NOT release the shared model from LlmModelHolder - that's managed separately.
     */
    fun release() {
        backgroundRunner?.shutdown()
        backgroundRunner = null
        // Just clear local reference, don't release the shared model
        llmModel = null
        // Unregister from model switch notifications
        LlmModelHolder.removeSwitchListener(modelSwitchListener)
        AppLogger.i(TAG, "AnalysisExecutor released (shared model retained in LlmModelHolder)")
    }
}

