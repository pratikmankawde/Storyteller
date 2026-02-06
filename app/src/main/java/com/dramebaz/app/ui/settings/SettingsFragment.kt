package com.dramebaz.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.AppSettings
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.ui.test.TestActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val settingsKey = "playback_theme"
    private val skipExtractionKey = "skip_character_extraction"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.settings_test_activities)?.setOnClickListener {
            startActivity(Intent(requireContext(), TestActivity::class.java))
        }

        // Playback theme toggle group
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.theme_group)
        viewLifecycleOwner.lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) {
                app.db.appSettingsDao().get(settingsKey)
            } ?: PlaybackTheme.CLASSIC.name
            val buttonId = when (current) {
                PlaybackTheme.CINEMATIC.name -> R.id.theme_cinematic
                PlaybackTheme.RELAXED.name -> R.id.theme_relaxed
                PlaybackTheme.IMMERSIVE.name -> R.id.theme_immersive
                else -> R.id.theme_classic
            }
            group.check(buttonId)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val theme = when (checkedId) {
                    R.id.theme_cinematic -> PlaybackTheme.CINEMATIC.name
                    R.id.theme_relaxed -> PlaybackTheme.RELAXED.name
                    R.id.theme_immersive -> PlaybackTheme.IMMERSIVE.name
                    else -> PlaybackTheme.CLASSIC.name
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.appSettingsDao().put(AppSettings(settingsKey, theme))
                    }
                }
            }
        }

        // Skip character extraction switch
        val skipSwitch = view.findViewById<SwitchMaterial>(R.id.switch_skip_extraction)
        viewLifecycleOwner.lifecycleScope.launch {
            val skipEnabled = withContext(Dispatchers.IO) {
                app.db.appSettingsDao().get(skipExtractionKey)
            }?.toBooleanStrictOrNull() ?: false
            skipSwitch.isChecked = skipEnabled
        }
        skipSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    app.db.appSettingsDao().put(AppSettings(skipExtractionKey, isChecked.toString()))
                }
            }
        }
    }
}
