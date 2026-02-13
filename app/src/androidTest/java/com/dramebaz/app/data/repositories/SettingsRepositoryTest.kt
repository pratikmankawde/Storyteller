package com.dramebaz.app.data.repositories

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.models.AudioSettings
import com.dramebaz.app.data.models.FeatureSettings
import com.dramebaz.app.data.models.LlmBackend
import com.dramebaz.app.data.models.LlmModelType
import com.dramebaz.app.data.models.LlmSettings
import com.dramebaz.app.data.models.NarratorSettings
import com.dramebaz.app.data.models.ReadingSettings
import com.dramebaz.app.data.models.ReadingTheme
import com.dramebaz.app.playback.mixer.PlaybackTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for SettingsRepository.
 * 
 * Tests settings persistence with real Room database:
 * - Load/save reading settings
 * - Load/save audio settings
 * - Load/save feature settings (including incrementalAnalysisPagePercent)
 * - Load/save LLM settings
 * - Load/save narrator settings
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class SettingsRepositoryTest {

    private lateinit var app: DramebazApplication
    private lateinit var db: AppDatabase
    private lateinit var settingsRepository: SettingsRepository

    companion object {
        private const val TAG = "SettingsRepositoryTest"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        app = context.applicationContext as DramebazApplication
        db = app.db
        settingsRepository = app.settingsRepository
    }

    @After
    fun teardown() {
        // Reset to default settings after tests
        runBlocking {
            settingsRepository.updateFeatureSettings(FeatureSettings())
            settingsRepository.updateReadingSettings(ReadingSettings())
        }
    }

    // ==================== Feature Settings Tests ====================

    @Test
    fun testIncrementalAnalysisPagePercent_defaultValue() {
        val settings = settingsRepository.featureSettings.value
        assertEquals("Default incrementalAnalysisPagePercent should be 50", 50, settings.incrementalAnalysisPagePercent)
        android.util.Log.d(TAG, "Default incrementalAnalysisPagePercent: ${settings.incrementalAnalysisPagePercent}")
    }

    @Test
    fun testIncrementalAnalysisPagePercent_saveAndLoad() {
        runBlocking {
            // Save custom percentage
            val newSettings = FeatureSettings(incrementalAnalysisPagePercent = 25)
            settingsRepository.updateFeatureSettings(newSettings)
            
            // Verify StateFlow updated immediately
            assertEquals(25, settingsRepository.featureSettings.value.incrementalAnalysisPagePercent)
            
            // Create new repository to verify persistence
            val newRepo = SettingsRepository(db.appSettingsDao())
            newRepo.loadSettings()
            
            assertEquals("Persisted incrementalAnalysisPagePercent should be 25", 
                25, newRepo.featureSettings.value.incrementalAnalysisPagePercent)
            android.util.Log.d(TAG, "Persisted incrementalAnalysisPagePercent: ${newRepo.featureSettings.value.incrementalAnalysisPagePercent}")
        }
    }

    @Test
    fun testFeatureSettings_allFieldsPersist() {
        runBlocking {
            val customSettings = FeatureSettings(
                enableSmartCasting = false,
                enableGenerativeVisuals = true,
                enableDeepAnalysis = false,
                enableEmotionModifiers = false,
                enableKaraokeHighlight = false,
                incrementalAnalysisPagePercent = 75
            )
            settingsRepository.updateFeatureSettings(customSettings)
            
            // Reload from database
            val newRepo = SettingsRepository(db.appSettingsDao())
            newRepo.loadSettings()
            
            val loaded = newRepo.featureSettings.value
            assertEquals(false, loaded.enableSmartCasting)
            assertEquals(true, loaded.enableGenerativeVisuals)
            assertEquals(false, loaded.enableDeepAnalysis)
            assertEquals(false, loaded.enableEmotionModifiers)
            assertEquals(false, loaded.enableKaraokeHighlight)
            assertEquals(75, loaded.incrementalAnalysisPagePercent)
            android.util.Log.d(TAG, "All feature settings persisted correctly")
        }
    }

    // ==================== Reading Settings Tests ====================

    @Test
    fun testReadingSettings_saveAndLoad() {
        runBlocking {
            val customSettings = ReadingSettings(
                theme = ReadingTheme.DARK,
                fontSize = 1.5f,
                lineHeight = 2.0f
            )
            settingsRepository.updateReadingSettings(customSettings)
            
            val newRepo = SettingsRepository(db.appSettingsDao())
            newRepo.loadSettings()
            
            val loaded = newRepo.readingSettings.value
            assertEquals(ReadingTheme.DARK, loaded.theme)
            assertEquals(1.5f, loaded.fontSize, 0.01f)
            assertEquals(2.0f, loaded.lineHeight, 0.01f)
            android.util.Log.d(TAG, "Reading settings: theme=${loaded.theme}, fontSize=${loaded.fontSize}")
        }
    }

    // ==================== Audio Settings Tests ====================

    @Test
    fun testAudioSettings_saveAndLoad() {
        runBlocking {
            val customSettings = AudioSettings(
                playbackSpeed = 1.25f,
                playbackTheme = PlaybackTheme.CINEMATIC,
                autoPlayNextChapter = false,
                pauseOnScreenOff = false,
                enableBackgroundPlayback = false
            )
            settingsRepository.updateAudioSettings(customSettings)
            
            val newRepo = SettingsRepository(db.appSettingsDao())
            newRepo.loadSettings()
            
            val loaded = newRepo.audioSettings.value
            assertEquals(1.25f, loaded.playbackSpeed, 0.01f)
            assertEquals(PlaybackTheme.CINEMATIC, loaded.playbackTheme)
            assertEquals(false, loaded.autoPlayNextChapter)
            android.util.Log.d(TAG, "Audio settings: speed=${loaded.playbackSpeed}, theme=${loaded.playbackTheme}")
        }
    }
}

