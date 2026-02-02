package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.DegradedModeManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * SherpaTTS implementation using the official Sherpa-ONNX SDK with VITS-Piper en_US-libritts-high model.
 * This Piper model uses espeak-ng for phoneme generation and supports 904 speakers (ID 0-903).
 */
class SherpaTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var initialized = false
    private val tag = "SherpaTtsEngine"

    // Model paths in assets (vits-piper-en_US-libritts-high)
    private val modelAssetPath = "models/tts/sherpa/en_US-libritts-high.onnx"
    private val tokensAssetPath = "models/tts/sherpa/tokens.txt"
    private val espeakDataAssetPath = "models/tts/sherpa/espeak-ng-data"

    // Model configuration
    private val sampleRate = 22050
    private val defaultSpeakerId = 0 // LibriTTS has 904 speakers (0-903)

    // AUG-034: Audio cache directory with LRU eviction
    private val cacheDir by lazy { File(context.cacheDir, "tts_audio_cache") }
    private val maxCacheSizeBytes = 100L * 1024 * 1024 // 100MB cache limit

    /**
     * Compute a cache key for the given text and synthesis parameters.
     * Uses MD5 hash of text + speakerId + speed to create a unique key.
     */
    private fun computeCacheKey(text: String, speakerId: Int, speed: Float): String {
        val input = "$text|$speakerId|${"%.2f".format(speed)}"
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to simple hash if MD5 not available
            input.hashCode().toString()
        }
    }

    /**
     * Check if cached audio exists for the given parameters.
     * AUG-034: Updates file access time for LRU tracking.
     */
    private fun getCachedAudio(text: String, speakerId: Int, speed: Float): File? {
        if (!cacheDir.exists()) return null
        val key = computeCacheKey(text, speakerId, speed)
        val cachedFile = File(cacheDir, "$key.wav")
        return if (cachedFile.exists() && cachedFile.length() > 44) { // 44 bytes = WAV header minimum
            // Touch file to update access time for LRU
            cachedFile.setLastModified(System.currentTimeMillis())
            AppLogger.d(tag, "Cache hit for audio: ${cachedFile.name}")
            cachedFile
        } else {
            null
        }
    }

    /**
     * AUG-034: Evict oldest files if cache exceeds size limit (LRU eviction).
     */
    private fun evictCacheIfNeeded() {
        try {
            if (!cacheDir.exists()) return
            val files = cacheDir.listFiles()?.filter { it.extension == "wav" } ?: return
            var totalSize = files.sumOf { it.length() }

            if (totalSize <= maxCacheSizeBytes) return

            // Sort by last modified (oldest first) for LRU eviction
            val sortedFiles = files.sortedBy { it.lastModified() }
            var evictedCount = 0
            var evictedSize = 0L

            for (file in sortedFiles) {
                if (totalSize <= maxCacheSizeBytes * 0.8) break // Evict until 80% of limit
                val fileSize = file.length()
                if (file.delete()) {
                    totalSize -= fileSize
                    evictedSize += fileSize
                    evictedCount++
                }
            }

            if (evictedCount > 0) {
                AppLogger.i(tag, "AUG-034: LRU cache eviction - removed $evictedCount files (${evictedSize / 1024}KB)")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "AUG-034: Cache eviction failed", e)
        }
    }

    /**
     * AUG-034: Get cache statistics for debugging/settings display.
     */
    fun getCacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles()?.filter { it.extension == "wav" } ?: emptyList()
        return files.size to files.sumOf { it.length() }
    }

    /**
     * AUG-034: Clear entire audio cache (user-triggered).
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            AppLogger.i(tag, "AUG-034: Audio cache cleared")
        } catch (e: Exception) {
            AppLogger.e(tag, "AUG-034: Failed to clear cache", e)
        }
    }

    /**
     * Save audio to cache with the given parameters.
     * AUG-034: Triggers LRU eviction if cache exceeds size limit.
     */
    private fun cacheAudio(originalFile: File, text: String, speakerId: Int, speed: Float): File {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Evict old files if needed before adding new ones
        evictCacheIfNeeded()

        val key = computeCacheKey(text, speakerId, speed)
        val cachedFile = File(cacheDir, "$key.wav")
        if (!cachedFile.exists() || cachedFile.absolutePath != originalFile.absolutePath) {
            originalFile.copyTo(cachedFile, overwrite = true)
            AppLogger.d(tag, "Cached audio: ${cachedFile.name}")
        }
        return cachedFile
    }

    fun init(): Boolean {
        return try {
            if (initialized) {
                AppLogger.d(tag, "SherpaTTS engine already initialized")
                return true
            }

            AppLogger.i(tag, "Initializing SherpaTTS engine with vits-piper-en_US-libritts-high model...")
            val startTime = System.currentTimeMillis()

            // Copy model files from assets to internal storage
            val modelDir = File(context.filesDir, "models/tts/sherpa")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val modelFile = copyAssetIfNeeded(modelAssetPath, File(modelDir, "en_US-libritts-high.onnx"))
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

            // Try GPU first, fall back to CPU if GPU fails
            var ttsCreated = false
            for (provider in listOf("gpu", "cpu")) {
                if (ttsCreated) break

                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    matcha = OfflineTtsMatchaModelConfig(),
                    kokoro = OfflineTtsKokoroModelConfig(),
                    kitten = OfflineTtsKittenModelConfig(),
                    numThreads = numThreads,
                    debug = false,
                    provider = provider
                )

                val ttsConfig = OfflineTtsConfig(model = modelConfig)

                try {
                    AppLogger.i(tag, "Trying TTS with provider: $provider")
                    tts = OfflineTts(config = ttsConfig)
                    ttsCreated = true
                    AppLogger.i(tag, "Sherpa-ONNX TTS created successfully with provider: $provider")
                } catch (e: Exception) {
                    AppLogger.w(tag, "Failed to create TTS with provider $provider: ${e.message}")
                    if (provider == "cpu") {
                        // Last resort failed
                        AppLogger.e(tag, "Failed to create Sherpa-ONNX TTS with all providers", e)
                        DegradedModeManager.setTtsMode(
                            DegradedModeManager.TtsMode.DISABLED,
                            e.message ?: "Failed to create TTS engine"
                        )
                        return false
                    }
                }
            }

            if (!ttsCreated) {
                AppLogger.e(tag, "Failed to create TTS with any provider")
                DegradedModeManager.setTtsMode(
                    DegradedModeManager.TtsMode.DISABLED,
                    "Failed to create TTS engine with any provider"
                )
                return false
            }

            initialized = true
            AppLogger.logPerformance(tag, "SherpaTTS vits-piper-en_US-libritts-high initialization", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "SherpaTTS engine (vits-piper-en_US-libritts-high) initialized successfully")
            // AUG-041: Report TTS success to DegradedModeManager
            DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize SherpaTTS engine", e)
            // AUG-041: Report TTS failure to DegradedModeManager
            DegradedModeManager.setTtsMode(
                DegradedModeManager.TtsMode.DISABLED,
                e.message ?: "Failed to initialize TTS engine"
            )
            false
        }
    }

    fun isInitialized(): Boolean = initialized

    /**
     * AUG-041: Retry initializing the TTS engine.
     * Call this if user wants to retry after a failure.
     * Returns true if initialization was successful.
     */
    suspend fun retryInit(): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(tag, "Retrying TTS engine initialization...")
        // Release any partial state
        release()
        // Try to initialize again
        val success = init()
        if (success) {
            AppLogger.i(tag, "TTS retry successful")
        } else {
            AppLogger.w(tag, "TTS retry failed")
        }
        success
    }

    /**
     * Release TTS resources and reset state.
     */
    fun release() {
        tts = null
        initialized = false
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.NOT_INITIALIZED)
        AppLogger.d(tag, "TTS engine released")
    }

    suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        onComplete: (() -> Unit)? = null,
        speakerId: Int? = null  // Optional speaker ID (0-903 for LibriTTS)
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
            AppLogger.d(tag, "TTS params: pitch=${params.pitch}, speed=${params.speed}, energy=${params.energy}, speakerId=${params.speakerId ?: defaultSpeakerId}")

            // Use speaker ID from params, voice profile, or default
            val sid = params.speakerId ?: defaultSpeakerId

            // Speed: 1.0 = normal, >1.0 = faster, <1.0 = slower
            val speed = params.speed.coerceIn(0.5f, 2.0f)

            // Energy: used for post-processing volume scaling (VITS doesn't support runtime energy)
            val energy = params.energy.coerceIn(0.5f, 1.5f)

            // Check cache first (include energy in cache key)
            val cachedFile = getCachedAudioWithEnergy(text, sid, speed, energy)
            if (cachedFile != null) {
                AppLogger.logPerformance(tag, "TTS cache hit", System.currentTimeMillis() - startTime)
                onComplete?.invoke()
                return@withContext Result.success(cachedFile)
            }

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

            // Apply energy scaling as post-processing volume control (AUG-012)
            // VITS-Piper has NO runtime energy/volume parameter, so we scale samples
            val scaledSamples = if (energy != 1.0f) {
                applyEnergyScaling(audio.samples, energy)
            } else {
                audio.samples
            }

            // Save audio to file and cache it
            val outputFile = saveAudioToFile(scaledSamples, audio.sampleRate, text.hashCode().toString())
            val cachedOutputFile = cacheAudioWithEnergy(outputFile, text, sid, speed, energy)
            AppLogger.logPerformance(tag, "TTS synthesis (vits-piper)", System.currentTimeMillis() - startTime)
            AppLogger.i(tag, "Speech synthesis complete: ${cachedOutputFile.absolutePath}, size=${cachedOutputFile.length()} bytes")

            onComplete?.invoke()
            Result.success(cachedOutputFile)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error during speech synthesis", e)
            Result.failure(e)
        }
    }

    /**
     * Apply energy scaling to audio samples as post-processing volume control.
     * VITS-Piper has NO runtime energy/volume parameter, so we scale the generated samples.
     * Energy range: 0.5 (quieter) to 1.5 (louder), 1.0 = no change.
     * Samples are clamped to [-1.0, 1.0] to prevent clipping.
     */
    private fun applyEnergyScaling(samples: FloatArray, energy: Float): FloatArray {
        val startTime = System.currentTimeMillis()
        val scaled = FloatArray(samples.size)
        for (i in samples.indices) {
            // Scale sample by energy factor and clamp to prevent clipping
            scaled[i] = (samples[i] * energy).coerceIn(-1f, 1f)
        }
        AppLogger.d(tag, "Applied energy scaling: factor=$energy, samples=${samples.size}, time=${System.currentTimeMillis() - startTime}ms")
        return scaled
    }

    /**
     * Compute a cache key including energy for the given text and synthesis parameters.
     */
    private fun computeCacheKeyWithEnergy(text: String, speakerId: Int, speed: Float, energy: Float): String {
        val input = "$text|$speakerId|${"%.2f".format(speed)}|${"%.2f".format(energy)}"
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    /**
     * Check if cached audio exists for the given parameters including energy.
     */
    private fun getCachedAudioWithEnergy(text: String, speakerId: Int, speed: Float, energy: Float): File? {
        if (!cacheDir.exists()) return null
        val key = computeCacheKeyWithEnergy(text, speakerId, speed, energy)
        val cachedFile = File(cacheDir, "$key.wav")
        return if (cachedFile.exists() && cachedFile.length() > 44) {
            AppLogger.d(tag, "Cache hit for audio with energy: ${cachedFile.name}")
            cachedFile
        } else {
            null
        }
    }

    /**
     * Save audio to cache with the given parameters including energy.
     */
    private fun cacheAudioWithEnergy(originalFile: File, text: String, speakerId: Int, speed: Float, energy: Float): File {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val key = computeCacheKeyWithEnergy(text, speakerId, speed, energy)
        val cachedFile = File(cacheDir, "$key.wav")
        if (!cachedFile.exists() || cachedFile.absolutePath != originalFile.absolutePath) {
            originalFile.copyTo(cachedFile, overwrite = true)
            AppLogger.d(tag, "Cached audio with energy: ${cachedFile.name}")
        }
        return cachedFile
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
