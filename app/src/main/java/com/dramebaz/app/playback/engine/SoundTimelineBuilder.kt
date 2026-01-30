package com.dramebaz.app.playback.engine

import com.dramebaz.app.data.db.SoundCue

/**
 * T3.3: Aligns sound_cues with playback positions (e.g. before/after dialogs or paragraphs).
 */
data class TimedSoundEvent(
    val cue: SoundCue,
    val startOffsetMs: Long,
    val durationMs: Long
)

object SoundTimelineBuilder {

    /**
     * Place segment starts at paragraph indices. Assumes ~3s per paragraph as rough estimate.
     */
    fun build(
        cues: List<SoundCue>,
        paragraphCount: Int
    ): List<TimedSoundEvent> {
        if (cues.isEmpty()) return emptyList()
        val msPerParagraph = 3000L
        return cues.mapIndexed { i, cue ->
            val pos = (i.toLong() * paragraphCount / (cues.size + 1)) * msPerParagraph
            TimedSoundEvent(cue, pos, (cue.duration * 1000).toLong())
        }
    }
}
