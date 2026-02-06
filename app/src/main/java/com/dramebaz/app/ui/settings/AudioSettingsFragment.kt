package com.dramebaz.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.models.AudioSettings
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SETTINGS-001: Audio settings tab fragment.
 * Handles playback speed, theme, and audio behavior settings.
 */
class AudioSettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "AudioSettingsFragment"
    }
    
    private val settingsRepository by lazy {
        (requireActivity().application as DramebazApplication).settingsRepository
    }
    
    private lateinit var speedSlider: Slider
    private lateinit var speedValue: TextView
    private lateinit var playbackThemeGroup: MaterialButtonToggleGroup
    private lateinit var switchAutoPlay: SwitchMaterial
    private lateinit var switchBackground: SwitchMaterial
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_tab_audio, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        speedSlider = view.findViewById(R.id.speed_slider)
        speedValue = view.findViewById(R.id.speed_value)
        playbackThemeGroup = view.findViewById(R.id.playback_theme_group)
        switchAutoPlay = view.findViewById(R.id.switch_auto_play)
        switchBackground = view.findViewById(R.id.switch_background)
        
        loadCurrentSettings()
        setupListeners()
    }
    
    private fun loadCurrentSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.audioSettings.first()
            
            // Set speed slider
            speedSlider.value = settings.playbackSpeed.coerceIn(0.5f, 2.0f)
            speedValue.text = String.format("%.2fx", settings.playbackSpeed)
            
            // Set playback theme
            val themeButtonId = when (settings.playbackTheme) {
                PlaybackTheme.CLASSIC -> R.id.btn_theme_classic
                PlaybackTheme.CINEMATIC -> R.id.btn_theme_cinematic
                PlaybackTheme.RELAXED -> R.id.btn_theme_relaxed
                PlaybackTheme.IMMERSIVE -> R.id.btn_theme_immersive
            }
            playbackThemeGroup.check(themeButtonId)
            
            // Set toggles
            switchAutoPlay.isChecked = settings.autoPlayNextChapter
            switchBackground.isChecked = settings.enableBackgroundPlayback
        }
    }
    
    private fun setupListeners() {
        speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                speedValue.text = String.format("%.2fx", value)
                updateSettings { it.copy(playbackSpeed = value) }
            }
        }
        
        playbackThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val theme = when (checkedId) {
                    R.id.btn_theme_classic -> PlaybackTheme.CLASSIC
                    R.id.btn_theme_cinematic -> PlaybackTheme.CINEMATIC
                    R.id.btn_theme_relaxed -> PlaybackTheme.RELAXED
                    R.id.btn_theme_immersive -> PlaybackTheme.IMMERSIVE
                    else -> return@addOnButtonCheckedListener
                }
                updateSettings { it.copy(playbackTheme = theme) }
            }
        }
        
        switchAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(autoPlayNextChapter = isChecked) }
        }
        
        switchBackground.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(enableBackgroundPlayback = isChecked) }
        }
    }
    
    private fun updateSettings(transform: (AudioSettings) -> AudioSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = settingsRepository.audioSettings.first()
            val updated = transform(current)
            settingsRepository.updateAudioSettings(updated)
            AppLogger.d(TAG, "Audio settings updated: $updated")
            (parentFragment as? SettingsBottomSheet)?.notifySettingsChanged()
        }
    }
}

