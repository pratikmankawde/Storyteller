package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS Engine implementation for Kokoro models.
 * 
 * Uses Sherpa-ONNX with OfflineTtsKokoroModelConfig for Kokoro models
 * that use voice embeddings for speaker selection.
 * 
 * Example model: kokoro-int8-en-v0_19
 */
class KokoroTtsEngine(
    context: Context,
    private val kokoroConfig: TtsModelConfig.Kokoro
) : BaseTtsEngine(context, kokoroConfig) {
    
    override val tag = "KokoroTtsEngine"
    
    private var tts: OfflineTts? = null
    
    override fun init(): Boolean {
        return try {
            if (initialized) {
                AppLogger.d(tag, "Engine already initialized")
                return true
            }
            
            AppLogger.i(tag, "Initializing Kokoro engine: ${kokoroConfig.displayName}")
            val startTime = System.currentTimeMillis()
            
            // Prepare model files
            val modelFile: File
            val tokensFile: File
            val voicesFile: File
            val espeakDataDir: File
            
            if (kokoroConfig.isExternal) {
                // External model - use paths directly
                modelFile = File(kokoroConfig.modelPath)
                tokensFile = File(kokoroConfig.tokensPath)
                voicesFile = File(kokoroConfig.voicesPath)
                espeakDataDir = File(kokoroConfig.espeakDataPath)
                
                if (!modelFile.exists()) {
                    AppLogger.e(tag, "Model file not found: ${modelFile.absolutePath}")
                    reportTtsFailure("Model file not found")
                    return false
                }
                if (!tokensFile.exists()) {
                    AppLogger.e(tag, "Tokens file not found: ${tokensFile.absolutePath}")
                    reportTtsFailure("Tokens file not found")
                    return false
                }
                if (!voicesFile.exists()) {
                    AppLogger.e(tag, "Voices file not found: ${voicesFile.absolutePath}")
                    reportTtsFailure("Voices file not found")
                    return false
                }
                if (!espeakDataDir.exists()) {
                    AppLogger.e(tag, "espeak-ng-data not found: ${espeakDataDir.absolutePath}")
                    reportTtsFailure("espeak-ng-data not found")
                    return false
                }
            } else {
                // Asset model - copy to internal storage
                val modelDir = File(context.filesDir, "models/tts/${kokoroConfig.id}")
                if (!modelDir.exists()) modelDir.mkdirs()
                
                modelFile = copyAssetIfNeeded(kokoroConfig.modelPath, 
                    File(modelDir, File(kokoroConfig.modelPath).name)) ?: run {
                    reportTtsFailure("Failed to copy model file")
                    return false
                }
                tokensFile = copyAssetIfNeeded(kokoroConfig.tokensPath,
                    File(modelDir, "tokens.txt")) ?: run {
                    reportTtsFailure("Failed to copy tokens file")
                    return false
                }
                voicesFile = copyAssetIfNeeded(kokoroConfig.voicesPath,
                    File(modelDir, "voices.bin")) ?: run {
                    reportTtsFailure("Failed to copy voices file")
                    return false
                }
                espeakDataDir = File(modelDir, "espeak-ng-data")
                if (!copyEspeakData(kokoroConfig.espeakDataPath, espeakDataDir)) {
                    reportTtsFailure("Failed to copy espeak-ng-data")
                    return false
                }
            }
            
            AppLogger.d(tag, "Model files ready:")
            AppLogger.d(tag, "  model: ${modelFile.absolutePath}")
            AppLogger.d(tag, "  tokens: ${tokensFile.absolutePath}")
            AppLogger.d(tag, "  voices: ${voicesFile.absolutePath}")
            AppLogger.d(tag, "  espeak: ${espeakDataDir.absolutePath}")
            
            // Configure Kokoro model
            val kokoroModelConfig = OfflineTtsKokoroModelConfig(
                model = modelFile.absolutePath,
                voices = voicesFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = espeakDataDir.absolutePath
            )
            
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            
            // Try GPU first, fall back to CPU
            for (provider in listOf("gpu", "cpu")) {
                val modelConfig = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(),
                    matcha = OfflineTtsMatchaModelConfig(),
                    kokoro = kokoroModelConfig,
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
            AppLogger.logPerformance(tag, "Kokoro initialization", System.currentTimeMillis() - startTime)
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

    override fun getSpeakerCount(): Int = kokoroConfig.voiceCount

    override fun getModelInfo(): TtsModelInfo = TtsModelInfo(
        id = kokoroConfig.id,
        displayName = kokoroConfig.displayName,
        modelType = TtsModelConfig.TYPE_KOKORO,
        speakerCount = kokoroConfig.voiceCount,
        sampleRate = kokoroConfig.sampleRate,
        isExternal = kokoroConfig.isExternal,
        modelPath = kokoroConfig.modelPath
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
            val finalVoiceId = speakerId ?: kokoroConfig.defaultVoiceId
            val speed = voiceProfile?.speed ?: kokoroConfig.defaultSpeed
            val energy = voiceProfile?.energy ?: 1.0f

            // Check cache
            val cached = getCachedAudio(text, finalVoiceId, speed, energy)
            if (cached != null) {
                onComplete?.invoke()
                return@withContext Result.success(cached)
            }

            AppLogger.d(tag, "Synthesizing: \"${text.take(50)}...\" voice=$finalVoiceId speed=$speed")
            val startTime = System.currentTimeMillis()

            // Generate audio with Kokoro
            val audio = currentTts.generate(
                text = text,
                sid = finalVoiceId,
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
            val fileName = "kokoro_${System.currentTimeMillis()}"
            val audioFile = saveAudioToFile(processedSamples, audio.sampleRate, fileName)

            // Cache the result
            val cachedFile = cacheAudio(audioFile, text, finalVoiceId, speed, energy)

            AppLogger.logPerformance(tag, "Kokoro synthesis", System.currentTimeMillis() - startTime)
            onComplete?.invoke()
            Result.success(cachedFile)
        } catch (e: Exception) {
            AppLogger.e(tag, "Kokoro synthesis failed", e)
            onComplete?.invoke()
            Result.failure(e)
        }
    }
}

