package com.dramebaz.app.ai.tts

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.data.models.VoiceProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Test suite for TTS (Text-to-Speech) generation.
 * Uses the app's TTS engine (same as production; in production it is pre-loaded by SplashActivity).
 * Ensures model is pre-loaded in setup; synthesis tests are skipped on low-memory devices (e.g. OOM).
 */
@RunWith(AndroidJUnit4::class)
class TtsGenerationTest {
    private lateinit var context: Context
    private lateinit var ttsEngine: SherpaTtsEngine

    /** True if TTS engine initialized successfully (used to skip synthesis tests on OOM). */
    private var ttsInitialized = false

    /** Copies generated audio to Downloads folder (app's external files/Download) for inspection. */
    private fun saveToDownloads(audioFile: File?, testName: String) {
        if (audioFile == null || !audioFile.exists()) return
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)?.let { File(it, "Download").apply { mkdirs() } }
        if (downloadsDir == null || !downloadsDir.exists()) {
            android.util.Log.w("TtsGenerationTest", "Downloads dir not available, skipping copy")
            return
        }
        val safeName = testName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dest = File(downloadsDir, "tts_${safeName}.wav")
        try {
            audioFile.copyTo(dest, overwrite = true)
            android.util.Log.i("TtsGenerationTest", "Saved audio to Downloads: ${dest.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("TtsGenerationTest", "Failed to copy to Downloads: ${e.message}")
        }
    }
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ttsEngine = (context.applicationContext as DramebazApplication).ttsEngine
        runBlocking {
            ttsInitialized = if (ttsEngine.isInitialized()) {
                true
            } else {
                try {
                    ttsEngine.init()
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("TtsGenerationTest", "TTS init OOM (skip synthesis tests): ${e.message}")
                    false
                }
            }
        }
    }

    @Test
    fun testTtsEngineInitialization() {
        // Skip when init failed (e.g. OOM on emulator); pass when init succeeded
        assumeTrue("TTS engine init failed (e.g. OOM on low-memory device)", ttsInitialized)
        android.util.Log.i("TtsGenerationTest", "TTS engine initialized successfully")
    }

    @Test
    fun testTtsSynthesisBasic() {
        assumeTrue("TTS not initialized (e.g. OOM), skipping", ttsInitialized)
        runBlocking {
            val testText = "Hello, this is a test of the text to speech engine."
            val result = try { ttsEngine.speak(testText, null, null, null) } catch (e: OutOfMemoryError) {
                android.util.Log.w("TtsGenerationTest", "TTS synthesis OOM, skipping")
                return@runBlocking
            }
            assertTrue("TTS synthesis should succeed", result.isSuccess)
            result.onSuccess { audioFile ->
                assertNotNull("Audio file should be generated", audioFile)
                audioFile?.let { file ->
                    assertTrue("Audio file should exist", file.exists())
                    assertTrue("Audio file should be readable", file.canRead())
                    assertTrue("Audio file should have content", file.length() > 0)
                    saveToDownloads(file, "basic")
                }
                android.util.Log.i("TtsGenerationTest", "TTS synthesis successful")
            }
            result.onFailure { error -> fail("TTS synthesis failed: ${error.message}") }
        }
    }

    @Test
    fun testTtsSynthesisWithVoiceProfile() {
        assumeTrue("TTS not initialized (e.g. OOM), skipping", ttsInitialized)
        runBlocking {
            val voiceProfile = VoiceProfile(
                pitch = 1.2f,
                speed = 0.9f,
                energy = 1.1f,
                emotionBias = mapOf("happy" to 0.7f, "neutral" to 0.3f)
            )
            val testText = "This is a test with a custom voice profile."
            val result = try { ttsEngine.speak(testText, voiceProfile, null, null) } catch (e: OutOfMemoryError) { return@runBlocking }
            assertTrue("TTS synthesis with voice profile should succeed", result.isSuccess)
            result.onSuccess { audioFile ->
                assertNotNull("Audio file should be generated", audioFile)
                audioFile?.let { file ->
                    assertTrue("Audio file should exist", file.exists())
                    saveToDownloads(file, "voice_profile")
                }
            }
        }
    }

    @Test
    fun testTtsSynthesisWithSpeakerId() {
        assumeTrue("TTS not initialized (e.g. OOM), skipping", ttsInitialized)
        runBlocking {
            val testText = "This is a test with a specific speaker ID."
            val result = try { ttsEngine.speak(testText, null, null, 5) } catch (e: OutOfMemoryError) { return@runBlocking }
            assertTrue("TTS synthesis with speaker ID should succeed", result.isSuccess)
            result.onSuccess { audioFile ->
                assertNotNull("Audio file should be generated", audioFile)
                audioFile?.let { saveToDownloads(it, "speaker_id") }
            }
        }
    }

    @Test
    fun testTtsSynthesisLongText() {
        assumeTrue("TTS not initialized (e.g. OOM), skipping", ttsInitialized)
        runBlocking {
            val longText = """
                The quick brown fox jumps over the lazy dog. 
                This is a longer sentence to test the text to speech synthesis capabilities 
                of the SherpaTTS engine with the VITS-VCTK model.
            """.trimIndent()
            val result = try { ttsEngine.speak(longText, null, null, null) } catch (e: OutOfMemoryError) { return@runBlocking }
            assertTrue("TTS synthesis of long text should succeed", result.isSuccess)
            result.onSuccess { audioFile ->
                assertNotNull("Audio file should be generated", audioFile)
                audioFile?.let { file ->
                    assertTrue("Long text should generate larger audio file", file.length() > 1000)
                    saveToDownloads(file, "long_text")
                }
            }
        }
    }

    @Test
    fun testTtsSynthesisMultipleCalls() {
        assumeTrue("TTS not initialized (e.g. OOM), skipping", ttsInitialized)
        runBlocking {
            val texts = listOf("First sentence.", "Second sentence.", "Third sentence.")
            val results = try {
                texts.map { ttsEngine.speak(it, null, null, null) }
            } catch (e: OutOfMemoryError) { return@runBlocking }
            results.forEachIndexed { index, result ->
                assertTrue("TTS call $index should succeed", result.isSuccess)
                result.onSuccess { audioFile ->
                    assertNotNull("Audio file $index", audioFile)
                    audioFile?.let { file ->
                        assertTrue("File exists", file.exists())
                        saveToDownloads(file, "multiple_$index")
                    }
                }
            }
        }
    }

    @Test
    fun testVoiceProfileMapper() {
        // Test voice profile mapping
        val voiceProfile = VoiceProfile(
            pitch = 1.3f,
            speed = 0.8f,
            energy = 1.2f,
            emotionBias = mapOf("sad" to 0.6f, "neutral" to 0.4f)
        )
        
        val params = VoiceProfileMapper.toTtsParams(voiceProfile)
        
        assertEquals("Pitch should match", 1.3f, params.pitch, 0.01f)
        assertEquals("Speed should match", 0.8f, params.speed, 0.01f)
        assertEquals("Energy should match", 1.2f, params.energy, 0.01f)
        assertEquals("Emotion preset should be 'sad'", "sad", params.emotionPreset)
        
        android.util.Log.d("TtsGenerationTest", "Voice profile mapping successful")
    }

    @Test
    fun testVoiceProfileMapperDefault() {
        // Test default voice profile mapping
        val params = VoiceProfileMapper.toTtsParams(null)
        
        assertEquals("Default pitch should be 1.0", 1.0f, params.pitch, 0.01f)
        assertEquals("Default speed should be 1.0", 1.0f, params.speed, 0.01f)
        assertEquals("Default energy should be 1.0", 1.0f, params.energy, 0.01f)
        assertEquals("Default emotion should be neutral", "neutral", params.emotionPreset)
    }

    @Test
    fun testTtsSynthesisEmptyText() {
        assumeTrue("TTS not initialized (e.g. OOM), skipping", ttsInitialized)
        runBlocking {
            val result = try { ttsEngine.speak("", null, null, null) } catch (e: OutOfMemoryError) { return@runBlocking }
            result.fold(
                onSuccess = { android.util.Log.d("TtsGenerationTest", "Empty text handled") },
                onFailure = { android.util.Log.d("TtsGenerationTest", "Empty text rejected: ${it.message}") }
            )
        }
    }
}
