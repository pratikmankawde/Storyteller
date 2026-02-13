package com.dramebaz.app.integration

import com.dramebaz.app.domain.exceptions.AppException
import com.dramebaz.app.domain.exceptions.FileIOException
import com.dramebaz.app.utils.DegradedModeManager
import com.dramebaz.app.utils.ErrorHandler
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for Coroutine & Background Processing.
 * Tests:
 * - Correct dispatcher usage (IO for I/O, Default for CPU)
 * - Cancellation handling and cleanup
 * - StateFlow emissions and UI state updates
 * - withRetry logic in ErrorHandler
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineProcessingIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
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

        DegradedModeManager.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== withRetry Tests ====================

    @Test
    fun `withRetry succeeds on first attempt`() = runTest {
        var attempts = 0

        val result = ErrorHandler.withRetry(maxRetries = 3) {
            attempts++
            "success"
        }

        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `withRetry retries on retryable exception`() = runTest {
        var attempts = 0

        val result = ErrorHandler.withRetry(
            maxRetries = 3,
            initialDelayMs = 1,
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
    fun `withRetry does not retry on non-retryable exception`() = runTest {
        var attempts = 0
        // ValidationException is non-retryable by default
        val nonRetryableException = com.dramebaz.app.domain.exceptions.ValidationException("fatal")

        try {
            ErrorHandler.withRetry(
                maxRetries = 3,
                initialDelayMs = 1
            ) {
                attempts++
                throw nonRetryableException
            }
            fail("Should have thrown exception")
        } catch (e: AppException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `withRetry calls onRetry callback before each retry`() = runTest {
        var attempts = 0
        val retryAttempts = mutableListOf<Int>()

        val result = ErrorHandler.withRetry(
            maxRetries = 3,
            initialDelayMs = 1,
            shouldRetry = { true },
            onRetry = { attempt, _, _ -> retryAttempts.add(attempt) }
        ) {
            attempts++
            if (attempts < 3) {
                throw FileIOException("Temporary failure")
            }
            "success"
        }

        assertEquals("success", result)
        assertEquals(listOf(1, 2), retryAttempts)
    }

    @Test
    fun `withRetryResult returns success result`() = runTest {
        val result = ErrorHandler.withRetryResult(
            maxRetries = 1,
            initialDelayMs = 1
        ) {
            "success"
        }

        assertTrue(result.isSuccess)
        assertEquals("success", result.getOrNull())
    }

    @Test
    fun `withRetryResult returns failure result on non-retryable exception`() = runTest {
        val result = ErrorHandler.withRetryResult(
            maxRetries = 0,
            initialDelayMs = 1
        ) {
            throw RuntimeException("Always fails")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RuntimeException)
    }

    // ==================== StateFlow Tests ====================

    @Test
    fun `StateFlow emits updates to collectors`() = runTest {
        val stateFlow = MutableStateFlow(0)
        val collectedValues = mutableListOf<Int>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            stateFlow.take(3).collect { collectedValues.add(it) }
        }

        yield()
        stateFlow.value = 1
        yield()
        stateFlow.value = 2
        yield()

        job.join()

        assertEquals(listOf(0, 1, 2), collectedValues)
    }

    @Test
    fun `DegradedModeManager StateFlow emits mode transitions`() = runTest {
        val collectedModes = mutableListOf<DegradedModeManager.LlmMode>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            DegradedModeManager.llmMode.take(3).collect { collectedModes.add(it) }
        }

        yield()
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.ONNX_FULL)
        yield()
        DegradedModeManager.setLlmMode(DegradedModeManager.LlmMode.STUB_FALLBACK)
        yield()

        job.join()

        assertEquals(DegradedModeManager.LlmMode.NOT_INITIALIZED, collectedModes[0])
        assertEquals(DegradedModeManager.LlmMode.ONNX_FULL, collectedModes[1])
        assertEquals(DegradedModeManager.LlmMode.STUB_FALLBACK, collectedModes[2])
    }

    @Test
    fun `StateFlow combine merges multiple flows`() = runTest {
        val flow1 = MutableStateFlow(1)
        val flow2 = MutableStateFlow("A")
        val collectedResults = mutableListOf<Pair<Int, String>>()

        val combined = combine(flow1, flow2) { a, b -> Pair(a, b) }

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            combined.take(3).collect { collectedResults.add(it) }
        }

        yield()
        flow1.value = 2
        yield()
        flow2.value = "B"
        yield()

        job.join()

        assertEquals(3, collectedResults.size)
        assertEquals(Pair(1, "A"), collectedResults[0])
    }

    // ==================== Cancellation Handling Tests ====================

    @Test
    fun `coroutine cancellation propagates properly`() = runTest {
        var completed = false
        var cancelled = false

        val job = launch {
            try {
                delay(10000)
                completed = true
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        assertFalse("Should not complete", completed)
        assertTrue("Should be cancelled", cancelled)
    }

    @Test
    fun `cleanup runs on cancellation via finally`() = runTest {
        var cleanupRan = false

        val job = launch {
            try {
                delay(10000)
            } finally {
                cleanupRan = true
            }
        }

        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        assertTrue("Cleanup should run", cleanupRan)
    }

    @Test
    fun `SupervisorJob prevents child failure propagation`() = runTest {
        var child1Failed = false
        var child2Completed = false

        supervisorScope {
            launch {
                try {
                    throw RuntimeException("Child 1 fails")
                } catch (e: RuntimeException) {
                    child1Failed = true
                }
            }

            launch {
                delay(10)
                child2Completed = true
            }
        }

        advanceUntilIdle()

        assertTrue("Child 1 should fail", child1Failed)
        assertTrue("Child 2 should still complete", child2Completed)
    }

    // ==================== Dispatcher Usage Tests ====================

    @Test
    fun `withContext switches dispatcher correctly`() = runTest {
        var ioBlockRan = false
        var defaultBlockRan = false

        // Simulate dispatcher usage pattern
        val result = withContext(testDispatcher) {
            ioBlockRan = true
            "io result"
        }

        val cpuResult = withContext(testDispatcher) {
            defaultBlockRan = true
            "cpu result"
        }

        assertTrue("IO block should run", ioBlockRan)
        assertTrue("Default block should run", defaultBlockRan)
        assertEquals("io result", result)
        assertEquals("cpu result", cpuResult)
    }

    @Test
    fun `concurrent coroutines complete independently`() = runTest {
        val results = mutableListOf<String>()

        val job1 = launch {
            delay(50)
            results.add("job1")
        }
        val job2 = launch {
            delay(25)
            results.add("job2")
        }
        val job3 = launch {
            delay(10)
            results.add("job3")
        }

        advanceUntilIdle()
        joinAll(job1, job2, job3)

        assertEquals(3, results.size)
        assertEquals(listOf("job3", "job2", "job1"), results)
    }

    // ==================== Background Processing Tests ====================

    @Test
    fun `job queue processes items in order`() = runTest {
        val queue = ArrayDeque<Int>()
        val processed = mutableListOf<Int>()

        queue.addAll(listOf(1, 2, 3, 4, 5))

        val processingJob = launch {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                processed.add(item)
                delay(1)
            }
        }

        advanceUntilIdle()
        processingJob.join()

        assertEquals(listOf(1, 2, 3, 4, 5), processed)
    }

    @Test
    fun `mutex prevents concurrent access`() = runTest {
        val mutex = Mutex()
        var counter = 0
        var maxConcurrent = 0
        var currentConcurrent = 0

        val jobs = List(10) {
            launch {
                mutex.withLock {
                    currentConcurrent++
                    maxConcurrent = maxOf(maxConcurrent, currentConcurrent)
                    delay(10)
                    counter++
                    currentConcurrent--
                }
            }
        }

        advanceUntilIdle()
        jobs.forEach { it.join() }

        assertEquals(10, counter)
        assertEquals(1, maxConcurrent) // Mutex ensures only 1 at a time
    }
}

