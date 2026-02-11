package com.dramebaz.app.ai.tts

import android.content.Context
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.DegradedModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Abstract base class for TTS engines providing common functionality:
 * - Audio caching with LRU eviction
 * - WAV file generation
 * - Energy scaling post-processing
 * - Asset copying utilities
 * 
 * Subclasses implement model-specific initialization and synthesis.
 */
abstract class BaseTtsEngine(
    protected val context: Context,
    protected val config: TtsModelConfig
) : TtsEngine {
    
    protected abstract val tag: String
    protected var initialized = false
    
    // Audio cache with LRU eviction
    protected val cacheDir by lazy { File(context.cacheDir, "tts_audio_cache_${config.id}") }
    protected val maxCacheSizeBytes = 100L * 1024 * 1024 // 100MB cache limit
    
    override fun isInitialized(): Boolean = initialized
    
    override suspend fun retryInit(): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(tag, "Retrying TTS engine initialization...")
        release()
        val success = init()
        if (success) {
            AppLogger.i(tag, "TTS retry successful")
        } else {
            AppLogger.w(tag, "TTS retry failed")
        }
        success
    }
    
    override fun getSampleRate(): Int = config.sampleRate
    
    override fun getCacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles()?.filter { it.extension == "wav" } ?: emptyList()
        return files.size to files.sumOf { it.length() }
    }
    
    override fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            AppLogger.i(tag, "Audio cache cleared")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to clear cache", e)
        }
    }
    
    // ==================== Caching Utilities ====================
    
    protected fun computeCacheKey(text: String, speakerId: Int, speed: Float, energy: Float): String {
        val input = "$text|$speakerId|${"%.2f".format(speed)}|${"%.2f".format(energy)}|${config.id}"
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
    
    protected fun getCachedAudio(text: String, speakerId: Int, speed: Float, energy: Float): File? {
        if (!cacheDir.exists()) return null
        val key = computeCacheKey(text, speakerId, speed, energy)
        val cachedFile = File(cacheDir, "$key.wav")
        return if (cachedFile.exists() && cachedFile.length() > 44) {
            cachedFile.setLastModified(System.currentTimeMillis())
            AppLogger.d(tag, "Cache hit: ${cachedFile.name}")
            cachedFile
        } else null
    }
    
    protected fun cacheAudio(originalFile: File, text: String, speakerId: Int, speed: Float, energy: Float): File {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        evictCacheIfNeeded()
        
        val key = computeCacheKey(text, speakerId, speed, energy)
        val cachedFile = File(cacheDir, "$key.wav")
        if (!cachedFile.exists() || cachedFile.absolutePath != originalFile.absolutePath) {
            originalFile.copyTo(cachedFile, overwrite = true)
            AppLogger.d(tag, "Cached audio: ${cachedFile.name}")
        }
        return cachedFile
    }
    
    protected fun evictCacheIfNeeded() {
        try {
            if (!cacheDir.exists()) return
            val files = cacheDir.listFiles()?.filter { it.extension == "wav" } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxCacheSizeBytes) return
            
            val sortedFiles = files.sortedBy { it.lastModified() }
            var evictedCount = 0
            for (file in sortedFiles) {
                if (totalSize <= maxCacheSizeBytes * 0.8) break
                val fileSize = file.length()
                if (file.delete()) {
                    totalSize -= fileSize
                    evictedCount++
                }
            }
            if (evictedCount > 0) {
                AppLogger.i(tag, "LRU cache eviction: removed $evictedCount files")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Cache eviction failed", e)
        }
    }
    
    // ==================== Audio Processing ====================
    
    protected fun applyEnergyScaling(samples: FloatArray, energy: Float): FloatArray {
        if (energy == 1.0f) return samples
        val scaled = FloatArray(samples.size)
        for (i in samples.indices) {
            scaled[i] = (samples[i] * energy).coerceIn(-1f, 1f)
        }
        return scaled
    }
    
    protected fun saveAudioToFile(samples: FloatArray, sampleRate: Int, fileName: String): File {
        val outputDir = File(context.cacheDir, "tts_audio")
        if (!outputDir.exists()) outputDir.mkdirs()
        
        val outputFile = File(outputDir, "$fileName.wav")
        val numSamples = samples.size
        val bitsPerSample = 16
        val byteRate = sampleRate * bitsPerSample / 8
        val dataSize = numSamples * bitsPerSample / 8
        val fileSize = 36 + dataSize
        
        FileOutputStream(outputFile).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToBytes(fileSize), 0, 4)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16), 0, 4)
            out.write(shortToBytes(1), 0, 2)
            out.write(shortToBytes(1), 0, 2) // mono
            out.write(intToBytes(sampleRate), 0, 4)
            out.write(intToBytes(byteRate), 0, 4)
            out.write(shortToBytes(2), 0, 2) // block align
            out.write(shortToBytes(bitsPerSample.toShort()), 0, 2)
            out.write("data".toByteArray())
            out.write(intToBytes(dataSize), 0, 4)
            
            samples.forEach { sample ->
                val intSample = ((sample.coerceIn(-1f, 1f)) * 32767).toInt()
                out.write(shortToBytes(intSample.toShort()), 0, 2)
            }
        }
        return outputFile
    }

    protected fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    protected fun shortToBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

    // ==================== File Utilities ====================

    protected fun copyAssetIfNeeded(assetPath: String, destFile: File): File? {
        return try {
            if (destFile.exists() && destFile.length() > 0) {
                AppLogger.d(tag, "File already exists: ${destFile.absolutePath}")
                return destFile
            }
            destFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists() && destFile.length() > 0) destFile else null
        } catch (e: Exception) {
            AppLogger.e(tag, "Error copying asset $assetPath", e)
            null
        }
    }

    protected fun copyAssetDirectory(assetPath: String, destDir: File) {
        val assetManager = context.assets
        if (!destDir.exists()) destDir.mkdirs()

        val files = assetManager.list(assetPath) ?: return
        for (fileName in files) {
            val assetFilePath = "$assetPath/$fileName"
            val destFile = File(destDir, fileName)
            val subFiles = assetManager.list(assetFilePath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetDirectory(assetFilePath, destFile)
            } else {
                try {
                    assetManager.open(assetFilePath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore - some entries might be empty directories
                }
            }
        }
    }

    protected fun reportTtsSuccess() {
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.FULL)
    }

    protected fun reportTtsFailure(reason: String) {
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.DISABLED, reason)
    }

    protected fun reportTtsNotInitialized() {
        DegradedModeManager.setTtsMode(DegradedModeManager.TtsMode.NOT_INITIALIZED)
    }
}

