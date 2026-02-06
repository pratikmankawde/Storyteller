package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * SUMMARY-002: DAO for BookSeries operations.
 */
@Dao
interface BookSeriesDao {
    @Query("SELECT * FROM book_series ORDER BY name ASC")
    fun getAll(): Flow<List<BookSeries>>

    @Query("SELECT * FROM book_series WHERE id = :id")
    suspend fun getById(id: Long): BookSeries?

    @Query("SELECT * FROM book_series WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): BookSeries?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(series: BookSeries): Long

    @Update
    suspend fun update(series: BookSeries)

    @Delete
    suspend fun delete(series: BookSeries)

    @Query("DELETE FROM book_series WHERE id = :id")
    suspend fun deleteById(id: Long)
}

