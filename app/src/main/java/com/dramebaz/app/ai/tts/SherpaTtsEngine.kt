package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * SherpaTTS implementation using the official Sherpa-ONNX SDK with VITS-Piper en_GB-vctk-medium model.
 * This Piper model uses espeak-ng for phoneme generation and supports 109 speakers (ID 0-108).
 */
class SherpaTtsEngine(private val context: Context) {
    
    private var tts: OfflineTts? = null
    private var initialized = false
    private val tag = "SherpaTtsEngine"
    
    // Model paths in assets (vits-piper-en_GB-vctk-medium)
    private val modelAssetPath = "models/tts/sherpa/en_GB-vctk-medium.onnx"
    private val tokensAssetPath = "models/tts/sherpa/tokens.txt"
    private val espeakDataAssetPath = "models/tts/sherpa/espeak-ng-data"
    
    // Model configuration
    private val sampleRate = 22050
    private val defaultSpeakerId = 0 // VCTK has 109 speakers (0-108)
    
    fun init(): Boolean {
        return try {
            if (initialized) {
                AppLogger.d(tag, "SherpaTTS engine already initialized")
                return true
            }
            
            AppLogger.i(tag, "Initializing SherpaTTS engine with vits-piper-en_GB-vctk-medium model...")
            val startTime = System.currentTimeMillis()
            
            // Copy model files from assets to internal storage
            val modelDir = File(context.filesDir, "models/tts/sherpa")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            val modelFile = copyAssetIfNeeded(modelAssetPath, File(modelDir, "en_GB-vctk-medium.onnx"))
            val tokensFile = copyAssetIfNeeded(tokensAssetPath, File(modelDir, "tokens.txt"))
            
            // Copy espeak-ng-data directory recursively
            val espeakDataDir = File(modelDir, "espeak-ng-data")
            if (!copyEspeakDataIfNeeded(espeakDataDir)) {
                AppLogger.e(tag, "Failed to copy espeak-ng-data directory from assets")
                return false
            }
            
            if (modelFile == null || tokensFile == null) {
                AppLogger.e(tag, "Failed to copy required model files from assets")
                return false
            }
            
            AppLogger.d(tag, "Model files ready:")
            AppLogger.d(tag, "  Model: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            AppLogger.d(tag, "  Tokens: ${tokensFile.absolutePath} (${tokensFile.length()} bytes)")
            AppLogger.d(tag, "  ESpeak Data: ${espeakDataDir.absolutePath}")
            
            // Configure VITS-Piper model with espeak-ng-data (NOT lexicon)
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = "",  // Not used for Piper models
                tokens = tokensFile.absolutePath,
                dataDir = espeakDataDir.absolutePath  // espeak-ng-data for phoneme generation
            )
            
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = numThreads,
                debug = false
            )
            
            val ttsConfig = OfflineTtsConfig(model = modelConfig)
            
            try {
                tts = OfflineTts(config = ttsConfig)
                AppLogger.d(tag, "Sherpa-ONNX TTS (vits-piper-en_GB-vctk-medium) created successfully")
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to create Sherpa-ONNX TTS", e)
                return false
            }
            
            initialized = true
            AppLogger.logPerformance(tag, "SherpaTTS vits-piper-en_GB-vctk-medium initialization", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "SherpaTTS engine (vits-piper-en_GB-vctk-medium) initialized successfully")
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize SherpaTTS engine", e)
            false
        }
    }
    
    fun isInitialized(): Boolean = initialized
    
    suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)? = null,
        speakerId: Int? = null  // Optional speaker ID (0-108 for VCTK)
    ): Result<File?> = withContext(Dispatchers.IO) {
        // Handle empty text gracefully
        if (text.isBlank()) {
            AppLogger.w(tag, "Empty text provided, returning failure")
            return@withContext Result.failure(IllegalArgumentException("Text cannot be empty"))
        }
        
        if (!initialized && !init()) {
            AppLogger.e(tag, "TTS engine not initialized")
            return@withContext Result.failure(Exception("TTS engine not initialized"))
        }
        
        val ttsInstance = tts
        if (ttsInstance == null) {
            AppLogger.e(tag, "TTS instance is null")
            return@withContext Result.failure(Exception("TTS instance not available"))
        }
        
        val startTime = System.currentTimeMillis()
        try {
            AppLogger.d(tag, "Synthesizing speech (vits-piper): textLength=${text.length}, " +
                    "textPreview=\"${text.take(50)}...\"")
            
            // Get TTS parameters from voice profile
            val params = VoiceProfileMapper.toTtsParams(voiceProfile).copy(speakerId = speakerId)
            AppLogger.d(tag, "TTS params: pitch=${params.pitch}, speed=${params.speed}, speakerId=${params.speakerId ?: defaultSpeakerId}")
            
            // Use speaker ID from params, voice profile, or default
            val sid = params.speakerId ?: defaultSpeakerId
            
            // Speed: 1.0 = normal, >1.0 = faster, <1.0 = slower
            val speed = params.speed.coerceIn(0.5f, 2.0f)
            
            // Generate audio using Sherpa-ONNX SDK
            val audio = ttsInstance.generate(
                text = text,
                sid = sid,
                speed = speed
            )
            
            AppLogger.d(tag, "Audio generated: ${audio.samples.size} samples, sampleRate=${audio.sampleRate}")
            
            // Verify audio data is valid
            if (audio.samples.isEmpty()) {
                AppLogger.w(tag, "Generated audio has no samples")
                return@withContext Result.failure(Exception("Failed to generate audio - no samples"))
            }
            
            // Check if audio is all zeros (silent)
            val nonZeroCount = audio.samples.count { it != 0f }
            if (nonZeroCount == 0) {
                AppLogger.w(tag, "Generated audio is completely silent")
                return@withContext Result.failure(Exception("Failed to generate audio - silent output"))
            }
            
            // Save audio to file
            val outputFile = saveAudioToFile(audio.samples, audio.sampleRate, text.hashCode().toString())
            AppLogger.logPerformance(tag, "TTS synthesis (vits-piper)", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "Speech synthesis complete: ${outputFile?.absolutePath}, size=${outputFile?.length()} bytes")
            
            onComplete?.invoke()
            Result.success(outputFile)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error during speech synthesis", e)
            Result.failure(e)
        }
    }
    
    fun stop() {
        // Stop any ongoing synthesis
        AppLogger.d(tag, "Stop requested")
    }
    
    /**
     * Copy asset file to internal storage if it doesn't exist or is empty.
     */
    private fun copyAssetIfNeeded(assetPath: String, destFile: File): File? {
        return try {
            // Check if file already exists with valid size
            if (destFile.exists() && destFile.length() > 0) {
                AppLogger.d(tag, "Model file already exists: ${destFile.absolutePath}")
                return destFile
            }
            
            // Ensure parent directory exists
            destFile.parentFile?.mkdirs()
            
            // Copy from assets
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            if (destFile.exists() && destFile.length() > 0) {
                AppLogger.d(tag, "Copied asset $assetPath to ${destFile.absolutePath}")
                destFile
            } else {
                AppLogger.e(tag, "Failed to copy asset $assetPath - file is empty or missing")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error copying asset $assetPath", e)
            null
        }
    }
    
    /**
     * Copy espeak-ng-data directory recursively from assets to internal storage.
     * This is required for Piper/VITS models that use espeak for phoneme generation.
     */
    private fun copyEspeakDataIfNeeded(destDir: File): Boolean {
        return try {
            // Check if espeak-ng-data already copied (check for a key file)
            val testFile = File(destDir, "en_dict")
            if (testFile.exists() && testFile.length() > 0) {
                AppLogger.d(tag, "espeak-ng-data already exists: ${destDir.absolutePath}")
                return true
            }
            
            AppLogger.d(tag, "Copying espeak-ng-data from assets...")
            copyAssetDirectory(espeakDataAssetPath, destDir)
            
            // Verify copy succeeded
            if (testFile.exists() && testFile.length() > 0) {
                AppLogger.d(tag, "espeak-ng-data copied successfully to ${destDir.absolutePath}")
                true
            } else {
                AppLogger.e(tag, "espeak-ng-data copy verification failed")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error copying espeak-ng-data", e)
            false
        }
    }
    
    /**
     * Recursively copy a directory from assets to internal storage.
     */
    private fun copyAssetDirectory(assetPath: String, destDir: File) {
        val assetManager = context.assets
        
        // Create destination directory
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        
        // List all files/directories in the asset path
        val files = assetManager.list(assetPath) ?: return
        
        for (fileName in files) {
            val assetFilePath = "$assetPath/$fileName"
            val destFile = File(destDir, fileName)
            
            // Check if it's a directory by trying to list its contents
            val subFiles = assetManager.list(assetFilePath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                // It's a directory, recurse
                copyAssetDirectory(assetFilePath, destFile)
            } else {
                // It's a file, copy it
                try {
                    assetManager.open(assetFilePath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    // Some entries might be directories that appear empty, ignore errors
                    AppLogger.w(tag, "Could not copy $assetFilePath: ${e.message}")
                }
            }
        }
    }
    
    private fun saveAudioToFile(samples: FloatArray, sampleRate: Int, fileName: String): File {
        val outputDir = File(context.cacheDir, "tts_audio")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        val outputFile = File(outputDir, "$fileName.wav")
        
        // Convert float array to WAV file
        val numSamples = samples.size
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = numSamples * numChannels * bitsPerSample / 8
        val fileSize = 36 + dataSize
        
        FileOutputStream(outputFile).use { out ->
            // WAV header
            out.write("RIFF".toByteArray())
            out.write(intToBytes(fileSize), 0, 4)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16), 0, 4) // fmt chunk size
            out.write(shortToBytes(1), 0, 2) // audio format (PCM)
            out.write(shortToBytes(numChannels.toShort()), 0, 2)
            out.write(intToBytes(sampleRate), 0, 4)
            out.write(intToBytes(byteRate), 0, 4)
            out.write(shortToBytes(blockAlign.toShort()), 0, 2)
            out.write(shortToBytes(bitsPerSample.toShort()), 0, 2)
            out.write("data".toByteArray())
            out.write(intToBytes(dataSize), 0, 4)
            
            // PCM data - convert float samples to 16-bit integers
            samples.forEach { sample ->
                val clamped = sample.coerceIn(-1f, 1f)
                val intSample = (clamped * 32767).toInt()
                out.write(shortToBytes(intSample.toShort()), 0, 2)
            }
        }
        
        AppLogger.d(tag, "Saved audio file: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
        return outputFile
    }
    
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    private fun shortToBytes(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
    
    fun cleanup() {
        try {
            tts?.release()
            tts = null
            initialized = false
            AppLogger.d(tag, "SherpaTTS engine cleaned up")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error during cleanup", e)
        }
    }
}
