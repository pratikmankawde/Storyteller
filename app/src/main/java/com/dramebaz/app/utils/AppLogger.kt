package com.dramebaz.app.utils

import android.util.Log

/**
 * Centralized logging utility for the Dramebaz app.
 * Provides consistent logging with tags and log levels.
 */
object AppLogger {
    private const val DEFAULT_TAG = "Dramebaz"
    private const val MAX_LOG_LENGTH = 4000 // Android log limit
    
    @JvmStatic
    fun d(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.d(tag, message, throwable)
        } else {
            logLongMessage(Log.DEBUG, tag, message)
        }
    }
    
    @JvmStatic
    fun i(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            logLongMessage(Log.INFO, tag, message)
        }
    }
    
    @JvmStatic
    fun w(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            logLongMessage(Log.WARN, tag, message)
        }
    }
    
    @JvmStatic
    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            logLongMessage(Log.ERROR, tag, message)
        }
    }
    
    private fun logLongMessage(level: Int, tag: String, message: String) {
        if (message.length <= MAX_LOG_LENGTH) {
            Log.println(level, tag, message)
        } else {
            // Split long messages
            var start = 0
            while (start < message.length) {
                val end = (start + MAX_LOG_LENGTH).coerceAtMost(message.length)
                Log.println(level, tag, message.substring(start, end))
                start = end
            }
        }
    }
    
    // Convenience methods for common operations
    @JvmStatic
    fun logMethodEntry(tag: String, methodName: String, vararg params: Pair<String, Any?>) {
        val paramsStr = params.joinToString(", ") { "${it.first}=${it.second}" }
        d(tag, "→ $methodName($paramsStr)")
    }
    
    @JvmStatic
    fun logMethodExit(tag: String, methodName: String, result: Any? = null) {
        if (result != null) {
            d(tag, "← $methodName() = $result")
        } else {
            d(tag, "← $methodName()")
        }
    }
    
    @JvmStatic
    fun logPerformance(tag: String, operation: String, durationMs: Long) {
        i(tag, "⏱ $operation took ${durationMs}ms")
    }
}
