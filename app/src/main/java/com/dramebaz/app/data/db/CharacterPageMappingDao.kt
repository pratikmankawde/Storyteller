package com.dramebaz.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterPageMappingDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mapping: CharacterPageMapping): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mappings: List<CharacterPageMapping>)
    
    @Update
    suspend fun update(mapping: CharacterPageMapping): Int
    
    /**
     * Get all segments for a specific page (for audio generation).
     */
    @Query("""
        SELECT * FROM character_page_mappings 
        WHERE bookId = :bookId AND chapterId = :chapterId AND pageNumber = :pageNumber 
        ORDER BY segmentIndex
    """)
    suspend fun getSegmentsForPage(bookId: Long, chapterId: Long, pageNumber: Int): List<CharacterPageMapping>
    
    /**
     * Get all segments for a character in a book (for speaker change handling).
     */
    @Query("""
        SELECT * FROM character_page_mappings 
        WHERE bookId = :bookId AND characterName = :characterName 
        ORDER BY chapterId, pageNumber, segmentIndex
    """)
    suspend fun getSegmentsForCharacter(bookId: Long, characterName: String): List<CharacterPageMapping>
    
    /**
     * Get all segments for a character on a specific page (for targeted regeneration).
     */
    @Query("""
        SELECT * FROM character_page_mappings 
        WHERE bookId = :bookId AND chapterId = :chapterId AND pageNumber = :pageNumber AND characterName = :characterName 
        ORDER BY segmentIndex
    """)
    suspend fun getSegmentsForCharacterOnPage(
        bookId: Long, 
        chapterId: Long, 
        pageNumber: Int, 
        characterName: String
    ): List<CharacterPageMapping>
    
    /**
     * Get all unique characters that appear on a page.
     */
    @Query("""
        SELECT DISTINCT characterName FROM character_page_mappings 
        WHERE bookId = :bookId AND chapterId = :chapterId AND pageNumber = :pageNumber
    """)
    suspend fun getCharactersOnPage(bookId: Long, chapterId: Long, pageNumber: Int): List<String>
    
    /**
     * Check if page has all audio generated.
     */
    @Query("""
        SELECT COUNT(*) = 0 FROM character_page_mappings 
        WHERE bookId = :bookId AND chapterId = :chapterId AND pageNumber = :pageNumber AND audioGenerated = 0
    """)
    suspend fun isPageAudioComplete(bookId: Long, chapterId: Long, pageNumber: Int): Boolean
    
    /**
     * Update audio status for a segment.
     */
    @Query("""
        UPDATE character_page_mappings 
        SET audioGenerated = :generated, audioPath = :path 
        WHERE id = :id
    """)
    suspend fun updateAudioStatus(id: Long, generated: Boolean, path: String?)
    
    /**
     * Update speaker ID for all segments of a character (when user changes speaker).
     */
    @Query("""
        UPDATE character_page_mappings 
        SET speakerId = :speakerId, audioGenerated = 0, audioPath = NULL 
        WHERE bookId = :bookId AND characterName = :characterName
    """)
    suspend fun updateSpeakerForCharacter(bookId: Long, characterName: String, speakerId: Int?)
    
    /**
     * Delete all mappings for a chapter (for re-analysis).
     */
    @Query("DELETE FROM character_page_mappings WHERE chapterId = :chapterId")
    suspend fun deleteByChapterId(chapterId: Long): Int
    
    /**
     * Delete all mappings for a book.
     */
    @Query("DELETE FROM character_page_mappings WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long): Int
    
    /**
     * Get segments that need audio generation for a page.
     */
    @Query("""
        SELECT * FROM character_page_mappings 
        WHERE bookId = :bookId AND chapterId = :chapterId AND pageNumber = :pageNumber AND audioGenerated = 0 
        ORDER BY segmentIndex
    """)
    suspend fun getPendingSegmentsForPage(bookId: Long, chapterId: Long, pageNumber: Int): List<CharacterPageMapping>
    
    /**
     * Count total segments for a chapter.
     */
    @Query("SELECT COUNT(*) FROM character_page_mappings WHERE chapterId = :chapterId")
    suspend fun countSegmentsForChapter(chapterId: Long): Int
    
    /**
     * Get all pages that have mappings for a chapter.
     */
    @Query("""
        SELECT DISTINCT pageNumber FROM character_page_mappings 
        WHERE bookId = :bookId AND chapterId = :chapterId 
        ORDER BY pageNumber
    """)
    suspend fun getPagesWithMappings(bookId: Long, chapterId: Long): List<Int>
}

