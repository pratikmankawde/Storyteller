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

    companion object {
        private const val DIR_NAME = "storyteller_audio"
        private const val PAGE_PREFIX = "page_"
        private const val WAV_EXT = ".wav"
    }
}
