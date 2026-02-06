package com.dramebaz.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.models.FeatureSettings
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SETTINGS-001: Features settings tab fragment.
 * Handles toggles for resource-intensive AI features.
 */
class FeaturesSettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "FeaturesSettingsFragment"
    }
    
    private val settingsRepository by lazy {
        (requireActivity().application as DramebazApplication).settingsRepository
    }
    
    private lateinit var switchSmartCasting: SwitchMaterial
    private lateinit var switchGenerativeVisuals: SwitchMaterial
    private lateinit var switchDeepAnalysis: SwitchMaterial
    private lateinit var switchEmotionModifiers: SwitchMaterial
    private lateinit var switchKaraoke: SwitchMaterial
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_tab_features, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        switchSmartCasting = view.findViewById(R.id.switch_smart_casting)
        switchGenerativeVisuals = view.findViewById(R.id.switch_generative_visuals)
        switchDeepAnalysis = view.findViewById(R.id.switch_deep_analysis)
        switchEmotionModifiers = view.findViewById(R.id.switch_emotion_modifiers)
        switchKaraoke = view.findViewById(R.id.switch_karaoke)
        
        loadCurrentSettings()
        setupListeners()
    }
    
    private fun loadCurrentSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.featureSettings.first()
            
            switchSmartCasting.isChecked = settings.enableSmartCasting
            switchGenerativeVisuals.isChecked = settings.enableGenerativeVisuals
            switchDeepAnalysis.isChecked = settings.enableDeepAnalysis
            switchEmotionModifiers.isChecked = settings.enableEmotionModifiers
            switchKaraoke.isChecked = settings.enableKaraokeHighlight
        }
    }
    
    private fun setupListeners() {
        switchSmartCasting.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(enableSmartCasting = isChecked) }
        }
        
        switchGenerativeVisuals.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(enableGenerativeVisuals = isChecked) }
        }
        
        switchDeepAnalysis.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(enableDeepAnalysis = isChecked) }
        }
        
        switchEmotionModifiers.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(enableEmotionModifiers = isChecked) }
        }
        
        switchKaraoke.setOnCheckedChangeListener { _, isChecked ->
            updateSettings { it.copy(enableKaraokeHighlight = isChecked) }
        }
    }
    
    private fun updateSettings(transform: (FeatureSettings) -> FeatureSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = settingsRepository.featureSettings.first()
            val updated = transform(current)
            settingsRepository.updateFeatureSettings(updated)
            AppLogger.d(TAG, "Feature settings updated: $updated")
            (parentFragment as? SettingsBottomSheet)?.notifySettingsChanged()
        }
    }
}

