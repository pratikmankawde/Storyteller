package com.dramebaz.app.utils

import android.util.LruCache
import com.dramebaz.app.playback.engine.TextAudioSync
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for expensive computations.
 * Provides thread-safe caching for text segments, parsed JSON, etc.
 */
object CacheManager {
    private const val MAX_CACHE_SIZE = 50
    
    // Cache for text segments: key = "chapterId_textHash", value = List<TextSegment>
    private val textSegmentCache = LruCache<String, List<TextAudioSync.TextSegment>>(MAX_CACHE_SIZE)
    
    // Cache for parsed chapter analysis: key = chapterId, value = parsed JSON object
    private val analysisCache = ConcurrentHashMap<Long, Any>()
    
    /**
     * Get cached text segments or compute and cache them.
     */
    fun getOrComputeTextSegments(
        chapterId: Long,
        chapterText: String,
        dialogs: List<com.dramebaz.app.data.models.Dialog>?,
        compute: () -> List<TextAudioSync.TextSegment>
    ): List<TextAudioSync.TextSegment> {
        val cacheKey = "${chapterId}_${chapterText.hashCode()}"
        val cached = textSegmentCache.get(cacheKey)
        if (cached != null) {
            AppLogger.d("CacheManager", "Cache hit for text segments: chapterId=$chapterId")
            return cached
        }
        
        AppLogger.d("CacheManager", "Cache miss, computing text segments: chapterId=$chapterId")
        val segments = compute()
        textSegmentCache.put(cacheKey, segments)
        return segments
    }
    
    /**
     * Clear text segment cache for a specific chapter.
     */
    fun clearTextSegments(chapterId: Long) {
        val keysToRemove = mutableListOf<String>()
        for (key in textSegmentCache.snapshot().keys) {
            if (key.startsWith("${chapterId}_")) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { textSegmentCache.remove(it) }
        AppLogger.d("CacheManager", "Cleared text segment cache for chapterId=$chapterId")
    }
    
    /**
     * Clear all caches.
     */
    fun clearAll() {
        textSegmentCache.evictAll()
        analysisCache.clear()
        AppLogger.d("CacheManager", "All caches cleared")
    }
    
    /**
     * Get cache statistics.
     */
    fun getCacheStats(): String {
        return "TextSegmentCache: ${textSegmentCache.size()}/$MAX_CACHE_SIZE, " +
                "AnalysisCache: ${analysisCache.size}"
    }
}
