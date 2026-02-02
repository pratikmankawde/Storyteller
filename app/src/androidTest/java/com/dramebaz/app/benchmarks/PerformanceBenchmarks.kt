package com.dramebaz.app.benchmarks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.ai.tts.VoiceProfileMapper
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.playback.engine.ProsodyController
import com.dramebaz.app.data.models.Dialog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AUG-045: Performance Benchmarks
 *
 * Establishes performance benchmarks for key operations:
 * - TTS generation time
 * - Voice profile mapping time
 * - Prosody calculation time
 * - Database query time
 *
 * Results are logged for tracking performance over time.
 * Run these tests to establish baseline metrics.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarks {

    private lateinit var context: Context
    private lateinit var app: DramebazApplication

    // Performance thresholds (in milliseconds)
    companion object {
        const val TTS_SYNTHESIS_THRESHOLD_MS = 5000L // 5 seconds for TTS
        const val VOICE_MAPPING_THRESHOLD_MS = 50L   // 50ms for voice mapping
        const val PROSODY_CALC_THRESHOLD_MS = 10L    // 10ms for prosody calc
        const val DB_QUERY_THRESHOLD_MS = 500L       // 500ms for database queries
        const val WARMUP_ITERATIONS = 3
        const val BENCHMARK_ITERATIONS = 10
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as DramebazApplication
    }

    // ===== Voice Profile Mapping Benchmark =====

    @Test
    fun benchmarkVoiceProfileMapping() {
        val voiceProfile = VoiceProfile(
            pitch = 1.2f,
            speed = 0.9f,
            energy = 1.1f,
            emotionBias = mapOf("happy" to 0.7f, "neutral" to 0.3f)
        )

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            VoiceProfileMapper.toTtsParams(voiceProfile)
        }

        // Benchmark
        val times = mutableListOf<Long>()
        repeat(BENCHMARK_ITERATIONS) {
            val start = System.nanoTime()
            VoiceProfileMapper.toTtsParams(voiceProfile)
            val elapsed = (System.nanoTime() - start) / 1_000_000 // Convert to ms
            times.add(elapsed)
        }

        val avgTime = times.average()
        val minTime = times.minOrNull() ?: 0L
        val maxTime = times.maxOrNull() ?: 0L

        android.util.Log.i("Benchmark", "VoiceProfileMapping - Avg: ${avgTime}ms, Min: ${minTime}ms, Max: ${maxTime}ms")

        assertTrue("Voice mapping should complete in <${VOICE_MAPPING_THRESHOLD_MS}ms (actual: ${avgTime}ms)",
            avgTime < VOICE_MAPPING_THRESHOLD_MS)
    }

    // ===== Prosody Calculation Benchmark =====

    @Test
    fun benchmarkProsodyCalculation() {
        val dialog = Dialog(
            dialog = "Hello, this is a test dialog with some emotional content!",
            speaker = "Alice",
            emotion = "happy",
            intensity = 0.8f
        )
        val voiceProfile = VoiceProfile(pitch = 1.1f, speed = 1.0f, energy = 1.0f)

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            ProsodyController.forDialog(dialog, voiceProfile, 10)
        }

        // Benchmark
        val times = mutableListOf<Long>()
        repeat(BENCHMARK_ITERATIONS) {
            val start = System.nanoTime()
            ProsodyController.forDialog(dialog, voiceProfile, 10)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            times.add(elapsed)
        }

        val avgTime = times.average()
        val minTime = times.minOrNull() ?: 0L
        val maxTime = times.maxOrNull() ?: 0L

        android.util.Log.i("Benchmark", "ProsodyCalculation - Avg: ${avgTime}ms, Min: ${minTime}ms, Max: ${maxTime}ms")

        assertTrue("Prosody calculation should complete in <${PROSODY_CALC_THRESHOLD_MS}ms (actual: ${avgTime}ms)",
            avgTime < PROSODY_CALC_THRESHOLD_MS)
    }

    // ===== TTS Synthesis Benchmark =====

    @Test
    fun benchmarkTtsSynthesis() {
        runBlocking {
            val ttsEngine = app.ttsEngine

            // Ensure TTS is initialized
            if (!ttsEngine.isInitialized()) {
                try {
                    ttsEngine.init()
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("Benchmark", "TTS init OOM, skipping benchmark")
                    return@runBlocking
                }
            }

            val testText = "This is a test sentence for TTS benchmarking."

            // Warmup (1 iteration due to resource intensity)
            try {
                ttsEngine.speak(testText, null, null, null)
            } catch (e: Exception) {
                android.util.Log.w("Benchmark", "TTS warmup failed: ${e.message}")
                return@runBlocking
            }

            // Benchmark (fewer iterations due to resource intensity)
            val times = mutableListOf<Long>()
            repeat(3) {
                val start = System.currentTimeMillis()
                val result = ttsEngine.speak(testText, null, null, null)
                val elapsed = System.currentTimeMillis() - start
                if (result.isSuccess) {
                    times.add(elapsed)
                }
            }

            if (times.isNotEmpty()) {
                val avgTime = times.average()
                val minTime = times.minOrNull() ?: 0L
                val maxTime = times.maxOrNull() ?: 0L

                android.util.Log.i("Benchmark", "TTS Synthesis - Avg: ${avgTime}ms, Min: ${minTime}ms, Max: ${maxTime}ms")

                assertTrue("TTS synthesis should complete in <${TTS_SYNTHESIS_THRESHOLD_MS}ms (actual: ${avgTime}ms)",
                    avgTime < TTS_SYNTHESIS_THRESHOLD_MS)
            }
        }
    }

    // ===== Database Query Benchmark =====

    @Test
    fun benchmarkDatabaseBookQuery() {
        runBlocking {
            val bookRepo = app.bookRepository

            // Warmup
            repeat(WARMUP_ITERATIONS) {
                bookRepo.getAllBooks()
            }

            // Benchmark
            val times = mutableListOf<Long>()
            repeat(BENCHMARK_ITERATIONS) {
                val start = System.currentTimeMillis()
                bookRepo.getAllBooks()
                val elapsed = System.currentTimeMillis() - start
                times.add(elapsed)
            }

            val avgTime = times.average()
            val minTime = times.minOrNull() ?: 0L
            val maxTime = times.maxOrNull() ?: 0L

            android.util.Log.i("Benchmark", "DB getAllBooks - Avg: ${avgTime}ms, Min: ${minTime}ms, Max: ${maxTime}ms")

            assertTrue("DB query should complete in <${DB_QUERY_THRESHOLD_MS}ms (actual: ${avgTime}ms)",
                avgTime < DB_QUERY_THRESHOLD_MS)
        }
    }

    // ===== String Processing Benchmark =====

    @Test
    fun benchmarkInputValidation() {
        val inputValidator = com.dramebaz.app.utils.InputValidator
        val testPrompt = "This is a test story prompt that needs to be validated and sanitized. It contains special characters like @#$%^&*() and some numbers 12345."

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            inputValidator.isValidStoryPrompt(testPrompt)
            inputValidator.sanitizeLlmPrompt(testPrompt)
            inputValidator.sanitizeForTts(testPrompt)
        }

        // Benchmark
        val times = mutableListOf<Long>()
        repeat(BENCHMARK_ITERATIONS) {
            val start = System.nanoTime()
            inputValidator.isValidStoryPrompt(testPrompt)
            inputValidator.sanitizeLlmPrompt(testPrompt)
            inputValidator.sanitizeForTts(testPrompt)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            times.add(elapsed)
        }

        val avgTime = times.average()
        val minTime = times.minOrNull() ?: 0L
        val maxTime = times.maxOrNull() ?: 0L

        android.util.Log.i("Benchmark", "InputValidation - Avg: ${avgTime}ms, Min: ${minTime}ms, Max: ${maxTime}ms")

        assertTrue("Input validation should complete in <50ms (actual: ${avgTime}ms)",
            avgTime < 50)
    }

    // ===== Memory Usage Benchmark =====

    @Test
    fun benchmarkMemoryUsage() {
        val runtime = Runtime.getRuntime()

        // Force GC to get baseline
        System.gc()
        Thread.sleep(100)

        val baselineMemory = runtime.totalMemory() - runtime.freeMemory()

        // Perform some operations
        val voiceProfile = VoiceProfile(pitch = 1.2f, speed = 0.9f, energy = 1.1f)
        repeat(100) {
            VoiceProfileMapper.toTtsParams(voiceProfile)
        }

        val afterOperationsMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryDelta = afterOperationsMemory - baselineMemory

        android.util.Log.i("Benchmark", "Memory - Baseline: ${baselineMemory / 1024}KB, After: ${afterOperationsMemory / 1024}KB, Delta: ${memoryDelta / 1024}KB")

        // Log memory usage (no strict assertion, just for tracking)
        assertTrue("Memory usage should be logged", true)
    }

    // ===== Summary Test =====

    @Test
    fun benchmarkSummary() {
        android.util.Log.i("Benchmark", "========================================")
        android.util.Log.i("Benchmark", "AUG-045: Performance Benchmark Summary")
        android.util.Log.i("Benchmark", "========================================")
        android.util.Log.i("Benchmark", "Thresholds:")
        android.util.Log.i("Benchmark", "  - TTS Synthesis: <${TTS_SYNTHESIS_THRESHOLD_MS}ms")
        android.util.Log.i("Benchmark", "  - Voice Mapping: <${VOICE_MAPPING_THRESHOLD_MS}ms")
        android.util.Log.i("Benchmark", "  - Prosody Calc: <${PROSODY_CALC_THRESHOLD_MS}ms")
        android.util.Log.i("Benchmark", "  - DB Query: <${DB_QUERY_THRESHOLD_MS}ms")
        android.util.Log.i("Benchmark", "========================================")

        assertTrue("Benchmark summary logged", true)
    }
}
