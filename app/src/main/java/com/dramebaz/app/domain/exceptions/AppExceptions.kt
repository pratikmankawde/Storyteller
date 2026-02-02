package com.dramebaz.app.domain.exceptions

/**
 * AUG-039: Base exception class for all app-specific exceptions.
 * Contains error type, user-friendly message, and optional retry capability.
 */
sealed class AppException(
    message: String,
    cause: Throwable? = null,
    val errorType: ErrorType,
    val userMessage: String,
    val isRetryable: Boolean = false
) : Exception(message, cause)

/**
 * Error types for categorization and handling.
 */
enum class ErrorType {
    FILE_IO,
    DATABASE,
    LLM,
    TTS,
    OUT_OF_MEMORY,
    VALIDATION,
    UNKNOWN
}

/**
 * File I/O related exceptions (PDF extraction, file access, etc.)
 */
class FileIOException(
    message: String,
    cause: Throwable? = null,
    userMessage: String = "Failed to read or write file. Please check if the file exists and is accessible."
) : AppException(message, cause, ErrorType.FILE_IO, userMessage, isRetryable = true)

/**
 * Database related exceptions (Room operations, migrations, etc.)
 */
class DatabaseException(
    message: String,
    cause: Throwable? = null,
    userMessage: String = "Database operation failed. Please try again or restart the app."
) : AppException(message, cause, ErrorType.DATABASE, userMessage, isRetryable = true)

/**
 * LLM related exceptions (ONNX model failures, analysis errors, etc.)
 */
class LLMException(
    message: String,
    cause: Throwable? = null,
    userMessage: String = "AI analysis failed. Using fallback mode."
) : AppException(message, cause, ErrorType.LLM, userMessage, isRetryable = true)

/**
 * TTS related exceptions (voice synthesis failures, audio generation errors, etc.)
 */
class TTSException(
    message: String,
    cause: Throwable? = null,
    userMessage: String = "Voice synthesis failed. Please try again."
) : AppException(message, cause, ErrorType.TTS, userMessage, isRetryable = true)

/**
 * Out of memory exceptions (model loading, large file processing, etc.)
 */
class OutOfMemoryException(
    message: String,
    cause: Throwable? = null,
    userMessage: String = "Not enough memory. Please close other apps and try again."
) : AppException(message, cause, ErrorType.OUT_OF_MEMORY, userMessage, isRetryable = false)

/**
 * Input validation exceptions (invalid prompts, file formats, etc.)
 */
class ValidationException(
    message: String,
    cause: Throwable? = null,
    userMessage: String = "Invalid input. Please check and try again."
) : AppException(message, cause, ErrorType.VALIDATION, userMessage, isRetryable = false)

