package com.dramebaz.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.data.db.AppSettings
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.ui.test.TestActivity
import kotlinx.coroutines.launch

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
        val group = view.findViewById<RadioGroup>(R.id.theme_group)
        viewLifecycleOwner.lifecycleScope.launch {
            val current = app.db.appSettingsDao().get(settingsKey) ?: PlaybackTheme.CLASSIC.name
            when (current) {
                PlaybackTheme.CINEMATIC.name -> group.check(R.id.theme_cinematic)
                PlaybackTheme.RELAXED.name -> group.check(R.id.theme_relaxed)
                PlaybackTheme.IMMERSIVE.name -> group.check(R.id.theme_immersive)
                else -> group.check(R.id.theme_classic)
            }
        }
        group.setOnCheckedChangeListener { _, id ->
            val theme = when (id) {
                R.id.theme_cinematic -> PlaybackTheme.CINEMATIC.name
                R.id.theme_relaxed -> PlaybackTheme.RELAXED.name
                R.id.theme_immersive -> PlaybackTheme.IMMERSIVE.name
                else -> PlaybackTheme.CLASSIC.name
            }
            viewLifecycleOwner.lifecycleScope.launch {
                app.db.appSettingsDao().put(AppSettings(settingsKey, theme))
            }
        }
    }
}
