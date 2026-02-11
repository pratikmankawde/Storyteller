package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(character: Character): Long
    @Update fun update(character: Character): Int
    @Query("SELECT * FROM characters WHERE bookId = :bookId ORDER BY name") fun getByBookId(bookId: Long): Flow<List<Character>>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun getById(id: Long): Character?
    @Query("DELETE FROM characters WHERE bookId = :bookId") suspend fun deleteByBookId(bookId: Long): Int

    /** AUG-043: Get character by book ID and name for speaker ID lookup during audio generation. */
    @Query("SELECT * FROM characters WHERE bookId = :bookId AND name = :name LIMIT 1")
    suspend fun getByBookIdAndName(bookId: Long, name: String): Character?

    /** Synchronous version for use in merge operations - returns list directly without Flow. */
    @Query("SELECT * FROM characters WHERE bookId = :bookId ORDER BY name")
    suspend fun getByBookIdDirect(bookId: Long): List<Character>
}
