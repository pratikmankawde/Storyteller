package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val format: String, // "pdf" | "epub" | "txt"
    val createdAt: Long = System.currentTimeMillis()
)
