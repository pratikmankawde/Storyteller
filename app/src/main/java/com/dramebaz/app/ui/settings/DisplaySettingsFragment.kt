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
import com.dramebaz.app.data.models.FontFamily
import com.dramebaz.app.data.models.ReadingSettings
import com.dramebaz.app.data.models.ReadingTheme
import com.dramebaz.app.ui.theme.FontManager
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SETTINGS-001: Display settings tab fragment.
 * Handles theme, font size, line height, and font family settings.
 */
class DisplaySettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "DisplaySettingsFragment"
    }
    
    private val settingsRepository by lazy {
        (requireActivity().application as DramebazApplication).settingsRepository
    }
    
    private lateinit var themeToggleGroup: MaterialButtonToggleGroup
    private lateinit var fontSizeSlider: Slider
    private lateinit var fontSizeValue: TextView
    private lateinit var lineHeightSlider: Slider
    private lateinit var lineHeightValue: TextView
    private lateinit var fontFamilyChips: ChipGroup
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_tab_display, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        themeToggleGroup = view.findViewById(R.id.theme_toggle_group)
        fontSizeSlider = view.findViewById(R.id.font_size_slider)
        fontSizeValue = view.findViewById(R.id.font_size_value)
        lineHeightSlider = view.findViewById(R.id.line_height_slider)
        lineHeightValue = view.findViewById(R.id.line_height_value)
        fontFamilyChips = view.findViewById(R.id.font_family_chips)
        
        setupFontFamilyChips()
        loadCurrentSettings()
        setupListeners()
    }
    
    private fun setupFontFamilyChips() {
        FontFamily.entries.forEach { font ->
            val chip = Chip(requireContext()).apply {
                text = font.displayName
                isCheckable = true
                tag = font
                // Apply the actual font typeface to the chip's text
                typeface = FontManager.getTypeface(requireContext(), font)
            }
            fontFamilyChips.addView(chip)
        }
    }
    
    private fun loadCurrentSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.readingSettings.first()
            
            // Set theme toggle
            val themeButtonId = when (settings.theme) {
                ReadingTheme.LIGHT -> R.id.btn_theme_light
                ReadingTheme.SEPIA -> R.id.btn_theme_sepia
                ReadingTheme.DARK -> R.id.btn_theme_dark
                ReadingTheme.OLED -> R.id.btn_theme_oled
            }
            themeToggleGroup.check(themeButtonId)
            
            // Set font size
            fontSizeSlider.value = settings.fontSize.coerceIn(0.8f, 2.0f)
            fontSizeValue.text = String.format("%.1fx", settings.fontSize)
            
            // Set line height
            lineHeightSlider.value = settings.lineHeight.coerceIn(1.0f, 2.4f)
            lineHeightValue.text = String.format("%.1f", settings.lineHeight)
            
            // Set font family
            for (i in 0 until fontFamilyChips.childCount) {
                val chip = fontFamilyChips.getChildAt(i) as? Chip
                if (chip?.tag == settings.fontFamily) {
                    chip.isChecked = true
                    break
                }
            }
        }
    }
    
    private fun setupListeners() {
        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val theme = when (checkedId) {
                    R.id.btn_theme_light -> ReadingTheme.LIGHT
                    R.id.btn_theme_sepia -> ReadingTheme.SEPIA
                    R.id.btn_theme_dark -> ReadingTheme.DARK
                    R.id.btn_theme_oled -> ReadingTheme.OLED
                    else -> return@addOnButtonCheckedListener
                }
                updateSettings { it.copy(theme = theme) }
            }
        }
        
        fontSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                fontSizeValue.text = String.format("%.1fx", value)
                updateSettings { it.copy(fontSize = value) }
            }
        }
        
        lineHeightSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                lineHeightValue.text = String.format("%.1f", value)
                updateSettings { it.copy(lineHeight = value) }
            }
        }
        
        fontFamilyChips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds[0])
                val font = chip?.tag as? FontFamily ?: return@setOnCheckedStateChangeListener
                updateSettings { it.copy(fontFamily = font) }
            }
        }
    }
    
    private fun updateSettings(transform: (ReadingSettings) -> ReadingSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = settingsRepository.readingSettings.first()
            val updated = transform(current)
            settingsRepository.updateReadingSettings(updated)
            AppLogger.d(TAG, "Settings updated: $updated")
            (parentFragment as? SettingsBottomSheet)?.notifySettingsChanged()
        }
    }
}

