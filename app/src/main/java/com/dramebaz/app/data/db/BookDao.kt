package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert fun insert(book: Book): Long
    @Query("SELECT * FROM books ORDER BY createdAt DESC") fun getAll(): Flow<List<Book>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun getById(id: Long): Book?
    @Query("SELECT * FROM books WHERE title = :title LIMIT 1") suspend fun getByTitle(title: String): Book?
    @Query("DELETE FROM books WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM books WHERE title = :title") suspend fun deleteByTitle(title: String)
}
