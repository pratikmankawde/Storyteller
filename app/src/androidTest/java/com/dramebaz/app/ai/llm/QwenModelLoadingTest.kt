package com.dramebaz.app.ai.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test suite for LLM initialization via LlmService.
 * In production, the model is pre-loaded by SplashActivity.
 * Note: LLM model may not load on test devices (limited memory/no model files).
 * Tests verify that initialization completes without crashing and stub fallback works.
 */
@RunWith(AndroidJUnit4::class)
class QwenModelLoadingTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testLlmServiceInitialization() {
        runBlocking {
            // Initialize - may return false if model doesn't load, but should not crash
            val initialized = LlmService.initialize(context)
            android.util.Log.i("QwenModelLoadingTest", "LlmService.initialize() returned: $initialized")

            // Either model loaded OR we're in stub fallback mode
            // The service should still function via StubFallbacks
            val result = LlmService.analyzeChapter("Alice said hello.")
            assertNotNull("Stub fallback should still work", result)

            LlmService.release()
        }
    }

    @Test
    fun testLlmServiceInitializationIdempotent() {
        runBlocking {
            val first = LlmService.initialize(context)
            val second = LlmService.initialize(context)
            // Both calls should return the same result
            assertEquals("Idempotent initialization should return same result", first, second)
            LlmService.release()
        }
    }

    @Test
    fun testLlmServiceReleaseThenReinit() {
        runBlocking {
            LlmService.initialize(context)
            LlmService.release()
            // After release, reinit should work (may be in stub mode)
            val reinit = LlmService.initialize(context)
            android.util.Log.i("QwenModelLoadingTest", "Re-initialize after release returned: $reinit")

            // Verify stub fallback still works after reinit
            val result = LlmService.analyzeChapter("Bob said goodbye.")
            assertNotNull("Stub fallback should work after reinit", result)

            LlmService.release()
        }
    }
}
