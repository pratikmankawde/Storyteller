package com.dramebaz.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.tts.TtsModelManager
import com.dramebaz.app.data.models.AudioSettings
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SETTINGS-001: Audio settings tab fragment.
 * Handles playback speed, theme, audio behavior, and TTS model settings.
 */
class AudioSettingsFragment : Fragment() {

    companion object {
        private const val TAG = "AudioSettingsFragment"
    }

    private val app by lazy { requireActivity().application as DramebazApplication }
    private val settingsRepository by lazy { app.settingsRepository }
    private val ttsModelManager by lazy { TtsModelManager(requireContext()) }

    private lateinit var speedSlider: Slider
    private lateinit var speedValue: TextView
    private lateinit var playbackThemeGroup: MaterialButtonToggleGroup
    private lateinit var switchAutoPlay: SwitchMaterial
    private lateinit var switchBackground: SwitchMaterial

    // TTS Model UI elements
    private lateinit var textTtsModelName: TextView
    private lateinit var textTtsModelInfo: TextView
    private lateinit var btnBrowseTtsModel: MaterialButton
    private lateinit var layoutTtsLoading: LinearLayout
    private lateinit var textTtsLoadingStatus: TextView
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_tab_audio, container, false)
    }
    
    // Folder picker launcher
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> handleFolderSelection(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        speedSlider = view.findViewById(R.id.speed_slider)
        speedValue = view.findViewById(R.id.speed_value)
        playbackThemeGroup = view.findViewById(R.id.playback_theme_group)
        switchAutoPlay = view.findViewById(R.id.switch_auto_play)
        switchBackground = view.findViewById(R.id.switch_background)

        // TTS Model UI elements
        textTtsModelName = view.findViewById(R.id.text_tts_model_name)
        textTtsModelInfo = view.findViewById(R.id.text_tts_model_info)
        btnBrowseTtsModel = view.findViewById(R.id.btn_browse_tts_model)
        layoutTtsLoading = view.findViewById(R.id.layout_tts_loading)
        textTtsLoadingStatus = view.findViewById(R.id.text_tts_loading_status)

        loadCurrentSettings()
        setupListeners()
        loadTtsModelInfo()
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

    private fun loadTtsModelInfo() {
        val modelInfo = app.ttsEngine.getModelInfo()
        if (modelInfo != null) {
            textTtsModelName.text = modelInfo.displayName
            textTtsModelInfo.text = "${modelInfo.modelType} • ${modelInfo.speakerCount} speakers • ${modelInfo.sampleRate} Hz"
            textTtsModelInfo.visibility = View.VISIBLE
        } else {
            textTtsModelName.text = getString(R.string.tts_model_not_loaded)
            textTtsModelInfo.visibility = View.GONE
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

        // TTS Model browse button
        btnBrowseTtsModel.setOnClickListener {
            folderPickerLauncher.launch(null)
        }
    }

    private fun handleFolderSelection(uri: Uri?) {
        if (uri == null) {
            AppLogger.d(TAG, "Folder selection cancelled")
            return
        }

        // Take persistent permission to access the folder
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not take persistable permission: ${e.message}")
        }

        // Convert content URI to actual file path
        val documentFile = DocumentFile.fromTreeUri(requireContext(), uri)
        if (documentFile == null || !documentFile.isDirectory) {
            Toast.makeText(requireContext(), R.string.tts_model_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        // Get the actual path from the URI
        val folderPath = getPathFromUri(uri)
        if (folderPath == null) {
            AppLogger.e(TAG, "Could not resolve folder path from URI: $uri")
            Toast.makeText(requireContext(), R.string.tts_model_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        AppLogger.i(TAG, "Selected TTS model folder: $folderPath")

        // Show loading state
        showTtsLoading(true)

        // Discover and load model
        viewLifecycleOwner.lifecycleScope.launch {
            discoverAndLoadModel(folderPath)
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        // Extract path from the content URI
        // Format: content://com.android.externalstorage.documents/tree/primary:Download/TTS/kokoro
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")

        return when {
            split.size >= 2 && split[0] == "primary" -> "/sdcard/${split[1]}"
            split.size >= 2 -> "/storage/${split[0]}/${split[1]}"
            else -> null
        }
    }

    private suspend fun discoverAndLoadModel(folderPath: String) {
        try {
            // Step 1: Discover model in folder
            val discovered = ttsModelManager.discoverModelFromFolder(folderPath)

            if (discovered == null) {
                withContext(Dispatchers.Main) {
                    showTtsLoading(false)
                    Toast.makeText(requireContext(), R.string.tts_model_not_found, Toast.LENGTH_SHORT).show()
                }
                return
            }

            withContext(Dispatchers.Main) {
                textTtsLoadingStatus.text = getString(R.string.tts_model_detected,
                    discovered.modelName, discovered.modelType.name)
            }

            // Step 2: Load and switch to the model
            val result = ttsModelManager.loadAndSwitchModel(app.ttsEngine, discovered)

            withContext(Dispatchers.Main) {
                showTtsLoading(false)

                when (result) {
                    is TtsModelManager.LoadResult.Success -> {
                        textTtsModelName.text = result.modelInfo.displayName
                        textTtsModelInfo.text = "${result.modelInfo.modelType} • ${result.modelInfo.speakerCount} speakers • ${result.modelInfo.sampleRate} Hz"
                        textTtsModelInfo.visibility = View.VISIBLE

                        // Save the model path to settings
                        updateSettings { it.copy(
                            ttsModelId = result.modelInfo.id,
                            ttsModelPath = folderPath
                        )}

                        Toast.makeText(requireContext(), R.string.tts_model_loaded, Toast.LENGTH_SHORT).show()
                        AppLogger.i(TAG, "TTS model loaded: ${result.modelInfo.displayName}")
                    }
                    is TtsModelManager.LoadResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        AppLogger.e(TAG, "TTS model load failed: ${result.message}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading TTS model", e)
            withContext(Dispatchers.Main) {
                showTtsLoading(false)
                Toast.makeText(requireContext(), R.string.tts_model_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTtsLoading(loading: Boolean) {
        layoutTtsLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnBrowseTtsModel.isEnabled = !loading
        textTtsLoadingStatus.text = getString(R.string.tts_model_loading)
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

