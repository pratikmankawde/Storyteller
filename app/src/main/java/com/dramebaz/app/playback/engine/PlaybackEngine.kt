package com.dramebaz.app.playback.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.media.MediaPlayer
import com.dramebaz.app.ai.tts.SherpaTtsEngine
import com.dramebaz.app.ai.tts.VoiceProfileMapper
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.VoiceProfile
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * T2.4: Playback engine that distinguishes narration vs dialog,
 * switches voices and prosody on the fly.
 */
class PlaybackEngine(
    private val context: Context,
    private val ttsEngine: SherpaTtsEngine,
    private val scope: CoroutineScope
) {
    private val tag = "PlaybackEngine"

    private var isPlaying = false
    private var currentPosition: Long = 0L
    private var totalDuration: Long = 0L
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val audioQueue = ConcurrentLinkedQueue<AudioSegment>()
    private val synthesizedAudioQueue = ConcurrentLinkedQueue<Pair<AudioSegment, File>>()
    private var currentSegment: AudioSegment? = null

    private var onProgressCallback: ((Long, Long) -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var onProgressSaveCallback: ((Long, Int) -> Unit)? = null  // AUG-018: (positionMs, segmentIndex)
    private var preSynthesisJob: Job? = null
    private var lookaheadJob: Job? = null
    private var lastProgressSaveTime = 0L  // AUG-018: Track last save time
    private var currentSegmentIndex = 0  // AUG-018: Track current segment for saving
    private var globalSpeedMultiplier = 1.0f  // AUG-021: Global speed multiplier

    // UI-001: Karaoke highlighting StateFlows
    private val _activeSegmentRange = MutableStateFlow<KaraokeHighlighter.TextRange?>(null)
    val activeSegmentRange: StateFlow<KaraokeHighlighter.TextRange?> = _activeSegmentRange.asStateFlow()

    private val _currentWordSegment = MutableStateFlow<KaraokeHighlighter.WordSegment?>(null)
    val currentWordSegment: StateFlow<KaraokeHighlighter.WordSegment?> = _currentWordSegment.asStateFlow()

    // Word segments for current audio segment (for word-level highlighting)
    private var currentWordSegments: List<KaraokeHighlighter.WordSegment> = emptyList()

    // UI-002: Amplitude StateFlow for waveform visualization
    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    // UI-002: Playback state for waveform (true when audio is playing)
    private val _isPlayingState = MutableStateFlow(false)
    val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

    // UI-003: Current speaker info for character avatar bubbles
    data class SpeakerInfo(
        val name: String,
        val speakerId: Int? = null,
        val isDialog: Boolean = false
    )
    private val _currentSpeaker = MutableStateFlow<SpeakerInfo?>(null)
    val currentSpeaker: StateFlow<SpeakerInfo?> = _currentSpeaker.asStateFlow()

    companion object {
        private const val LOOKAHEAD_SEGMENTS = 3  // Number of segments to pre-generate ahead
        private const val BUFFER_AHEAD_MS = 60_000L  // AUG-019: Buffer 60 seconds ahead
        private const val INITIAL_SEGMENTS = 5  // AUG-019: Generate 5 segments immediately
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L  // AUG-018: Save every 10 seconds
    }

    data class AudioSegment(
        val text: String,
        val isDialog: Boolean,
        val voiceProfile: VoiceProfile?,
        val prosodyParams: VoiceProfileMapper.TtsParams,
        val speakerId: Int? = null,  // T11.1: Optional speaker ID (0-108 for VCTK)
        val audioFile: File? = null,
        val startOffset: Long = 0L,
        // UI-001: Text range for karaoke highlighting
        val textStartIndex: Int = 0,
        val textEndIndex: Int = text.length,
        val speaker: String? = null
    )

    /**
     * Add a narration segment to the playback queue.
     * UI-003: Now includes speaker name for avatar display.
     */
    fun addNarration(
        text: String,
        emotionalArc: List<com.dramebaz.app.data.models.EmotionalSegment>,
        segmentIndex: Int,
        voiceProfile: VoiceProfile? = null,
        speakerId: Int? = null  // T11.1: Optional speaker ID
    ) {
        val prosody = ProsodyController.forNarration(emotionalArc, segmentIndex, voiceProfile)
        val segment = AudioSegment(
            text = text,
            isDialog = false,
            voiceProfile = voiceProfile,
            prosodyParams = prosody,
            speakerId = speakerId,
            speaker = "Narrator"  // UI-003: Mark as narrator
        )
        audioQueue.offer(segment)
        AppLogger.d(tag, "Added narration segment #$segmentIndex: textLength=${text.length}, " +
                "emotion=${emotionalArc.getOrNull(segmentIndex)?.emotion ?: "none"}, " +
                "queueSize=${audioQueue.size}")
    }

    /**
     * Add a dialog segment to the playback queue.
     * UI-003: Now includes speaker name for avatar display.
     * AUG-FEATURE: enableEmotionModifiers controls whether emotion-based prosody adjustments are applied.
     */
    fun addDialog(
        dialog: Dialog,
        characterVoiceProfile: VoiceProfile?,
        speakerId: Int? = null,  // UI-003: Speaker ID for the character
        enableEmotionModifiers: Boolean = true  // AUG-FEATURE: Feature toggle for emotion modifiers
    ) {
        val prosody = ProsodyController.forDialog(dialog, characterVoiceProfile, speakerId, enableEmotionModifiers)
        val segment = AudioSegment(
            text = dialog.dialog,
            isDialog = true,
            voiceProfile = characterVoiceProfile,
            prosodyParams = prosody,
            speakerId = speakerId,
            speaker = dialog.speaker  // UI-003: Pass speaker name
        )
        audioQueue.offer(segment)
        AppLogger.d(tag, "Added dialog segment: speaker=${dialog.speaker}, " +
                "emotion=${dialog.emotion}, intensity=${dialog.intensity}, " +
                "textLength=${dialog.dialog.length}, speakerId=$speakerId, queueSize=${audioQueue.size}")
    }

    /**
     * Pre-synthesize audio for all queued segments in parallel.
     * This allows playback to start immediately without waiting for TTS synthesis.
     */
    suspend fun preSynthesizeAudio(): Unit = withContext(Dispatchers.IO) {
        if (audioQueue.isEmpty()) {
            AppLogger.d(tag, "No segments to pre-synthesize")
            return@withContext
        }

        AppLogger.i(tag, "Pre-synthesizing audio for ${audioQueue.size} segments in parallel")
        val startTime = System.currentTimeMillis()

        // Create a snapshot of segments to synthesize
        val segmentsToSynthesize = audioQueue.toList()

        // Synthesize all segments in parallel
        val synthesisJobs = segmentsToSynthesize.mapIndexed { index, segment ->
            async(Dispatchers.IO) {
                try {
                    AppLogger.d(tag, "Pre-synthesizing segment #${index + 1}/${segmentsToSynthesize.size}: " +
                            "isDialog=${segment.isDialog}, textLength=${segment.text.length}")
                    val synthesisStartTime = System.currentTimeMillis()

                    val result = ttsEngine.speak(segment.text, segment.voiceProfile, null, segment.speakerId)

                    result.fold(
                        onSuccess = { audioFile ->
                            audioFile?.let { file ->
                                AppLogger.logPerformance(tag, "Pre-synthesis segment #${index + 1}",
                                        System.currentTimeMillis() - synthesisStartTime)
                                AppLogger.d(tag, "Pre-synthesized audio: ${file.absolutePath}, size=${file.length()} bytes")
                                Pair(segment, file)
                            } ?: run {
                                AppLogger.w(tag, "No audio file generated for pre-synthesis segment #${index + 1}")
                                null
                            }
                        },
                        onFailure = { error ->
                            AppLogger.e(tag, "Failed to pre-synthesize segment #${index + 1}", error)
                            null
                        }
                    )
                } catch (e: Exception) {
                    AppLogger.e(tag, "Error pre-synthesizing segment #${index + 1}", e)
                    null
                }
            }
        }

        // Wait for all synthesis jobs and add to synthesized queue
        val results = synthesisJobs.awaitAll().filterNotNull()
        results.forEach { (segment, file) ->
            synthesizedAudioQueue.offer(Pair(segment, file))
        }

        AppLogger.logPerformance(tag, "Pre-synthesis all segments (parallel)", System.currentTimeMillis() - startTime)
        AppLogger.i(tag, "Pre-synthesis complete: ${results.size}/${segmentsToSynthesize.size} segments ready")
    }

    private var segmentsProcessed = 0

    /**
     * Start playback of queued segments.
     * Uses pre-synthesized audio if available, otherwise synthesizes on-demand.
     */
    suspend fun play(): Unit = withContext(Dispatchers.IO) {
        if (isPlaying) {
            AppLogger.w(tag, "play() called but already playing")
            return@withContext
        }

        AppLogger.i(tag, "Starting playback: queueSize=${audioQueue.size}, preSynthesized=${synthesizedAudioQueue.size}")
        isPlaying = true
        segmentsProcessed = 0

        // Start pre-synthesis in background if not already done
        if (synthesizedAudioQueue.isEmpty() && audioQueue.isNotEmpty()) {
            AppLogger.d(tag, "No pre-synthesized audio, starting background synthesis")
            preSynthesisJob = scope.launch {
                preSynthesizeAudio()
            }
        }

        // Start playing the first segment
        playNextSegment()
    }

    /**
     * Play the next segment in the queue.
     * Called recursively via completion listener until queue is empty.
     */
    private suspend fun playNextSegment(): Unit = withContext(Dispatchers.IO) {
        if (!isPlaying) {
            AppLogger.d(tag, "playNextSegment: Not playing, stopping")
            return@withContext
        }

        // Get next segment (prefer pre-synthesized, fall back to on-demand)
        val segmentAndFile: Pair<AudioSegment, File>? = if (synthesizedAudioQueue.isNotEmpty()) {
            val pair = synthesizedAudioQueue.poll()
            if (pair != null) {
                segmentsProcessed++
                AppLogger.d(tag, "Using pre-synthesized audio for segment #$segmentsProcessed")
                pair
            } else {
                null
            }
        } else if (audioQueue.isNotEmpty()) {
            // Fallback: synthesize on-demand if pre-synthesis not ready
            val segment = audioQueue.poll() ?: run {
                // No more segments
                if (synthesizedAudioQueue.isEmpty()) {
                    AppLogger.i(tag, "Playback completed: processed $segmentsProcessed segments")
                    isPlaying = false
                    onCompleteCallback?.invoke()
                }
                return@withContext
            }
            segmentsProcessed++

            AppLogger.d(tag, "Synthesizing on-demand segment #$segmentsProcessed: " +
                    "isDialog=${segment.isDialog}, textLength=${segment.text.length}")

            try {
                val synthesisStartTime = System.currentTimeMillis()
                val result = ttsEngine.speak(segment.text, segment.voiceProfile, null, segment.speakerId)

                result.fold(
                    onSuccess = { file ->
                        if (file != null) {
                            AppLogger.logPerformance(tag, "On-demand TTS synthesis segment #$segmentsProcessed",
                                    System.currentTimeMillis() - synthesisStartTime)
                            Pair(segment, file)
                        } else {
                            AppLogger.w(tag, "On-demand synthesis failed for segment #$segmentsProcessed")
                            null
                        }
                    },
                    onFailure = { error ->
                        AppLogger.e(tag, "On-demand synthesis error for segment #$segmentsProcessed", error)
                        null
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(tag, "Error in on-demand synthesis segment #$segmentsProcessed", e)
                null
            }
        } else {
            // No more segments in either queue
            AppLogger.i(tag, "Playback completed: processed $segmentsProcessed segments")
            isPlaying = false
            onCompleteCallback?.invoke()
            return@withContext
        }

        segmentAndFile?.let { (segment: AudioSegment, audioFile: File) ->
            playSegment(audioFile, segment)
        } ?: run {
            // Failed to get segment, try next one
            playNextSegment()
        }
    }

    private suspend fun playSegment(audioFile: File, segment: AudioSegment) = withContext(Dispatchers.Main) {
        try {
            AppLogger.d(tag, "Playing segment: file=${audioFile.name}, isDialog=${segment.isDialog}")
            mediaPlayer?.release()

            // UI-001: Emit segment range for karaoke highlighting
            val segmentRange = KaraokeHighlighter.TextRange(
                start = segment.textStartIndex,
                end = segment.textEndIndex,
                text = segment.text,
                isDialog = segment.isDialog,
                speaker = segment.speaker
            )
            _activeSegmentRange.value = segmentRange
            AppLogger.d(tag, "UI-001: Emitting segment range [${segmentRange.start}, ${segmentRange.end}]")

            // UI-003: Emit current speaker for character avatar bubbles
            _currentSpeaker.value = SpeakerInfo(
                name = segment.speaker ?: "Narrator",
                speakerId = segment.speakerId,
                isDialog = segment.isDialog
            )
            AppLogger.d(tag, "UI-003: Current speaker: ${segment.speaker ?: "Narrator"}, speakerId=${segment.speakerId}")

            val player = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                val audioDuration = duration.toLong()
                AppLogger.d(tag, "MediaPlayer prepared: duration=${audioDuration}ms")

                // UI-001: Build word segments for fine-grained highlighting
                currentWordSegments = KaraokeHighlighter.buildWordSegments(
                    text = segment.text,
                    startIndex = segment.textStartIndex,
                    audioStartMs = 0L,
                    audioEndMs = audioDuration
                )
                AppLogger.d(tag, "UI-001: Built ${currentWordSegments.size} word segments")

                setOnCompletionListener {
                    release()
                    this@PlaybackEngine.mediaPlayer = null
                    // UI-001: Clear current word when segment completes
                    _currentWordSegment.value = null
                    // Continue to next segment
                    val engine = this@PlaybackEngine
                    scope.launch {
                        engine.playNextSegment()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    AppLogger.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    release()
                    this@PlaybackEngine.mediaPlayer = null
                    true
                }

                start()

                // UI-002: Update playing state for waveform
                _isPlayingState.value = true

                // Update progress
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val wordSegments = currentWordSegments  // Capture for runnable
                val updateProgress = object : Runnable {
                    override fun run() {
                        val mp = this@PlaybackEngine.mediaPlayer
                        val playing = this@PlaybackEngine.isPlaying
                        if (mp != null && playing) {
                            try {
                                val pos: Long = mp.currentPosition.toLong()
                                val dur: Long = mp.duration.toLong()
                                this@PlaybackEngine.currentPosition = pos
                                this@PlaybackEngine.totalDuration = dur
                                onProgressCallback?.invoke(pos, dur)

                                // UI-001: Update current word for karaoke highlighting
                                val currentWord = KaraokeHighlighter.findCurrentWord(wordSegments, pos)
                                if (currentWord != _currentWordSegment.value) {
                                    _currentWordSegment.value = currentWord
                                }

                                // UI-002: Emit simulated amplitude for waveform visualization
                                // Since MediaPlayer doesn't provide PCM access, simulate based on position
                                val amplitude = generateSimulatedAmplitude(pos, dur)
                                _audioAmplitude.value = amplitude

                                checkAndSaveProgress()  // AUG-018: Periodically save progress
                                handler.postDelayed(this, 50)  // UI-001: Faster updates for word-level sync
                            } catch (e: Exception) {
                                // Player was released
                            }
                        } else {
                            // UI-002: Reset amplitude when not playing
                            _audioAmplitude.value = 0f
                        }
                    }
                }
                handler.post(updateProgress)
            }
            mediaPlayer = player
        } catch (e: Exception) {
            AppLogger.e(tag, "Error playing segment", e)
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun pause() {
        AppLogger.d(tag, "Pausing playback")
        mediaPlayer?.pause()
        saveProgress()  // AUG-018: Save on pause
        // UI-002: Update playing state
        _isPlayingState.value = false
        _audioAmplitude.value = 0f
    }

    fun resume() {
        AppLogger.d(tag, "Resuming playback")
        mediaPlayer?.start()
        // UI-002: Update playing state
        _isPlayingState.value = true
    }

    fun stop() {
        AppLogger.i(tag, "Stopping playback: queueSize=${audioQueue.size}, preSynthesized=${synthesizedAudioQueue.size}, position=${currentPosition}ms")
        saveProgress()  // AUG-018: Save on stop
        isPlaying = false
        preSynthesisJob?.cancel()
        preSynthesisJob = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        audioQueue.clear()
        synthesizedAudioQueue.clear()
        currentPosition = 0L
        totalDuration = 0L
        // UI-001: Clear karaoke highlighting state
        _activeSegmentRange.value = null
        _currentWordSegment.value = null
        currentWordSegments = emptyList()
        // UI-002: Clear waveform state
        _isPlayingState.value = false
        _audioAmplitude.value = 0f
    }

    fun seekTo(position: Long) {
        val posInt = position.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        AppLogger.d(tag, "Seeking to position: ${position}ms (${posInt}ms)")
        mediaPlayer?.seekTo(posInt)
        currentPosition = position
    }

    fun setOnProgressListener(callback: (Long, Long) -> Unit) {
        onProgressCallback = callback
    }

    fun setOnCompleteListener(callback: () -> Unit) {
        onCompleteCallback = callback
    }

    fun getCurrentPosition(): Long = currentPosition
    fun getDuration(): Long = totalDuration
    fun isPlaying(): Boolean = isPlaying && mediaPlayer?.isPlaying == true

    /**
     * AUG-018: Set callback for progress persistence.
     * Called every PROGRESS_SAVE_INTERVAL_MS and on pause/stop.
     */
    fun setOnProgressSaveListener(callback: (Long, Int) -> Unit) {
        onProgressSaveCallback = callback
    }

    /**
     * AUG-018: Get current segment index for resuming.
     */
    fun getCurrentSegmentIndex(): Int = currentSegmentIndex

    /**
     * AUG-018: Resume playback from a saved position.
     */
    fun resumeFromPosition(segmentIndex: Int, positionMs: Long) {
        AppLogger.i(tag, "AUG-018: Resuming from segment $segmentIndex, position ${positionMs}ms")
        currentSegmentIndex = segmentIndex
        currentPosition = positionMs
        // Skip to the correct segment in the queue
        repeat(segmentIndex) {
            audioQueue.poll()
            synthesizedAudioQueue.poll()
        }
    }

    /**
     * AUG-021: Set the global speed multiplier for TTS.
     */
    fun setSpeedMultiplier(speed: Float) {
        AppLogger.d(tag, "AUG-021: Setting speed multiplier to $speed")
        globalSpeedMultiplier = speed.coerceIn(0.5f, 2.0f)
    }

    /**
     * AUG-021: Get the current speed multiplier.
     */
    fun getSpeedMultiplier(): Float = globalSpeedMultiplier

    /**
     * AUG-018: Save current progress (call on pause, stop, or periodically).
     */
    private fun saveProgress() {
        onProgressSaveCallback?.invoke(currentPosition, currentSegmentIndex)
        lastProgressSaveTime = System.currentTimeMillis()
        AppLogger.d(tag, "AUG-018: Saved progress: position=${currentPosition}ms, segment=$currentSegmentIndex")
    }

    /**
     * AUG-018: Check if it's time to save progress.
     */
    private fun checkAndSaveProgress() {
        val now = System.currentTimeMillis()
        if (now - lastProgressSaveTime >= PROGRESS_SAVE_INTERVAL_MS) {
            saveProgress()
        }
    }

    /**
     * UI-002: Generate simulated amplitude for waveform visualization.
     * Since MediaPlayer doesn't provide PCM buffer access, we simulate
     * natural-looking audio amplitude based on playback position.
     * This creates a visually pleasing waveform that responds to playback.
     */
    private fun generateSimulatedAmplitude(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 0f

        val time = positionMs / 1000.0
        // Combine multiple sine waves at different frequencies for natural speech pattern
        val wave1 = kotlin.math.sin(time * 8.0).toFloat() * 0.4f  // Base rhythm
        val wave2 = kotlin.math.sin(time * 13.0).toFloat() * 0.25f  // Mid frequency
        val wave3 = kotlin.math.sin(time * 23.0).toFloat() * 0.15f  // High frequency detail
        val wave4 = kotlin.math.sin(time * 3.0).toFloat() * 0.2f  // Slow envelope

        // Combine and normalize to 0.1-0.9 range (never silent, never clipping)
        val combined = (wave1 + wave2 + wave3 + wave4 + 1f) / 2f
        return (combined * 0.8f + 0.1f).coerceIn(0.1f, 0.9f)
    }

    fun cleanup() {
        AppLogger.d(tag, "Cleaning up PlaybackEngine")
        stop()
        // Scope is owned by caller, so we don't cancel it here
    }
}
