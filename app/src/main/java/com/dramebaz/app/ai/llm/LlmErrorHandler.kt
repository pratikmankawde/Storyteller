package com.dramebaz.app.ai.llm

import android.os.Build
import com.dramebaz.app.utils.AppLogger
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized error handling for LLM inference operations.
 * 
 * Provides:
 * - Detailed error logging with context for crash analysis
 * - Error categorization (native crash, timeout, OOM, etc.)
 * - Error history for debugging
 * - Safe execution wrappers that catch and log errors
 * 
 * Native crashes in liblitertlm_jni.so or libllama_jni.so are logged with
 * full context to help diagnose issues like SIGSEGV during inference.
 */
object LlmErrorHandler {
    private const val TAG = "LlmErrorHandler"
    
    // Error categories for classification
    enum class ErrorCategory {
        NATIVE_CRASH,       // SIGSEGV, SIGABRT in native code
        OUT_OF_MEMORY,      // OOM during inference
        TIMEOUT,            // Inference took too long
        MODEL_NOT_LOADED,   // Model not initialized
        INVALID_INPUT,      // Bad input text (encoding issues, etc.)
        TOKEN_LIMIT,        // Exceeded token limit
        INFERENCE_ERROR,    // General inference failure
        UNKNOWN             // Unclassified error
    }
    
    // Error record for history
    data class LlmError(
        val timestamp: Long = System.currentTimeMillis(),
        val category: ErrorCategory,
        val operation: String,
        val message: String,
        val inputPreview: String? = null,
        val inputLength: Int = 0,
        val stackTrace: String? = null,
        val deviceInfo: String = getDeviceInfo(),
        val memoryInfo: String = getMemoryInfo()
    ) {
        fun toLogString(): String = buildString {
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine("LLM ERROR REPORT")
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine("Timestamp: ${formatTimestamp(timestamp)}")
            appendLine("Category: $category")
            appendLine("Operation: $operation")
            appendLine("Message: $message")
            appendLine("───────────────────────────────────────────────────────────")
            appendLine("Input Length: $inputLength chars")
            inputPreview?.let { appendLine("Input Preview: $it") }
            appendLine("───────────────────────────────────────────────────────────")
            appendLine("Device: $deviceInfo")
            appendLine("Memory: $memoryInfo")
            stackTrace?.let {
                appendLine("───────────────────────────────────────────────────────────")
                appendLine("Stack Trace:")
                appendLine(it)
            }
            appendLine("═══════════════════════════════════════════════════════════")
        }
        
        private fun formatTimestamp(ts: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ts))
        }
    }
    
    // Keep last N errors for debugging
    private const val MAX_ERROR_HISTORY = 50
    private val errorHistory = ConcurrentLinkedQueue<LlmError>()
    private val errorCount = AtomicInteger(0)
    
    /**
     * Log an LLM error with full context for later analysis.
     */
    fun logError(
        category: ErrorCategory,
        operation: String,
        message: String,
        throwable: Throwable? = null,
        inputText: String? = null
    ) {
        val error = LlmError(
            category = category,
            operation = operation,
            message = message,
            inputPreview = inputText?.take(200)?.replace("\n", "\\n"),
            inputLength = inputText?.length ?: 0,
            stackTrace = throwable?.let { getStackTraceString(it) }
        )
        
        // Add to history (with size limit)
        errorHistory.add(error)
        while (errorHistory.size > MAX_ERROR_HISTORY) {
            errorHistory.poll()
        }
        errorCount.incrementAndGet()
        
        // Log the full error report
        AppLogger.e(TAG, error.toLogString())
    }
    
    /**
     * Categorize an exception into an error category.
     */
    fun categorizeError(throwable: Throwable): ErrorCategory {
        val message = throwable.message?.lowercase() ?: ""
        val className = throwable.javaClass.simpleName.lowercase()
        
        return when {
            // Native crashes
            message.contains("signal") || message.contains("sigsegv") ||
            message.contains("sigabrt") || message.contains("native") ||
            className.contains("error") && message.contains("jni") -> ErrorCategory.NATIVE_CRASH
            
            // Out of memory
            throwable is OutOfMemoryError || message.contains("out of memory") ||
            message.contains("oom") || message.contains("alloc") -> ErrorCategory.OUT_OF_MEMORY
            
            // Timeout
            message.contains("timeout") || message.contains("timed out") ||
            throwable is kotlinx.coroutines.TimeoutCancellationException -> ErrorCategory.TIMEOUT
            
            // Token limit
            message.contains("token") && (message.contains("limit") || message.contains("max")) -> ErrorCategory.TOKEN_LIMIT
            
            // Invalid input
            message.contains("encoding") || message.contains("invalid") ||
            message.contains("utf") || message.contains("character") -> ErrorCategory.INVALID_INPUT
            
            // Model not loaded
            message.contains("not loaded") || message.contains("not initialized") ||
            message.contains("null") && message.contains("model") -> ErrorCategory.MODEL_NOT_LOADED
            
            else -> ErrorCategory.UNKNOWN
        }
    }
    
    /**
     * Get device info for error context.
     */
    private fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
    }
    
    /**
     * Get memory info for error context.
     */
    private fun getMemoryInfo(): String {
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        return "Used: ${usedMB}MB / Max: ${maxMB}MB"
    }
    
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
    
    /**
     * Get total error count since app start.
     */
    fun getErrorCount(): Int = errorCount.get()
    
    /**
     * Get recent errors for debugging.
     */
    fun getRecentErrors(): List<LlmError> = errorHistory.toList()
    
    /**
     * Clear error history.
     */
    fun clearHistory() {
        errorHistory.clear()
    }

    /**
     * Execute an LLM operation safely with error handling.
     * Catches all exceptions, logs them with context, and returns a default value.
     *
     * @param operation Name of the operation for logging
     * @param inputText Input text for context (optional)
     * @param defaultValue Value to return on error
     * @param block The operation to execute
     * @return Result of the operation or defaultValue on error
     */
    inline fun <T> safeExecute(
        operation: String,
        inputText: String? = null,
        defaultValue: T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (e: Throwable) {
            val category = categorizeError(e)
            logError(
                category = category,
                operation = operation,
                message = e.message ?: "Unknown error",
                throwable = e,
                inputText = inputText
            )
            defaultValue
        }
    }

    /**
     * Execute a suspend LLM operation safely with error handling.
     * Catches all exceptions, logs them with context, and returns a default value.
     *
     * @param operation Name of the operation for logging
     * @param inputText Input text for context (optional)
     * @param defaultValue Value to return on error
     * @param block The suspend operation to execute
     * @return Result of the operation or defaultValue on error
     */
    suspend inline fun <T> safeExecuteSuspend(
        operation: String,
        inputText: String? = null,
        defaultValue: T,
        crossinline block: suspend () -> T
    ): T {
        return try {
            block()
        } catch (e: Throwable) {
            val category = categorizeError(e)
            logError(
                category = category,
                operation = operation,
                message = e.message ?: "Unknown error",
                throwable = e,
                inputText = inputText
            )
            defaultValue
        }
    }

    /**
     * Validate and sanitize input text before LLM inference.
     * Helps prevent native crashes from malformed input.
     *
     * @param text Input text to validate
     * @param maxLength Maximum allowed length
     * @return Sanitized text or null if invalid
     */
    fun sanitizeInput(text: String?, maxLength: Int = 100_000): String? {
        if (text.isNullOrBlank()) {
            AppLogger.w(TAG, "sanitizeInput: Empty or null input")
            return null
        }

        // Truncate if too long
        val truncated = if (text.length > maxLength) {
            AppLogger.w(TAG, "sanitizeInput: Truncating input from ${text.length} to $maxLength chars")
            text.take(maxLength)
        } else {
            text
        }

        // Remove null characters and other problematic control characters
        // These can cause issues in native code
        val sanitized = truncated
            .replace("\u0000", "") // Null character
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "") // Control chars except \t, \n, \r

        if (sanitized.length != truncated.length) {
            AppLogger.w(TAG, "sanitizeInput: Removed ${truncated.length - sanitized.length} problematic characters")
        }

        return sanitized.ifBlank { null }
    }

    /**
     * Log a warning before a potentially risky native call.
     * Useful for debugging native crashes by seeing what was attempted.
     */
    fun logPreInference(
        operation: String,
        inputLength: Int,
        maxTokens: Int,
        temperature: Float,
        additionalInfo: Map<String, Any> = emptyMap()
    ) {
        val infoStr = additionalInfo.entries.joinToString(", ") { "${it.key}=${it.value}" }
        AppLogger.d(TAG, "→ $operation: input=${inputLength} chars, maxTokens=$maxTokens, temp=$temperature" +
            if (infoStr.isNotEmpty()) ", $infoStr" else "")
    }

    /**
     * Log successful completion of an inference call.
     */
    fun logPostInference(
        operation: String,
        outputLength: Int,
        durationMs: Long
    ) {
        AppLogger.d(TAG, "← $operation: output=${outputLength} chars, duration=${durationMs}ms")
    }
}

