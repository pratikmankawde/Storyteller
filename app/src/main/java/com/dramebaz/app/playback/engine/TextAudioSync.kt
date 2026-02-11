package com.dramebaz.app.playback.engine

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.playback.engine.CharacterColorPalette
import com.dramebaz.app.playback.engine.ColoredUnderlineSpanCompat

/**
 * T6.3: Text-audio synchronization - maps text spans to audio segments.
 * Provides highlighting based on playback position.
 */
object TextAudioSync {
    private const val tag = "TextAudioSync"

    data class TextSegment(
        val text: String,
        val startIndex: Int,
        val endIndex: Int,
        val audioStartMs: Long,
        val audioEndMs: Long,
        val isDialog: Boolean = false,
        val speaker: String? = null
    )

    /**
     * Build segments from chapter text, dialogs, and estimated audio durations.
     * Assumes ~150 words per minute for narration, ~180 for dialog.
     */
    fun buildSegments(
        chapterText: String,
        dialogs: List<com.dramebaz.app.data.models.Dialog>? = null,
        narrationWPM: Int = 150,
        dialogWPM: Int = 180
    ): List<TextSegment> {
        AppLogger.d(tag, "Building text segments: textLength=${chapterText.length}, " +
                "dialogs=${dialogs?.size ?: 0}, narrationWPM=$narrationWPM, dialogWPM=$dialogWPM")
        val startTime = System.currentTimeMillis()

        val segments = mutableListOf<TextSegment>()
        var currentOffset = 0L
        var textIndex = 0

        // Split text into paragraphs
        val paragraphs = chapterText.split("\n\n", "\n").filter { it.isNotBlank() }
        AppLogger.d(tag, "Split into ${paragraphs.size} paragraphs")

        for (paragraph in paragraphs) {
            val startIndex = textIndex
            val endIndex = textIndex + paragraph.length

            // Check if this paragraph contains dialog
            val paragraphDialogs = dialogs?.filter { dialog ->
                paragraph.contains(dialog.dialog, ignoreCase = true)
            } ?: emptyList()

            if (paragraphDialogs.isNotEmpty()) {
                // Split paragraph into narration and dialog segments
                var paraOffset = 0L
                var paraTextIndex = startIndex

                for (dialog in paragraphDialogs) {
                    val dialogStart = paragraph.indexOf(dialog.dialog, paraTextIndex - startIndex)
                    if (dialogStart >= 0) {
                        // Narration before dialog
                        val narrationText = paragraph.substring(0, dialogStart).trim()
                        if (narrationText.isNotBlank()) {
                            val wordCount = narrationText.split(Regex("\\s+")).size
                            val durationMs = (wordCount.toFloat() / narrationWPM * 60 * 1000).toLong()
                            segments.add(TextSegment(
                                text = narrationText,
                                startIndex = paraTextIndex,
                                endIndex = paraTextIndex + narrationText.length,
                                audioStartMs = currentOffset + paraOffset,
                                audioEndMs = currentOffset + paraOffset + durationMs,
                                isDialog = false
                            ))
                            paraOffset += durationMs
                            paraTextIndex += narrationText.length
                        }

                        // Dialog segment
                        val dialogWordCount = dialog.dialog.split(Regex("\\s+")).size
                        val dialogDurationMs = (dialogWordCount.toFloat() / dialogWPM * 60 * 1000).toLong()
                        segments.add(TextSegment(
                            text = dialog.dialog,
                            startIndex = paraTextIndex,
                            endIndex = paraTextIndex + dialog.dialog.length,
                            audioStartMs = currentOffset + paraOffset,
                            audioEndMs = currentOffset + paraOffset + dialogDurationMs,
                            isDialog = true,
                            speaker = dialog.speaker
                        ))
                        paraOffset += dialogDurationMs
                        paraTextIndex += dialog.dialog.length
                    }
                }

                // Remaining narration after last dialog
                val remainingText = paragraph.substring(paraTextIndex - startIndex).trim()
                if (remainingText.isNotBlank()) {
                    val wordCount = remainingText.split(Regex("\\s+")).size
                    val durationMs = (wordCount.toFloat() / narrationWPM * 60 * 1000).toLong()
                    segments.add(TextSegment(
                        text = remainingText,
                        startIndex = paraTextIndex,
                        endIndex = paraTextIndex + remainingText.length,
                        audioStartMs = currentOffset + paraOffset,
                        audioEndMs = currentOffset + paraOffset + durationMs,
                        isDialog = false
                    ))
                    paraOffset += durationMs
                }

                currentOffset += paraOffset
            } else {
                // Pure narration paragraph
                val wordCount = paragraph.split(Regex("\\s+")).size
                val durationMs = (wordCount.toFloat() / narrationWPM * 60 * 1000).toLong()
                segments.add(TextSegment(
                    text = paragraph,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    audioStartMs = currentOffset,
                    audioEndMs = currentOffset + durationMs,
                    isDialog = false
                ))
                currentOffset += durationMs
            }

            textIndex = endIndex + 2 // +2 for paragraph separator
        }

        AppLogger.logPerformance(tag, "Build text segments", System.currentTimeMillis() - startTime)
        AppLogger.d(tag, "Built ${segments.size} segments: narration=${segments.count { !it.isDialog }}, " +
                "dialog=${segments.count { it.isDialog }}, totalDuration=${segments.lastOrNull()?.audioEndMs ?: 0}ms")
        return segments
    }

    /**
     * Find the current text segment based on playback position.
     */
    fun findCurrentSegment(segments: List<TextSegment>, positionMs: Long): TextSegment? {
        val segment = segments.firstOrNull { segment ->
            positionMs >= segment.audioStartMs && positionMs < segment.audioEndMs
        } ?: segments.lastOrNull { positionMs >= it.audioStartMs }
        if (segment != null) {
            AppLogger.d(tag, "Found segment at ${positionMs}ms: isDialog=${segment.isDialog}, " +
                    "textLength=${segment.text.length}, speaker=${segment.speaker ?: "narrator"}")
        }
        return segment
    }

    /**
     * Highlight the current segment in the text.
     * Uses underline with character-specific colors instead of background highlight.
     */
    fun highlightCurrentSegment(
        text: String,
        segments: List<TextSegment>,
        positionMs: Long,
        highlightColor: Int,
        textColor: Int? = null
    ): SpannableString {
        val spannable = SpannableString(text)
        val currentSegment = findCurrentSegment(segments, positionMs)

        currentSegment?.let { segment ->
            if (segment.startIndex < text.length && segment.endIndex <= text.length) {
                // Get the underline color for the current speaker
                val underlineColor = CharacterColorPalette.getColorForCharacter(segment.speaker)

                // Use colored underline instead of background highlight
                spannable.setSpan(
                    ColoredUnderlineSpanCompat(underlineColor, 4f),
                    segment.startIndex,
                    segment.endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                textColor?.let {
                    spannable.setSpan(
                        ForegroundColorSpan(it),
                        segment.startIndex,
                        segment.endIndex,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        return spannable
    }

    /**
     * AUG-017: Update segment timings with actual audio durations.
     * This replaces WPM-based estimates with actual durations from TTS audio files.
     *
     * @param segments Original segments with WPM-based estimates
     * @param actualDurations Map of segment index to actual duration in milliseconds
     * @return New list of segments with accurate timing
     */
    fun updateWithActualDurations(
        segments: List<TextSegment>,
        actualDurations: Map<Int, Long>
    ): List<TextSegment> {
        if (actualDurations.isEmpty()) return segments

        AppLogger.d(tag, "AUG-017: Updating ${segments.size} segments with ${actualDurations.size} actual durations")

        var currentOffset = 0L
        val updatedSegments = segments.mapIndexed { index, segment ->
            val actualDuration = actualDurations[index]
            val newSegment = if (actualDuration != null) {
                segment.copy(
                    audioStartMs = currentOffset,
                    audioEndMs = currentOffset + actualDuration
                )
            } else {
                // Keep estimated duration, just update offset
                val estimatedDuration = segment.audioEndMs - segment.audioStartMs
                segment.copy(
                    audioStartMs = currentOffset,
                    audioEndMs = currentOffset + estimatedDuration
                )
            }
            currentOffset = newSegment.audioEndMs
            newSegment
        }

        val totalDuration = updatedSegments.lastOrNull()?.audioEndMs ?: 0L
        AppLogger.d(tag, "AUG-017: Updated timings complete. Total duration: ${totalDuration}ms")

        return updatedSegments
    }

    /**
     * AUG-017: Get audio duration from file (samples / sampleRate).
     * Returns duration in milliseconds.
     */
    fun getAudioDurationMs(audioFile: java.io.File, sampleRate: Int = 22050): Long {
        return try {
            // Each sample is 4 bytes (32-bit float in PCM format)
            // For WAV files with header, we need to subtract header size (44 bytes typically)
            val fileSize = audioFile.length()
            val dataSize = (fileSize - 44).coerceAtLeast(0)
            val numSamples = dataSize / 2  // 16-bit audio = 2 bytes per sample
            val durationMs = (numSamples * 1000 / sampleRate)
            AppLogger.d(tag, "AUG-017: Audio duration for ${audioFile.name}: ${durationMs}ms (samples: $numSamples)")
            durationMs
        } catch (e: Exception) {
            AppLogger.e(tag, "AUG-017: Failed to get audio duration", e)
            0L
        }
    }
}
