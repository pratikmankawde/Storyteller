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
        AppSettings::class,
        CharacterPageMapping::class  // AUG-043: Character-page mapping for per-segment audio
    ],
    version = 8,  // Added dialogsJson to Character for extracted dialogs (3-pass workflow)
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
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN fullAnalysisJson TEXT")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN playbackPositionMs INTEGER NOT NULL DEFAULT 0")
    }
}

/** AUG-035: Migration to add composite indexes for optimized queries. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Chapter composite index: (bookId, orderIndex)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapter_book_order ON chapters(bookId, orderIndex)")
        // Character composite index: (bookId, name)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_character_book_name ON characters(bookId, name)")
        // Bookmark composite indexes: (bookId, chapterId) and (bookId, timestamp)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmark_book_chapter ON bookmarks(bookId, chapterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmark_book_time ON bookmarks(bookId, timestamp)")
    }
}

/** AUG-042: Migration to add pdfPagesJson column for storing individual PDF pages. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN pdfPagesJson TEXT")
    }
}

/** AUG-043: Migration to add CharacterPageMapping table for per-segment audio caching. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS character_page_mappings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                chapterId INTEGER NOT NULL,
                pageNumber INTEGER NOT NULL,
                segmentIndex INTEGER NOT NULL,
                characterName TEXT NOT NULL,
                speakerId INTEGER,
                dialogText TEXT NOT NULL,
                isDialog INTEGER NOT NULL,
                firstAppearance INTEGER NOT NULL DEFAULT 0,
                audioGenerated INTEGER NOT NULL DEFAULT 0,
                audioPath TEXT,
                FOREIGN KEY (bookId) REFERENCES books(id) ON DELETE CASCADE,
                FOREIGN KEY (chapterId) REFERENCES chapters(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mapping_bookId ON character_page_mappings(bookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mapping_chapterId ON character_page_mappings(chapterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mapping_book_chapter_page ON character_page_mappings(bookId, chapterId, pageNumber)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mapping_book_character ON character_page_mappings(bookId, characterName)")
    }
}

/** Migration to add dialogsJson column for storing extracted dialogs (used by 3-pass workflow Pass-2). */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN dialogsJson TEXT")
    }
}

fun createAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "dramebaz.db")
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        .build()
