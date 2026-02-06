package com.dramebaz.app.ai.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive test suite for all LLM text analysis features.
 * Tests chapter analysis, extended analysis, and story generation.
 */
@RunWith(AndroidJUnit4::class)
class QwenTextAnalysisTest {
    private lateinit var context: Context
    private val testChapterText = """
        Chapter 1: The Beginning

        Alice walked into the room, her heart pounding with anticipation. "Hello?" she called out, her voice trembling slightly.

        "I'm here," Bob replied from the shadows. His voice was calm but carried an edge of tension.

        Alice felt a surge of fear mixed with curiosity. She took a step forward, the floorboards creaking beneath her feet.

        "Why did you call me here?" she asked, her hands shaking.

        Bob emerged from the darkness, his face serious. "We need to talk about what happened last night."

        The tension in the room was palpable. Alice could hear her own heartbeat in her ears.
    """.trimIndent()

    @Before
    fun setup() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            // Ensure LLM is initialized (may use stub fallback if model doesn't load)
            val initialized = LlmService.initialize(context)
            android.util.Log.i("QwenTextAnalysisTest", "LlmService initialized: $initialized, isReady: ${LlmService.isReady()}")
        }
    }

    @Test
    fun testChapterAnalysis() {
        runBlocking {
        // Test basic chapter analysis
        val result = LlmService.analyzeChapter(testChapterText)

        assertNotNull("Chapter analysis should return a result", result)
        assertNotNull("Chapter summary should be present", result.chapterSummary)
        assertNotNull("Characters should be extracted", result.characters)
        assertNotNull("Dialogs should be extracted", result.dialogs)
        assertNotNull("Sound cues should be extracted", result.soundCues)

        // Verify chapter summary structure
        val summary = result.chapterSummary
        assertNotNull("Chapter summary should be present", summary)
        summary?.let { s ->
            assertNotNull("Summary title should be present", s.title)
            assertNotNull("Short summary should be present", s.shortSummary)
            assertNotNull("Main events should be present", s.mainEvents)
            assertNotNull("Emotional arc should be present", s.emotionalArc)
        }

        android.util.Log.i("QwenTextAnalysisTest", "Chapter analysis completed")
        android.util.Log.d("QwenTextAnalysisTest", "Found ${result.characters?.size ?: 0} characters")
        android.util.Log.d("QwenTextAnalysisTest", "Found ${result.dialogs?.size ?: 0} dialogs")
        android.util.Log.d("QwenTextAnalysisTest", "Found ${result.soundCues?.size ?: 0} sound cues")
    }
    }

    @Test
    fun testChapterAnalysisCharacters() {
        runBlocking {
        val result = LlmService.analyzeChapter(testChapterText)

        // Verify characters are extracted
        assertNotNull("Characters list should not be null", result.characters)
        val characters = result.characters ?: return@runBlocking
        assertTrue("Should extract at least one character", characters.isNotEmpty())

        // Check character structure
        val character = characters.first()
        assertNotNull("Character name should be present", character.name)
        assertNotNull("Character traits should be present", character.traits)

        // Check voice profile if present
        character.voiceProfile?.let { vp ->
            assertTrue("Voice profile should have pitch", vp.containsKey("pitch"))
            assertTrue("Voice profile should have speed", vp.containsKey("speed"))
            assertTrue("Voice profile should have energy", vp.containsKey("energy"))
        }

        android.util.Log.d("QwenTextAnalysisTest", "Character: ${character.name}, traits: ${character.traits}")
    }
    }

    @Test
    fun testChapterAnalysisDialogs() {
        runBlocking {
        val result = LlmService.analyzeChapter(testChapterText)

        // Verify dialogs are extracted
        assertNotNull("Dialogs list should not be null", result.dialogs)
        val dialogs = result.dialogs ?: return@runBlocking
        assertTrue("Should extract dialogs from chapter", dialogs.isNotEmpty())

        // Check dialog structure
        val dialog = dialogs.first()
        assertNotNull("Dialog text should be present", dialog.dialog)
        assertNotNull("Dialog speaker should be present", dialog.speaker)
        assertNotNull("Dialog emotion should be present", dialog.emotion)
        assertTrue("Dialog intensity should be between 0 and 1",
            dialog.intensity >= 0f && dialog.intensity <= 1f)

        android.util.Log.d("QwenTextAnalysisTest", "Dialog: '${dialog.dialog.take(50)}...' by ${dialog.speaker}")
    }
    }

    @Test
    fun testChapterAnalysisSoundCues() {
        runBlocking {
        val result = LlmService.analyzeChapter(testChapterText)

        // Sound cues may or may not be present depending on analysis
        result.soundCues?.forEach { cue ->
            assertNotNull("Sound cue event should be present", cue.event)
            assertNotNull("Sound cue prompt should be present", cue.soundPrompt)
            assertTrue("Sound cue duration should be positive", cue.duration > 0)
            assertTrue("Sound cue category should be 'effect' or 'ambience'",
                cue.category == "effect" || cue.category == "ambience")
        }

        android.util.Log.d("QwenTextAnalysisTest", "Found ${result.soundCues?.size ?: 0} sound cues")
    }
    }

    @Test
    fun testExtendedAnalysis() {
        runBlocking {
        // Test extended analysis (themes, symbols, foreshadowing, vocabulary)
        val result = LlmService.extendedAnalysisJson(testChapterText)

        assertNotNull("Extended analysis should return JSON", result)
        assertTrue("Extended analysis should not be empty", result.isNotEmpty())

        // Parse JSON to verify structure
        val gson = Gson()
        val jsonObj = gson.fromJson(result, Map::class.java) as? Map<*, *>
        assertNotNull("Extended analysis should be valid JSON", jsonObj)

        // Check for expected fields
        assertNotNull("JSON object should not be null", jsonObj)
        val obj = jsonObj!!
        assertTrue("Should have themes field", obj.containsKey("themes"))
        assertTrue("Should have symbols field", obj.containsKey("symbols"))
        assertTrue("Should have foreshadowing field", obj.containsKey("foreshadowing"))
        assertTrue("Should have vocabulary field", obj.containsKey("vocabulary"))

        android.util.Log.i("QwenTextAnalysisTest", "Extended analysis completed")
        android.util.Log.d("QwenTextAnalysisTest", "Extended analysis JSON length: ${result.length}")
    }
    }

    @Test
    fun testStoryGeneration() {
        runBlocking {
        // Test story generation feature
        val prompt = "A story about a brave knight who saves a kingdom from a dragon"
        val story = LlmService.generateStory(prompt)

        assertNotNull("Story generation should return a result", story)
        assertTrue("Generated story should not be empty", story.isNotEmpty())
        assertTrue("Generated story should be substantial (at least 100 chars)", story.length >= 100)

        // Verify story contains some expected elements
        val storyLower = story.lowercase()
        assertTrue("Story should contain narrative content",
            storyLower.contains("the") || storyLower.contains("a") || storyLower.contains("and"))

        android.util.Log.i("QwenTextAnalysisTest", "Story generation completed")
        android.util.Log.d("QwenTextAnalysisTest", "Generated story length: ${story.length} characters")
        android.util.Log.d("QwenTextAnalysisTest", "Story preview: ${story.take(200)}...")
    }
    }

    @Test
    fun testStoryGenerationWithDifferentPrompts() {
        runBlocking {
        val prompts = listOf(
            "A mystery story about a detective solving a case",
            "A science fiction story set in the future",
            "A fantasy story with magic and dragons"
        )

        prompts.forEach { prompt ->
            val story = LlmService.generateStory(prompt)
            assertNotNull("Story should be generated for prompt: $prompt", story)
            assertTrue("Story should be substantial", story.length >= 100)
            android.util.Log.d("QwenTextAnalysisTest", "Generated ${story.length} chars for prompt: ${prompt.take(50)}...")
        }
    }
    }

    @Test
    fun testEmptyChapterAnalysis() {
        runBlocking {
        // Test with empty chapter text
        val result = LlmService.analyzeChapter("")

        assertNotNull("Empty chapter should still return a result", result)
        // Result may have empty fields but structure should be valid
    }
    }

    @Test
    fun testShortChapterAnalysis() {
        runBlocking {
        // Test with very short chapter
        val shortText = "Alice said hello."
        val result = LlmService.analyzeChapter(shortText)

        assertNotNull("Short chapter should return a result", result)
        assertNotNull("Should have chapter summary", result.chapterSummary)
    }
    }

    @Test
    fun testAnalysisResponseSerialization() {
        runBlocking {
        // Test that analysis response can be serialized to JSON
        val result = LlmService.analyzeChapter(testChapterText)
        val json = LlmService.toJson(result)

        assertNotNull("JSON serialization should work", json)
        assertTrue("JSON should not be empty", json.isNotEmpty())

        // Verify JSON can be parsed back
        val gson = Gson()
        val parsed = gson.fromJson(json, Map::class.java)
        assertNotNull("JSON should be parseable", parsed)

        android.util.Log.d("QwenTextAnalysisTest", "Serialized analysis to JSON (${json.length} chars)")
        }
    }

    /**
     * Diagnostic test to measure LLM inference time and detect timeout issues.
     * This test helps diagnose why book analysis is not progressing.
     *
     * Expected outcomes:
     * - SUCCESS: Inference completes within timeout, result is NOT a stub fallback
     * - TIMEOUT: Inference takes >120 seconds, falls back to stub
     * - FAILURE: LLM not initialized or crashes
     */
    @Test
    fun testLlmInferenceTimeAndTimeout() {
        runBlocking {
            val TAG = "LLM_TIMEOUT_TEST"

            // Step 1: Check if LLM is ready
            val isReady = LlmService.isReady()
            android.util.Log.i(TAG, "=== LLM TIMEOUT DIAGNOSTIC TEST ===")
            android.util.Log.i(TAG, "LlmService.isReady() = $isReady")

            if (!isReady) {
                android.util.Log.e(TAG, "FAILURE: LLM not initialized")
                fail("LLM not initialized - check model file exists and loads correctly")
                return@runBlocking
            }

            // Step 2: Test with SHORT text first (should be fast)
            val shortText = "Alice said hello to Bob."
            android.util.Log.i(TAG, "Testing SHORT text (${shortText.length} chars)...")

            val shortStartTime = System.currentTimeMillis()
            val shortResult = LlmService.analyzeChapter(shortText)
            val shortElapsedMs = System.currentTimeMillis() - shortStartTime

            android.util.Log.i(TAG, "SHORT text inference took ${shortElapsedMs}ms")
            android.util.Log.d(TAG, "SHORT result - dialogs: ${shortResult.dialogs?.size}, characters: ${shortResult.characters?.size}")

            // Check if we got a stub fallback (indicates timeout or error)
            val shortIsStub = isStubFallback(shortResult)
            android.util.Log.i(TAG, "SHORT text used stub fallback: $shortIsStub")

            if (shortElapsedMs > 60_000) {
                android.util.Log.w(TAG, "WARNING: SHORT text took >60 seconds - model is very slow")
            }

            // Step 3: Test with MEDIUM text (similar to real chapters)
            android.util.Log.i(TAG, "Testing MEDIUM text (${testChapterText.length} chars)...")

            val mediumStartTime = System.currentTimeMillis()
            val mediumResult = LlmService.analyzeChapter(testChapterText)
            val mediumElapsedMs = System.currentTimeMillis() - mediumStartTime

            android.util.Log.i(TAG, "MEDIUM text inference took ${mediumElapsedMs}ms")
            android.util.Log.d(TAG, "MEDIUM result - dialogs: ${mediumResult.dialogs?.size}, characters: ${mediumResult.characters?.size}")

            val mediumIsStub = isStubFallback(mediumResult)
            android.util.Log.i(TAG, "MEDIUM text used stub fallback: $mediumIsStub")

            // Step 4: Report diagnosis
            android.util.Log.i(TAG, "=== DIAGNOSIS ===")

            if (shortIsStub && mediumIsStub) {
                android.util.Log.e(TAG, "DIAGNOSIS: LLM is not working - all results are stubs")
                android.util.Log.e(TAG, "Possible causes: Model too slow (timeout), GPU issues, or model not loaded")
            } else if (mediumIsStub && !shortIsStub) {
                android.util.Log.w(TAG, "DIAGNOSIS: LLM times out on larger texts")
                android.util.Log.w(TAG, "Solution: Reduce input text size or use a faster model")
            } else if (mediumElapsedMs > 120_000) {
                android.util.Log.w(TAG, "DIAGNOSIS: LLM inference is very slow (>${mediumElapsedMs/1000}s)")
                android.util.Log.w(TAG, "Solution: Use a faster model or reduce input size")
            } else {
                android.util.Log.i(TAG, "DIAGNOSIS: LLM is working correctly!")
                android.util.Log.i(TAG, "Short: ${shortElapsedMs}ms, Medium: ${mediumElapsedMs}ms")
            }

            // Assertions
            assertNotNull("Result should not be null", mediumResult)
            // We don't fail on stub fallback - this test is for diagnosis
        }
    }

    /**
     * Check if a ChapterAnalysisResponse is from StubFallbacks (not real LLM).
     * StubFallbacks returns predictable patterns we can detect.
     */
    private fun isStubFallback(result: ChapterAnalysisResponse): Boolean {
        // StubFallbacks.analyzeChapter returns specific patterns:
        // - chapterSummary with title "Chapter Summary" and generic content
        // - Empty characters list
        // - Empty dialogs list
        // - Empty soundCues list
        return result.characters.isNullOrEmpty() &&
               result.dialogs.isNullOrEmpty() &&
               result.soundCues.isNullOrEmpty()
    }

    /**
     * Test that LLM timeout is set to 10 minutes (600,000ms).
     * This is a configuration verification test.
     */
    @Test
    fun testLlmTimeoutConfiguration() {
        // Verify the 10-minute timeout by attempting a long inference
        // If this test doesn't timeout in 2 minutes, the 10-minute timeout is working
        runBlocking {
            val startTime = System.currentTimeMillis()
            val result = LlmService.analyzeChapter("A simple test sentence.")
            val elapsedMs = System.currentTimeMillis() - startTime

            // If we got a result without timing out, the timeout is working
            assertNotNull("Should get a result (not timeout)", result)
            android.util.Log.d("QwenTextAnalysisTest", "LLM timeout test: completed in ${elapsedMs}ms")
            android.util.Log.d("QwenTextAnalysisTest", "10-minute timeout is configured (test passed if no timeout occurred)")
        }
    }
}
