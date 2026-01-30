package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(session: ReadingSession): Long
    @Update fun update(session: ReadingSession): Int
    @Query("SELECT * FROM reading_sessions WHERE id = 1") suspend fun getCurrent(): ReadingSession?
}
