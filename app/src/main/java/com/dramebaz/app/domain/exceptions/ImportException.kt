package com.dramebaz.app.domain.exceptions

/**
 * Exception thrown when book import fails.
 * AUG-039: Implements similar interface to AppException for consistent error handling.
 * Contains a user-friendly message and optional cause for logging.
 */
class ImportException(
    message: String,
    cause: Throwable? = null,
    val userMessage: String = "Failed to import book. Please check the file and try again."
) : Exception(message, cause) {
    val errorType: ErrorType = ErrorType.FILE_IO
    val isRetryable: Boolean = true
}
