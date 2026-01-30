package com.dramebaz.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Book::class,
        Chapter::class,
        Character::class,
        Bookmark::class,
        SoundCue::class,
        ReadingSession::class,
        AppSettings::class
    ],
    version = 3,
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
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN fullAnalysisJson TEXT")
    }
}

fun createAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "dramebaz.db")
        .addMigrations(MIGRATION_2_3)
        .build()
