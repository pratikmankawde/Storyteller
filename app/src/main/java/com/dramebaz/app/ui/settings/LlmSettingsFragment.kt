package com.dramebaz.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.data.models.LlmBackend
import com.dramebaz.app.data.models.LlmModelType
import com.dramebaz.app.data.models.LlmSettings
import com.dramebaz.app.domain.usecases.AnalysisQueueManager
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LLM Settings tab fragment.
 * Allows users to select LLM model and CPU/GPU backend.
 */
class LlmSettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "LlmSettingsFragment"
    }
    
    private val app by lazy { requireActivity().application as DramebazApplication }
    private val settingsRepository by lazy { app.settingsRepository }
    
    private lateinit var textModelStatus: TextView
    private lateinit var textBackendStatus: TextView
    private lateinit var textAvailableModels: TextView
    private lateinit var toggleBackend: MaterialButtonToggleGroup
    private lateinit var dropdownModelFile: AutoCompleteTextView
    private lateinit var btnTuneParams: MaterialButton
    private lateinit var btnApply: MaterialButton

    private var pendingBackend: LlmBackend = LlmBackend.GPU
    private var pendingModelPath: String? = null

    // Cache discovered models for dropdown
    private var discoveredModels: List<LlmModelFactory.DiscoveredModel> = emptyList()

    // Store original icon to restore after loading
    private var originalButtonIcon: android.graphics.drawable.Drawable? = null
    private var originalButtonText: String? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_tab_llm, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textModelStatus = view.findViewById(R.id.text_model_status)
        textBackendStatus = view.findViewById(R.id.text_backend_status)
        textAvailableModels = view.findViewById(R.id.text_available_models)
        toggleBackend = view.findViewById(R.id.toggle_backend)
        dropdownModelFile = view.findViewById(R.id.dropdown_model_file)
        btnTuneParams = view.findViewById(R.id.btn_tune_params)
        btnApply = view.findViewById(R.id.btn_apply)

        loadCurrentSettings()
        setupListeners()
        updateModelStatus()
        checkAvailableModels()
    }
    
    private fun loadCurrentSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.llmSettings.first()
            pendingBackend = settings.preferredBackend
            pendingModelPath = settings.selectedModelPath

            // Set backend toggle
            val backendBtnId = when (settings.preferredBackend) {
                LlmBackend.GPU -> R.id.btn_backend_gpu
                LlmBackend.CPU -> R.id.btn_backend_cpu
            }
            toggleBackend.check(backendBtnId)

            // Dropdown will be populated by checkAvailableModels() which runs after this
        }
    }

    private fun setupListeners() {
        // Backend toggle listener
        toggleBackend.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                pendingBackend = when (checkedId) {
                    R.id.btn_backend_gpu -> LlmBackend.GPU
                    R.id.btn_backend_cpu -> LlmBackend.CPU
                    else -> LlmBackend.GPU
                }
            }
        }
        
        btnApply.setOnClickListener {
            applyAndReloadModel()
        }

        btnTuneParams.setOnClickListener {
            openModelParamsDialog()
        }

        // Dropdown selection listener
        dropdownModelFile.setOnItemClickListener { _, _, position, _ ->
            pendingModelPath = if (position == 0) {
                // "Auto" selected - clear path to use first available
                null
            } else {
                // Specific model selected - get its path
                discoveredModels.getOrNull(position - 1)?.path
            }
            AppLogger.d(TAG, "Model file selected: position=$position, path=$pendingModelPath")
        }
    }

    private fun openModelParamsDialog() {
        val dialog = ModelParamsDialogFragment.newInstance()
        dialog.show(childFragmentManager, "ModelParamsDialog")
    }
    
    private fun updateModelStatus() {
        val isReady = LlmService.isReady()
        val provider = LlmService.getExecutionProvider()

        if (isReady) {
            // Get the actual loaded model name from ModelCapabilities
            val capabilities = LlmService.getModelCapabilities()
            textModelStatus.text = capabilities.modelName
        } else {
            textModelStatus.text = getString(R.string.llm_status_not_loaded)
        }

        textBackendStatus.text = "Backend: $provider"
    }
    
    private fun checkAvailableModels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()

            // Force rescan to detect any newly added models
            LlmModelFactory.clearCache()

            // Discover all available models
            discoveredModels = withContext(Dispatchers.IO) {
                LlmModelFactory.discoverAllModels(ctx, forceRescan = true)
            }

            // Build dropdown items: "Auto" + discovered model filenames with size
            val dropdownItems = mutableListOf(getString(R.string.llm_auto_select))
            dropdownItems.addAll(discoveredModels.map { model ->
                val sizeMb = model.sizeBytes / (1024 * 1024)
                val typeLabel = when (model.type) {
                    LlmModelFactory.ModelType.LITERTLM -> "LiteRT"
                    LlmModelFactory.ModelType.GGUF -> "GGUF"
                    LlmModelFactory.ModelType.MEDIAPIPE -> "MediaPipe"
                    LlmModelFactory.ModelType.REMOTE_SERVER -> "Remote"
                }
                "${model.fileName} ($sizeMb MB, $typeLabel)"
            })

            // Set up the dropdown adapter
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, dropdownItems)
            dropdownModelFile.setAdapter(adapter)

            // Set current selection based on pendingModelPath
            val currentIndex = if (pendingModelPath.isNullOrEmpty()) {
                0 // Auto
            } else {
                val modelIndex = discoveredModels.indexOfFirst { it.path == pendingModelPath }
                if (modelIndex >= 0) modelIndex + 1 else 0
            }
            if (currentIndex < dropdownItems.size) {
                dropdownModelFile.setText(dropdownItems[currentIndex], false)
            }

            // Get the description of all discovered models for the info text
            val description = withContext(Dispatchers.IO) {
                LlmModelFactory.getDiscoveredModelsDescription(ctx)
            }
            textAvailableModels.text = description
        }
    }

    private fun applyAndReloadModel() {
        // Auto-detect engine type based on selected model file extension
        val autoDetectedModelType = getEngineTypeForModel(pendingModelPath)
        val newSettings = LlmSettings(autoDetectedModelType, pendingBackend, pendingModelPath)

        // Show loading state on button
        showButtonLoading(true)
        textModelStatus.text = getString(R.string.llm_reloading)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Pause any running analysis before changing LLM settings
                AppLogger.i(TAG, "Pausing analysis before LLM settings change...")
                AnalysisQueueManager.pauseAnalysis()

                // Wait for any in-flight LLM inference to complete before releasing the engine
                // Native LLM inference cannot be cancelled, so we need to wait for it to finish
                AppLogger.d(TAG, "Waiting for in-flight LLM inference to complete...")
                LlmService.waitForInferenceCompletion(5000L)

                // Save settings first
                settingsRepository.updateLlmSettings(newSettings)
                AppLogger.i(TAG, "Applying LLM settings: engine=${autoDetectedModelType}, backend=${pendingBackend}, path=${pendingModelPath}")

                // Reload model with new settings
                val success = withContext(Dispatchers.IO) {
                    LlmService.reloadWithSettings(requireContext(), newSettings)
                }

                if (success) {
                    Toast.makeText(context, R.string.llm_reload_success, Toast.LENGTH_SHORT).show()
                    AppLogger.i(TAG, "LLM model reloaded successfully")

                    // Update status text with loaded model info
                    updateModelStatusWithLoadedModel()
                } else {
                    Toast.makeText(context, R.string.llm_reload_failed, Toast.LENGTH_SHORT).show()
                    AppLogger.w(TAG, "LLM model reload failed")
                    updateModelStatus()
                }

                (parentFragment as? SettingsBottomSheet)?.notifySettingsChanged()

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reloading LLM model", e)
                Toast.makeText(context, R.string.llm_reload_failed, Toast.LENGTH_SHORT).show()
                updateModelStatus()
            } finally {
                // Always resume analysis (will use new model if loaded, or fallback)
                AppLogger.i(TAG, "Resuming analysis after LLM settings change...")
                AnalysisQueueManager.resumeAnalysis()
                showButtonLoading(false)
            }
        }
    }

    /**
     * Show/hide loading indicator on the Apply button.
     */
    private fun showButtonLoading(loading: Boolean) {
        if (loading) {
            // Save original state
            originalButtonIcon = btnApply.icon
            originalButtonText = btnApply.text.toString()

            // Create circular progress indicator
            val spec = CircularProgressIndicatorSpec(
                requireContext(),
                null,
                0,
                com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall
            )
            val progressDrawable = IndeterminateDrawable.createCircularDrawable(requireContext(), spec)
            progressDrawable.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.on_primary),
                android.graphics.PorterDuff.Mode.SRC_IN
            )

            btnApply.icon = progressDrawable
            btnApply.text = getString(R.string.llm_loading_model)
            btnApply.isEnabled = false
        } else {
            // Restore original state
            btnApply.icon = originalButtonIcon
            btnApply.text = originalButtonText ?: getString(R.string.llm_apply_and_reload)
            btnApply.isEnabled = true
        }
    }

    /**
     * Update model status text with the loaded model name.
     */
    private fun updateModelStatusWithLoadedModel() {
        // Just call updateModelStatus which now uses ModelCapabilities
        updateModelStatus()
    }

    /**
     * Auto-detect engine type based on model file extension.
     * .litertlm -> LITERTLM engine
     * .gguf -> GGUF engine
     */
    private fun getEngineTypeForModel(modelPath: String?): LlmModelType {
        if (modelPath.isNullOrEmpty()) {
            return LlmModelType.AUTO
        }
        return when {
            modelPath.endsWith(".litertlm", ignoreCase = true) -> LlmModelType.LITERTLM
            modelPath.endsWith(".gguf", ignoreCase = true) -> LlmModelType.GGUF
            else -> LlmModelType.AUTO
        }
    }
}

