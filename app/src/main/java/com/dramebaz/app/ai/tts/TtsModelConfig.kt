package com.dramebaz.app.ai.tts

/**
 * Sealed class hierarchy for TTS model configurations.
 * 
 * Each model type has its own configuration class with the specific
 * parameters needed for that model architecture.
 * 
 * Supports both asset-based models (bundled with app) and external
 * models (loaded from device storage).
 */
sealed class TtsModelConfig {
    /** Unique identifier for this model configuration */
    abstract val id: String
    
    /** Human-readable display name */
    abstract val displayName: String
    
    /** Whether this model is loaded from external storage */
    abstract val isExternal: Boolean
    
    /** Audio sample rate in Hz */
    abstract val sampleRate: Int
    
    /**
     * VITS-Piper model configuration.
     * Used for Piper TTS models with espeak-ng phoneme backend.
     * 
     * Example: en_US-libritts-high (904 speakers)
     */
    data class VitsPiper(
        override val id: String,
        override val displayName: String,
        override val isExternal: Boolean = false,
        override val sampleRate: Int = 22050,
        
        /** Path to the ONNX model file */
        val modelPath: String,
        
        /** Path to the tokens.txt file */
        val tokensPath: String,
        
        /** Path to the espeak-ng-data directory */
        val espeakDataPath: String,
        
        /** Number of speakers in the model */
        val speakerCount: Int = 904,
        
        /** Default speaker ID */
        val defaultSpeakerId: Int = 0
    ) : TtsModelConfig()
    
    /**
     * Kokoro model configuration.
     * Used for Kokoro TTS models with voice embeddings.
     * 
     * Example: kokoro-int8-en-v0_19
     */
    data class Kokoro(
        override val id: String,
        override val displayName: String,
        override val isExternal: Boolean = false,
        override val sampleRate: Int = 24000,
        
        /** Path to the ONNX model file */
        val modelPath: String,
        
        /** Path to the tokens.txt file */
        val tokensPath: String,
        
        /** Path to the voices.bin file */
        val voicesPath: String,
        
        /** Path to the espeak-ng-data directory */
        val espeakDataPath: String,
        
        /** Number of voices in the model */
        val voiceCount: Int = 1,
        
        /** Default voice ID */
        val defaultVoiceId: Int = 0,
        
        /** Voice speed (0.5 to 2.0, 1.0 = normal) */
        val defaultSpeed: Float = 1.0f
    ) : TtsModelConfig()
    
    companion object {
        /** Model type identifier for VITS-Piper */
        const val TYPE_VITS_PIPER = "vits-piper"
        
        /** Model type identifier for Kokoro */
        const val TYPE_KOKORO = "kokoro"
    }
}

/**
 * JSON-serializable representation of TTS model configuration.
 * Used for loading model configs from tts_model_config.json.
 */
data class TtsModelConfigJson(
    val id: String,
    val displayName: String,
    val modelType: String,
    val isExternal: Boolean = false,
    val sampleRate: Int = 22050,
    val modelPath: String,
    val tokensPath: String,
    val espeakDataPath: String = "",
    val voicesPath: String = "",
    val speakerCount: Int = 1,
    val defaultSpeakerId: Int = 0,
    val defaultSpeed: Float = 1.0f
) {
    /**
     * Convert to the appropriate TtsModelConfig subclass.
     */
    fun toModelConfig(): TtsModelConfig {
        return when (modelType) {
            TtsModelConfig.TYPE_VITS_PIPER -> TtsModelConfig.VitsPiper(
                id = id,
                displayName = displayName,
                isExternal = isExternal,
                sampleRate = sampleRate,
                modelPath = modelPath,
                tokensPath = tokensPath,
                espeakDataPath = espeakDataPath,
                speakerCount = speakerCount,
                defaultSpeakerId = defaultSpeakerId
            )
            TtsModelConfig.TYPE_KOKORO -> TtsModelConfig.Kokoro(
                id = id,
                displayName = displayName,
                isExternal = isExternal,
                sampleRate = sampleRate,
                modelPath = modelPath,
                tokensPath = tokensPath,
                voicesPath = voicesPath,
                espeakDataPath = espeakDataPath,
                voiceCount = speakerCount,
                defaultVoiceId = defaultSpeakerId,
                defaultSpeed = defaultSpeed
            )
            else -> throw IllegalArgumentException("Unknown model type: $modelType")
        }
    }
}

