package com.dramebaz.app.domain.exceptions

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AppExceptions hierarchy.
 * Tests error type classification and properties.
 */
class AppExceptionsTest {

    // FileIOException tests

    @Test
    fun `FileIOException has FILE_IO error type`() {
        val exception = FileIOException("Test error")
        assertEquals(ErrorType.FILE_IO, exception.errorType)
    }

    @Test
    fun `FileIOException is retryable`() {
        val exception = FileIOException("Test error")
        assertTrue(exception.isRetryable)
    }

    @Test
    fun `FileIOException has default user message`() {
        val exception = FileIOException("Test error")
        assertTrue(exception.userMessage.isNotBlank())
    }

    @Test
    fun `FileIOException accepts custom user message`() {
        val customMessage = "Custom file error message"
        val exception = FileIOException("Test error", userMessage = customMessage)
        assertEquals(customMessage, exception.userMessage)
    }

    // DatabaseException tests

    @Test
    fun `DatabaseException has DATABASE error type`() {
        val exception = DatabaseException("Test error")
        assertEquals(ErrorType.DATABASE, exception.errorType)
    }

    @Test
    fun `DatabaseException is retryable`() {
        val exception = DatabaseException("Test error")
        assertTrue(exception.isRetryable)
    }

    // LLMException tests

    @Test
    fun `LLMException has LLM error type`() {
        val exception = LLMException("Test error")
        assertEquals(ErrorType.LLM, exception.errorType)
    }

    @Test
    fun `LLMException is retryable`() {
        val exception = LLMException("Test error")
        assertTrue(exception.isRetryable)
    }

    // TTSException tests

    @Test
    fun `TTSException has TTS error type`() {
        val exception = TTSException("Test error")
        assertEquals(ErrorType.TTS, exception.errorType)
    }

    @Test
    fun `TTSException is retryable`() {
        val exception = TTSException("Test error")
        assertTrue(exception.isRetryable)
    }

    // OutOfMemoryException tests

    @Test
    fun `OutOfMemoryException has OUT_OF_MEMORY error type`() {
        val exception = OutOfMemoryException("Test error")
        assertEquals(ErrorType.OUT_OF_MEMORY, exception.errorType)
    }

    @Test
    fun `OutOfMemoryException is NOT retryable`() {
        val exception = OutOfMemoryException("Test error")
        assertFalse(exception.isRetryable)
    }

    // ValidationException tests

    @Test
    fun `ValidationException has VALIDATION error type`() {
        val exception = ValidationException("Test error")
        assertEquals(ErrorType.VALIDATION, exception.errorType)
    }

    @Test
    fun `ValidationException is NOT retryable`() {
        val exception = ValidationException("Test error")
        assertFalse(exception.isRetryable)
    }

    // Cause propagation tests

    @Test
    fun `exceptions propagate cause correctly`() {
        val cause = RuntimeException("Original error")
        val exception = LLMException("Wrapped error", cause)
        
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `exception message is accessible`() {
        val message = "Detailed error message"
        val exception = FileIOException(message)
        
        assertEquals(message, exception.message)
    }

    // ErrorType enum tests

    @Test
    fun `ErrorType has all expected values`() {
        val types = ErrorType.entries
        
        assertTrue(types.any { it == ErrorType.FILE_IO })
        assertTrue(types.any { it == ErrorType.DATABASE })
        assertTrue(types.any { it == ErrorType.LLM })
        assertTrue(types.any { it == ErrorType.TTS })
        assertTrue(types.any { it == ErrorType.OUT_OF_MEMORY })
        assertTrue(types.any { it == ErrorType.VALIDATION })
        assertTrue(types.any { it == ErrorType.UNKNOWN })
    }

    @Test
    fun `ErrorType has exactly 7 values`() {
        assertEquals(7, ErrorType.entries.size)
    }
}

