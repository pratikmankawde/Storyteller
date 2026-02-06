package com.dramebaz.app.playback.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.ai.tts.SherpaTtsEngine
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.EmotionModifier
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * AUDIO-001: Enhanced Director Pipeline
 * 
 * Producer-Consumer audio pipeline using Kotlin Channels for better performance and latency.
 * Implements a three-stage pipeline:
 * 1. Producer: LLM script generation (dialog extraction with speaker/emotion)
 * 2. Transformer: TTS synthesis with speaker mapping
 * 3. Consumer: Audio playback with UI sync
 * 
 * Based on NovelReaderWeb docs/ARCHITECTURE.md - "The Director Pipeline"
 */
class AudioDirector(
    private val ttsEngine: SherpaTtsEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "AudioDirector"
        private const val SEGMENT_CHANNEL_CAPACITY = 10
        private const val AUDIO_BUFFER_CHANNEL_CAPACITY = 5
        private const val SAMPLE_RATE = 22050
    }

    // ==================== Data Classes ====================

    /**
     * A speech segment extracted from text, ready for TTS synthesis.
     */
    data class SpeechSegment(
        val text: String,
        val speaker: String,
        val emotion: String = "neutral",
        val intensity: Float = 0.5f,
        val isDialog: Boolean = false,
        val speakerId: Int? = null,
        val voiceProfile: VoiceProfile? = null,
        val index: Int = 0
    )

    /**
     * An audio buffer containing PCM audio data and its source segment.
     */
    data class AudioBuffer(
        val pcmData: FloatArray,
        val segment: SpeechSegment,
        val sampleRate: Int = SAMPLE_RATE,
        val durationMs: Long = 0L
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioBuffer) return false
            return pcmData.contentEquals(other.pcmData) && segment == other.segment
        }

        override fun hashCode(): Int {
            var result = pcmData.contentHashCode()
            result = 31 * result + segment.hashCode()
            return result
        }
    }

    /**
     * Playback state for UI synchronization.
     */
    data class PlaybackState(
        val currentSegment: SpeechSegment?,
        val positionMs: Long = 0L,
        val isPlaying: Boolean = false,
        val totalSegments: Int = 0,
        val completedSegments: Int = 0
    )

    // ==================== Channels ====================

    /** Channel for speech segments (Producer -> Transformer) */
    private var segmentChannel = Channel<SpeechSegment>(capacity = SEGMENT_CHANNEL_CAPACITY)

    /** Channel for audio buffers (Transformer -> Consumer) */
    private var audioBufferChannel = Channel<AudioBuffer>(capacity = AUDIO_BUFFER_CHANNEL_CAPACITY)

    // ==================== State ====================

    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    private var isPaused = false
    
    private var producerJob: Job? = null
    private var transformerJob: Job? = null
    private var consumerJob: Job? = null

    /** Character to speaker ID mapping (for consistent voice casting) */
    private val characterSpeakerMap = mutableMapOf<String, Int>()
    
    /** Default narrator speaker ID */
    private var narratorSpeakerId: Int = 0

    // ==================== UI Sync ====================

    private val _playbackState = MutableSharedFlow<PlaybackState>(replay = 1)
    val playbackState: SharedFlow<PlaybackState> = _playbackState.asSharedFlow()

    private val _activeSegment = MutableSharedFlow<SpeechSegment?>(replay = 1)
    val activeSegment: SharedFlow<SpeechSegment?> = _activeSegment.asSharedFlow()

    private var completedSegmentCount = 0
    private var totalSegmentCount = 0

    // ==================== Configuration ====================

    /**
     * Set the speaker ID for a character.
     */
    fun setCharacterSpeaker(characterName: String, speakerId: Int) {
        characterSpeakerMap[characterName.lowercase()] = speakerId
        AppLogger.d(TAG, "Character speaker mapped: $characterName -> $speakerId")
    }

    /**
     * Set the narrator speaker ID.
     */
    fun setNarratorSpeaker(speakerId: Int) {
        narratorSpeakerId = speakerId
        AppLogger.d(TAG, "Narrator speaker set to: $speakerId")
    }

    /**
     * Get speaker ID for a character (or narrator fallback).
     */
    fun getSpeakerId(characterName: String?): Int {
        if (characterName.isNullOrBlank() || characterName.lowercase() == "narrator") {
            return narratorSpeakerId
        }
        return characterSpeakerMap[characterName.lowercase()] ?: narratorSpeakerId
    }

    // ==================== Pipeline Control ====================

    /**
     * Start reading the page text using the Producer-Consumer pipeline.
     *
     * @param pageText The full page text to read
     * @param dialogs Pre-extracted dialogs (if available)
     * @param onComplete Callback when playback is complete
     */
    suspend fun startReading(
        pageText: String,
        dialogs: List<Dialog> = emptyList(),
        onComplete: (() -> Unit)? = null
    ) {
        if (isRunning) {
            AppLogger.w(TAG, "startReading called but pipeline already running")
            return
        }

        AppLogger.i(TAG, "Starting Director Pipeline: ${pageText.length} chars, ${dialogs.size} dialogs")

        // Reset state
        isRunning = true
        isPaused = false
        completedSegmentCount = 0

        // Create fresh channels
        segmentChannel = Channel(capacity = SEGMENT_CHANNEL_CAPACITY)
        audioBufferChannel = Channel(capacity = AUDIO_BUFFER_CHANNEL_CAPACITY)

        // Initialize AudioTrack
        initAudioTrack()

        // Launch pipeline stages
        scope.launch {
            try {
                coroutineScope {
                    // Producer: Generate speech segments from text
                    producerJob = launch(Dispatchers.Default) {
                        produceSegments(pageText, dialogs)
                    }

                    // Transformer: TTS synthesis for each segment
                    transformerJob = launch(Dispatchers.IO) {
                        transformSegments()
                    }

                    // Consumer: Play audio and emit UI sync events
                    consumerJob = launch(Dispatchers.IO) {
                        consumeAudioBuffers()
                    }
                }
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "Pipeline cancelled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Pipeline error", e)
            } finally {
                isRunning = false
                releaseAudioTrack()
                emitState(isPlaying = false)
                AppLogger.i(TAG, "Pipeline completed: $completedSegmentCount/$totalSegmentCount segments")
                onComplete?.invoke()
            }
        }
    }

    /**
     * Pause the pipeline.
     */
    fun pause() {
        if (!isRunning || isPaused) return
        isPaused = true
        audioTrack?.pause()
        emitState(isPlaying = false)
        AppLogger.d(TAG, "Pipeline paused")
    }

    /**
     * Resume the pipeline.
     */
    fun resume() {
        if (!isRunning || !isPaused) return
        isPaused = false
        audioTrack?.play()
        emitState(isPlaying = true)
        AppLogger.d(TAG, "Pipeline resumed")
    }

    /**
     * Stop the pipeline and clean up.
     */
    fun stop() {
        if (!isRunning) return
        AppLogger.d(TAG, "Stopping pipeline...")

        producerJob?.cancel()
        transformerJob?.cancel()
        consumerJob?.cancel()

        segmentChannel.close()
        audioBufferChannel.close()

        releaseAudioTrack()
        isRunning = false
        isPaused = false
        emitState(isPlaying = false)
    }

    /**
     * Check if the pipeline is currently running.
     */
    fun isPlaying(): Boolean = isRunning && !isPaused

    /**
     * Check if the pipeline is paused.
     */
    fun isPaused(): Boolean = isPaused

    // ==================== Producer Stage ====================

    /**
     * Producer: Generate speech segments from page text.
     * Parses text into narration and dialog segments with speaker/emotion info.
     */
    private suspend fun produceSegments(pageText: String, dialogs: List<Dialog>) {
        AppLogger.d(TAG, "Producer: Starting segment generation")

        try {
            val segments = buildSegmentsFromText(pageText, dialogs)
            totalSegmentCount = segments.size

            AppLogger.d(TAG, "Producer: Generated ${segments.size} segments")

            for (segment in segments) {
                if (!isRunning) break
                segmentChannel.send(segment)
                AppLogger.d(TAG, "Producer: Sent segment #${segment.index}: ${segment.speaker} (${segment.emotion})")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Producer: Error generating segments", e)
        } finally {
            segmentChannel.close()
            AppLogger.d(TAG, "Producer: Channel closed")
        }
    }

    /**
     * Build speech segments from page text and dialogs.
     * Interleaves narration with dialog segments.
     */
    private suspend fun buildSegmentsFromText(
        pageText: String,
        dialogs: List<Dialog>
    ): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        var segmentIndex = 0

        if (dialogs.isEmpty()) {
            // No dialogs - treat entire text as narration
            val sentences = splitIntoSentences(pageText)
            for (sentence in sentences) {
                if (sentence.isNotBlank()) {
                    segments.add(SpeechSegment(
                        text = sentence.trim(),
                        speaker = "Narrator",
                        emotion = "neutral",
                        isDialog = false,
                        speakerId = narratorSpeakerId,
                        index = segmentIndex++
                    ))
                }
            }
        } else {
            // Interleave narration with dialogs
            var currentPos = 0

            for (dialog in dialogs) {
                // Find dialog position in text
                val dialogPos = pageText.indexOf(dialog.dialog, currentPos)

                // Add narration before this dialog
                if (dialogPos > currentPos) {
                    val narration = pageText.substring(currentPos, dialogPos).trim()
                    if (narration.isNotBlank()) {
                        val sentences = splitIntoSentences(narration)
                        for (sentence in sentences) {
                            if (sentence.isNotBlank()) {
                                segments.add(SpeechSegment(
                                    text = sentence.trim(),
                                    speaker = "Narrator",
                                    emotion = "neutral",
                                    isDialog = false,
                                    speakerId = narratorSpeakerId,
                                    index = segmentIndex++
                                ))
                            }
                        }
                    }
                }

                // Add dialog segment
                segments.add(SpeechSegment(
                    text = dialog.dialog,
                    speaker = dialog.speaker,
                    emotion = dialog.emotion,
                    intensity = dialog.intensity,
                    isDialog = true,
                    speakerId = getSpeakerId(dialog.speaker),
                    index = segmentIndex++
                ))

                currentPos = if (dialogPos >= 0) dialogPos + dialog.dialog.length else currentPos
            }

            // Add remaining narration after last dialog
            if (currentPos < pageText.length) {
                val remaining = pageText.substring(currentPos).trim()
                if (remaining.isNotBlank()) {
                    val sentences = splitIntoSentences(remaining)
                    for (sentence in sentences) {
                        if (sentence.isNotBlank()) {
                            segments.add(SpeechSegment(
                                text = sentence.trim(),
                                speaker = "Narrator",
                                emotion = "neutral",
                                isDialog = false,
                                speakerId = narratorSpeakerId,
                                index = segmentIndex++
                            ))
                        }
                    }
                }
            }
        }

        return segments
    }

    /**
     * Split text into sentences for natural narration pacing.
     */
    private fun splitIntoSentences(text: String): List<String> {
        // Split on sentence-ending punctuation while preserving the punctuation
        return text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
    }

    // ==================== Transformer Stage ====================

    /**
     * Transformer: TTS synthesis for each segment.
     * Consumes segments from segmentChannel and produces audio buffers.
     */
    private suspend fun transformSegments() {
        AppLogger.d(TAG, "Transformer: Starting TTS synthesis")

        try {
            for (segment in segmentChannel) {
                if (!isRunning) break

                val startTime = System.currentTimeMillis()
                AppLogger.d(TAG, "Transformer: Synthesizing segment #${segment.index}: '${segment.text.take(50)}...'")

                try {
                    val audioBuffer = synthesizeSegment(segment)
                    if (audioBuffer != null) {
                        audioBufferChannel.send(audioBuffer)
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.d(TAG, "Transformer: Segment #${segment.index} synthesized in ${elapsed}ms")
                    } else {
                        AppLogger.w(TAG, "Transformer: Failed to synthesize segment #${segment.index}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Transformer: Error synthesizing segment #${segment.index}", e)
                }
            }
        } finally {
            audioBufferChannel.close()
            AppLogger.d(TAG, "Transformer: Channel closed")
        }
    }

    /**
     * Synthesize a single segment to audio.
     */
    private suspend fun synthesizeSegment(segment: SpeechSegment): AudioBuffer? {
        // Apply emotion modifiers to TTS parameters
        val emotionModifier = EmotionModifier.forEmotion(segment.emotion)
        val speed = emotionModifier.speedMultiplier

        // Use TTS engine to generate audio
        val result = ttsEngine.speak(
            text = segment.text,
            voiceProfile = segment.voiceProfile,
            speakerId = segment.speakerId
        )

        return result.fold(
            onSuccess = { audioFile ->
                audioFile?.let { file ->
                    // Read the audio file and convert to PCM
                    val pcmData = readAudioFileToPcm(file)
                    if (pcmData != null) {
                        val durationMs = (pcmData.size.toLong() * 1000) / SAMPLE_RATE
                        AudioBuffer(
                            pcmData = pcmData,
                            segment = segment,
                            sampleRate = SAMPLE_RATE,
                            durationMs = durationMs
                        )
                    } else {
                        null
                    }
                }
            },
            onFailure = { error ->
                AppLogger.e(TAG, "TTS synthesis failed for segment #${segment.index}", error)
                null
            }
        )
    }

    /**
     * Read audio file and convert to PCM float array.
     */
    private fun readAudioFileToPcm(file: File): FloatArray? {
        return try {
            // Read WAV file (generated by Sherpa TTS)
            val bytes = file.readBytes()

            // Skip WAV header (44 bytes for standard WAV)
            if (bytes.size < 44) return null

            // Convert 16-bit PCM to float samples
            val dataBytes = bytes.copyOfRange(44, bytes.size)
            val samples = FloatArray(dataBytes.size / 2)

            for (i in samples.indices) {
                val low = dataBytes[i * 2].toInt() and 0xFF
                val high = dataBytes[i * 2 + 1].toInt()
                val sample = (high shl 8) or low
                samples[i] = sample.toFloat() / 32768f
            }

            samples
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading audio file", e)
            null
        }
    }

    // ==================== Consumer Stage ====================

    /**
     * Consumer: Play audio buffers and emit UI sync events.
     */
    private suspend fun consumeAudioBuffers() {
        AppLogger.d(TAG, "Consumer: Starting audio playback")

        try {
            for (buffer in audioBufferChannel) {
                if (!isRunning) break

                // Wait if paused
                while (isPaused && isRunning) {
                    delay(100)
                }
                if (!isRunning) break

                AppLogger.d(TAG, "Consumer: Playing segment #${buffer.segment.index}")

                // Emit active segment for UI highlighting
                emitActiveSegment(buffer.segment)
                emitState(isPlaying = true)

                // Play the audio
                playAudioBuffer(buffer)

                completedSegmentCount++
                AppLogger.d(TAG, "Consumer: Completed segment #${buffer.segment.index} ($completedSegmentCount/$totalSegmentCount)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Consumer: Error during playback", e)
        } finally {
            emitActiveSegment(null)
            AppLogger.d(TAG, "Consumer: Playback finished")
        }
    }

    /**
     * Play a single audio buffer through AudioTrack.
     */
    private suspend fun playAudioBuffer(buffer: AudioBuffer) = withContext(Dispatchers.IO) {
        val track = audioTrack ?: return@withContext

        // Convert float samples to 16-bit PCM for AudioTrack
        val shortSamples = ShortArray(buffer.pcmData.size) { i ->
            (buffer.pcmData[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Write to AudioTrack (blocking until audio is written)
        track.write(shortSamples, 0, shortSamples.size)

        // Wait for the audio to finish playing
        val durationMs = buffer.durationMs
        if (durationMs > 0) {
            delay(durationMs)
        }
    }

    // ==================== AudioTrack Management ====================

    /**
     * Initialize AudioTrack for playback.
     */
    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        AppLogger.d(TAG, "AudioTrack initialized: sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize")
    }

    /**
     * Release AudioTrack resources.
     */
    private fun releaseAudioTrack() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        AppLogger.d(TAG, "AudioTrack released")
    }

    // ==================== UI Sync Helpers ====================

    private fun emitActiveSegment(segment: SpeechSegment?) {
        scope.launch {
            _activeSegment.emit(segment)
        }
    }

    private fun emitState(isPlaying: Boolean) {
        scope.launch {
            _playbackState.emit(PlaybackState(
                currentSegment = _activeSegment.replayCache.firstOrNull(),
                positionMs = 0L,
                isPlaying = isPlaying,
                totalSegments = totalSegmentCount,
                completedSegments = completedSegmentCount
            ))
        }
    }

    // ==================== Resource Cleanup ====================

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        scope.cancel()
        characterSpeakerMap.clear()
        AppLogger.d(TAG, "AudioDirector released")
    }
}

