package com.dramebaz.app.data.repositories

import com.dramebaz.app.data.db.AppSettings
import com.dramebaz.app.data.db.AppSettingsDao
import com.dramebaz.app.data.models.AudioSettings
import com.dramebaz.app.data.models.FeatureSettings
import com.dramebaz.app.data.models.FontFamily
import com.dramebaz.app.data.models.LlmBackend
import com.dramebaz.app.data.models.LlmModelType
import com.dramebaz.app.data.models.LlmSettings
import com.dramebaz.app.data.models.NarratorSettings
import com.dramebaz.app.data.models.ReadingSettings
import com.dramebaz.app.data.models.ReadingTheme
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * SETTINGS-001: Repository for managing all app settings.
 * Uses Room's AppSettingsDao for persistence.
 */
class SettingsRepository(private val appSettingsDao: AppSettingsDao) {
    
    companion object {
        private const val TAG = "SettingsRepository"
        
        // Reading settings keys
        private const val KEY_READING_THEME = "reading_theme"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_FONT_FAMILY = "font_family"
        
        // Audio settings keys
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_PLAYBACK_THEME = "playback_theme"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_PAUSE_SCREEN_OFF = "pause_screen_off"
        private const val KEY_BACKGROUND_PLAYBACK = "background_playback"
        private const val KEY_TTS_MODEL_ID = "tts_model_id"
        private const val KEY_TTS_MODEL_PATH = "tts_model_path"
        
        // Feature settings keys
        private const val KEY_SMART_CASTING = "enable_smart_casting"
        private const val KEY_GENERATIVE_VISUALS = "enable_generative_visuals"
        private const val KEY_DEEP_ANALYSIS = "enable_deep_analysis"
        private const val KEY_EMOTION_MODIFIERS = "enable_emotion_modifiers"
        private const val KEY_KARAOKE_HIGHLIGHT = "enable_karaoke_highlight"
        private const val KEY_INCREMENTAL_ANALYSIS_PERCENT = "incremental_analysis_page_percent"

        // LLM settings keys
        private const val KEY_LLM_MODEL_TYPE = "llm_model_type"
        private const val KEY_LLM_BACKEND = "llm_backend"
        private const val KEY_LLM_MODEL_PATH = "llm_model_path"

        // Narrator voice settings keys
        private const val KEY_NARRATOR_SPEAKER_ID = "narrator_speaker_id"
        private const val KEY_NARRATOR_SPEED = "narrator_speed"
        private const val KEY_NARRATOR_ENERGY = "narrator_energy"
    }
    
    private val _readingSettings = MutableStateFlow(ReadingSettings())
    val readingSettings: StateFlow<ReadingSettings> = _readingSettings.asStateFlow()
    
    private val _audioSettings = MutableStateFlow(AudioSettings())
    val audioSettings: StateFlow<AudioSettings> = _audioSettings.asStateFlow()
    
    private val _featureSettings = MutableStateFlow(FeatureSettings())
    val featureSettings: StateFlow<FeatureSettings> = _featureSettings.asStateFlow()

    private val _llmSettings = MutableStateFlow(LlmSettings())
    val llmSettings: StateFlow<LlmSettings> = _llmSettings.asStateFlow()

    private val _narratorSettings = MutableStateFlow(NarratorSettings())
    val narratorSettings: StateFlow<NarratorSettings> = _narratorSettings.asStateFlow()
    
    /**
     * Load all settings from database into StateFlows.
     * Call this on app startup.
     */
    suspend fun loadSettings() = withContext(Dispatchers.IO) {
        try {
            // Load reading settings
            val theme = appSettingsDao.get(KEY_READING_THEME)?.let { 
                ReadingTheme.entries.find { t -> t.name == it } 
            } ?: ReadingTheme.LIGHT
            val fontSize = appSettingsDao.get(KEY_FONT_SIZE)?.toFloatOrNull() ?: 1.0f
            val lineHeight = appSettingsDao.get(KEY_LINE_HEIGHT)?.toFloatOrNull() ?: 1.4f
            val fontFamily = appSettingsDao.get(KEY_FONT_FAMILY)?.let {
                FontFamily.entries.find { f -> f.name == it }
            } ?: FontFamily.SERIF
            
            _readingSettings.value = ReadingSettings(theme, fontSize, lineHeight, fontFamily)
            
            // Load audio settings
            val speed = appSettingsDao.get(KEY_PLAYBACK_SPEED)?.toFloatOrNull() ?: 1.0f
            val playbackTheme = appSettingsDao.get(KEY_PLAYBACK_THEME)?.let {
                PlaybackTheme.entries.find { t -> t.name == it }
            } ?: PlaybackTheme.CLASSIC
            val autoPlay = appSettingsDao.get(KEY_AUTO_PLAY_NEXT)?.toBooleanStrictOrNull() ?: true
            val pauseOnOff = appSettingsDao.get(KEY_PAUSE_SCREEN_OFF)?.toBooleanStrictOrNull() ?: true
            val bgPlay = appSettingsDao.get(KEY_BACKGROUND_PLAYBACK)?.toBooleanStrictOrNull() ?: true
            val ttsModelId = appSettingsDao.get(KEY_TTS_MODEL_ID)?.takeIf { it.isNotEmpty() }
            val ttsModelPath = appSettingsDao.get(KEY_TTS_MODEL_PATH)?.takeIf { it.isNotEmpty() }

            _audioSettings.value = AudioSettings(speed, playbackTheme, autoPlay, pauseOnOff, bgPlay, ttsModelId, ttsModelPath)
            
            // Load feature settings
            val smartCast = appSettingsDao.get(KEY_SMART_CASTING)?.toBooleanStrictOrNull() ?: true
            val genVisuals = appSettingsDao.get(KEY_GENERATIVE_VISUALS)?.toBooleanStrictOrNull() ?: false
            val deepAnalysis = appSettingsDao.get(KEY_DEEP_ANALYSIS)?.toBooleanStrictOrNull() ?: true
            val emotionMod = appSettingsDao.get(KEY_EMOTION_MODIFIERS)?.toBooleanStrictOrNull() ?: true
            val karaoke = appSettingsDao.get(KEY_KARAOKE_HIGHLIGHT)?.toBooleanStrictOrNull() ?: true
            val incrementalPercent = appSettingsDao.get(KEY_INCREMENTAL_ANALYSIS_PERCENT)?.toIntOrNull() ?: 50

            _featureSettings.value = FeatureSettings(smartCast, genVisuals, deepAnalysis, emotionMod, karaoke, incrementalPercent)

            // Load LLM settings
            val modelType = appSettingsDao.get(KEY_LLM_MODEL_TYPE)?.let {
                LlmModelType.entries.find { t -> t.name == it }
            } ?: LlmModelType.AUTO
            val backend = appSettingsDao.get(KEY_LLM_BACKEND)?.let {
                LlmBackend.entries.find { b -> b.name == it }
            } ?: LlmBackend.GPU
            val modelPath = appSettingsDao.get(KEY_LLM_MODEL_PATH)

            _llmSettings.value = LlmSettings(modelType, backend, modelPath)

            // Load Narrator settings
            val narratorSpeakerId = appSettingsDao.get(KEY_NARRATOR_SPEAKER_ID)?.toIntOrNull() ?: 0
            val narratorSpeed = appSettingsDao.get(KEY_NARRATOR_SPEED)?.toFloatOrNull() ?: NarratorSettings.DEFAULT_SPEED
            val narratorEnergy = appSettingsDao.get(KEY_NARRATOR_ENERGY)?.toFloatOrNull() ?: 1.0f

            _narratorSettings.value = NarratorSettings(narratorSpeakerId, narratorSpeed, narratorEnergy)

            AppLogger.d(TAG, "Settings loaded successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading settings", e)
        }
    }
    
    suspend fun updateReadingSettings(settings: ReadingSettings) = withContext(Dispatchers.IO) {
        appSettingsDao.put(AppSettings(KEY_READING_THEME, settings.theme.name))
        appSettingsDao.put(AppSettings(KEY_FONT_SIZE, settings.fontSize.toString()))
        appSettingsDao.put(AppSettings(KEY_LINE_HEIGHT, settings.lineHeight.toString()))
        appSettingsDao.put(AppSettings(KEY_FONT_FAMILY, settings.fontFamily.name))
        _readingSettings.value = settings
    }
    
    suspend fun updateAudioSettings(settings: AudioSettings) = withContext(Dispatchers.IO) {
        appSettingsDao.put(AppSettings(KEY_PLAYBACK_SPEED, settings.playbackSpeed.toString()))
        appSettingsDao.put(AppSettings(KEY_PLAYBACK_THEME, settings.playbackTheme.name))
        appSettingsDao.put(AppSettings(KEY_AUTO_PLAY_NEXT, settings.autoPlayNextChapter.toString()))
        appSettingsDao.put(AppSettings(KEY_PAUSE_SCREEN_OFF, settings.pauseOnScreenOff.toString()))
        appSettingsDao.put(AppSettings(KEY_BACKGROUND_PLAYBACK, settings.enableBackgroundPlayback.toString()))
        appSettingsDao.put(AppSettings(KEY_TTS_MODEL_ID, settings.ttsModelId ?: ""))
        appSettingsDao.put(AppSettings(KEY_TTS_MODEL_PATH, settings.ttsModelPath ?: ""))
        _audioSettings.value = settings
    }
    
    suspend fun updateFeatureSettings(settings: FeatureSettings) = withContext(Dispatchers.IO) {
        appSettingsDao.put(AppSettings(KEY_SMART_CASTING, settings.enableSmartCasting.toString()))
        appSettingsDao.put(AppSettings(KEY_GENERATIVE_VISUALS, settings.enableGenerativeVisuals.toString()))
        appSettingsDao.put(AppSettings(KEY_DEEP_ANALYSIS, settings.enableDeepAnalysis.toString()))
        appSettingsDao.put(AppSettings(KEY_EMOTION_MODIFIERS, settings.enableEmotionModifiers.toString()))
        appSettingsDao.put(AppSettings(KEY_KARAOKE_HIGHLIGHT, settings.enableKaraokeHighlight.toString()))
        appSettingsDao.put(AppSettings(KEY_INCREMENTAL_ANALYSIS_PERCENT, settings.incrementalAnalysisPagePercent.toString()))
        _featureSettings.value = settings
    }

    suspend fun updateLlmSettings(settings: LlmSettings) = withContext(Dispatchers.IO) {
        appSettingsDao.put(AppSettings(KEY_LLM_MODEL_TYPE, settings.selectedModelType.name))
        appSettingsDao.put(AppSettings(KEY_LLM_BACKEND, settings.preferredBackend.name))
        // Store model path (empty string if null to allow clearing)
        appSettingsDao.put(AppSettings(KEY_LLM_MODEL_PATH, settings.selectedModelPath ?: ""))
        _llmSettings.value = settings
        AppLogger.d(TAG, "LLM settings updated: model=${settings.selectedModelType}, backend=${settings.preferredBackend}, path=${settings.selectedModelPath}")
    }

    /**
     * NARRATOR-001: Update narrator voice settings.
     */
    suspend fun updateNarratorSettings(settings: NarratorSettings) = withContext(Dispatchers.IO) {
        appSettingsDao.put(AppSettings(KEY_NARRATOR_SPEAKER_ID, settings.speakerId.toString()))
        appSettingsDao.put(AppSettings(KEY_NARRATOR_SPEED, settings.speed.toString()))
        appSettingsDao.put(AppSettings(KEY_NARRATOR_ENERGY, settings.energy.toString()))
        _narratorSettings.value = settings
        AppLogger.d(TAG, "Narrator settings updated: speaker=${settings.speakerId}, speed=${settings.speed}, energy=${settings.energy}")
    }
}

