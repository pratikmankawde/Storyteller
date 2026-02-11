package com.dramebaz.app.ai.audio

import android.content.Context
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * SFX engine implementation with AI-powered generation and tag-based fallback.
 *
 * Primary: Uses StableAudioEngine (Stable Audio Open Small) for AI-generated SFX
 * Fallback: Maps sound prompts to bundled sound files based on keywords and categories.
 *
 * T3.2: SFX generation/selection - resolves sound_cue to file_path.
 */
class SfxEngine(private val context: Context) {
    private val tag = "SfxEngine"

    // AI-powered SFX generator (optional - enabled when models are available)
    private var stableAudioEngine: StableAudioEngine? = null
    private var aiGenerationEnabled = false

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
     * Check if AI-powered generation is available.
     */
    fun isAiGenerationEnabled(): Boolean = aiGenerationEnabled && stableAudioEngine?.isReady() == true

    /**
     * Release AI engine resources.
     */
    fun release() {
        stableAudioEngine?.release()
        stableAudioEngine = null
        aiGenerationEnabled = false
    }

    /**
     * Resolve sound prompt to a sound file.
     * Primary: Uses AI generation if enabled
     * Fallback: Uses keyword matching and category-based selection from bundled assets.
     */
    suspend fun resolveToFile(
        soundPrompt: String,
        durationSeconds: Float,
        category: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(tag, "Resolving SFX: prompt='$soundPrompt', category='$category', duration=${durationSeconds}s")

            // Try AI generation first if enabled
            if (isAiGenerationEnabled()) {
                val aiGeneratedFile = generateWithAi(soundPrompt, durationSeconds)
                if (aiGeneratedFile != null) {
                    AppLogger.i(tag, "✅ AI-generated SFX: '$soundPrompt' -> ${aiGeneratedFile.name}")
                    return@withContext aiGeneratedFile
                }
                AppLogger.w(tag, "AI generation failed, falling back to bundled assets")
            }

            // Fallback to bundled assets
            resolveToBundledAsset(soundPrompt, category)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error resolving SFX file", e)
            null
        }
    }

    /**
     * Generate SFX using AI (Stable Audio Open Small).
     */
    private suspend fun generateWithAi(prompt: String, durationSeconds: Float): File? {
        return try {
            stableAudioEngine?.generateAudio(prompt, durationSeconds)
        } catch (e: Exception) {
            AppLogger.e(tag, "AI SFX generation error", e)
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
