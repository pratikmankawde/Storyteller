package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS Engine implementation for VITS-Piper models.
 * 
 * Uses Sherpa-ONNX with OfflineTtsVitsModelConfig for Piper models
 * that use espeak-ng for phoneme generation.
 * 
 * Example model: en_US-libritts-high (904 speakers)
 */
class VitsPiperTtsEngine(
    context: Context,
    private val vitsPiperConfig: TtsModelConfig.VitsPiper
) : BaseTtsEngine(context, vitsPiperConfig) {
    
    override val tag = "VitsPiperTtsEngine"
    
    private var tts: OfflineTts? = null
    
    override fun init(): Boolean {
        return try {
            if (initialized) {
                AppLogger.d(tag, "Engine already initialized")
                return true
            }
            
            AppLogger.i(tag, "Initializing VITS-Piper engine: ${vitsPiperConfig.displayName}")
            val startTime = System.currentTimeMillis()
            
            // Prepare model files
            val modelFile: File
            val tokensFile: File
            val espeakDataDir: File
            
            if (vitsPiperConfig.isExternal) {
                // External model - use paths directly
                modelFile = File(vitsPiperConfig.modelPath)
                tokensFile = File(vitsPiperConfig.tokensPath)
                espeakDataDir = File(vitsPiperConfig.espeakDataPath)
                
                if (!modelFile.exists() || !tokensFile.exists() || !espeakDataDir.exists()) {
                    AppLogger.e(tag, "External model files not found")
                    reportTtsFailure("External model files not found")
                    return false
                }
            } else {
                // Asset model - copy to internal storage
                val modelDir = File(context.filesDir, "models/tts/${vitsPiperConfig.id}")
                if (!modelDir.exists()) modelDir.mkdirs()
                
                modelFile = copyAssetIfNeeded(vitsPiperConfig.modelPath, 
                    File(modelDir, File(vitsPiperConfig.modelPath).name)) ?: run {
                    reportTtsFailure("Failed to copy model file")
                    return false
                }
                tokensFile = copyAssetIfNeeded(vitsPiperConfig.tokensPath,
                    File(modelDir, "tokens.txt")) ?: run {
                    reportTtsFailure("Failed to copy tokens file")
                    return false
                }
                espeakDataDir = File(modelDir, "espeak-ng-data")
                if (!copyEspeakData(vitsPiperConfig.espeakDataPath, espeakDataDir)) {
                    reportTtsFailure("Failed to copy espeak-ng-data")
                    return false
                }
            }
            
            AppLogger.d(tag, "Model files ready: model=${modelFile.absolutePath}")
            
            // Configure VITS model
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = "",
                tokens = tokensFile.absolutePath,
                dataDir = espeakDataDir.absolutePath
            )
            
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            
            // Try GPU first, fall back to CPU
            for (provider in listOf("gpu", "cpu")) {
                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    matcha = OfflineTtsMatchaModelConfig(),
                    kokoro = OfflineTtsKokoroModelConfig(),
                    kitten = OfflineTtsKittenModelConfig(),
                    numThreads = numThreads,
                    debug = false,
                    provider = provider
                )
                
                try {
                    AppLogger.i(tag, "Trying provider: $provider")
                    tts = OfflineTts(config = OfflineTtsConfig(model = modelConfig))
                    AppLogger.i(tag, "Created TTS with provider: $provider")
                    break
                } catch (e: Exception) {
                    AppLogger.w(tag, "Failed with provider $provider: ${e.message}")
                    if (provider == "cpu") {
                        reportTtsFailure(e.message ?: "Failed to create TTS")
                        return false
                    }
                }
            }
            
            initialized = true
            reportTtsSuccess()
            AppLogger.logPerformance(tag, "VITS-Piper initialization", System.currentTimeMillis() - startTime)
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize", e)
            reportTtsFailure(e.message ?: "Initialization failed")
            false
        }
    }
    
    private fun copyEspeakData(assetPath: String, destDir: File): Boolean {
        return try {
            val testFile = File(destDir, "en_dict")
            if (testFile.exists() && testFile.length() > 0) {
                AppLogger.d(tag, "espeak-ng-data already exists")
                return true
            }
            copyAssetDirectory(assetPath, destDir)
            testFile.exists() && testFile.length() > 0
        } catch (e: Exception) {
            AppLogger.e(tag, "Error copying espeak-ng-data", e)
            false
        }
    }
    
    override fun release() {
        tts = null
        initialized = false
        reportTtsNotInitialized()
        AppLogger.d(tag, "Engine released")
    }
    
    override fun getSpeakerCount(): Int = vitsPiperConfig.speakerCount
    
    override fun getModelInfo(): TtsModelInfo = TtsModelInfo(
        id = vitsPiperConfig.id,
        displayName = vitsPiperConfig.displayName,
        modelType = TtsModelConfig.TYPE_VITS_PIPER,
        speakerCount = vitsPiperConfig.speakerCount,
        sampleRate = vitsPiperConfig.sampleRate,
        isExternal = vitsPiperConfig.isExternal,
        modelPath = vitsPiperConfig.modelPath
    )

    override suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        speakerId: Int?,
        onComplete: (() -> Unit)?
    ): Result<File?> = withContext(Dispatchers.IO) {
        try {
            val currentTts = tts ?: run {
                AppLogger.w(tag, "TTS not initialized, attempting init...")
                if (!init()) {
                    return@withContext Result.failure(Exception("TTS not initialized"))
                }
                tts ?: return@withContext Result.failure(Exception("TTS still null after init"))
            }

            if (text.isBlank()) {
                AppLogger.w(tag, "Empty text, skipping synthesis")
                onComplete?.invoke()
                return@withContext Result.success(null)
            }

            // Extract parameters from voice profile
            // Note: speakerId is passed separately (not part of VoiceProfile)
            val finalSpeakerId = speakerId ?: vitsPiperConfig.defaultSpeakerId
            val speed = voiceProfile?.speed ?: 1.0f
            val energy = voiceProfile?.energy ?: 1.0f

            // Check cache
            val cached = getCachedAudio(text, finalSpeakerId, speed, energy)
            if (cached != null) {
                onComplete?.invoke()
                return@withContext Result.success(cached)
            }

            AppLogger.d(tag, "Synthesizing: \"${text.take(50)}...\" speaker=$finalSpeakerId speed=$speed")
            val startTime = System.currentTimeMillis()

            // Generate audio
            val audio = currentTts.generate(
                text = text,
                sid = finalSpeakerId,
                speed = speed
            )

            if (audio.samples.isEmpty()) {
                AppLogger.w(tag, "TTS generated empty audio")
                onComplete?.invoke()
                return@withContext Result.success(null)
            }

            // Apply energy scaling
            val processedSamples = applyEnergyScaling(audio.samples, energy)

            // Save to file
            val fileName = "tts_${System.currentTimeMillis()}"
            val audioFile = saveAudioToFile(processedSamples, audio.sampleRate, fileName)

            // Cache the result
            val cachedFile = cacheAudio(audioFile, text, finalSpeakerId, speed, energy)

            AppLogger.logPerformance(tag, "Speech synthesis", System.currentTimeMillis() - startTime)
            onComplete?.invoke()
            Result.success(cachedFile)
        } catch (e: Exception) {
            AppLogger.e(tag, "Speech synthesis failed", e)
            onComplete?.invoke()
            Result.failure(e)
        }
    }
}

