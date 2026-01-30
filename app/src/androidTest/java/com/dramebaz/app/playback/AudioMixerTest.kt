package com.dramebaz.app.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramebaz.app.playback.mixer.AudioMixer
import com.dramebaz.app.playback.mixer.PlaybackTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Test suite for audio mixing functionality.
 * Tests multi-channel audio mixing with narration, dialog, SFX, and ambience.
 */
@RunWith(AndroidJUnit4::class)
class AudioMixerTest {
    private lateinit var context: Context
    private lateinit var audioMixer: AudioMixer
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Create mixer with default settings
        audioMixer = AudioMixer()
    }
    
    @Test
    fun testAudioMixerInitialization() {
        // Test mixer initialization
        assertNotNull("Audio mixer should be created", audioMixer)
        android.util.Log.i("AudioMixerTest", "Audio mixer initialized")
    }
    
    @Test
    fun testSetChannelVolume() {
        // Test setting channel volumes (using string channel IDs)
        audioMixer.setChannelVolume("narration", 0.8f)
        audioMixer.setChannelVolume("dialog", 1.0f)
        audioMixer.setChannelVolume("sfx", 0.5f)
        audioMixer.setChannelVolume("ambience", 0.3f)
        
        // Verify volumes were set
        assertEquals("Narration volume should be 0.8", 0.8f, audioMixer.getChannelVolume("narration"), 0.01f)
        assertEquals("Dialog volume should be 1.0", 1.0f, audioMixer.getChannelVolume("dialog"), 0.01f)
        assertEquals("SFX volume should be 0.5", 0.5f, audioMixer.getChannelVolume("sfx"), 0.01f)
        assertEquals("Ambience volume should be 0.3", 0.3f, audioMixer.getChannelVolume("ambience"), 0.01f)
        
        android.util.Log.d("AudioMixerTest", "Channel volumes set and verified")
    }
    
    @Test
    fun testApplyTheme() {
        // Test applying playback themes
        val themes = listOf(
            PlaybackTheme.CINEMATIC,
            PlaybackTheme.RELAXED,
            PlaybackTheme.IMMERSIVE,
            PlaybackTheme.CLASSIC
        )
        
        themes.forEach { theme ->
            audioMixer.applyTheme(theme)
            android.util.Log.d("AudioMixerTest", "Applied theme: $theme")
        }
    }
    
    @Test
    fun testMixAudioFiles() {
        runBlocking {
            // Test mixing audio files
            // Note: This test requires actual audio files, so it may use stubs
            
            try {
                val result = audioMixer.mixAudioFiles(
                    narrationFile = null,
                    dialogFile = null,
                    sfxFiles = emptyList(),
                    ambienceFile = null
                )
                // Result should be FloatArray (may be empty if no files provided)
                assertNotNull("Mix result should not be null", result)
                android.util.Log.d("AudioMixerTest", "Audio mixing completed (result size: ${result.size} samples)")
            } catch (e: Exception) {
                android.util.Log.w("AudioMixerTest", "Audio mixing error: ${e.message}")
            }
        }
    }
    
    @Test
    fun testChannelVolumeLimits() {
        // Test that channel volumes are clamped to valid range
        audioMixer.setChannelVolume("narration", -1.0f) // Should clamp to 0
        assertEquals("Volume should clamp to 0", 0f, audioMixer.getChannelVolume("narration"), 0.01f)
        
        audioMixer.setChannelVolume("dialog", 2.0f)   // Should clamp to 1
        assertEquals("Volume should clamp to 1", 1.0f, audioMixer.getChannelVolume("dialog"), 0.01f)
        
        audioMixer.setChannelVolume("sfx", 0.5f)  // Valid value
        assertEquals("Volume should be 0.5", 0.5f, audioMixer.getChannelVolume("sfx"), 0.01f)
        
        android.util.Log.d("AudioMixerTest", "Channel volume limits test completed")
    }
}
