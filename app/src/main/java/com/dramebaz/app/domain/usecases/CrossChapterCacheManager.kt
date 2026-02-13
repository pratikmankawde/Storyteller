package com.dramebaz.app.domain.usecases

import com.dramebaz.app.ai.llm.ChapterAnalysisResponse
import com.dramebaz.app.ai.tts.SherpaTtsEngine
import com.dramebaz.app.audio.SegmentAudioGenerator
import com.dramebaz.app.data.audio.PageAudioStorage
import com.dramebaz.app.data.db.ChapterDao
import com.dramebaz.app.ui.reader.NovelPage
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * CROSS-CHAPTER CACHING: Manages pre-caching of edge pages from adjacent chapters.
 *
 * Pre-caches the first page of the next chapter and last page of the previous chapter
 * to ensure seamless transitions when navigating between chapters. Includes both
 * text content and pre-generated audio for instant playback.
 *
 * Design Pattern: Manager/Service pattern following AudioBufferManager and ChapterLookaheadManager.
 *
 * Features:
 * - Pre-caches next chapter's first page when user reaches last page
 * - Pre-caches previous chapter's last page when user reaches first page
 * - Includes audio generation for cached pages
 * - Thread-safe with mutex protection
 * - Prevents duplicate caching operations
 */
class CrossChapterCacheManager(
    private val scope: CoroutineScope,
    private val chapterDao: ChapterDao,
    private val pageAudioStorage: PageAudioStorage,
    private val segmentAudioGenerator: SegmentAudioGenerator,
    private val ttsEngine: SherpaTtsEngine,
    private val gson: Gson = Gson()
) {
    private val tag = "CrossChapterCache"

    /**
     * Cached page data from an adjacent chapter.
     */
    data class CachedChapterPage(
        val chapterId: Long,
        val chapterOrderIndex: Int,
        val chapterTitle: String,
        val pageIndex: Int,
        val totalPages: Int,
        val novelPage: NovelPage,
        val audioFile: File?
    )

    /** Direction constants for caching */
    companion object {
        const val DIRECTION_NEXT = 1
        const val DIRECTION_PREVIOUS = -1
    }

    // Cache state
    private var cachedNextPage: CachedChapterPage? = null
    private var cachedPrevPage: CachedChapterPage? = null

    // Caching flags to prevent duplicate operations
    private var isCachingNext = false
    private var isCachingPrev = false

    // Active caching jobs
    private var nextCacheJob: Job? = null
    private var prevCacheJob: Job? = null

    // Mutex for thread-safe cache access
    private val cacheMutex = Mutex()

    /**
     * Get the cached next chapter page if available.
     */
    suspend fun getCachedNextPage(): CachedChapterPage? = cacheMutex.withLock { cachedNextPage }

    /**
     * Get the cached previous chapter page if available.
     */
    suspend fun getCachedPrevPage(): CachedChapterPage? = cacheMutex.withLock { cachedPrevPage }

    /**
     * Check if caching is in progress for a direction.
     */
    fun isCaching(direction: Int): Boolean = when (direction) {
        DIRECTION_NEXT -> isCachingNext
        DIRECTION_PREVIOUS -> isCachingPrev
        else -> false
    }

    /**
     * Get cached audio file for a chapter's target page if available.
     * @param targetPageIndex 0 for first page (next chapter), -1 for last page (previous chapter)
     * @return Audio file if cache hit, null otherwise
     */
    suspend fun getCachedAudioForChapter(chapterId: Long, targetPageIndex: Int): File? = cacheMutex.withLock {
        when {
            // Next chapter's first page (targetPageIndex == 0)
            targetPageIndex == 0 && cachedNextPage?.chapterId == chapterId -> {
                AppLogger.i(tag, "Cache hit: next chapter audio (chapterId=$chapterId)")
                cachedNextPage?.audioFile
            }
            // Previous chapter's last page (targetPageIndex == -1 means "last page")
            targetPageIndex == -1 && cachedPrevPage?.chapterId == chapterId -> {
                AppLogger.i(tag, "Cache hit: previous chapter audio (chapterId=$chapterId)")
                cachedPrevPage?.audioFile
            }
            else -> null
        }
    }

    /**
     * Clear all cached data. Called when chapter changes.
     */
    suspend fun clearCache() = cacheMutex.withLock {
        cachedNextPage = null
        cachedPrevPage = null
        nextCacheJob?.cancel()
        prevCacheJob?.cancel()
        isCachingNext = false
        isCachingPrev = false
        AppLogger.d(tag, "Cache cleared")
    }

    /**
     * Check if the cache for a direction is already valid.
     */
    suspend fun isCacheValid(direction: Int, currentOrderIndex: Int): Boolean = cacheMutex.withLock {
        when (direction) {
            DIRECTION_NEXT -> cachedNextPage?.chapterOrderIndex == currentOrderIndex + 1
            DIRECTION_PREVIOUS -> cachedPrevPage?.chapterOrderIndex == currentOrderIndex - 1
            else -> false
        }
    }

    /**
     * Pre-cache the edge page of an adjacent chapter.
     * @param direction DIRECTION_NEXT (1) or DIRECTION_PREVIOUS (-1)
     * @param bookId Current book ID
     * @param currentOrderIndex Current chapter's orderIndex
     * @param isPdfBook Whether the book is a PDF
     * @param pdfPageRenderer Optional renderer for PDF books (for page flag)
     * @param contextProvider Provider for Android Context (needed for text splitting)
     */
    fun preCacheAdjacentPage(
        direction: Int,
        bookId: Long,
        currentOrderIndex: Int,
        isPdfBook: Boolean,
        pdfRendererAvailable: Boolean,
        contextProvider: () -> android.content.Context?
    ) {
        // Prevent duplicate operations
        if (direction == DIRECTION_NEXT && isCachingNext) return
        if (direction == DIRECTION_PREVIOUS && isCachingPrev) return

        // Check if already cached
        scope.launch {
            if (isCacheValid(direction, currentOrderIndex)) {
                val dirLabel = if (direction == DIRECTION_NEXT) "next" else "previous"
                AppLogger.d(tag, "$dirLabel chapter already cached")
                return@launch
            }

            // Set caching flag
            if (direction == DIRECTION_NEXT) isCachingNext = true
            else isCachingPrev = true

            val job = scope.launch(Dispatchers.IO) {
                preCacheInternal(direction, bookId, currentOrderIndex, isPdfBook, pdfRendererAvailable, contextProvider)
            }

            if (direction == DIRECTION_NEXT) nextCacheJob = job
            else prevCacheJob = job
        }
    }

    /**
     * Internal caching logic - runs on IO dispatcher.
     */
    private suspend fun preCacheInternal(
        direction: Int,
        bookId: Long,
        currentOrderIndex: Int,
        isPdfBook: Boolean,
        pdfRendererAvailable: Boolean,
        contextProvider: () -> android.content.Context?
    ) {
        val dirLabel = if (direction == DIRECTION_NEXT) "next" else "previous"
        AppLogger.i(tag, "Starting pre-cache of $dirLabel chapter")

        try {
            // Fetch adjacent chapter
            val adjacentChapter = if (direction == DIRECTION_NEXT) {
                chapterDao.getNextChapter(bookId, currentOrderIndex)
            } else {
                chapterDao.getPreviousChapter(bookId, currentOrderIndex)
            }

            if (adjacentChapter == null) {
                AppLogger.d(tag, "No $dirLabel chapter available")
                return
            }

            val chapterId = adjacentChapter.id
            val chapterTitle = adjacentChapter.title
            val orderIndex = chapterDao.getOrderIndex(chapterId) ?: 0
            AppLogger.d(tag, "Found $dirLabel chapter: id=$chapterId, title=$chapterTitle")

            // Parse pages
            val novelPages = parseChapterPages(
                adjacentChapter.pdfPagesJson,
                adjacentChapter.body,
                isPdfBook,
                pdfRendererAvailable,
                contextProvider
            )

            if (novelPages.isEmpty()) {
                AppLogger.w(tag, "$dirLabel chapter has no pages")
                return
            }

            // Target page: first for next, last for previous
            val targetPageIndex = if (direction == DIRECTION_NEXT) 0 else novelPages.size - 1
            val targetPage = novelPages[targetPageIndex]

            AppLogger.d(tag, "Pre-caching $dirLabel chapter page $targetPageIndex")

            // Generate or retrieve audio
            val audioFile = getOrGenerateAudio(
                bookId, chapterId, targetPageIndex, targetPage.text,
                adjacentChapter.fullAnalysisJson
            )

            // Store in cache
            val cachedPage = CachedChapterPage(
                chapterId = chapterId,
                chapterOrderIndex = orderIndex,
                chapterTitle = chapterTitle,
                pageIndex = targetPageIndex,
                totalPages = novelPages.size,
                novelPage = targetPage,
                audioFile = audioFile
            )

            cacheMutex.withLock {
                if (direction == DIRECTION_NEXT) {
                    cachedNextPage = cachedPage
                } else {
                    cachedPrevPage = cachedPage
                }
            }

            AppLogger.i(tag, "$dirLabel chapter cached: chapterId=$chapterId, page=$targetPageIndex, hasAudio=${audioFile != null}")

        } catch (e: Exception) {
            AppLogger.e(tag, "Error pre-caching $dirLabel chapter", e)
        } finally {
            if (direction == DIRECTION_NEXT) isCachingNext = false
            else isCachingPrev = false
        }
    }

    /**
     * Parse chapter into NovelPage list.
     */
    private suspend fun parseChapterPages(
        pdfPagesJson: String?,
        bodyText: String,
        isPdfBook: Boolean,
        pdfRendererAvailable: Boolean,
        contextProvider: () -> android.content.Context?
    ): List<NovelPage> {
        // Try parsing from PDF pages JSON
        val pdfPages = pdfPagesJson?.let { json ->
            com.dramebaz.app.pdf.PdfChapterDetector.pdfPagesFromJson(json)
        } ?: emptyList()

        return if (pdfPages.isNotEmpty()) {
            var cumulativeOffset = 0
            pdfPages.mapIndexed { index, pdfPageInfo ->
                val page = NovelPage(
                    pageNumber = index + 1,
                    text = pdfPageInfo.text,
                    lines = pdfPageInfo.text.split("\n"),
                    startOffset = cumulativeOffset,
                    pdfPageNumber = pdfPageInfo.pdfPage,
                    usePdfRendering = isPdfBook && pdfRendererAvailable
                )
                cumulativeOffset += pdfPageInfo.text.length
                page
            }
        } else {
            // Fallback: split text into pages
            withContext(Dispatchers.Main) {
                contextProvider()?.let { ctx ->
                    com.dramebaz.app.ui.reader.NovelPageSplitter.splitIntoPages(bodyText, ctx)
                } ?: emptyList()
            }
        }
    }

    /**
     * Get existing audio or generate new audio for a page.
     */
    private suspend fun getOrGenerateAudio(
        bookId: Long,
        chapterId: Long,
        pageIndex: Int,
        pageText: String,
        analysisJson: String?
    ): File? {
        // Check for existing saved audio
        var audioFile = pageAudioStorage.getAudioFile(bookId, chapterId, pageIndex)
        if (audioFile != null) {
            AppLogger.d(tag, "Found saved audio for page $pageIndex")
            return audioFile
        }

        if (pageText.isBlank()) return null

        AppLogger.d(tag, "Generating audio for page $pageIndex")

        try {
            // Parse analysis for dialogs
            val analysis = analysisJson?.let { json ->
                try {
                    gson.fromJson(json, ChapterAnalysisResponse::class.java)
                } catch (e: Exception) { null }
            }

            val pageNumber = pageIndex + 1
            val dialogs = analysis?.dialogs?.let { allDialogs ->
                segmentAudioGenerator.getDialogsForPage(pageText, allDialogs)
            } ?: emptyList()

            val segmentFiles = segmentAudioGenerator.generatePageAudio(
                bookId = bookId,
                chapterId = chapterId,
                pageNumber = pageNumber,
                pageText = pageText,
                dialogs = dialogs,
                onSegmentGenerated = null  // Silent background operation
            )

            audioFile = if (segmentFiles.isNotEmpty()) {
                val stitchedFile = pageAudioStorage.getAudioFilePath(bookId, chapterId, pageIndex)
                com.dramebaz.app.audio.AudioStitcher.stitchWavFiles(segmentFiles, stitchedFile)
            } else {
                // Fallback: TTS for whole page
                val result = ttsEngine.speak(pageText, null, null, null)
                result.getOrNull()?.let { file ->
                    pageAudioStorage.saveAudioFile(bookId, chapterId, pageIndex, file)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error generating audio for page $pageIndex", e)
        }

        return audioFile
    }

    /**
     * Reset manager state. Called when reader closes.
     */
    fun reset() {
        scope.launch {
            nextCacheJob?.cancel()
            prevCacheJob?.cancel()
            cacheMutex.withLock {
                cachedNextPage = null
                cachedPrevPage = null
            }
            isCachingNext = false
            isCachingPrev = false
            AppLogger.d(tag, "CrossChapterCacheManager reset")
        }
    }
}

