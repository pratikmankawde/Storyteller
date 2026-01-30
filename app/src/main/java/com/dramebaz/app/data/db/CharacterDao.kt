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
}
