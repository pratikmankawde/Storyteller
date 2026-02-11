package com.dramebaz.app.ai.llm.pipeline.checkpoint

import com.dramebaz.app.ai.llm.pipeline.BatchedAnalysisCheckpoint
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest

/**
 * Repository interface for checkpoint persistence.
 * Abstracts the I/O operations for checkpoint management.
 */
interface CheckpointManager<T> {
    /**
     * Load a checkpoint if it exists and is valid.
     * @param bookId Book identifier
     * @param chapterId Chapter identifier
     * @param contentHash Hash of current content for validation
     * @return The checkpoint if valid, null otherwise
     */
    fun load(bookId: Long, chapterId: Long, contentHash: String): T?
    
    /**
     * Save a checkpoint.
     * @param checkpoint The checkpoint to save
     */
    fun save(checkpoint: T)
    
    /**
     * Delete a checkpoint.
     * @param bookId Book identifier
     * @param chapterId Chapter identifier
     */
    fun delete(bookId: Long, chapterId: Long)
    
    /**
     * Check if a checkpoint exists.
     */
    fun exists(bookId: Long, chapterId: Long): Boolean
}

/**
 * File-based implementation of CheckpointManager for BatchedAnalysisCheckpoint.
 * 
 * Features:
 * - JSON serialization
 * - Atomic writes (temp file + rename)
 * - Expiry-based invalidation
 * - Content hash validation
 */
class FileCheckpointManager(
    private val cacheDir: File,
    private val expiryMs: Long = DEFAULT_EXPIRY_MS
) : CheckpointManager<BatchedAnalysisCheckpoint> {
    
    companion object {
        private const val TAG = "FileCheckpointManager"
        private const val CHECKPOINT_DIR = "batched_analysis_checkpoints"
        const val DEFAULT_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours
        
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        
        /**
         * Compute content hash for a list of paragraphs.
         */
        fun computeContentHash(paragraphs: List<String>): String {
            val content = paragraphs.joinToString("\n")
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
    
    private fun getCheckpointFile(bookId: Long, chapterId: Long): File {
        val checkpointDir = File(cacheDir, CHECKPOINT_DIR)
        if (!checkpointDir.exists()) checkpointDir.mkdirs()
        return File(checkpointDir, "batched_${bookId}_${chapterId}.json")
    }
    
    override fun load(bookId: Long, chapterId: Long, contentHash: String): BatchedAnalysisCheckpoint? {
        val file = getCheckpointFile(bookId, chapterId)
        if (!file.exists()) {
            AppLogger.d(TAG, "No checkpoint file found for book=$bookId, chapter=$chapterId")
            return null
        }
        
        return try {
            val checkpoint = gson.fromJson(file.readText(), BatchedAnalysisCheckpoint::class.java)
            
            // Check if expired
            val age = System.currentTimeMillis() - checkpoint.timestamp
            if (age > expiryMs) {
                AppLogger.d(TAG, "Checkpoint expired (age=${age}ms > expiry=${expiryMs}ms)")
                file.delete()
                return null
            }
            
            // Check if content has changed
            if (checkpoint.contentHash != contentHash) {
                AppLogger.d(TAG, "Checkpoint content hash mismatch (expected=$contentHash, found=${checkpoint.contentHash})")
                file.delete()
                return null
            }
            
            AppLogger.i(TAG, "Loaded valid checkpoint for book=$bookId, chapter=$chapterId")
            checkpoint
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load checkpoint", e)
            file.delete()
            null
        }
    }
    
    override fun save(checkpoint: BatchedAnalysisCheckpoint) {
        val file = getCheckpointFile(checkpoint.bookId, checkpoint.chapterId)
        val tempFile = File(file.parent, "${file.name}.tmp")
        
        try {
            tempFile.writeText(gson.toJson(checkpoint))
            tempFile.renameTo(file)
            AppLogger.d(TAG, "Saved checkpoint for book=${checkpoint.bookId}, chapter=${checkpoint.chapterId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save checkpoint", e)
            tempFile.delete()
        }
    }
    
    override fun delete(bookId: Long, chapterId: Long) {
        val file = getCheckpointFile(bookId, chapterId)
        if (file.exists()) {
            file.delete()
            AppLogger.d(TAG, "Deleted checkpoint for book=$bookId, chapter=$chapterId")
        }
    }
    
    override fun exists(bookId: Long, chapterId: Long): Boolean {
        return getCheckpointFile(bookId, chapterId).exists()
    }
}

