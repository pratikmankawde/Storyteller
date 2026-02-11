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

    /** Alias for getCurrent() for cleaner code */
    @Query("SELECT * FROM reading_sessions WHERE id = 1") suspend fun get(): ReadingSession?

    /** Clear the reading session (reset to default state) */
    @Query("DELETE FROM reading_sessions WHERE id = 1") suspend fun clear()

    /** AUDIO-REGEN-001: Update last page index for audio regeneration targeting. */
    @Query("UPDATE reading_sessions SET lastPageIndex = :pageIndex WHERE id = 1")
    suspend fun updateLastPageIndex(pageIndex: Int)

    /** AUDIO-REGEN-001: Get last page index for a book, or default to 1 if not set. */
    @Query("SELECT lastPageIndex FROM reading_sessions WHERE id = 1 AND bookId = :bookId")
    suspend fun getLastPageIndexForBook(bookId: Long): Int?
}
