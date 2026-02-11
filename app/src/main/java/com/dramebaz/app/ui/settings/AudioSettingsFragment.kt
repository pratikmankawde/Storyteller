package com.dramebaz.app.ui.settings

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.tts.TtsModelManager
import com.dramebaz.app.ai.tts.VctkSpeakerCatalog
import com.dramebaz.app.data.models.AudioSettings
import com.dramebaz.app.data.models.NarratorSettings
import com.dramebaz.app.playback.mixer.PlaybackTheme
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // Narrator Speaker UI elements
    private lateinit var textNarratorSpeaker: TextView
    private lateinit var textNarratorSpeakerInfo: TextView
    private lateinit var btnSelectNarratorSpeaker: MaterialButton
    private lateinit var narratorSpeedSlider: Slider
    private lateinit var narratorSpeedValue: TextView

    // NARRATOR-002: Voice preview state
    private var mediaPlayer: MediaPlayer? = null
    private var previewJob: Job? = null

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

        // Narrator Speaker UI elements
        textNarratorSpeaker = view.findViewById(R.id.text_narrator_speaker)
        textNarratorSpeakerInfo = view.findViewById(R.id.text_narrator_speaker_info)
        btnSelectNarratorSpeaker = view.findViewById(R.id.btn_select_narrator_speaker)
        narratorSpeedSlider = view.findViewById(R.id.narrator_speed_slider)
        narratorSpeedValue = view.findViewById(R.id.narrator_speed_value)

        // TTS Model UI elements
        textTtsModelName = view.findViewById(R.id.text_tts_model_name)
        textTtsModelInfo = view.findViewById(R.id.text_tts_model_info)
        btnBrowseTtsModel = view.findViewById(R.id.btn_browse_tts_model)
        layoutTtsLoading = view.findViewById(R.id.layout_tts_loading)
        textTtsLoadingStatus = view.findViewById(R.id.text_tts_loading_status)

        loadCurrentSettings()
        setupListeners()
        loadTtsModelInfo()
        loadNarratorSpeakerInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // NARRATOR-002: Release media player resources
        stopPreview()
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

        // Narrator speaker selection button
        btnSelectNarratorSpeaker.setOnClickListener {
            showNarratorSpeakerDialog()
        }

        // NARRATOR-002: Narrator speed slider
        narratorSpeedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                narratorSpeedValue.text = String.format("%.1fx", value)
                updateNarratorSettings { it.copy(speed = value) }
            }
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

    /**
     * NARRATOR-001: Load and display current narrator speaker info.
     * NARRATOR-002: Also loads narrator speed setting.
     */
    private fun loadNarratorSpeakerInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            val narratorSettings = settingsRepository.narratorSettings.first()
            updateNarratorSpeakerDisplay(narratorSettings.speakerId)

            // NARRATOR-002: Load narrator speed
            val speed = narratorSettings.speed.coerceIn(NarratorSettings.MIN_SPEED, NarratorSettings.MAX_SPEED)
            narratorSpeedSlider.value = speed
            narratorSpeedValue.text = String.format("%.1fx", speed)
        }
    }

    /**
     * NARRATOR-001: Update the narrator speaker display with speaker info.
     */
    private fun updateNarratorSpeakerDisplay(speakerId: Int) {
        val speakerTraits = VctkSpeakerCatalog.getTraits(speakerId)
        textNarratorSpeaker.text = "Speaker $speakerId"
        if (speakerTraits != null) {
            textNarratorSpeakerInfo.text = speakerTraits.displayLabel()
            textNarratorSpeakerInfo.visibility = View.VISIBLE
        } else {
            textNarratorSpeakerInfo.visibility = View.GONE
        }
    }

    /**
     * NARRATOR-002: Update narrator settings (speed/energy).
     */
    private fun updateNarratorSettings(transform: (NarratorSettings) -> NarratorSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = settingsRepository.narratorSettings.first()
            val updated = transform(current)
            settingsRepository.updateNarratorSettings(updated)
            AppLogger.d(TAG, "Narrator settings updated: $updated")
            (parentFragment as? SettingsBottomSheet)?.notifySettingsChanged()
        }
    }

    /**
     * NARRATOR-002: Show dialog to select narrator speaker with preview functionality.
     */
    private fun showNarratorSpeakerDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentSettings = settingsRepository.narratorSettings.first()
            val currentSpeakerId = currentSettings.speakerId

            // Build speaker list
            val speakerCount = VctkSpeakerCatalog.SPEAKER_COUNT
            val speakers = (0 until speakerCount).map { id ->
                val traits = VctkSpeakerCatalog.getTraits(id)
                NarratorSpeakerItem(id, traits?.displayLabel())
            }

            // Create custom dialog with RecyclerView
            val dialogView = layoutInflater.inflate(R.layout.dialog_narrator_speaker_selection, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.speaker_list)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create()

            val adapter = NarratorSpeakerAdapter(
                speakers = speakers,
                currentSelection = currentSpeakerId,
                onSpeakerSelected = { speakerId ->
                    // Update narrator settings with new speaker ID
                    viewLifecycleOwner.lifecycleScope.launch {
                        val updated = currentSettings.copy(speakerId = speakerId)
                        settingsRepository.updateNarratorSettings(updated)
                        // NARRATOR-003: Update global narrator speaker ID for audio generation
                        app.segmentAudioGenerator.setGlobalNarratorSpeakerId(speakerId)
                        updateNarratorSpeakerDisplay(speakerId)
                        AppLogger.d(TAG, "Narrator speaker updated to: $speakerId")
                        (parentFragment as? SettingsBottomSheet)?.notifySettingsChanged()
                    }
                    dialog.dismiss()
                },
                onPreviewClicked = { position, speakerId ->
                    previewNarratorVoice(speakerId, recyclerView.adapter as NarratorSpeakerAdapter, position)
                }
            )
            recyclerView.adapter = adapter

            dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
                stopPreview()
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                stopPreview()
            }

            dialog.show()
        }
    }

    /**
     * NARRATOR-002: Preview narrator voice with current settings.
     */
    private fun previewNarratorVoice(speakerId: Int, adapter: NarratorSpeakerAdapter, position: Int) {
        // Stop any current preview
        stopPreview()

        // Show loading indicator
        adapter.setPreviewingPosition(position, isLoading = true)

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val sampleText = getString(R.string.narrator_preview_text)
                val currentSettings = settingsRepository.narratorSettings.first()

                val result = withContext(Dispatchers.IO) {
                    app.ttsEngine.speak(
                        text = sampleText,
                        voiceProfile = null,
                        onComplete = null,
                        speakerId = speakerId
                    )
                }

                val audioFile = result.getOrNull()
                if (audioFile != null && audioFile.exists()) {
                    // Play the audio
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(audioFile.absolutePath)
                        prepare()
                        setOnCompletionListener {
                            stopPreview()
                            adapter.clearPreviewingPosition()
                        }
                        start()
                    }

                    // Update UI to show playing state
                    adapter.setPreviewingPosition(position, isLoading = false)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(),
                            getString(R.string.narrator_preview_failed, "No audio generated"),
                            Toast.LENGTH_SHORT).show()
                        adapter.clearPreviewingPosition()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error previewing narrator voice", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(),
                        getString(R.string.narrator_preview_failed, e.message),
                        Toast.LENGTH_SHORT).show()
                    adapter.clearPreviewingPosition()
                }
            }
        }
    }

    /**
     * NARRATOR-002: Stop any playing preview.
     */
    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * NARRATOR-002: Data class for narrator speaker item.
     */
    private data class NarratorSpeakerItem(
        val speakerId: Int,
        val traitsLabel: String?
    )

    /**
     * NARRATOR-002: Adapter for narrator speaker selection with preview.
     */
    private inner class NarratorSpeakerAdapter(
        private val speakers: List<NarratorSpeakerItem>,
        private val currentSelection: Int,
        private val onSpeakerSelected: (Int) -> Unit,
        private val onPreviewClicked: (Int, Int) -> Unit
    ) : RecyclerView.Adapter<NarratorSpeakerAdapter.ViewHolder>() {

        private var previewingPosition: Int = -1
        private var isPreviewLoading: Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_narrator_speaker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = speakers[position]

            holder.speakerName.text = "Speaker #${item.speakerId}"
            holder.speakerTraits.text = item.traitsLabel ?: "Unknown"
            holder.speakerTraits.visibility = if (item.traitsLabel != null) View.VISIBLE else View.GONE

            // Selected indicator
            holder.selectedIndicator.visibility = if (item.speakerId == currentSelection) View.VISIBLE else View.GONE

            // Preview button state
            val isPreviewing = position == previewingPosition
            if (isPreviewing && isPreviewLoading) {
                holder.previewButton.visibility = View.GONE
                holder.previewLoading.visibility = View.VISIBLE
            } else if (isPreviewing) {
                holder.previewButton.visibility = View.VISIBLE
                holder.previewButton.setImageResource(R.drawable.ic_stop_circle)
                holder.previewLoading.visibility = View.GONE
            } else {
                holder.previewButton.visibility = View.VISIBLE
                holder.previewButton.setImageResource(R.drawable.ic_play_circle)
                holder.previewLoading.visibility = View.GONE
            }

            // Click handlers
            holder.itemView.setOnClickListener {
                onSpeakerSelected(item.speakerId)
            }

            holder.previewButton.setOnClickListener {
                if (isPreviewing && !isPreviewLoading) {
                    // Stop current preview
                    stopPreview()
                    clearPreviewingPosition()
                } else {
                    onPreviewClicked(position, item.speakerId)
                }
            }
        }

        override fun getItemCount() = speakers.size

        fun setPreviewingPosition(position: Int, isLoading: Boolean) {
            val oldPosition = previewingPosition
            previewingPosition = position
            isPreviewLoading = isLoading
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            notifyItemChanged(position)
        }

        fun clearPreviewingPosition() {
            val oldPosition = previewingPosition
            previewingPosition = -1
            isPreviewLoading = false
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val speakerName: TextView = view.findViewById(R.id.speaker_name)
            val speakerTraits: TextView = view.findViewById(R.id.speaker_traits)
            val selectedIndicator: ImageView = view.findViewById(R.id.selected_indicator)
            val previewButton: ImageButton = view.findViewById(R.id.preview_button)
            val previewLoading: ProgressBar = view.findViewById(R.id.preview_loading)
        }
    }
}

