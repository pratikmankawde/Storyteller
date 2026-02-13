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

    /** CHAP-001: Delete a single chapter by ID */
    @Query("DELETE FROM chapters WHERE id = :chapterId") suspend fun deleteChapter(chapterId: Long)

    /** Get all chapters for a book as a list (for dialog counting). */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex")
    suspend fun getChaptersList(bookId: Long): List<Chapter>

    /** CROSS-CHAPTER: Get the next chapter after the given orderIndex for a book. */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND orderIndex > :currentOrderIndex ORDER BY orderIndex ASC LIMIT 1")
    suspend fun getNextChapter(bookId: Long, currentOrderIndex: Int): Chapter?

    /** CROSS-CHAPTER: Get the previous chapter before the given orderIndex for a book. */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND orderIndex < :currentOrderIndex ORDER BY orderIndex DESC LIMIT 1")
    suspend fun getPreviousChapter(bookId: Long, currentOrderIndex: Int): Chapter?

    /** CROSS-CHAPTER: Get the orderIndex of a chapter by its ID. */
    @Query("SELECT orderIndex FROM chapters WHERE id = :chapterId")
    suspend fun getOrderIndex(chapterId: Long): Int?
}
