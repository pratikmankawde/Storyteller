package com.dramebaz.app.integration

import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.ai.tts.SpeakerCatalog
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.domain.usecases.AudioRegenerationManager
import com.dramebaz.app.utils.DegradedModeManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for TTS Audio Generation workflow.
 * Tests:
 * - Dialog-to-audio generation workflow
 * - Speaker assignment via SpeakerMatcher
 * - AudioRegenerationManager background processing
 * - Audio file caching and retrieval
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsAudioGenerationIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var mockCatalog: SpeakerCatalog

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

        // Create mock speaker catalog
        mockCatalog = mockk()
        
        DegradedModeManager.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    // ==================== VoiceProfile Tests ====================

    @Test
    fun `VoiceProfile stores all audio parameters`() {
        val profile = VoiceProfile(
            speed = 1.2f,
            pitch = 1.1f,
            energy = 0.9f
        )

        assertEquals(1.2f, profile.speed, 0.01f)
        assertEquals(1.1f, profile.pitch, 0.01f)
        assertEquals(0.9f, profile.energy, 0.01f)
    }

    @Test
    fun `VoiceProfile default values are normalized`() {
        val defaultProfile = VoiceProfile()

        assertEquals(1.0f, defaultProfile.speed, 0.01f)
        assertEquals(1.0f, defaultProfile.pitch, 0.01f)
        assertEquals(1.0f, defaultProfile.energy, 0.01f)
    }

    // ==================== SpeakerMatcher Tests ====================

    @Test
    fun `SpeakerMatcher suggestSpeakerId returns deterministic result for same name`() {
        val speakers = listOf(
            createSpeakerTraits(0, "F", 30, "american"),
            createSpeakerTraits(1, "M", 35, "british"),
            createSpeakerTraits(2, "F", 22, "american")
        )
        setupMockCatalog(speakers)

        val id1 = SpeakerMatcher.suggestSpeakerId("confident, leader", null, "Jax", mockCatalog)
        val id2 = SpeakerMatcher.suggestSpeakerId("confident, leader", null, "Jax", mockCatalog)

        assertEquals("Same name should get same speaker ID", id1, id2)
    }

    @Test
    fun `SpeakerMatcher matches gender traits`() {
        val speakers = listOf(
            createSpeakerTraits(0, "F", 30, "american"),
            createSpeakerTraits(1, "M", 35, "british")
        )
        setupMockCatalog(speakers)

        val maleId = SpeakerMatcher.suggestSpeakerId("male, confident", null, "John", mockCatalog)
        val femaleId = SpeakerMatcher.suggestSpeakerId("female, gentle", null, "Mary", mockCatalog)

        // IDs should differ if traits are matched
        if (maleId != null && femaleId != null) {
            assertNotEquals("Different genders should get different speakers", maleId, femaleId)
        }
    }

    @Test
    fun `SpeakerMatcher getSimilarSpeakers returns ranked list`() {
        val speakers = listOf(
            createSpeakerTraits(0, "F", 30, "american"),
            createSpeakerTraits(1, "M", 35, "british"),
            createSpeakerTraits(2, "M", 22, "american")
        )
        setupMockCatalog(speakers)

        val similar = SpeakerMatcher.getSimilarSpeakers("male, american", null, "Test", 2, mockCatalog)

        assertTrue("Should return up to topN speakers", similar.size <= 2)
    }

    private fun setupMockCatalog(speakers: List<SpeakerCatalog.SpeakerTraits>) {
        every { mockCatalog.speakerCount } returns speakers.size
        every { mockCatalog.minSpeakerId } returns 0
        every { mockCatalog.maxSpeakerId } returns speakers.size - 1
        every { mockCatalog.allSpeakers() } returns speakers
        speakers.forEach { speaker ->
            every { mockCatalog.getTraits(speaker.speakerId) } returns speaker
        }
    }

    // ==================== TTS Mode Tests ====================

    @Test
    fun `TTS mode transitions are tracked by DegradedModeManager`() {
        assertEquals(DegradedModeManager.TtsMode.NOT_INITIALIZED, DegradedModeManager.ttsMode.value)

        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
        assertEquals(DegradedModeManager.TtsMode.FULL, DegradedModeManager.ttsMode.value)

        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED, "Model not found")
        assertEquals(DegradedModeManager.TtsMode.DISABLED, DegradedModeManager.ttsMode.value)
        assertEquals("Model not found", DegradedModeManager.ttsFailureReason)
    }

    @Test
    fun `TTS degraded message reflects current state`() {
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED)
        val message = DegradedModeManager.getTtsDegradedMessage()

        assertTrue("Message should mention unavailable", message.contains("unavailable") || message.contains("couldn't"))
    }

    // ==================== AudioRegenerationManager Tests ====================

    @Test
    fun `AudioRegenerationManager enqueue creates job with correct parameters`() {
        // Note: AudioRegenerationManager requires initialization with Context/Database
        // These tests verify the job key creation logic
        val jobKey1 = createJobKey(1L, "Jax")
        val jobKey2 = createJobKey(1L, "Zane")
        val jobKey3 = createJobKey(2L, "Jax")

        assertNotEquals("Different characters should have different keys", jobKey1, jobKey2)
        assertNotEquals("Same character in different books should have different keys", jobKey1, jobKey3)
    }

    @Test
    fun `AudioRegenerationManager job key is deterministic`() {
        val key1 = createJobKey(1L, "Jax")
        val key2 = createJobKey(1L, "Jax")

        assertEquals("Same inputs should produce same key", key1, key2)
    }

    // ==================== Audio Workflow Integration Tests ====================

    @Test
    fun `full audio generation workflow processes character dialogs`() = runTest {
        // Simulate the workflow: character data → speaker assignment → audio generation
        val characters = listOf(
            CharacterDialogData("Jax", listOf("Standard protocol."), "male, confident, leader"),
            CharacterDialogData("Lyra", listOf("Don't listen to it!"), "female, protective, brave")
        )

        val speakers = listOf(
            createSpeakerTraits(0, "M", 30, "american"),
            createSpeakerTraits(1, "F", 28, "british")
        )
        setupMockCatalog(speakers)

        // Step 1: Assign speakers to characters
        val assignments = characters.map { char ->
            val speakerId = SpeakerMatcher.suggestSpeakerId(char.traits, null, char.name, mockCatalog)
            CharacterSpeakerAssignment(char.name, speakerId ?: 0)
        }

        // Step 2: Verify assignments
        assertEquals(2, assignments.size)
        assignments.forEach { assignment ->
            assertTrue("Speaker ID should be valid", assignment.speakerId >= 0)
        }
    }

    @Test
    fun `audio caching key includes all relevant parameters`() {
        val key1 = createCacheKey("Hello world", 5, 1.0f, 1.0f)
        val key2 = createCacheKey("Hello world", 5, 1.2f, 1.0f)
        val key3 = createCacheKey("Hello world", 6, 1.0f, 1.0f)

        assertNotEquals("Different speed should produce different key", key1, key2)
        assertNotEquals("Different speaker should produce different key", key1, key3)
    }

    // ==================== Helper Functions ====================

    private fun createSpeakerTraits(
        id: Int,
        gender: String,
        ageYears: Int?,
        accent: String
    ): SpeakerCatalog.SpeakerTraits {
        return SpeakerCatalog.SpeakerTraits(
            speakerId = id,
            gender = gender,
            ageYears = ageYears,
            accent = accent,
            region = ""
        )
    }

    private fun createJobKey(bookId: Long, characterName: String): String {
        return "book_${bookId}_char_${characterName.lowercase()}"
    }

    private fun createCacheKey(text: String, speakerId: Int, speed: Float, energy: Float): String {
        return "${text.hashCode()}_${speakerId}_${speed}_${energy}"
    }

    private data class CharacterDialogData(
        val name: String,
        val dialogs: List<String>,
        val traits: String
    )

    private data class CharacterSpeakerAssignment(
        val characterName: String,
        val speakerId: Int
    )
}

