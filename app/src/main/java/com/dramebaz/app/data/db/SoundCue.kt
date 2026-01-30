package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** T3.1: SoundCue – chapterid, event, soundprompt, duration, category, file_path. */
@Entity(
    tableName = "sound_cues",
    foreignKeys = [ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chapterId")]
)
data class SoundCue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val event: String,
    val soundPrompt: String,
    val duration: Float,
    val category: String, // "effect" | "ambience"
    val filePath: String? = null,
    /** Optional: position hint (paragraph index or time offset) for timeline alignment */
    val positionHint: Int? = null
)
