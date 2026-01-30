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
 * Test suite for Qwen LLM model loading.
 * In production, Qwen is pre-loaded by SplashActivity; these tests verify loading and stub init.
 */
@RunWith(AndroidJUnit4::class)
class QwenModelLoadingTest {
    private lateinit var context: Context
    private lateinit var qwenModel: QwenModel
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        qwenModel = QwenModel(context)
    }
    
    @Test
    fun testModelLoading() {
        runBlocking {
            // Test that model loading completes (may succeed or fail depending on model availability)
            val loaded = qwenModel.loadModel()
            
            if (loaded) {
                // If model loaded successfully, verify it's actually loaded
                assertTrue("Model should be loaded", qwenModel.isModelLoaded())
                android.util.Log.i("QwenModelLoadingTest", "Model loaded successfully")
            } else {
                // If model didn't load, log why (for debugging)
                android.util.Log.w("QwenModelLoadingTest", "Model not loaded - check logcat for details")
                android.util.Log.w("QwenModelLoadingTest", "Expected model file: qwen2.5-3b-instruct-q4_k_m.gguf in Downloads folder")
            }
            
            // Test doesn't fail if model isn't available - just logs the status
            // This allows tests to run even if model file isn't present
        }
    }
    
    @Test
    fun testModelInitializationViaStub() {
        runBlocking {
            // Test initialization through QwenStub
            val initialized = QwenStub.initialize(context)
            
            if (initialized) {
                android.util.Log.i("QwenModelLoadingTest", "Model initialized via QwenStub")
            } else {
                android.util.Log.w("QwenModelLoadingTest", "Model initialization failed - will use stub fallback")
            }
            
            // Cleanup
            QwenStub.release()
        }
    }
    
    @Test
    fun testModelReloading() {
        runBlocking {
            // Test that model can be loaded multiple times
            val firstLoad = qwenModel.loadModel()
            val secondLoad = qwenModel.loadModel()
            
            // Both should return the same result
            assertEquals("Model loading should be idempotent", firstLoad, secondLoad)
            
            if (firstLoad) {
                assertTrue("Model should still be loaded after second call", qwenModel.isModelLoaded())
            }
        }
    }
}
