package com.dramebaz.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Book::class,
        Chapter::class,
        Character::class,
        Bookmark::class,
        SoundCue::class,
        ReadingSession::class,
        AppSettings::class,
        CharacterPageMapping::class,  // AUG-043: Character-page mapping for per-segment audio
        BookSeries::class  // SUMMARY-002: Book series for multi-book cross-reference
    ],
    version = 12,  // LIBRARY-001: Added library organization fields to books
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun characterDao(): CharacterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun soundCueDao(): SoundCueDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun characterPageMappingDao(): CharacterPageMappingDao  // AUG-043
    abstract fun bookSeriesDao(): BookSeriesDao  // SUMMARY-002
}

/**
 * Create the app database.
 *
 * NOTE: Using fallbackToDestructiveMigration() - this will DELETE the old database
 * and create a fresh one when the schema changes. User data will be lost on upgrade.
 * Migration support will be added later.
 */
fun createAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "dramebaz.db")
        .fallbackToDestructiveMigration()
        .build()
