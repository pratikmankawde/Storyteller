package com.dramebaz.app.playback.engine

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.animation.AccelerateDecelerateInterpolator
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.playback.engine.ColoredUnderlineSpanCompat

/**
 * UI-001: Karaoke Text Highlighting.
 * 
 * Provides word-by-word text synchronization with audio playback.
 * Highlights current segment/word as TTS plays with smooth color animations.
 * 
 * From NovelReaderWeb docs/UI.md - "Karaoke Flow"
 */
object KaraokeHighlighter {
    private const val TAG = "KaraokeHighlighter"
    private const val ANIMATION_DURATION_MS = 200L
    
    /**
     * Represents a text range for highlighting.
     */
    data class TextRange(
        val start: Int,
        val end: Int,
        val text: String = "",
        val isDialog: Boolean = false,
        val speaker: String? = null
    ) {
        val isEmpty: Boolean get() = start >= end
        val length: Int get() = end - start
    }
    
    /**
     * Word-level segment for fine-grained karaoke highlighting.
     */
    data class WordSegment(
        val word: String,
        val startIndex: Int,
        val endIndex: Int,
        val audioStartMs: Long,
        val audioEndMs: Long
    )
    
    /**
     * Build word-level segments from a text segment for fine-grained karaoke highlighting.
     * Distributes the segment duration proportionally across words based on character count.
     */
    fun buildWordSegments(
        text: String,
        startIndex: Int,
        audioStartMs: Long,
        audioEndMs: Long
    ): List<WordSegment> {
        val words = mutableListOf<WordSegment>()
        val totalDuration = audioEndMs - audioStartMs
        
        // Split by whitespace while preserving word positions
        var currentIndex = startIndex
        val wordMatches = Regex("\\S+").findAll(text)
        
        val allWords = wordMatches.toList()
        if (allWords.isEmpty()) return words
        
        // Calculate total character count for proportional timing
        val totalChars = allWords.sumOf { it.value.length }
        var currentTime = audioStartMs
        
        for ((i, match) in allWords.withIndex()) {
            val wordStart = startIndex + match.range.first
            val wordEnd = startIndex + match.range.last + 1
            
            // Proportional duration based on character count
            val wordDuration = if (i == allWords.lastIndex) {
                // Last word gets remaining time to avoid rounding errors
                audioEndMs - currentTime
            } else {
                (match.value.length.toFloat() / totalChars * totalDuration).toLong()
            }
            
            words.add(WordSegment(
                word = match.value,
                startIndex = wordStart,
                endIndex = wordEnd,
                audioStartMs = currentTime,
                audioEndMs = currentTime + wordDuration
            ))
            
            currentTime += wordDuration
        }
        
        AppLogger.d(TAG, "Built ${words.size} word segments from text (${text.length} chars)")
        return words
    }
    
    /**
     * Find the current word being spoken based on playback position.
     */
    fun findCurrentWord(wordSegments: List<WordSegment>, positionMs: Long): WordSegment? {
        return wordSegments.firstOrNull { word ->
            positionMs >= word.audioStartMs && positionMs < word.audioEndMs
        } ?: wordSegments.lastOrNull { positionMs >= it.audioStartMs }
    }
    
    /**
     * Create a SpannableString with word-level highlighting.
     * Highlights both the current segment (light) and current word (bold).
     *
     * @deprecated Use highlightWordWithUnderline instead for character-colored underlines
     */
    fun highlightWord(
        fullText: String,
        segmentRange: TextRange,
        currentWord: WordSegment?,
        segmentHighlightColor: Int,
        wordHighlightColor: Int
    ): SpannableString {
        // Delegate to the new underline-based highlighting with character colors
        return highlightWordWithUnderline(
            fullText = fullText,
            segmentRange = segmentRange,
            currentWord = currentWord
        )
    }

    /**
     * Create a SpannableString with underline-based highlighting.
     * Uses character-specific colors from CharacterColorPalette.
     * The current word is shown bold with colored underline based on speaker.
     *
     * @param fullText The complete text to display
     * @param segmentRange The current segment being spoken (includes speaker info)
     * @param currentWord The current word being spoken (for bold emphasis)
     */
    fun highlightWordWithUnderline(
        fullText: String,
        segmentRange: TextRange,
        currentWord: WordSegment?
    ): SpannableString {
        val spannable = SpannableString(fullText)

        // Get the underline color for the current speaker
        val underlineColor = CharacterColorPalette.getColorForCharacter(segmentRange.speaker)

        // Underline the entire segment with character-specific color
        if (!segmentRange.isEmpty && segmentRange.end <= fullText.length) {
            spannable.setSpan(
                ColoredUnderlineSpanCompat(underlineColor, 4f),
                segmentRange.start,
                segmentRange.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Highlight the current word with bold
        currentWord?.let { word ->
            if (word.endIndex <= fullText.length && word.startIndex >= 0) {
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    word.startIndex,
                    word.endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return spannable
    }
    
    /**
     * Create an animator for smooth color transitions between highlight states.
     */
    fun createColorAnimator(
        fromColor: Int,
        toColor: Int,
        onUpdate: (Int) -> Unit
    ): ValueAnimator {
        return ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                onUpdate(animator.animatedValue as Int)
            }
        }
    }
}

