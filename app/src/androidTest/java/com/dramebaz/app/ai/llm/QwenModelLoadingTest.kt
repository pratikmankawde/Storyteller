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
 * Test suite for LLM initialization (ONNX or stub via QwenStub).
 * In production, the model is pre-loaded by SplashActivity.
 */
@RunWith(AndroidJUnit4::class)
class QwenModelLoadingTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testStubInitialization() {
        runBlocking {
            val initialized = QwenStub.initialize(context)
            assertTrue("QwenStub should initialize (ONNX or stub)", initialized)
            assertTrue("QwenStub should report ready", QwenStub.isReady())
            QwenStub.release()
        }
    }

    @Test
    fun testStubInitializationIdempotent() {
        runBlocking {
            val first = QwenStub.initialize(context)
            val second = QwenStub.initialize(context)
            assertTrue(first)
            assertTrue(second)
            QwenStub.release()
        }
    }

    @Test
    fun testStubReleaseThenReinit() {
        runBlocking {
            QwenStub.initialize(context)
            QwenStub.release()
            val reinit = QwenStub.initialize(context)
            assertTrue(reinit)
            QwenStub.release()
        }
    }
}
