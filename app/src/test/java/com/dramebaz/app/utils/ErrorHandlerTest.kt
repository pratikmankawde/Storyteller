package com.dramebaz.app.utils

import com.dramebaz.app.domain.exceptions.*
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for ErrorHandler.
 * Tests error classification and retry logic.
 * Note: android.util.Log is mocked at the static level to avoid JVM unit test issues.
 */
class ErrorHandlerTest {

    @Before
    fun setup() {
        // Mock android.util.Log at the static level
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `classify returns AppException unchanged`() {
        val original = DatabaseException("Test DB error")
        val classified = ErrorHandler.classify(original)

        assertSame(original, classified)
    }

    @Test
    fun `classify converts OutOfMemoryError to OutOfMemoryException`() {
        val oom = OutOfMemoryError("Java heap space")
        val classified = ErrorHandler.classify(oom)

        assertTrue(classified is OutOfMemoryException)
        assertEquals(ErrorType.OUT_OF_MEMORY, classified.errorType)
    }

    @Test
    fun `classify detects database errors from message`() {
        val error = RuntimeException("Database connection failed")
        val classified = ErrorHandler.classify(error)

        assertTrue(classified is DatabaseException)
        assertEquals(ErrorType.DATABASE, classified.errorType)
    }

    @Test
    fun `classify detects SQLite errors from message`() {
        val error = RuntimeException("SQLite constraint violation")
        val classified = ErrorHandler.classify(error)

        assertTrue(classified is DatabaseException)
    }

    @Test
    fun `classify detects file IO errors from message`() {
        val error = RuntimeException("Failed to read file")
        val classified = ErrorHandler.classify(error)

        assertTrue(classified is FileIOException)
        assertEquals(ErrorType.FILE_IO, classified.errorType)
    }

    @Test
    fun `classify detects LLM errors from message`() {
        val error = RuntimeException("ONNX model failed to load")
        val classified = ErrorHandler.classify(error)

        assertTrue(classified is LLMException)
        assertEquals(ErrorType.LLM, classified.errorType)
    }

    @Test
    fun `classify detects TTS errors from message`() {
        val error = RuntimeException("TTS synthesis failed")
        val classified = ErrorHandler.classify(error)

        assertTrue(classified is TTSException)
        assertEquals(ErrorType.TTS, classified.errorType)
    }

    @Test
    fun `classify returns LLMException for unknown errors`() {
        val error = RuntimeException("Something went wrong")
        val classified = ErrorHandler.classify(error)

        assertTrue(classified is LLMException)
    }

    @Test
    fun `getUserMessage returns AppException userMessage`() {
        val exception = DatabaseException(
            message = "Technical message",
            userMessage = "User friendly message"
        )

        val message = ErrorHandler.getUserMessage(exception)

        assertEquals("User friendly message", message)
    }

    @Test
    fun `getUserMessage handles OutOfMemoryError`() {
        val oom = OutOfMemoryError()

        val message = ErrorHandler.getUserMessage(oom)

        assertTrue(message.contains("memory"))
    }

    @Test
    fun `withRetry succeeds on first attempt`() = runBlocking {
        var attempts = 0

        val result = ErrorHandler.withRetry(maxRetries = 3) {
            attempts++
            "success"
        }

        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `withRetry retries on retryable exception`() = runBlocking {
        var attempts = 0

        val result = ErrorHandler.withRetry(
            maxRetries = 3,
            initialDelayMs = 10,
            shouldRetry = { true }
        ) {
            attempts++
            if (attempts < 3) {
                throw FileIOException("Temporary failure")
            }
            "success after retry"
        }

        assertEquals("success after retry", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `withRetryResult returns success result`() = runBlocking {
        val result = ErrorHandler.withRetryResult(maxRetries = 1) {
            "success"
        }

        assertTrue(result.isSuccess)
        assertEquals("success", result.getOrNull())
    }

    @Test
    fun `withRetryResult returns failure result on exception`() = runBlocking {
        val result = ErrorHandler.withRetryResult(
            maxRetries = 0,
            initialDelayMs = 10
        ) {
            throw RuntimeException("Always fails")
        }

        assertTrue(result.isFailure)
    }
}
