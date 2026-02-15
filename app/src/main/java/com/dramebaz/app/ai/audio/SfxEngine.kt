package com.dramebaz.app.ai.audio

import android.content.Context
import android.util.Base64
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
 * SFX engine implementation with AI-powered generation and tag-based fallback.
 *
 * Generation priority:
 * 1. Remote server (if configured and connected)
 * 2. Local AI (StableAudioEngine) if enabled
 * 3. Bundled assets (keyword/category matching)
 *
 * T3.2: SFX generation/selection - resolves sound_cue to file_path.
 */
class SfxEngine(private val context: Context) {
    private val tag = "SfxEngine"

    // AI-powered SFX generator (optional - enabled when models are available)
    private var stableAudioEngine: StableAudioEngine? = null
    private var aiGenerationEnabled = false

    // Remote SFX generation support
    private var remoteConfig: RemoteSfxConfig? = null
    private var remoteServerConnected = false
    private val gson = Gson()

    // SFX library mapping: keyword/category -> asset path
    private val sfxLibrary = mapOf(
        // Ambient sounds
        "rain" to "sfx/rain.wav",
        "thunder" to "sfx/thunder.wav",
        "wind" to "sfx/wind.wav",
        "forest" to "sfx/forest.wav",
        "ocean" to "sfx/ocean.wav",
        "fire" to "sfx/fire.wav",
        "crickets" to "sfx/crickets.wav",

        // Action sounds
        "footsteps" to "sfx/footsteps.wav",
        "door" to "sfx/door.wav",
        "door_close" to "sfx/door_close.wav",
        "door_open" to "sfx/door_open.wav",
        "knock" to "sfx/knock.wav",
        "slam" to "sfx/slam.wav",
        "crash" to "sfx/crash.wav",
        "explosion" to "sfx/explosion.wav",

        // Nature sounds
        "bird" to "sfx/bird.wav",
        "bird_chirp" to "sfx/bird_chirp.wav",
        "wolf" to "sfx/wolf.wav",
        "horse" to "sfx/horse.wav",

        // Technology sounds
        "phone" to "sfx/phone.wav",
        "ring" to "sfx/ring.wav",
        "beep" to "sfx/beep.wav",
        "alarm" to "sfx/alarm.wav",
        "clock" to "sfx/clock.wav",
        "tick" to "sfx/tick.wav",

        // Emotional/atmospheric
        "suspense" to "sfx/suspense.wav",
        "tension" to "sfx/tension.wav",
        "mystery" to "sfx/mystery.wav",
        "dramatic" to "sfx/dramatic.wav",
        "sad" to "sfx/sad.wav",
        "happy" to "sfx/happy.wav",

        // Combat/conflict
        "sword" to "sfx/sword.wav",
        "clash" to "sfx/clash.wav",
        "battle" to "sfx/battle.wav",
        "gunshot" to "sfx/gunshot.wav",

        // Transportation
        "car" to "sfx/car.wav",
        "engine" to "sfx/engine.wav",
        "train" to "sfx/train.wav",
        "plane" to "sfx/plane.wav",

        // General
        "click" to "sfx/click.wav",
        "pop" to "sfx/pop.wav",
        "whoosh" to "sfx/whoosh.wav",
        "magic" to "sfx/magic.wav"
    )

    // Category-based mappings
    private val categoryMappings = mapOf(
        "ambience" to listOf("rain", "wind", "forest", "ocean", "fire", "crickets"),
        "action" to listOf("footsteps", "door", "crash", "explosion", "slam"),
        "nature" to listOf("bird", "wolf", "horse"),
        "technology" to listOf("phone", "ring", "beep", "alarm", "clock"),
        "emotional" to listOf("suspense", "tension", "mystery", "dramatic"),
        "combat" to listOf("sword", "clash", "battle", "gunshot"),
        "transportation" to listOf("car", "engine", "train", "plane")
    )

    /**
     * Initialize AI-powered SFX generation with Stable Audio Open Small models.
     * @param modelDirectory Directory containing the TFLite model files
     * @param useGpu Whether to use GPU acceleration if available (default: false - CPU is faster for init)
     * @return true if AI generation was successfully initialized
     */
    suspend fun initializeAiGeneration(modelDirectory: String, useGpu: Boolean = false): Boolean {
        return try {
            val engine = StableAudioEngine(context)
            val success = engine.initialize(modelDirectory, useGpu = useGpu)
            if (success) {
                stableAudioEngine = engine
                aiGenerationEnabled = true
                val accelerator = if (useGpu) "GPU delegate" else "CPU"
                AppLogger.i(tag, "✅ AI SFX generation enabled with Stable Audio ($accelerator)")
            } else {
                AppLogger.w(tag, "Failed to initialize AI SFX generation, using fallback only")
            }
            success
        } catch (e: Exception) {
            AppLogger.e(tag, "Error initializing AI SFX generation", e)
            false
        }
    }

    /**
     * Check if AI-powered generation is available (local or remote).
     */
    fun isAiGenerationEnabled(): Boolean =
        (aiGenerationEnabled && stableAudioEngine?.isReady() == true) || isRemoteGenerationEnabled()

    /**
     * Initialize remote SFX generation with server configuration.
     * @param config Remote server configuration
     * @return true if remote server connection was established
     */
    suspend fun initializeRemoteGeneration(config: RemoteSfxConfig): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(tag, "Checking connection to remote SFX server: ${config.baseUrl}")
        try {
            val url = URL(config.healthUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            connection.disconnect()

            remoteServerConnected = responseCode == 200
            if (remoteServerConnected) {
                remoteConfig = config
                AppLogger.i(tag, "✅ Connected to remote SFX server: ${config.baseUrl}")
            } else {
                AppLogger.w(tag, "❌ Remote SFX server returned status $responseCode")
            }
            remoteServerConnected
        } catch (e: Exception) {
            AppLogger.e(tag, "❌ Failed to connect to remote SFX server: ${e.message}", e)
            remoteServerConnected = false
            false
        }
    }

    /**
     * Check if remote SFX generation is available.
     */
    fun isRemoteGenerationEnabled(): Boolean = remoteServerConnected && remoteConfig != null

    /**
     * Release all engine resources (local AI and remote).
     */
    fun release() {
        stableAudioEngine?.release()
        stableAudioEngine = null
        aiGenerationEnabled = false
        remoteServerConnected = false
        remoteConfig = null
    }

    /**
     * Resolve sound prompt to a sound file.
     * Priority: 1. Remote server, 2. Local AI, 3. Bundled assets
     */
    suspend fun resolveToFile(
        soundPrompt: String,
        durationSeconds: Float,
        category: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(tag, "Resolving SFX: prompt='$soundPrompt', category='$category', duration=${durationSeconds}s")

            // 1. Try remote generation first if enabled
            if (isRemoteGenerationEnabled()) {
                val remoteFile = generateWithRemoteServer(soundPrompt, durationSeconds)
                if (remoteFile != null) {
                    AppLogger.i(tag, "✅ Remote-generated SFX: '$soundPrompt' -> ${remoteFile.name}")
                    return@withContext remoteFile
                }
                AppLogger.w(tag, "Remote SFX generation failed, trying local AI")
            }

            // 2. Try local AI generation if enabled
            if (aiGenerationEnabled && stableAudioEngine?.isReady() == true) {
                val aiGeneratedFile = generateWithLocalAi(soundPrompt, durationSeconds)
                if (aiGeneratedFile != null) {
                    AppLogger.i(tag, "✅ Local AI-generated SFX: '$soundPrompt' -> ${aiGeneratedFile.name}")
                    return@withContext aiGeneratedFile
                }
                AppLogger.w(tag, "Local AI generation failed, falling back to bundled assets")
            }

            // 3. Fallback to bundled assets
            resolveToBundledAsset(soundPrompt, category)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error resolving SFX file", e)
            null
        }
    }

    /**
     * Generate SFX using remote server.
     */
    private suspend fun generateWithRemoteServer(prompt: String, durationSeconds: Float): File? {
        val config = remoteConfig ?: return null
        return try {
            val request = AudioGenRequest(
                model = config.modelId,
                prompt = prompt
            )
            val response = sendAudioGenRequest(config, request)
            if (response == null) {
                AppLogger.w(tag, "No response from remote SFX server")
                null
            } else if (response.error != null) {
                AppLogger.e(tag, "SFX server error: ${response.error.code} - ${response.error.message}")
                null
            } else if (response.outputData == null) {
                AppLogger.w(tag, "No audio data in SFX response")
                null
            } else {
                saveRemoteSfxResponse(response, prompt)
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Remote SFX generation error", e)
            remoteServerConnected = false
            null
        }
    }

    /**
     * Generate SFX using local AI (Stable Audio Open Small).
     */
    private suspend fun generateWithLocalAi(prompt: String, durationSeconds: Float): File? {
        return try {
            stableAudioEngine?.generateAudio(prompt, durationSeconds)
        } catch (e: Exception) {
            AppLogger.e(tag, "Local AI SFX generation error", e)
            null
        }
    }

    private fun sendAudioGenRequest(config: RemoteSfxConfig, request: AudioGenRequest): AudioGenResponse? {
        val url = URL(config.audioGenUrl)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonBody = gson.toJson(request)
            AppLogger.d(tag, "Audio gen request: $jsonBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                gson.fromJson(responseBody, AudioGenResponse::class.java)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                AppLogger.e(tag, "Audio gen server error $responseCode: $errorBody")
                // Try to parse error response
                try {
                    gson.fromJson(errorBody, AudioGenResponse::class.java)
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveRemoteSfxResponse(response: AudioGenResponse, prompt: String): File? {
        return try {
            val audioData = Base64.decode(response.outputData!!, Base64.DEFAULT)
            val cacheDir = File(context.cacheDir, "sfx_remote")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val safeFileName = prompt.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")
            val outputFile = File(cacheDir, "${safeFileName}_${System.currentTimeMillis()}.wav")

            FileOutputStream(outputFile).use { it.write(audioData) }
            AppLogger.d(tag, "Saved remote SFX: ${outputFile.name} (${audioData.size} bytes)")
            outputFile
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to save remote SFX", e)
            null
        }
    }

    /**
     * Resolve sound prompt to bundled asset file.
     */
    private fun resolveToBundledAsset(soundPrompt: String, category: String): File? {
        val normalizedPrompt = soundPrompt.lowercase().trim()

        // Try direct keyword match first
        val matchedKeyword = sfxLibrary.keys.firstOrNull { keyword ->
            normalizedPrompt.contains(keyword, ignoreCase = true)
        }

        // If no direct match, try category-based selection
        val keyword = matchedKeyword ?: run {
            val categoryKeywords = categoryMappings[category.lowercase()] ?: emptyList()
            categoryKeywords.firstOrNull { normalizedPrompt.contains(it, ignoreCase = true) }
                ?: categoryKeywords.firstOrNull()
        }

        if (keyword == null) {
            AppLogger.w(tag, "No matching SFX found for prompt: '$soundPrompt', category: '$category'")
            return null
        }

        val assetPath = sfxLibrary[keyword]
        if (assetPath == null) {
            AppLogger.w(tag, "No asset path found for keyword: '$keyword'")
            return null
        }

        // Copy asset to cache directory for playback
        val cachedFile = copyAssetToCache(assetPath, keyword)
        return if (cachedFile != null && cachedFile.exists()) {
            AppLogger.d(tag, "Resolved SFX: '$soundPrompt' -> ${cachedFile.name}")
            cachedFile
        } else {
            AppLogger.w(tag, "Failed to copy SFX asset: $assetPath")
            null
        }
    }

    /**
     * Copy asset file to cache directory for playback.
     */
    private fun copyAssetToCache(assetPath: String, fileName: String): File? {
        return try {
            val cacheDir = File(context.cacheDir, "sfx")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val cachedFile = File(cacheDir, "$fileName.wav")

            // Return cached file if it already exists
            if (cachedFile.exists() && cachedFile.length() > 0) {
                return cachedFile
            }

            // Try to copy from assets
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(cachedFile).use { output ->
                        input.copyTo(output)
                    }
                }
                AppLogger.d(tag, "Copied SFX asset to cache: ${cachedFile.absolutePath}")
                cachedFile
            } catch (e: Exception) {
                // Asset doesn't exist - generate silence as fallback
                AppLogger.w(tag, "SFX asset not found: $assetPath, generating silence")
                generateSilence(cachedFile, 1.0f) // Default 1 second
                cachedFile
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error copying SFX asset to cache", e)
            null
        }
    }

    /**
     * Generate a silence WAV file as fallback when SFX asset is not available.
     */
    private fun generateSilence(outputFile: File, durationSeconds: Float) {
        try {
            val sampleRate = 22050
            val numSamples = (sampleRate * durationSeconds).toInt()
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

                // Silence (all zeros)
                out.write(ByteArray(dataSize))
            }

            AppLogger.d(tag, "Generated silence file: ${outputFile.absolutePath} (${durationSeconds}s)")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error generating silence file", e)
        }
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
}

// ==================== Request/Response DTOs ====================

/**
 * Request body for /api/v1/audio-gen endpoint.
 * Unified request format matching AIServer API.
 */
private data class AudioGenRequest(
    @SerializedName("model") val model: String,
    @SerializedName("prompt") val prompt: String
)

/**
 * Response body from /api/v1/audio-gen endpoint.
 * Unified response format matching AIServer API.
 */
private data class AudioGenResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("task") val task: String?,
    @SerializedName("generated_text") val generatedText: String?,
    @SerializedName("output_data") val outputData: String?,
    @SerializedName("output_mime_type") val outputMimeType: String?,
    @SerializedName("usage") val usage: AudioGenUsageInfo?,
    @SerializedName("timing") val timing: AudioGenTiming?,
    @SerializedName("error") val error: AudioGenErrorInfo?
)

private data class AudioGenUsageInfo(
    @SerializedName("prompt_tokens") val promptTokens: Int?,
    @SerializedName("completion_tokens") val completionTokens: Int?,
    @SerializedName("total_tokens") val totalTokens: Int?
)

private data class AudioGenTiming(
    @SerializedName("inference_time_ms") val inferenceTimeMs: Double?,
    @SerializedName("tokens_per_second") val tokensPerSecond: Double?
)

private data class AudioGenErrorInfo(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("http_status") val httpStatus: Int?
)
