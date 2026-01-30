package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** T8.3: Stored settings including playback theme. */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val key: String,
    val value: String
)
