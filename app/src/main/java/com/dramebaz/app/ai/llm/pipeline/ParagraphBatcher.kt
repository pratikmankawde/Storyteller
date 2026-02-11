package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.utils.AppLogger

/**
 * Groups paragraphs into batches based on token budget constraints.
 * 
 * Key features:
 * - Never truncates mid-paragraph
 * - Respects token budget (CHARS_PER_TOKEN = 4)
 * - Creates batches that maximize token utilization (aim for 90%+ fill)
 * - Tracks paragraph indices for checkpoint/resume support
 */
object ParagraphBatcher {
    private const val TAG = "ParagraphBatcher"
    
    /** Characters per token (conservative estimate for English) */
    const val CHARS_PER_TOKEN = 4
    
    /** Minimum fill percentage for a batch (try to use at least 70% of budget) */
    private const val MIN_FILL_PERCENT = 0.70
    
    /**
     * A single batch of paragraphs for LLM processing.
     */
    data class ParagraphBatch(
        /** Index of this batch (0-based) */
        val batchIndex: Int,
        /** Index of first paragraph in this batch (0-based, global) */
        val startParagraphIndex: Int,
        /** Index of last paragraph in this batch (0-based, global, inclusive) */
        val endParagraphIndex: Int,
        /** Combined text of all paragraphs in this batch */
        val text: String,
        /** Number of paragraphs in this batch */
        val paragraphCount: Int,
        /** Estimated token count for this batch */
        val estimatedTokens: Int
    )
    
    /**
     * Create batches from a list of paragraphs, respecting token budget.
     * 
     * @param paragraphs List of cleaned paragraphs
     * @param maxInputTokens Maximum tokens for input text (excluding prompt tokens)
     * @return List of batches, each containing multiple paragraphs
     */
    fun createBatches(
        paragraphs: List<String>,
        maxInputTokens: Int
    ): List<ParagraphBatch> {
        if (paragraphs.isEmpty()) {
            AppLogger.d(TAG, "No paragraphs to batch")
            return emptyList()
        }
        
        val maxInputChars = maxInputTokens * CHARS_PER_TOKEN
        val batches = mutableListOf<ParagraphBatch>()
        
        var batchIndex = 0
        var currentBatchStart = 0
        var currentBatchBuilder = StringBuilder()
        var currentBatchParagraphCount = 0
        
        for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
            val paragraphChars = paragraph.length
            val separatorChars = if (currentBatchBuilder.isNotEmpty()) 2 else 0 // "\n\n"
            val newLength = currentBatchBuilder.length + separatorChars + paragraphChars
            
            // Check if adding this paragraph would exceed the budget
            if (newLength > maxInputChars && currentBatchBuilder.isNotEmpty()) {
                // Save current batch and start new one
                val batchText = currentBatchBuilder.toString()
                batches.add(ParagraphBatch(
                    batchIndex = batchIndex,
                    startParagraphIndex = currentBatchStart,
                    endParagraphIndex = paragraphIndex - 1,
                    text = batchText,
                    paragraphCount = currentBatchParagraphCount,
                    estimatedTokens = batchText.length / CHARS_PER_TOKEN
                ))
                
                batchIndex++
                currentBatchStart = paragraphIndex
                currentBatchBuilder = StringBuilder()
                currentBatchParagraphCount = 0
            }
            
            // Add paragraph to current batch
            if (currentBatchBuilder.isNotEmpty()) {
                currentBatchBuilder.append("\n\n")
            }
            currentBatchBuilder.append(paragraph)
            currentBatchParagraphCount++
        }
        
        // Don't forget the last batch
        if (currentBatchBuilder.isNotEmpty()) {
            val batchText = currentBatchBuilder.toString()
            batches.add(ParagraphBatch(
                batchIndex = batchIndex,
                startParagraphIndex = currentBatchStart,
                endParagraphIndex = paragraphs.size - 1,
                text = batchText,
                paragraphCount = currentBatchParagraphCount,
                estimatedTokens = batchText.length / CHARS_PER_TOKEN
            ))
        }
        
        AppLogger.i(TAG, "Created ${batches.size} batches from ${paragraphs.size} paragraphs " +
                "(maxTokens=$maxInputTokens, maxChars=$maxInputChars)")
        
        // Log batch statistics
        batches.forEach { batch ->
            val fillPercent = (batch.text.length.toFloat() / maxInputChars * 100).toInt()
            AppLogger.d(TAG, "  Batch ${batch.batchIndex}: paras ${batch.startParagraphIndex}-${batch.endParagraphIndex}, " +
                    "${batch.text.length} chars (~${batch.estimatedTokens} tokens), ${fillPercent}% fill")
        }
        
        return batches
    }
    
    /**
     * Get batches starting from a specific paragraph index (for resume).
     */
    fun createBatchesFromIndex(
        paragraphs: List<String>,
        maxInputTokens: Int,
        startFromParagraphIndex: Int
    ): List<ParagraphBatch> {
        if (startFromParagraphIndex >= paragraphs.size) {
            AppLogger.d(TAG, "startFromParagraphIndex ($startFromParagraphIndex) >= paragraphs.size (${paragraphs.size})")
            return emptyList()
        }
        
        val remainingParagraphs = paragraphs.subList(startFromParagraphIndex, paragraphs.size)
        val batches = createBatches(remainingParagraphs, maxInputTokens)
        
        // Adjust indices to be global (relative to original paragraph list)
        return batches.map { batch ->
            batch.copy(
                startParagraphIndex = batch.startParagraphIndex + startFromParagraphIndex,
                endParagraphIndex = batch.endParagraphIndex + startFromParagraphIndex
            )
        }
    }
    
    /**
     * Estimate the number of batches for a given set of paragraphs.
     */
    fun estimateBatchCount(paragraphs: List<String>, maxInputTokens: Int): Int {
        val maxChars = maxInputTokens * CHARS_PER_TOKEN
        val totalChars = paragraphs.sumOf { it.length + 2 } // +2 for \n\n
        return ((totalChars / maxChars) + 1).coerceAtLeast(1)
    }
}

