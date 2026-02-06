package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(settings: AppSettings): Long
    @Query("SELECT value FROM app_settings WHERE key = :key") suspend fun get(key: String): String?
}
