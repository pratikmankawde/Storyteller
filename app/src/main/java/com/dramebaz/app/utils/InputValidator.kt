package com.dramebaz.app.utils

import com.dramebaz.app.domain.exceptions.ValidationException
import java.io.File

/**
 * AUG-040: Input validation and sanitization utilities.
 * Provides validation for story prompts, file imports, and LLM prompt sanitization.
 */
object InputValidator {
    private const val TAG = "InputValidator"

    // Story prompt constraints
    const val MIN_PROMPT_LENGTH = 10
    const val MAX_PROMPT_LENGTH = 1000

    // File import constraints
    const val MAX_FILE_SIZE_MB = 100
    const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024L

    // LLM prompt constraints
    const val MAX_LLM_PROMPT_LENGTH = 32000 // Context window limit for Qwen3-1.7B

    // Supported file formats for import
    val SUPPORTED_FORMATS = setOf("pdf", "txt", "epub")

    /**
     * Validate story generation prompt.
     * @throws ValidationException if prompt is invalid
     */
    fun validateStoryPrompt(prompt: String): Result<String> {
        val trimmed = prompt.trim()

        return when {
            trimmed.isEmpty() -> Result.failure(
                ValidationException(
                    "Story prompt is empty",
                    userMessage = "Please enter a story prompt."
                )
            )
            trimmed.length < MIN_PROMPT_LENGTH -> Result.failure(
                ValidationException(
                    "Story prompt too short: ${trimmed.length} < $MIN_PROMPT_LENGTH",
                    userMessage = "Prompt must be at least $MIN_PROMPT_LENGTH characters. Please add more details."
                )
            )
            trimmed.length > MAX_PROMPT_LENGTH -> Result.failure(
                ValidationException(
                    "Story prompt too long: ${trimmed.length} > $MAX_PROMPT_LENGTH",
                    userMessage = "Prompt must be less than $MAX_PROMPT_LENGTH characters. Please shorten it."
                )
            )
            else -> Result.success(trimmed)
        }
    }

    /**
     * Validate file for import.
     * @throws ValidationException if file is invalid
     */
    fun validateFileForImport(file: File, format: String): Result<File> {
        return when {
            !file.exists() -> Result.failure(
                ValidationException(
                    "File does not exist: ${file.absolutePath}",
                    userMessage = "File not found. Please select a valid file."
                )
            )
            !file.canRead() -> Result.failure(
                ValidationException(
                    "File not readable: ${file.absolutePath}",
                    userMessage = "Cannot read the file. Please check permissions."
                )
            )
            file.length() == 0L -> Result.failure(
                ValidationException(
                    "File is empty: ${file.absolutePath}",
                    userMessage = "The file is empty. Please select a valid book file."
                )
            )
            file.length() > MAX_FILE_SIZE_BYTES -> Result.failure(
                ValidationException(
                    "File too large: ${file.length()} bytes > $MAX_FILE_SIZE_BYTES",
                    userMessage = "File is too large (max ${MAX_FILE_SIZE_MB}MB). Please select a smaller file."
                )
            )
            !SUPPORTED_FORMATS.contains(format.lowercase()) -> Result.failure(
                ValidationException(
                    "Unsupported format: $format",
                    userMessage = "Unsupported file format. Please use PDF, TXT, or EPUB."
                )
            )
            else -> Result.success(file)
        }
    }

    /**
     * Sanitize LLM prompt input.
     * Removes/escapes special characters and limits length.
     */
    fun sanitizeLlmPrompt(input: String, maxLength: Int = MAX_LLM_PROMPT_LENGTH): String {
        return input
            // Remove null characters
            .replace("\u0000", "")
            // Normalize whitespace (preserve newlines but collapse multiple spaces)
            .replace(Regex("[ \\t]+"), " ")
            // Remove control characters except newline/tab
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            // Limit length
            .take(maxLength)
            .trim()
    }

    /**
     * Sanitize text for TTS synthesis.
     * Removes characters that may cause issues with TTS.
     */
    fun sanitizeForTts(input: String): String {
        return input
            // Remove excessive punctuation repeats
            .replace(Regex("([.!?]){3,}"), "$1$1$1")
            // Remove control characters
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            // Normalize smart quotes to standard quotes
            .replace("\u201C", "\"") // left double quote
            .replace("\u201D", "\"") // right double quote
            .replace("\u2018", "'")  // left single quote
            .replace("\u2019", "'")  // right single quote
            // Remove emoji (basic pattern - surrogate pairs)
            .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "")
            .trim()
    }

    /**
     * Get user-friendly error message from validation result.
     */
    fun getErrorMessage(result: Result<*>): String {
        val error = result.exceptionOrNull()
        return when (error) {
            is ValidationException -> error.userMessage
            else -> error?.message ?: "Invalid input"
        }
    }
}
