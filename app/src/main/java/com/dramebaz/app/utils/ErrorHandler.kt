package com.dramebaz.app.utils

import com.dramebaz.app.domain.exceptions.*
import kotlinx.coroutines.delay

/**
 * AUG-039: Centralized error handling utility.
 * Provides retry logic with exponential backoff and error classification.
 */
object ErrorHandler {
    private const val TAG = "ErrorHandler"
    
    // Default retry configuration
    private const val DEFAULT_MAX_RETRIES = 3
    private const val DEFAULT_INITIAL_DELAY_MS = 1000L
    private const val DEFAULT_MAX_DELAY_MS = 30000L
    private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
    
    /**
     * Execute an operation with exponential backoff retry.
     * 
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param initialDelayMs Initial delay in milliseconds (default: 1000)
     * @param maxDelayMs Maximum delay in milliseconds (default: 30000)
     * @param backoffMultiplier Multiplier for delay on each retry (default: 2.0)
     * @param shouldRetry Lambda to determine if error should trigger retry
     * @param onRetry Callback invoked before each retry with attempt number and delay
     * @param operation The suspending operation to execute
     * @return Result of the operation
     */
    suspend fun <T> withRetry(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
        shouldRetry: (Throwable) -> Boolean = { it is AppException && it.isRetryable },
        onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
        operation: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null
        
        repeat(maxRetries + 1) { attempt ->
            try {
                return operation()
            } catch (e: Throwable) {
                lastException = e
                
                if (attempt == maxRetries || !shouldRetry(e)) {
                    AppLogger.e(TAG, "Operation failed after ${attempt + 1} attempts", e)
                    throw e
                }
                
                AppLogger.w(TAG, "Attempt ${attempt + 1} failed, retrying in ${currentDelay}ms: ${e.message}")
                onRetry(attempt + 1, currentDelay, e)
                delay(currentDelay)
                currentDelay = (currentDelay * backoffMultiplier).toLong().coerceAtMost(maxDelayMs)
            }
        }
        
        throw lastException ?: IllegalStateException("Retry loop exited unexpectedly")
    }
    
    /**
     * Execute an operation with retry, returning Result.
     * Never throws, returns Result.failure on all failures.
     */
    suspend fun <T> withRetryResult(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
        operation: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(withRetry(maxRetries, initialDelayMs, onRetry = onRetry, operation = operation))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
    
    /**
     * Classify a generic exception into an AppException type.
     */
    fun classify(error: Throwable): AppException {
        return when {
            error is AppException -> error
            error is OutOfMemoryError -> OutOfMemoryException(
                "Out of memory: ${error.message}",
                error,
                "Not enough memory available. Please close other apps and try again."
            )
            error.message?.contains("database", ignoreCase = true) == true ||
            error.message?.contains("SQLite", ignoreCase = true) == true ||
            error.message?.contains("room", ignoreCase = true) == true -> DatabaseException(
                "Database error: ${error.message}",
                error
            )
            error.message?.contains("file", ignoreCase = true) == true ||
            error.message?.contains("io", ignoreCase = true) == true ||
            error.message?.contains("read", ignoreCase = true) == true ||
            error.message?.contains("write", ignoreCase = true) == true -> FileIOException(
                "File I/O error: ${error.message}",
                error
            )
            error.message?.contains("onnx", ignoreCase = true) == true ||
            error.message?.contains("model", ignoreCase = true) == true ||
            error.message?.contains("llm", ignoreCase = true) == true -> LLMException(
                "LLM error: ${error.message}",
                error
            )
            error.message?.contains("tts", ignoreCase = true) == true ||
            error.message?.contains("audio", ignoreCase = true) == true ||
            error.message?.contains("synthesis", ignoreCase = true) == true -> TTSException(
                "TTS error: ${error.message}",
                error
            )
            else -> LLMException(
                "Unknown error: ${error.message}",
                error,
                "An unexpected error occurred. Please try again."
            )
        }
    }
    
    /**
     * Get a user-friendly message for any error.
     */
    fun getUserMessage(error: Throwable): String {
        return when (error) {
            is AppException -> error.userMessage
            is OutOfMemoryError -> "Not enough memory. Please close other apps and try again."
            else -> classify(error).userMessage
        }
    }
    
    /**
     * Log an error with appropriate severity based on type.
     */
    fun logError(tag: String, message: String, error: Throwable) {
        val classified = classify(error)
        when (classified.errorType) {
            ErrorType.OUT_OF_MEMORY -> AppLogger.e(tag, "[OOM] $message", error)
            ErrorType.DATABASE -> AppLogger.e(tag, "[DB] $message", error)
            ErrorType.FILE_IO -> AppLogger.w(tag, "[IO] $message", error)
            ErrorType.LLM -> AppLogger.w(tag, "[LLM] $message", error)
            ErrorType.TTS -> AppLogger.w(tag, "[TTS] $message", error)
            ErrorType.VALIDATION -> AppLogger.w(tag, "[VALID] $message", error)
            ErrorType.UNKNOWN -> AppLogger.e(tag, "[UNK] $message", error)
        }
    }
}

