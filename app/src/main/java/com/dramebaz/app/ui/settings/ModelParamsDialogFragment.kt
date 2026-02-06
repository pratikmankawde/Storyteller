package com.dramebaz.app.ui.settings

import android.app.Dialog
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
 */
class ModelParamsDialogFragment : DialogFragment() {
    
    companion object {
        private const val TAG = "ModelParamsDialog"
        
        fun newInstance(): ModelParamsDialogFragment {
            return ModelParamsDialogFragment()
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
    
    private fun loadCurrentParams() {
        val params = ModelParamsManager.getParams(requireContext())
        
        sliderTemperature.value = params.temperature.toFloat()
        sliderMaxTokens.value = params.maxTokens.toFloat()
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
        textMaxTokensValue.text = sliderMaxTokens.value.toInt().toString()
        textTopKValue.text = sliderTopK.value.toInt().toString()
        textTopPValue.text = String.format("%.2f", sliderTopP.value)
        textRepeatPenaltyValue.text = String.format("%.2f", sliderRepeatPenalty.value)
    }
    
    private fun saveParams() {
        val params = ModelParams(
            temperature = sliderTemperature.value.toDouble(),
            maxTokens = sliderMaxTokens.value.toInt(),
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
        }
    }
}

