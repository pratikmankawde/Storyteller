package com.dramebaz.app.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AUG-041: Manages graceful degradation state for AI models.
 * Tracks which models are running in degraded mode (stub/fallback) and provides
 * user-friendly messages about degraded functionality.
 */
object DegradedModeManager {
    private const val TAG = "DegradedModeManager"

    /**
     * Represents the operational mode of the LLM.
     */
    enum class LlmMode {
        /** Full ONNX model is loaded and operational */
        ONNX_FULL,
        /** Using stub/fallback analysis (basic rule-based extraction) */
        STUB_FALLBACK,
        /** Not yet initialized */
        NOT_INITIALIZED
    }

    /**
     * Represents the operational mode of the TTS engine.
     */
    enum class TtsMode {
        /** Full TTS engine is loaded and operational */
        FULL,
        /** TTS failed to initialize or load */
        DISABLED,
        /** Not yet initialized */
        NOT_INITIALIZED
    }

    // LLM state
    private val _llmMode = MutableStateFlow(LlmMode.NOT_INITIALIZED)
    val llmMode: StateFlow<LlmMode> = _llmMode.asStateFlow()

    private var _llmFailureReason: String? = null
    val llmFailureReason: String? get() = _llmFailureReason

    // TTS state
    private val _ttsMode = MutableStateFlow(TtsMode.NOT_INITIALIZED)
    val ttsMode: StateFlow<TtsMode> = _ttsMode.asStateFlow()

    private var _ttsFailureReason: String? = null
    val ttsFailureReason: String? get() = _ttsFailureReason

    // Overall degraded mode
    val isDegraded: Boolean
        get() = _llmMode.value == LlmMode.STUB_FALLBACK || _ttsMode.value == TtsMode.DISABLED

    /**
     * Update LLM mode and optionally set failure reason.
     */
    fun setLlmMode(mode: LlmMode, failureReason: String? = null) {
        _llmMode.value = mode
        _llmFailureReason = failureReason
        if (mode != LlmMode.ONNX_FULL && failureReason != null) {
            AppLogger.w(TAG, "LLM running in degraded mode: $mode, reason: $failureReason")
        } else if (mode == LlmMode.ONNX_FULL) {
            AppLogger.i(TAG, "LLM running in full mode (ONNX)")
        }
    }

    /**
     * Update TTS mode and optionally set failure reason.
     */
    fun setTtsMode(mode: TtsMode, failureReason: String? = null) {
        _ttsMode.value = mode
        _ttsFailureReason = failureReason
        if (mode != TtsMode.FULL && failureReason != null) {
            AppLogger.w(TAG, "TTS running in degraded mode: $mode, reason: $failureReason")
        } else if (mode == TtsMode.FULL) {
            AppLogger.i(TAG, "TTS running in full mode")
        }
    }

    /**
     * Get a user-friendly status message about the current mode.
     */
    fun getStatusMessage(): String? {
        val messages = mutableListOf<String>()
        
        when (_llmMode.value) {
            LlmMode.STUB_FALLBACK -> messages.add("AI analysis using basic mode")
            LlmMode.NOT_INITIALIZED -> {} // Don't show anything if not initialized
            LlmMode.ONNX_FULL -> {}
        }
        
        when (_ttsMode.value) {
            TtsMode.DISABLED -> messages.add("Voice playback unavailable")
            TtsMode.NOT_INITIALIZED -> {}
            TtsMode.FULL -> {}
        }
        
        return if (messages.isEmpty()) null else messages.joinToString(". ")
    }

    /**
     * Get a detailed user-friendly message for degraded LLM mode.
     */
    fun getLlmDegradedMessage(): String {
        return when (_llmMode.value) {
            LlmMode.STUB_FALLBACK -> 
                "The AI model couldn't be loaded. Character and dialog detection " +
                "is using basic text analysis, which may be less accurate."
            LlmMode.NOT_INITIALIZED -> "AI model is initializing..."
            LlmMode.ONNX_FULL -> "AI model is fully operational."
        }
    }

    /**
     * Get a detailed user-friendly message for degraded TTS mode.
     */
    fun getTtsDegradedMessage(): String {
        return when (_ttsMode.value) {
            TtsMode.DISABLED -> 
                "Voice synthesis couldn't be loaded. You can still read the text, " +
                "but audio playback is unavailable."
            TtsMode.NOT_INITIALIZED -> "Voice synthesis is initializing..."
            TtsMode.FULL -> "Voice synthesis is fully operational."
        }
    }

    /**
     * Reset all modes to not initialized (for testing or app restart).
     */
    fun reset() {
        _llmMode.value = LlmMode.NOT_INITIALIZED
        _ttsMode.value = TtsMode.NOT_INITIALIZED
        _llmFailureReason = null
        _ttsFailureReason = null
    }
}

