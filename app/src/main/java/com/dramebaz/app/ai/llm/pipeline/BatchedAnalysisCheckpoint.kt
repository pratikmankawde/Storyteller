package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.prompts.ExtractedVoiceProfile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest

/**
 * Checkpoint data for batched chapter analysis.
 * 
 * Supports resume from the exact paragraph where analysis stopped.
 * Uses content hash to detect if chapter text has changed.
 */
data class BatchedAnalysisCheckpoint(
    /** Book ID for this checkpoint */
    val bookId: Long,
    /** Chapter ID for this checkpoint */
    val chapterId: Long,
    /** Hash of the chapter content for change detection */
    val contentHash: String,
    /** Index of last fully processed paragraph (0-based) */
    val lastProcessedParagraphIndex: Int,
    /** Total number of paragraphs in the chapter */
    val totalParagraphs: Int,
    /** Number of batches completed */
    val batchesCompleted: Int,
    /** Total number of batches */
    val totalBatches: Int,
    /** Timestamp when checkpoint was created */
    val timestamp: Long,
    /** Accumulated character data so far */
    val accumulatedCharacters: List<CheckpointCharacterData>
) {
    companion object {
        private const val CHECKPOINT_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours
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
        
        /**
         * Get checkpoint file path.
         */
        fun getCheckpointFile(cacheDir: File, bookId: Long, chapterId: Long): File {
            val checkpointDir = File(cacheDir, "batched_analysis_checkpoints")
            if (!checkpointDir.exists()) checkpointDir.mkdirs()
            return File(checkpointDir, "batched_${bookId}_${chapterId}.json")
        }
        
        /**
         * Load checkpoint from file if valid.
         * Returns null if checkpoint doesn't exist, is expired, or content hash doesn't match.
         */
        fun load(
            cacheDir: File,
            bookId: Long,
            chapterId: Long,
            currentContentHash: String
        ): BatchedAnalysisCheckpoint? {
            val file = getCheckpointFile(cacheDir, bookId, chapterId)
            if (!file.exists()) return null
            
            return try {
                val checkpoint = gson.fromJson(file.readText(), BatchedAnalysisCheckpoint::class.java)
                
                // Check if expired
                val age = System.currentTimeMillis() - checkpoint.timestamp
                if (age > CHECKPOINT_EXPIRY_MS) {
                    file.delete()
                    return null
                }
                
                // Check if content has changed
                if (checkpoint.contentHash != currentContentHash) {
                    file.delete()
                    return null
                }
                
                checkpoint
            } catch (e: Exception) {
                file.delete()
                null
            }
        }
        
        /**
         * Save checkpoint to file atomically.
         */
        fun save(cacheDir: File, checkpoint: BatchedAnalysisCheckpoint) {
            val file = getCheckpointFile(cacheDir, checkpoint.bookId, checkpoint.chapterId)
            val tempFile = File(file.parent, "${file.name}.tmp")
            
            tempFile.writeText(gson.toJson(checkpoint))
            tempFile.renameTo(file)
        }
        
        /**
         * Delete checkpoint file.
         */
        fun delete(cacheDir: File, bookId: Long, chapterId: Long) {
            val file = getCheckpointFile(cacheDir, bookId, chapterId)
            if (file.exists()) file.delete()
        }
    }
    
    /**
     * Check if analysis is complete.
     */
    fun isComplete(): Boolean {
        return lastProcessedParagraphIndex >= totalParagraphs - 1
    }
    
    /**
     * Get the paragraph index to resume from.
     */
    fun getResumeIndex(): Int {
        return lastProcessedParagraphIndex + 1
    }
    
    /**
     * Get progress as percentage.
     */
    fun getProgressPercent(): Int {
        if (totalParagraphs == 0) return 100
        return ((lastProcessedParagraphIndex + 1) * 100) / totalParagraphs
    }
}

/**
 * Character data stored in checkpoint (serializable subset).
 */
data class CheckpointCharacterData(
    val name: String,
    val canonicalName: String,
    val dialogs: List<String>,
    val traits: List<String>,
    val voiceProfile: ExtractedVoiceProfile?,
    val nameVariants: List<String>
)

/**
 * Convert MergedCharacterData to CheckpointCharacterData for serialization.
 */
fun IncrementalMerger.MergedCharacterData.toCheckpoint(): CheckpointCharacterData {
    return CheckpointCharacterData(
        name = this.name,
        canonicalName = this.canonicalName,
        dialogs = this.dialogs.toList(),
        traits = this.traits.toList(),
        voiceProfile = this.voiceProfile,
        nameVariants = this.nameVariants.toList()
    )
}

/**
 * Convert CheckpointCharacterData back to MergedCharacterData.
 */
fun CheckpointCharacterData.toMerged(): IncrementalMerger.MergedCharacterData {
    return IncrementalMerger.MergedCharacterData(
        name = this.name,
        canonicalName = this.canonicalName,
        dialogs = this.dialogs.toMutableList(),
        traits = this.traits.toMutableSet(),
        voiceProfile = this.voiceProfile,
        nameVariants = this.nameVariants.toMutableSet()
    )
}

