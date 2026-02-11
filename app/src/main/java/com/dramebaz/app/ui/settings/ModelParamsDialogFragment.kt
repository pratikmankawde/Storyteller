package com.dramebaz.app.ui.settings

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.ModelParamsManager
import com.dramebaz.app.data.models.ModelParams
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

/**
 * Dialog fragment for tuning model parameters.
 * Allows users to adjust temperature, maxTokens, topK, topP, repeatPenalty.
 *
 * The maxTokens slider uses power-of-2 discrete steps (1K, 2K, 4K, 8K, 16K, 32K)
 * for optimal memory alignment with LLM KV-cache.
 */
class ModelParamsDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "ModelParamsDialog"

        /** Power-of-2 steps for maxTokens slider (index-based for discrete slider) */
        private val MAX_TOKENS_STEPS = ModelParams.MAX_TOKENS_STEPS

        fun newInstance(): ModelParamsDialogFragment {
            return ModelParamsDialogFragment()
        }

        /** Convert actual token value to slider index (0-5) */
        private fun tokensToSliderIndex(tokens: Int): Float {
            val index = MAX_TOKENS_STEPS.indexOfFirst { it >= tokens }
            return if (index >= 0) index.toFloat() else (MAX_TOKENS_STEPS.size - 1).toFloat()
        }

        /** Convert slider index to actual token value */
        private fun sliderIndexToTokens(index: Float): Int {
            val idx = index.toInt().coerceIn(0, MAX_TOKENS_STEPS.size - 1)
            return MAX_TOKENS_STEPS[idx]
        }

        /** Format token count for display (e.g., "8K", "16K", "32K") */
        private fun formatTokens(tokens: Int): String {
            return if (tokens >= 1024) "${tokens / 1024}K" else tokens.toString()
        }
    }

    private lateinit var sliderTemperature: Slider
    private lateinit var sliderMaxTokens: Slider
    private lateinit var sliderTopK: Slider
    private lateinit var sliderTopP: Slider
    private lateinit var sliderRepeatPenalty: Slider

    private lateinit var textTemperatureValue: TextView
    private lateinit var textMaxTokensValue: TextView
    private lateinit var textTopKValue: TextView
    private lateinit var textTopPValue: TextView
    private lateinit var textRepeatPenaltyValue: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_model_params, null)

        initViews(view)
        setupMaxTokensSlider()
        loadCurrentParams()
        setupSliderListeners()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.model_params_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ -> saveParams() }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset_defaults) { _, _ -> resetToDefaults() }
            .create()
    }

    private fun initViews(view: View) {
        sliderTemperature = view.findViewById(R.id.slider_temperature)
        sliderMaxTokens = view.findViewById(R.id.slider_max_tokens)
        sliderTopK = view.findViewById(R.id.slider_top_k)
        sliderTopP = view.findViewById(R.id.slider_top_p)
        sliderRepeatPenalty = view.findViewById(R.id.slider_repeat_penalty)

        textTemperatureValue = view.findViewById(R.id.text_temperature_value)
        textMaxTokensValue = view.findViewById(R.id.text_max_tokens_value)
        textTopKValue = view.findViewById(R.id.text_top_k_value)
        textTopPValue = view.findViewById(R.id.text_top_p_value)
        textRepeatPenaltyValue = view.findViewById(R.id.text_repeat_penalty_value)
    }

    /**
     * Configure maxTokens slider to use discrete power-of-2 steps.
     * Slider uses index-based values (0-5) that map to [1K, 2K, 4K, 8K, 16K, 32K].
     */
    private fun setupMaxTokensSlider() {
        sliderMaxTokens.apply {
            valueFrom = 0f
            valueTo = (MAX_TOKENS_STEPS.size - 1).toFloat()
            stepSize = 1f
            // Add label formatter to show actual token values
            setLabelFormatter { value -> formatTokens(sliderIndexToTokens(value)) }
        }
    }

    private fun loadCurrentParams() {
        val params = ModelParamsManager.getParams(requireContext())

        sliderTemperature.value = params.temperature.toFloat()
        // Convert actual tokens to slider index
        sliderMaxTokens.value = tokensToSliderIndex(params.maxTokens)
        sliderTopK.value = params.topK.toFloat()
        sliderTopP.value = params.topP.toFloat()
        sliderRepeatPenalty.value = params.repeatPenalty.toFloat()

        updateValueLabels()
    }

    private fun setupSliderListeners() {
        sliderTemperature.addOnChangeListener { _, _, _ -> updateValueLabels() }
        sliderMaxTokens.addOnChangeListener { _, _, _ -> updateValueLabels() }
        sliderTopK.addOnChangeListener { _, _, _ -> updateValueLabels() }
        sliderTopP.addOnChangeListener { _, _, _ -> updateValueLabels() }
        sliderRepeatPenalty.addOnChangeListener { _, _, _ -> updateValueLabels() }
    }

    private fun updateValueLabels() {
        textTemperatureValue.text = String.format("%.2f", sliderTemperature.value)
        // Show formatted token value with recommendation hint
        val tokens = sliderIndexToTokens(sliderMaxTokens.value)
        val recommended = getRecommendedMaxTokens()
        val hint = if (tokens == recommended) " ✓" else ""
        textMaxTokensValue.text = "${formatTokens(tokens)}$hint"
        textTopKValue.text = sliderTopK.value.toInt().toString()
        textTopPValue.text = String.format("%.2f", sliderTopP.value)
        textRepeatPenaltyValue.text = String.format("%.2f", sliderRepeatPenalty.value)
    }

    /** Get recommended max tokens based on device RAM */
    private fun getRecommendedMaxTokens(): Int {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        return ModelParams.getRecommendedMaxTokens(memInfo.totalMem)
    }

    private fun saveParams() {
        val params = ModelParams(
            temperature = sliderTemperature.value.toDouble(),
            // Convert slider index back to actual token value
            maxTokens = sliderIndexToTokens(sliderMaxTokens.value),
            topK = sliderTopK.value.toInt(),
            topP = sliderTopP.value.toDouble(),
            repeatPenalty = sliderRepeatPenalty.value.toDouble()
        )

        val success = ModelParamsManager.saveParams(requireContext(), params)
        if (success) {
            Toast.makeText(context, R.string.model_params_saved, Toast.LENGTH_SHORT).show()
            AppLogger.i(TAG, "Model params saved: $params")
        } else {
            Toast.makeText(context, R.string.model_params_save_failed, Toast.LENGTH_SHORT).show()
            AppLogger.e(TAG, "Failed to save model params")
        }
    }

    private fun resetToDefaults() {
        val success = ModelParamsManager.resetToDefaults(requireContext())
        if (success) {
            Toast.makeText(context, R.string.model_params_reset, Toast.LENGTH_SHORT).show()
            AppLogger.i(TAG, "Model params reset to defaults")
            // Reload the dialog with new defaults
            loadCurrentParams()
        }
    }
}

