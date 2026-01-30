package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Insert fun insert(chapter: Chapter): Long
    @Insert fun insertAll(chapters: List<Chapter>): List<Long>
    @Update fun update(chapter: Chapter): Int
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex") fun getByBookId(bookId: Long): Flow<List<Chapter>>
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun getById(id: Long): Chapter?
    @Query("DELETE FROM chapters WHERE bookId = :bookId") suspend fun deleteByBookId(bookId: Long)
}
