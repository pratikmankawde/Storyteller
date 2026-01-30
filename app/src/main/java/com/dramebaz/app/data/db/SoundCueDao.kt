package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SoundCueDao {
    @Insert fun insert(cue: SoundCue): Long
    @Insert fun insertAll(cues: List<SoundCue>): List<Long>
    @Query("SELECT * FROM sound_cues WHERE chapterId = :chapterId ORDER BY id") fun getByChapterId(chapterId: Long): Flow<List<SoundCue>>
}
