package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert fun insert(bookmark: Bookmark): Long
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestamp DESC") fun getByBookId(bookId: Long): Flow<List<Bookmark>>
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("SELECT * FROM bookmarks WHERE id = :id") suspend fun getById(id: Long): Bookmark?
}
