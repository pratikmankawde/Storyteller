package com.dramebaz.app.playback.mixer

import android.media.AudioFormat
import android.media.AudioTrack
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.async
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min

/**
 * T3.4: Multi-channel audio mixer with separate channels for:
 * - Narration
 * - Dialog
 * - SFX
 * - Ambience
 *
 * Provides per-channel volume controls and mixes down to output.
 */
class AudioMixer(
    private val sampleRate: Int = 22050,
    private val numChannels: Int = 1,
    private val bitDepth: Int = 16
) {
    private val tag = "AudioMixer"

    init {
        AppLogger.d(tag, "AudioMixer initialized: sampleRate=$sampleRate, channels=$numChannels, bitDepth=$bitDepth")
    }

    data class AudioChannel(
        val id: String,
        var volume: Float = 1.0f,
        var isActive: Boolean = false
    )

    private val audioChannels = mapOf(
        "narration" to AudioChannel("narration", 1.0f),
        "dialog" to AudioChannel("dialog", 1.0f),
        "sfx" to AudioChannel("sfx", 1.0f),
        "ambience" to AudioChannel("ambience", 0.8f)
    )

    private var audioTrack: AudioTrack? = null
    private var isMixing = false
    private val mixingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Set volume for a specific channel (0.0 to 1.0).
     */
    fun setChannelVolume(channelId: String, volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        audioChannels[channelId]?.volume = clampedVolume
        AppLogger.d(tag, "Set channel volume: channel=$channelId, volume=$clampedVolume")
    }

    /**
     * Get volume for a specific channel.
     */
    fun getChannelVolume(channelId: String): Float {
        return audioChannels[channelId]?.volume ?: 0f
    }

    /**
     * Apply theme to channel volumes.
     */
    fun applyTheme(theme: PlaybackTheme) {
        AppLogger.i(tag, "Applying theme: $theme (sfx=${theme.sfxVolumeMultiplier}, ambience=${theme.ambienceVolumeMultiplier})")
        setChannelVolume("sfx", theme.sfxVolumeMultiplier)
        setChannelVolume("ambience", theme.ambienceVolumeMultiplier)
        // Prosody intensity scaling is applied at TTS level, not mixer level
    }

    /**
     * Mix multiple audio files with their respective channels and volumes.
     * Returns mixed audio as FloatArray (normalized -1.0 to 1.0).
     */
    suspend fun mixAudioFiles(
        narrationFile: File? = null,
        dialogFile: File? = null,
        sfxFiles: List<File> = emptyList(),
        ambienceFile: File? = null
    ): FloatArray = withContext(Dispatchers.IO) {
        AppLogger.d(tag, "Mixing audio files: narration=${narrationFile?.name}, " +
                "dialog=${dialogFile?.name}, sfx=${sfxFiles.size} files, ambience=${ambienceFile?.name}")
        val startTime = System.currentTimeMillis()
        try {
            // Load all audio files in parallel
            val loadStartTime = System.currentTimeMillis()
            val narrationJob = async(Dispatchers.IO) { narrationFile?.let { loadAudioFile(it) } ?: FloatArray(0) }
            val dialogJob = async(Dispatchers.IO) { dialogFile?.let { loadAudioFile(it) } ?: FloatArray(0) }
            val sfxJobs = sfxFiles.map { async(Dispatchers.IO) { loadAudioFile(it) } }
            val ambienceJob = async(Dispatchers.IO) { ambienceFile?.let { loadAudioFile(it) } ?: FloatArray(0) }

            val narrationSamples = narrationJob.await()
            val dialogSamples = dialogJob.await()
            val sfxSamples = sfxJobs.awaitAll().filterNotNull()
            val ambienceSamples = ambienceJob.await()

            AppLogger.logPerformance(tag, "Parallel audio file loading", System.currentTimeMillis() - loadStartTime)

            // Find maximum length
            val maxLength = max(
                max(narrationSamples.size, dialogSamples.size),
                max(
                    sfxSamples.maxOfOrNull { it.size } ?: 0,
                    ambienceSamples.size
                )
            )

            if (maxLength == 0) {
                return@withContext FloatArray(0)
            }

            // Mix samples
            val mixed = FloatArray(maxLength) { 0f }

            // Mix narration
            if (narrationFile != null && audioChannels["narration"]!!.isActive) {
                val volume = audioChannels["narration"]!!.volume
                for (i in narrationSamples.indices) {
                    mixed[i] += narrationSamples[i] * volume
                }
            }

            // Mix dialog
            if (dialogFile != null && audioChannels["dialog"]!!.isActive) {
                val volume = audioChannels["dialog"]!!.volume
                for (i in dialogSamples.indices) {
                    mixed[i] += dialogSamples[i] * volume
                }
            }

            // Mix SFX
            if (sfxFiles.isNotEmpty() && audioChannels["sfx"]!!.isActive) {
                val volume = audioChannels["sfx"]!!.volume
                for (sfx in sfxSamples) {
                    for (i in sfx.indices) {
                        if (i < mixed.size) {
                            mixed[i] += sfx[i] * volume
                        }
                    }
                }
            }

            // Mix ambience (loop if shorter than mixed)
            if (ambienceFile != null && audioChannels["ambience"]!!.isActive) {
                val volume = audioChannels["ambience"]!!.volume
                for (i in mixed.indices) {
                    val ambienceIndex = i % ambienceSamples.size
                    mixed[i] += ambienceSamples[ambienceIndex] * volume
                }
            }

            // Normalize to prevent clipping
            val maxAmplitude = mixed.maxOfOrNull { kotlin.math.abs(it) } ?: 1f
            if (maxAmplitude > 1.0f) {
                val scale = 0.95f / maxAmplitude
                for (i in mixed.indices) {
                    mixed[i] *= scale
                }
            }

            AppLogger.logPerformance(tag, "Audio mixing", System.currentTimeMillis() - startTime)
            AppLogger.d(tag, "Audio mixing complete: ${mixed.size} samples, maxAmplitude=${mixed.maxOfOrNull { kotlin.math.abs(it) }}")
            mixed
        } catch (e: Exception) {
            AppLogger.e(tag, "Error mixing audio", e)
            FloatArray(0)
        }
    }

    /**
     * Play mixed audio using AudioTrack.
     */
    suspend fun playMixedAudio(mixedSamples: FloatArray) = withContext(Dispatchers.IO) {
        try {
            val channelConfig = if (numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()

            // Convert float samples to PCM16 and write
            val pcmBuffer = ByteBuffer.allocate(mixedSamples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)

            for (sample in mixedSamples) {
                val intSample = (sample.coerceIn(-1f, 1f) * 32767).toInt()
                pcmBuffer.putShort(intSample.toShort())
            }

            audioTrack?.write(pcmBuffer.array(), 0, pcmBuffer.position())
        } catch (e: Exception) {
            AppLogger.e(tag, "Error playing mixed audio", e)
        }
    }

    /**
     * Save mixed audio to WAV file.
     */
    suspend fun saveMixedAudio(mixedSamples: FloatArray, outputFile: File) = withContext(Dispatchers.IO) {
        try {
            val numSamples = mixedSamples.size
            val bitsPerSample = 16
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val dataSize = numSamples * numChannels * bitsPerSample / 8
            val fileSize = 36 + dataSize

            Files.newOutputStream(outputFile.toPath()).use { out ->
                // WAV header
                out.write("RIFF".toByteArray())
                out.write(intToBytes(fileSize), 0, 4)
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intToBytes(16), 0, 4)
                out.write(shortToBytes(1), 0, 2) // PCM
                out.write(shortToBytes(numChannels.toShort()), 0, 2)
                out.write(intToBytes(sampleRate), 0, 4)
                out.write(intToBytes(byteRate), 0, 4)
                out.write(shortToBytes(blockAlign.toShort()), 0, 2)
                out.write(shortToBytes(bitsPerSample.toShort()), 0, 2)
                out.write("data".toByteArray())
                out.write(intToBytes(dataSize), 0, 4)

                // PCM data
                for (sample in mixedSamples) {
                    val intSample = (sample.coerceIn(-1f, 1f) * 32767).toInt()
                    out.write(shortToBytes(intSample.toShort()), 0, 2)
                }
            }

            AppLogger.d(tag, "Saved mixed audio to: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error saving mixed audio", e)
        }
    }

    private fun loadAudioFile(file: File): FloatArray {
        return try {
            // Simple WAV loader - assumes 16-bit PCM mono
            val bytes = Files.readAllBytes(file.toPath())
            if (bytes.size < 44) return FloatArray(0) // WAV header is 44 bytes

            val dataStart = 44 // Skip WAV header
            val samples = ByteArray(bytes.size - dataStart)
            System.arraycopy(bytes, dataStart, samples, 0, samples.size)

            val floatSamples = FloatArray(samples.size / 2)
            val buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN)

            for (i in floatSamples.indices) {
                val shortSample = buffer.short
                floatSamples[i] = shortSample.toInt().toFloat() / 32768f
            }

            floatSamples
        } catch (e: Exception) {
            AppLogger.e(tag, "Error loading audio file: ${file.absolutePath}", e)
            FloatArray(0)
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

    fun stop() {
        AppLogger.d(tag, "Stopping audio mixer")
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isMixing = false
    }

    fun cleanup() {
        AppLogger.d(tag, "Cleaning up AudioMixer")
        stop()
        mixingScope.cancel()
    }
}
