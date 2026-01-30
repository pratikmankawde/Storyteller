package com.dramebaz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** T5.1: Character entity – id, name, traits, personalitysummary, voiceprofile, key_moments, relationships. */
@Entity(
    tableName = "characters",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class Character(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val traits: String, // JSON array or comma-separated
    val personalitySummary: String = "",
    /** T1.4: voice profile JSON */
    val voiceProfileJson: String? = null,
    /** T11.1: Selected speaker ID (0-108 for VCTK model). Null means use default mapping. */
    val speakerId: Int? = null,
    val keyMoments: String = "", // JSON array
    val relationships: String = ""  // JSON array
)
