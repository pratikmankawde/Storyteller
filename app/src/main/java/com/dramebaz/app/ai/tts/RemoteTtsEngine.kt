package com.dramebaz.app.ai.tts

import android.content.Context
import android.util.Base64
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * TtsEngine implementation that connects to a remote AIServer for TTS synthesis.
 * Uses HTTP/REST API to communicate with the server.
 *
 * Design Patterns:
 * - **Strategy Pattern**: Interchangeable with other TtsEngine implementations
 * - **Adapter Pattern**: Adapts REST API to TtsEngine interface
 *
 * @param context Android context for file operations
 * @param config Remote server configuration
 */
class RemoteTtsEngine(
    private val context: Context,
    private val config: RemoteTtsConfig = RemoteTtsConfig.DEFAULT
) : TtsEngine {

    companion object {
        private const val TAG = "RemoteTtsEngine"
        private const val SAMPLE_RATE = 22050
        private const val DEFAULT_SPEAKER_COUNT = 904
    }

    private val gson = Gson()
    private val cacheDir by lazy { File(context.cacheDir, "tts_audio_cache_remote") }
    private val maxCacheSizeBytes = 100L * 1024 * 1024 // 100MB

    @Volatile
    private var serverConnected = false
    private var speakerCount = DEFAULT_SPEAKER_COUNT
    private var serverSampleRate = SAMPLE_RATE

    // ==================== Lifecycle ====================

    override fun init(): Boolean {
        AppLogger.i(TAG, "Checking connection to remote TTS server: ${config.baseUrl}")
        return try {
            val url = URL(config.healthUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            connection.disconnect()

            serverConnected = responseCode == 200
            if (serverConnected) {
                AppLogger.i(TAG, "✅ Connected to remote TTS server: ${config.baseUrl}")
                if (!cacheDir.exists()) cacheDir.mkdirs()
            } else {
                AppLogger.w(TAG, "❌ TTS server returned status $responseCode")
            }
            serverConnected
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to connect to TTS server: ${e.message}", e)
            serverConnected = false
            false
        }
    }

    override fun isInitialized(): Boolean = serverConnected

    override fun release() {
        serverConnected = false
        AppLogger.d(TAG, "Released remote TTS server connection")
    }

    override suspend fun retryInit(): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Retrying remote TTS engine initialization...")
        release()
        val success = init()
        if (success) {
            AppLogger.i(TAG, "Remote TTS retry successful")
        } else {
            AppLogger.w(TAG, "Remote TTS retry failed")
        }
        success
    }

    // ==================== Core Synthesis ====================

    override suspend fun speak(
        text: String,
        voiceProfile: VoiceProfile?,
        speakerId: Int?,
        onComplete: (() -> Unit)?
    ): Result<File?> = withContext(Dispatchers.IO) {
        try {
            if (!serverConnected) {
                AppLogger.w(TAG, "TTS server not connected, attempting reconnect...")
                if (!init()) {
                    onComplete?.invoke()
                    return@withContext Result.failure(Exception("TTS server not connected"))
                }
            }

            if (text.isBlank()) {
                onComplete?.invoke()
                return@withContext Result.success(null)
            }

            // Check cache - use speakerId if provided, otherwise use 0 for default
            val effectiveSpeakerId = speakerId ?: 0
            val speed = voiceProfile?.speed ?: 1.0f
            val cached = getCachedAudio(text, effectiveSpeakerId, speed)
            if (cached != null) {
                onComplete?.invoke()
                return@withContext Result.success(cached)
            }

            AppLogger.d(TAG, "Synthesizing via remote server: \"${text.take(50)}...\"")
            val startTime = System.currentTimeMillis()

            val request = TtsRequest(
                model = config.modelId,
                prompt = text
            )

            val response = sendTtsRequest(request)
            if (response == null) {
                onComplete?.invoke()
                return@withContext Result.failure(Exception("TTS synthesis failed"))
            }
            if (response.error != null) {
                AppLogger.e(TAG, "TTS server error: ${response.error.code} - ${response.error.message}")
                onComplete?.invoke()
                return@withContext Result.failure(Exception(response.error.message ?: "TTS error"))
            }
            if (response.outputData == null) {
                onComplete?.invoke()
                return@withContext Result.failure(Exception("No audio data in response"))
            }

            val audioFile = saveAudioResponse(response, text, effectiveSpeakerId, speed)
            AppLogger.logPerformance(TAG, "Remote TTS synthesis", System.currentTimeMillis() - startTime)

            onComplete?.invoke()
            Result.success(audioFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Remote TTS synthesis error: ${e.message}", e)
            onComplete?.invoke()
            Result.failure(e)
        }
    }

    // ==================== Model Info ====================

    override fun getSampleRate(): Int = serverSampleRate

    override fun getSpeakerCount(): Int = speakerCount

    override fun getModelInfo(): TtsModelInfo = TtsModelInfo(
        id = "remote-tts",
        displayName = "Remote TTS (${config.host}:${config.port})",
        modelType = "remote",
        speakerCount = speakerCount,
        sampleRate = serverSampleRate,
        isExternal = true,
        modelPath = config.baseUrl
    )

    // ==================== Cache Management ====================

    override fun getCacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles()?.filter { it.extension == "wav" } ?: emptyList()
        return files.size to files.sumOf { it.length() }
    }

    override fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            AppLogger.i(TAG, "Remote TTS audio cache cleared")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear cache", e)
        }
    }

    // ==================== Private Helpers ====================

    private fun getCachedAudio(text: String, speakerId: Int, speed: Float): File? {
        if (!cacheDir.exists()) return null
        val key = computeCacheKey(text, speakerId, speed)
        val cachedFile = File(cacheDir, "$key.wav")
        return if (cachedFile.exists() && cachedFile.length() > 44) {
            cachedFile.setLastModified(System.currentTimeMillis())
            AppLogger.d(TAG, "Cache hit: ${cachedFile.name}")
            cachedFile
        } else null
    }

    private fun computeCacheKey(text: String, speakerId: Int, speed: Float): String {
        val input = "$text|$speakerId|${"%.2f".format(speed)}|remote"
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    private fun sendTtsRequest(request: TtsRequest): TtsResponse? {
        val url = URL(config.ttsUrl)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonBody = gson.toJson(request)
            AppLogger.d(TAG, "TTS request: $jsonBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                gson.fromJson(responseBody, TtsResponse::class.java)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                AppLogger.e(TAG, "TTS server error $responseCode: $errorBody")
                // Try to parse error response
                try {
                    gson.fromJson(errorBody, TtsResponse::class.java)
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveAudioResponse(response: TtsResponse, text: String, speakerId: Int, speed: Float): File? {
        return try {
            val audioData = Base64.decode(response.outputData!!, Base64.DEFAULT)
            if (!cacheDir.exists()) cacheDir.mkdirs()
            evictCacheIfNeeded()

            val key = computeCacheKey(text, speakerId, speed)
            val outputFile = File(cacheDir, "$key.wav")

            FileOutputStream(outputFile).use { it.write(audioData) }
            AppLogger.d(TAG, "Saved TTS audio: ${outputFile.name} (${audioData.size} bytes)")
            outputFile
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save TTS audio", e)
            null
        }
    }

    private fun evictCacheIfNeeded() {
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
                AppLogger.i(TAG, "LRU cache eviction: removed $evictedCount files")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Cache eviction failed", e)
        }
    }
}

// ==================== Request/Response DTOs ====================

/**
 * Request body for /api/v1/tts endpoint.
 * Unified request format matching AIServer API.
 */
private data class TtsRequest(
    @SerializedName("model") val model: String,
    @SerializedName("prompt") val prompt: String
)

/**
 * Response body from /api/v1/tts endpoint.
 * Unified response format matching AIServer API.
 */
private data class TtsResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("task") val task: String?,
    @SerializedName("generated_text") val generatedText: String?,
    @SerializedName("output_data") val outputData: String?,
    @SerializedName("output_mime_type") val outputMimeType: String?,
    @SerializedName("usage") val usage: TtsUsageInfo?,
    @SerializedName("timing") val timing: TtsTiming?,
    @SerializedName("error") val error: TtsErrorInfo?
)

private data class TtsUsageInfo(
    @SerializedName("prompt_tokens") val promptTokens: Int?,
    @SerializedName("completion_tokens") val completionTokens: Int?,
    @SerializedName("total_tokens") val totalTokens: Int?
)

private data class TtsTiming(
    @SerializedName("inference_time_ms") val inferenceTimeMs: Double?,
    @SerializedName("tokens_per_second") val tokensPerSecond: Double?
)

private data class TtsErrorInfo(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("http_status") val httpStatus: Int?
)

