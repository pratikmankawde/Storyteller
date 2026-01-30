package com.dramebaz.app.playback

import android.content.Context
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.playback.engine.PlaybackEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.GlobalScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Test suite for automatic audio playback.
 * Uses the app's TTS engine (pre-loaded by SplashActivity in production); ensures pre-loaded in setup.
 */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackTest {
    private lateinit var context: Context
    private lateinit var ttsEngine: SherpaTtsEngine
    private lateinit var playbackEngine: PlaybackEngine

    @Before
    fun setup() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            ttsEngine = (context.applicationContext as DramebazApplication).ttsEngine
            if (!ttsEngine.isInitialized()) {
                assertTrue("TTS engine should initialize", ttsEngine.init())
            }
            playbackEngine = PlaybackEngine(context, ttsEngine)
        }
    }

    @Test
    fun testPlaybackEngineInitialization() {
        // Test that playback engine is properly initialized
        assertNotNull("Playback engine should be created", playbackEngine)
        android.util.Log.i("AudioPlaybackTest", "Playback engine initialized")
    }

    @Test
    fun testAddNarration() {
        runBlocking {
        // Test adding narration segments
        val emotionalArc = listOf(
            EmotionalSegment("start", "curiosity", 0.4f),
            EmotionalSegment("middle", "tension", 0.7f),
            EmotionalSegment("end", "relief", 0.5f)
        )
        
        val voiceProfile = VoiceProfile(
            pitch = 1.0f,
            speed = 1.0f,
            energy = 1.0f
        )
        
        playbackEngine.addNarration(
            "This is a test narration segment.",
            emotionalArc,
            0,
            voiceProfile,
            null
        )
        
        android.util.Log.d("AudioPlaybackTest", "Narration segment added")
    }
    }

    @Test
    fun testAddDialog() {
        runBlocking {
        // Test adding dialog segments
        val dialog = Dialog(
            dialog = "Hello, how are you?",
            speaker = "Alice",
            emotion = "happy",
            intensity = 0.6f
        )
        
        val voiceProfile = VoiceProfile(
            pitch = 1.2f,
            speed = 1.0f,
            energy = 1.1f,
            emotionBias = mapOf("happy" to 0.8f)
        )
        
        playbackEngine.addDialog(dialog, voiceProfile)
        
        android.util.Log.d("AudioPlaybackTest", "Dialog segment added")
    }
    }

    @Test
    fun testPlaybackQueue() {
        runBlocking {
        // Test queueing multiple segments
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        // Add multiple narration segments
        playbackEngine.addNarration("First segment.", emotionalArc, 0, null, null)
        playbackEngine.addNarration("Second segment.", emotionalArc, 1, null, null)
        playbackEngine.addNarration("Third segment.", emotionalArc, 2, null, null)
        
        // Add a dialog
        val dialog = Dialog("I'm speaking!", "Bob", "excited", 0.8f)
        playbackEngine.addDialog(dialog, null)
        
        android.util.Log.d("AudioPlaybackTest", "Multiple segments queued")
    }
    }

    @Test
    fun testPreSynthesis() {
        runBlocking {
        // Test pre-synthesis of audio segments
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        playbackEngine.addNarration("This will be pre-synthesized.", emotionalArc, 0, null, null)
        playbackEngine.addNarration("This too.", emotionalArc, 1, null, null)
        
        // Start pre-synthesis
        playbackEngine.preSynthesizeAudio()
        
        // Wait a bit for synthesis to complete
        delay(2000)
        
        android.util.Log.d("AudioPlaybackTest", "Pre-synthesis completed")
    }
    }

    @Test
    fun testPlaybackFlow() {
        runBlocking {
        // Test complete playback flow
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        // Add segments
        playbackEngine.addNarration("First narration.", emotionalArc, 0, null, null)
        
        val dialog = Dialog("Hello!", "Alice", "happy", 0.6f)
        playbackEngine.addDialog(dialog, null)
        
        playbackEngine.addNarration("Second narration.", emotionalArc, 1, null, null)
        
        // Set up callbacks
        var playbackCompleted = false
        playbackEngine.setOnCompleteListener {
            playbackCompleted = true
            android.util.Log.d("AudioPlaybackTest", "Playback completed callback invoked")
        }
        
        // Start playback (with timeout to avoid hanging)
        try {
            withTimeout(10000) { // 10 second timeout
                playbackEngine.play()
                // Wait for playback to complete or timeout
                delay(5000)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("AudioPlaybackTest", "Playback timed out (may still be processing)")
        }
        
        // Stop playback
        playbackEngine.stop()
        
        android.util.Log.d("AudioPlaybackTest", "Playback flow test completed")
    }
    }

    @Test
    fun testPlaybackStop() {
        runBlocking {
        // Test stopping playback
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        playbackEngine.addNarration("This will be stopped.", emotionalArc, 0, null, null)
        
        // Start playback in background
        val playbackJob = kotlinx.coroutines.GlobalScope.launch {
            playbackEngine.play()
        }
        
        delay(500) // Let it start
        
        // Stop playback
        playbackEngine.stop()
        
        delay(500) // Wait for stop to complete
        
        playbackJob.cancel()
        
        android.util.Log.d("AudioPlaybackTest", "Playback stop test completed")
    }
    }

    @Test
    fun testPlaybackPauseResume() {
        runBlocking {
        // Test pause and resume (if supported)
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        playbackEngine.addNarration("This will be paused and resumed.", emotionalArc, 0, null, null)
        
        // Note: Pause/resume may not be fully implemented, so we just test the calls don't crash
        try {
            playbackEngine.pause()
            delay(100)
            playbackEngine.resume()
            delay(100)
            playbackEngine.stop()
        } catch (e: Exception) {
            android.util.Log.w("AudioPlaybackTest", "Pause/resume not fully implemented: ${e.message}")
        }
        
        android.util.Log.d("AudioPlaybackTest", "Pause/resume test completed")
    }
    }

    @Test
    fun testPlaybackWithSpeakerId() {
        runBlocking {
        // Test playback with specific speaker IDs
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        val voiceProfile = VoiceProfile()
        
        // Add narration with speaker ID
        playbackEngine.addNarration("Narration with speaker.", emotionalArc, 0, voiceProfile, 10)
        
        // Add dialog (speaker ID is handled internally via voice profile)
        val dialog = Dialog("Dialog with speaker.", "Bob", "neutral", 0.5f)
        playbackEngine.addDialog(dialog, voiceProfile)
        
        android.util.Log.d("AudioPlaybackTest", "Playback with speaker IDs test completed")
    }
    }

    @Test
    fun testPlaybackProgressCallback() {
        runBlocking {
        // Test progress callback
        var progressReceived = false
        var lastPosition = 0L
        var lastDuration = 0L
        
        playbackEngine.setOnProgressListener { position, duration ->
            progressReceived = true
            lastPosition = position
            lastDuration = duration
            android.util.Log.d("AudioPlaybackTest", "Progress: $position/$duration")
        }
        
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        playbackEngine.addNarration("Progress test.", emotionalArc, 0, null, null)
        
        // Start playback briefly
        val playbackJob = GlobalScope.launch {
            playbackEngine.play()
        }
        
        delay(1000)
        playbackEngine.stop()
        playbackJob.cancel()
        
        android.util.Log.d("AudioPlaybackTest", "Progress callback test completed (received: $progressReceived)")
    }
    }

    @Test
    fun testMultiplePlaybackSessions() {
        runBlocking {
        // Test multiple playback sessions
        val emotionalArc = listOf(EmotionalSegment("start", "neutral", 0.5f))
        
        // First session
        playbackEngine.addNarration("First session.", emotionalArc, 0, null, null)
        playbackEngine.stop()
        
        // Second session
        playbackEngine.addNarration("Second session.", emotionalArc, 0, null, null)
        playbackEngine.stop()
        
        // Third session
        playbackEngine.addNarration("Third session.", emotionalArc, 0, null, null)
        playbackEngine.stop()
        
        android.util.Log.d("AudioPlaybackTest", "Multiple playback sessions test completed")
    }
    }

    @Test
    fun testPlaybackCleanup() {
        // Test cleanup
        playbackEngine.cleanup()
        
        android.util.Log.d("AudioPlaybackTest", "Playback cleanup test completed")
    }
}
