package com.dramebaz.app.domain.usecases

import java.util.UUID

/**
 * Represents a job in the analysis queue.
 * 
 * Jobs are units of work that can be queued and processed by the queue manager.
 * Each job has a type that determines what kind of analysis will be performed.
 */
data class AnalysisJob(
    /**
     * Unique identifier for this job.
     */
    val id: String = UUID.randomUUID().toString(),
    
    /**
     * The book ID this job is associated with.
     */
    val bookId: Long,
    
    /**
     * The type of analysis job.
     */
    val type: JobType,
    
    /**
     * Priority of this job. Higher values = higher priority.
     * Default priority is 0. Use negative values for low priority, positive for high.
     */
    val priority: Int = 0,
    
    /**
     * Whether this job should run in foreground service mode (wake lock + notification).
     * Set to true for long-running tasks that need to survive app being backgrounded.
     */
    val runInForeground: Boolean = true,
    
    /**
     * Optional chapter ID for chapter-specific jobs.
     */
    val chapterId: Long? = null,
    
    /**
     * Timestamp when this job was created.
     */
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Types of analysis jobs that can be queued.
     */
    enum class JobType {
        /**
         * Analyze only the first chapter of a book.
         * Used for quick preview during book import.
         */
        FIRST_CHAPTER_ANALYSIS,
        
        /**
         * Analyze all chapters of a book.
         * This is the full book analysis workflow.
         */
        FULL_BOOK_ANALYSIS,
        
        /**
         * Analyze a single specific chapter.
         * Used for re-analysis or on-demand analysis.
         */
        SINGLE_CHAPTER_ANALYSIS,
        
        /**
         * Extract character traits from analyzed chapters.
         * Runs in background after chapter analysis completes.
         */
        TRAITS_EXTRACTION,
        
        /**
         * Generate initial audio for a chapter.
         * Runs after chapter analysis completes.
         */
        AUDIO_GENERATION
    }
    
    /**
     * Get a human-readable display name for this job.
     */
    fun getDisplayName(): String {
        return when (type) {
            JobType.FIRST_CHAPTER_ANALYSIS -> "First Chapter Analysis (Book $bookId)"
            JobType.FULL_BOOK_ANALYSIS -> "Full Book Analysis (Book $bookId)"
            JobType.SINGLE_CHAPTER_ANALYSIS -> "Chapter Analysis (Book $bookId, Chapter $chapterId)"
            JobType.TRAITS_EXTRACTION -> "Traits Extraction (Book $bookId)"
            JobType.AUDIO_GENERATION -> "Audio Generation (Book $bookId, Chapter $chapterId)"
        }
    }
    
    companion object {
        /**
         * Create a first-chapter analysis job.
         */
        fun firstChapterAnalysis(bookId: Long, priority: Int = 10): AnalysisJob {
            return AnalysisJob(
                bookId = bookId,
                type = JobType.FIRST_CHAPTER_ANALYSIS,
                priority = priority,
                runInForeground = true
            )
        }
        
        /**
         * Create a full book analysis job.
         */
        fun fullBookAnalysis(bookId: Long, priority: Int = 0): AnalysisJob {
            return AnalysisJob(
                bookId = bookId,
                type = JobType.FULL_BOOK_ANALYSIS,
                priority = priority,
                runInForeground = true
            )
        }
        
        /**
         * Create a single chapter analysis job.
         */
        fun singleChapterAnalysis(bookId: Long, chapterId: Long, priority: Int = 5): AnalysisJob {
            return AnalysisJob(
                bookId = bookId,
                type = JobType.SINGLE_CHAPTER_ANALYSIS,
                priority = priority,
                runInForeground = true,
                chapterId = chapterId
            )
        }
        
        /**
         * Create a background traits extraction job.
         */
        fun traitsExtraction(bookId: Long): AnalysisJob {
            return AnalysisJob(
                bookId = bookId,
                type = JobType.TRAITS_EXTRACTION,
                priority = -10,
                runInForeground = false
            )
        }
        
        /**
         * Create an audio generation job.
         */
        fun audioGeneration(bookId: Long, chapterId: Long): AnalysisJob {
            return AnalysisJob(
                bookId = bookId,
                type = JobType.AUDIO_GENERATION,
                priority = -5,
                runInForeground = false,
                chapterId = chapterId
            )
        }
    }
}

