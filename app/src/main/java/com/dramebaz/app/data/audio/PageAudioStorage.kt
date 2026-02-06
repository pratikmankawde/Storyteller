package com.dramebaz.app.data.audio

import android.content.Context
import com.dramebaz.app.utils.AppLogger
import java.io.File

/**
 * Persists and retrieves generated TTS audio per book/chapter/page so it can be reused
 * across sessions. Files are stored under app files dir (not cache) so they survive
 * cache clears.
 */
class PageAudioStorage(private val context: Context) {

    private val tag = "PageAudioStorage"
    private val rootDir: File
        get() = File(context.filesDir, DIR_NAME).also {
            if (!it.exists()) it.mkdirs()
        }

    /**
     * Returns the persisted audio file for the given page if it exists, null otherwise.
     */
    fun getAudioFile(bookId: Long, chapterId: Long, pageIndex: Int): File? {
        val file = pageFile(bookId, chapterId, pageIndex)
        return if (file.exists() && file.length() > 0) {
            AppLogger.d(tag, "Found saved audio: book=$bookId chapter=$chapterId page=$pageIndex")
            file
        } else null
    }

    /**
     * Saves the given source file as the persisted audio for this book/chapter/page.
     * Copies the file so the caller can keep using the original (e.g. cache file).
     * Returns the persisted file.
     */
    fun saveAudioFile(bookId: Long, chapterId: Long, pageIndex: Int, sourceFile: File): File {
        val dest = pageFile(bookId, chapterId, pageIndex)
        dest.parentFile?.mkdirs()
        sourceFile.copyTo(dest, overwrite = true)
        AppLogger.i(tag, "Saved audio: book=$bookId chapter=$chapterId page=$pageIndex -> ${dest.absolutePath}")
        return dest
    }

    /**
     * Load all saved page files for a chapter into a map (pageIndex -> File).
     * Only returns files that exist and have size > 0.
     */
    fun getSavedPagesForChapter(bookId: Long, chapterId: Long): Map<Int, File> {
        val chapterDir = File(rootDir, "book_$bookId").resolve("chapter_$chapterId")
        if (!chapterDir.exists()) return emptyMap()
        val map = mutableMapOf<Int, File>()
        chapterDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith(PAGE_PREFIX) && name.endsWith(WAV_EXT)) {
                val indexStr = name.removePrefix(PAGE_PREFIX).removeSuffix(WAV_EXT)
                indexStr.toIntOrNull()?.let { index ->
                    if (file.length() > 0) map[index] = file
                }
            }
        }
        AppLogger.d(tag, "Loaded ${map.size} saved pages for book=$bookId chapter=$chapterId")
        return map
    }

    private fun pageFile(bookId: Long, chapterId: Long, pageIndex: Int): File {
        return File(File(rootDir, "book_$bookId"), "chapter_$chapterId")
            .resolve("$PAGE_PREFIX$pageIndex$WAV_EXT")
    }

    /**
     * Get the file path for a page audio file (for stitching segments).
     * Creates parent directories if needed.
     */
    fun getAudioFilePath(bookId: Long, chapterId: Long, pageIndex: Int): File {
        val file = pageFile(bookId, chapterId, pageIndex)
        file.parentFile?.mkdirs()
        return file
    }

    // =====================================================================
    // AUG-043: Per-segment audio file support
    // Path format: audio/{bookId}/ch{chapterId}/p{pageNum}/seg{index}_{char}_{speakerId}.wav
    // =====================================================================

    /**
     * Get the directory for a specific page's segments.
     */
    private fun segmentDir(bookId: Long, chapterId: Long, pageNumber: Int): File {
        return File(rootDir, "book_$bookId")
            .resolve("ch$chapterId")
            .resolve("p$pageNumber")
    }

    /**
     * Get the file for a specific segment.
     * @param bookId Book ID
     * @param chapterId Chapter ID
     * @param pageNumber 1-based page number
     * @param segmentIndex 0-based segment index on the page
     * @param characterName Character name (or "Narrator")
     * @param speakerId Speaker ID used for TTS
     */
    fun getSegmentAudioFile(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        segmentIndex: Int,
        characterName: String,
        speakerId: Int
    ): File? {
        val file = segmentFile(bookId, chapterId, pageNumber, segmentIndex, characterName, speakerId)
        return if (file.exists() && file.length() > 0) {
            AppLogger.d(tag, "Found segment audio: book=$bookId ch=$chapterId p=$pageNumber seg=$segmentIndex char=$characterName")
            file
        } else null
    }

    /**
     * Save audio for a specific segment.
     * @param bookId Book ID
     * @param chapterId Chapter ID
     * @param pageNumber 1-based page number
     * @param segmentIndex 0-based segment index on the page
     * @param characterName Character name (or "Narrator")
     * @param speakerId Speaker ID used for TTS
     * @param sourceFile Source audio file to copy
     * @return The saved file path
     */
    fun saveSegmentAudioFile(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        segmentIndex: Int,
        characterName: String,
        speakerId: Int,
        sourceFile: File
    ): File {
        val dest = segmentFile(bookId, chapterId, pageNumber, segmentIndex, characterName, speakerId)
        dest.parentFile?.mkdirs()
        sourceFile.copyTo(dest, overwrite = true)
        AppLogger.i(tag, "Saved segment: book=$bookId ch=$chapterId p=$pageNumber seg=$segmentIndex -> ${dest.absolutePath}")
        return dest
    }

    /**
     * Get all segment audio files for a page, ordered by segment index.
     * @return Map of segmentIndex -> File
     */
    fun getSegmentsForPage(bookId: Long, chapterId: Long, pageNumber: Int): Map<Int, File> {
        val dir = segmentDir(bookId, chapterId, pageNumber)
        if (!dir.exists()) return emptyMap()

        val map = mutableMapOf<Int, File>()
        dir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith(SEGMENT_PREFIX) && name.endsWith(WAV_EXT)) {
                // Parse: seg{index}_{char}_{speakerId}.wav
                val parts = name.removePrefix(SEGMENT_PREFIX).removeSuffix(WAV_EXT).split("_", limit = 2)
                parts.firstOrNull()?.toIntOrNull()?.let { index ->
                    if (file.length() > 0) map[index] = file
                }
            }
        }
        AppLogger.d(tag, "Found ${map.size} segments for book=$bookId ch=$chapterId p=$pageNumber")
        return map.toSortedMap()
    }

    /**
     * Check if all segments for a page have audio generated.
     * @param expectedCount Expected number of segments on the page
     */
    fun isPageAudioComplete(bookId: Long, chapterId: Long, pageNumber: Int, expectedCount: Int): Boolean {
        val segments = getSegmentsForPage(bookId, chapterId, pageNumber)
        return segments.size >= expectedCount
    }

    /**
     * Delete all segment audio files for a specific character in a book.
     * Used when user changes a character's speaker ID.
     * @return Number of files deleted
     */
    fun deleteSegmentsForCharacter(bookId: Long, characterName: String): Int {
        val sanitizedName = sanitizeFileName(characterName)
        var deletedCount = 0
        val bookDir = File(rootDir, "book_$bookId")
        if (!bookDir.exists()) return 0

        bookDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.contains("_${sanitizedName}_") && file.name.endsWith(WAV_EXT)) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        AppLogger.i(tag, "Deleted $deletedCount segment files for character '$characterName' in book $bookId")
        return deletedCount
    }

    /**
     * Delete all segments for a specific page.
     * Used when re-generating page audio.
     */
    fun deleteSegmentsForPage(bookId: Long, chapterId: Long, pageNumber: Int): Int {
        val dir = segmentDir(bookId, chapterId, pageNumber)
        if (!dir.exists()) return 0

        var deletedCount = 0
        dir.listFiles()?.forEach { file ->
            if (file.delete()) deletedCount++
        }
        if (dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
        AppLogger.i(tag, "Deleted $deletedCount segment files for book=$bookId ch=$chapterId p=$pageNumber")
        return deletedCount
    }

    /**
     * Delete all audio files for a book.
     * Called when a book is deleted to clean up all associated audio.
     * @return Number of files deleted
     */
    fun deleteAudioForBook(bookId: Long): Int {
        val bookDir = File(rootDir, "book_$bookId")
        if (!bookDir.exists()) {
            AppLogger.d(tag, "No audio directory for book $bookId")
            return 0
        }

        var deletedCount = 0
        bookDir.walkTopDown().forEach { file ->
            if (file.isFile && file.delete()) {
                deletedCount++
            }
        }
        // Delete the book directory and any empty subdirectories
        bookDir.deleteRecursively()
        AppLogger.i(tag, "Deleted $deletedCount audio files for book $bookId")
        return deletedCount
    }

    /**
     * Get the relative path for a segment file (for storing in CharacterPageMapping.audioPath).
     */
    fun getSegmentRelativePath(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        segmentIndex: Int,
        characterName: String,
        speakerId: Int
    ): String {
        val sanitizedName = sanitizeFileName(characterName)
        return "book_$bookId/ch$chapterId/p$pageNumber/$SEGMENT_PREFIX${segmentIndex}_${sanitizedName}_$speakerId$WAV_EXT"
    }

    private fun segmentFile(
        bookId: Long,
        chapterId: Long,
        pageNumber: Int,
        segmentIndex: Int,
        characterName: String,
        speakerId: Int
    ): File {
        val sanitizedName = sanitizeFileName(characterName)
        return segmentDir(bookId, chapterId, pageNumber)
            .resolve("$SEGMENT_PREFIX${segmentIndex}_${sanitizedName}_$speakerId$WAV_EXT")
    }

    /**
     * Sanitize character name for use in file names.
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
    }

    companion object {
        private const val DIR_NAME = "storyteller_audio"
        private const val PAGE_PREFIX = "page_"
        private const val SEGMENT_PREFIX = "seg"
        private const val WAV_EXT = ".wav"
    }
}
