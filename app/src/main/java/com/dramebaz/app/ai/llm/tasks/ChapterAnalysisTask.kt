package com.dramebaz.app.ai.llm.tasks

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.pipeline.AccumulatedCharacterData
import com.dramebaz.app.ai.llm.pipeline.ChapterAnalysisContext
import com.dramebaz.app.ai.llm.pipeline.CharacterExtractionStep
import com.dramebaz.app.ai.llm.pipeline.DialogExtractionStep
import com.dramebaz.app.ai.llm.pipeline.DialogWithPage
import com.dramebaz.app.ai.llm.pipeline.PassConfig
import com.dramebaz.app.ai.llm.pipeline.PipelineStep
import com.dramebaz.app.ai.llm.pipeline.StepProgressCallback
import com.dramebaz.app.ai.llm.pipeline.VoiceProfileStep
import com.dramebaz.app.ai.llm.prompts.VoiceProfileData
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Analysis task for chapter-level character, dialog, and voice profile extraction.
 *
 * Wraps the 3-pass chapter analysis workflow (Character Extraction, Dialog Extraction,
 * Voice Profile Suggestion) into an AnalysisTask that can be run by either
 * AnalysisBackgroundRunner or AnalysisForegroundService.
 *
 * Features:
 * - Checkpoint-based persistence with resume capability
 * - Content hash validation (detects if chapter content changed)
 * - 24-hour checkpoint expiry
 * - Atomic file writes for checkpoint safety
 *
 * NOT tied to any specific service - pure pipeline logic.
 */
/**
 * Callback invoked after each pipeline step completes.
 * @param stepIndex 0=CharacterExtraction, 1=DialogExtraction, 2=VoiceProfile
 * @param stepName Human-readable step name
 * @param characters Current accumulated character data
 */
typealias StepCompletionCallback = suspend (stepIndex: Int, stepName: String, characters: Map<String, AccumulatedCharacterData>) -> Unit

class ChapterAnalysisTask(
    val bookId: Long,
    val chapterId: Long,
    val cleanedPages: List<String>,
    val chapterTitle: String = "Chapter Analysis",
    private val appContext: Context? = null,  // Enables checkpoint persistence
    private val onStepCompleted: StepCompletionCallback? = null  // Called after each step to save to DB
) : AnalysisTask {

    companion object {
        private const val TAG = "ChapterAnalysisTask"
        private const val CHECKPOINT_DIR = "chapter_analysis_checkpoints"
        private const val CHECKPOINT_EXPIRY_HOURS = 24L

        // Result keys for resultData map
        const val KEY_CHARACTERS = "characters"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHARACTER_COUNT = "character_count"
        const val KEY_DIALOG_COUNT = "dialog_count"

        /**
         * Delete all checkpoints for a specific book.
         * Called when a book is deleted.
         */
        fun deleteCheckpointsForBook(context: Context, bookId: Long): Int {
            val cacheDir = context.cacheDir
            val checkpointDir = File(cacheDir, CHECKPOINT_DIR)
            if (!checkpointDir.exists()) return 0

            var deletedCount = 0
            checkpointDir.listFiles()?.forEach { file ->
                // Files are named: {bookId}_{chapterId}.json
                if (file.name.startsWith("${bookId}_")) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
            AppLogger.i(TAG, "Deleted $deletedCount checkpoints for book $bookId")
            return deletedCount
        }
    }

    private val gson = Gson()
    private val checkpointMutex = Mutex()

    override val taskId: String = "chapter_analysis_${bookId}_${chapterId}"

    override val displayName: String = chapterTitle

    // Estimate: ~30 seconds per page for full 3-pass analysis
    override val estimatedDurationSeconds: Int = (cleanedPages.size * 30).coerceAtLeast(120)

    // ==================== Checkpoint Data Classes ====================

    /**
     * Checkpoint data for resuming interrupted analysis.
     */
    data class AnalysisCheckpoint(
        val bookId: Long,
        val chapterId: Long,
        val timestamp: Long,
        val contentHash: Int,
        val lastCompletedStep: Int,  // -1=none, 0=CharacterExtraction, 1=DialogExtraction, 2=complete
        val characters: Map<String, SerializableCharacterData>,
        val totalDialogs: Int,
        val pagesProcessed: Int
    )

    /**
     * Serializable version of AccumulatedCharacterData for JSON persistence.
     */
    data class SerializableCharacterData(
        val name: String,
        val pagesAppearing: List<Int>,
        val dialogs: List<SerializableDialog>,
        val traits: List<String>,
        val voiceProfile: SerializableVoiceProfile?,
        val speakerId: Int?
    )

    data class SerializableDialog(
        val pageNumber: Int,
        val text: String,
        val emotion: String,
        val intensity: Float
    )

    data class SerializableVoiceProfile(
        val characterName: String,
        val gender: String,
        val age: String,
        val tone: String,
        val pitch: Float,
        val speed: Float,
        val energy: Float
    )
    
    // ==================== Checkpoint Persistence Methods ====================

    private fun getCheckpointFile(): File? {
        val cacheDir = appContext?.cacheDir ?: return null
        val checkpointDir = File(cacheDir, CHECKPOINT_DIR)
        if (!checkpointDir.exists()) {
            checkpointDir.mkdirs()
        }
        return File(checkpointDir, "${bookId}_${chapterId}.json")
    }

    private suspend fun loadCheckpoint(contentHash: Int): AnalysisCheckpoint? = checkpointMutex.withLock {
        val file = getCheckpointFile() ?: return@withLock null
        if (!file.exists()) {
            AppLogger.d(TAG, "📁 No checkpoint found for book=$bookId, chapter=$chapterId")
            return@withLock null
        }

        try {
            val json = file.readText()
            val checkpoint = gson.fromJson(json, AnalysisCheckpoint::class.java)

            // Check if checkpoint is expired (>24 hours old)
            val ageHours = (System.currentTimeMillis() - checkpoint.timestamp) / (1000 * 60 * 60)
            if (ageHours > CHECKPOINT_EXPIRY_HOURS) {
                AppLogger.i(TAG, "🗑️ Checkpoint expired (${ageHours}h old), deleting")
                file.delete()
                return@withLock null
            }

            // Check if chapter content changed (hash mismatch)
            if (checkpoint.contentHash != contentHash) {
                AppLogger.i(TAG, "🗑️ Checkpoint invalid (chapter content changed), deleting")
                file.delete()
                return@withLock null
            }

            AppLogger.i(TAG, "✅ Loaded valid checkpoint: step=${checkpoint.lastCompletedStep}, chars=${checkpoint.characters.size}")
            checkpoint
        } catch (e: Exception) {
            AppLogger.w(TAG, "⚠️ Failed to load checkpoint, deleting corrupted file", e)
            file.delete()
            null
        }
    }

    private suspend fun saveCheckpoint(context: ChapterAnalysisContext, stepIndex: Int) = checkpointMutex.withLock {
        val file = getCheckpointFile() ?: return@withLock
        try {
            val checkpoint = AnalysisCheckpoint(
                bookId = bookId,
                chapterId = chapterId,
                timestamp = System.currentTimeMillis(),
                contentHash = context.contentHash,
                lastCompletedStep = stepIndex,
                characters = toSerializable(context.characters),
                totalDialogs = context.totalDialogs,
                pagesProcessed = context.pagesProcessed
            )
            val json = gson.toJson(checkpoint)
            // Write to temp file first, then rename for atomicity
            val tempFile = File(file.parent, "${file.name}.tmp")
            tempFile.writeText(json)
            tempFile.renameTo(file)
            AppLogger.d(TAG, "💾 Checkpoint saved: step=$stepIndex, chars=${context.characters.size}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to save checkpoint", e)
        }
    }

    private suspend fun deleteCheckpoint() = checkpointMutex.withLock {
        val file = getCheckpointFile() ?: return@withLock
        if (file.exists()) {
            file.delete()
            AppLogger.i(TAG, "🗑️ Checkpoint deleted after successful completion")
        }
    }

    private fun toSerializable(characters: Map<String, AccumulatedCharacterData>): Map<String, SerializableCharacterData> {
        return characters.mapValues { (_, char) ->
            SerializableCharacterData(
                name = char.name,
                pagesAppearing = char.pagesAppearing.toList(),
                dialogs = char.dialogs.map { d ->
                    SerializableDialog(d.pageNumber, d.text, d.emotion, d.intensity)
                },
                traits = char.traits,
                voiceProfile = char.voiceProfile?.let { vp ->
                    SerializableVoiceProfile(
                        characterName = vp.characterName,
                        gender = vp.gender,
                        age = vp.age,
                        tone = vp.tone,
                        pitch = vp.pitch,
                        speed = vp.speed,
                        energy = vp.energy
                    )
                },
                speakerId = char.speakerId
            )
        }
    }

    private fun fromSerializable(serialized: Map<String, SerializableCharacterData>): MutableMap<String, AccumulatedCharacterData> {
        return serialized.mapValues { (_, ser) ->
            AccumulatedCharacterData(
                name = ser.name,
                pagesAppearing = ser.pagesAppearing.toMutableSet(),
                dialogs = ser.dialogs.map { d ->
                    DialogWithPage(d.pageNumber, d.text, d.emotion, d.intensity)
                }.toMutableList(),
                traits = ser.traits,
                voiceProfile = ser.voiceProfile?.let { vp ->
                    VoiceProfileData(
                        characterName = vp.characterName,
                        gender = vp.gender,
                        age = vp.age,
                        tone = vp.tone,
                        pitch = vp.pitch,
                        speed = vp.speed,
                        energy = vp.energy
                    )
                },
                speakerId = ser.speakerId
            )
        }.toMutableMap()
    }

    // ==================== Main Execute Method ====================

    override suspend fun execute(
        model: LlmModel,
        progressCallback: ((TaskProgress) -> Unit)?
    ): TaskResult {
        val startTime = System.currentTimeMillis()

        AppLogger.i(TAG, "Starting chapter analysis: bookId=$bookId, chapterId=$chapterId, " +
                "pages=${cleanedPages.size}")

        try {
            val contentHash = cleanedPages.joinToString("").hashCode()

            // Try to load existing checkpoint
            val checkpoint = loadCheckpoint(contentHash)
            val startStepIndex = if (checkpoint != null) checkpoint.lastCompletedStep + 1 else 0

            // Create or restore context
            var context = if (checkpoint != null) {
                AppLogger.i(TAG, "📥 Resuming from checkpoint at step $startStepIndex")
                ChapterAnalysisContext(
                    contentHash = contentHash,
                    bookId = bookId,
                    chapterId = chapterId,
                    cleanedPages = cleanedPages,
                    characters = fromSerializable(checkpoint.characters),
                    totalDialogs = checkpoint.totalDialogs,
                    pagesProcessed = checkpoint.pagesProcessed
                )
            } else {
                ChapterAnalysisContext(
                    contentHash = contentHash,
                    bookId = bookId,
                    chapterId = chapterId,
                    cleanedPages = cleanedPages
                )
            }

            val steps: List<PipelineStep<ChapterAnalysisContext>> = listOf(
                CharacterExtractionStep(),
                DialogExtractionStep(),
                VoiceProfileStep()
            )

            val config = PassConfig()

            // Execute each step (skipping already completed ones)
            for ((index, step) in steps.withIndex()) {
                if (index < startStepIndex) {
                    AppLogger.d(TAG, "⏭️ Skipping already completed step ${index + 1}: ${step.name}")
                    continue
                }

                progressCallback?.invoke(TaskProgress(
                    taskId = taskId,
                    message = "Executing ${step.name}...",
                    percent = (index * 100) / steps.size,
                    currentStep = index + 1,
                    totalSteps = steps.size,
                    stepName = step.name
                ))

                // Create segment progress callback for per-segment updates
                val segmentProgressCallback: StepProgressCallback = { currentSegment, totalSegments ->
                    // Calculate overall percent: step base + segment progress within step
                    val stepBasePercent = (index * 100) / steps.size
                    val stepContribution = 100 / steps.size
                    val segmentProgress = if (totalSegments > 0) (currentSegment * stepContribution) / totalSegments else 0
                    val overallPercent = stepBasePercent + segmentProgress

                    progressCallback?.invoke(TaskProgress(
                        taskId = taskId,
                        message = "${step.name} ($currentSegment/$totalSegments)",
                        percent = overallPercent,
                        currentStep = index + 1,
                        totalSteps = steps.size,
                        stepName = step.name
                    ))
                }

                AppLogger.d(TAG, "Executing step ${index + 1}/${steps.size}: ${step.name}")
                context = step.execute(model, context, config, segmentProgressCallback)

                // Save checkpoint after each step (except the last one)
                if (index < steps.size - 1) {
                    saveCheckpoint(context, index)
                }

                // Notify callback to save characters to database after each step
                onStepCompleted?.invoke(index, step.name, context.characters)
                AppLogger.i(TAG, "Step ${index + 1} (${step.name}) completed, ${context.characters.size} characters")
            }

            // Delete checkpoint on successful completion
            deleteCheckpoint()

            // Report 99% here - the workflow will report 100% after saving to database
            progressCallback?.invoke(TaskProgress(
                taskId = taskId,
                message = "Saving results...",
                percent = 99,
                currentStep = steps.size,
                totalSteps = steps.size
            ))

            val duration = System.currentTimeMillis() - startTime
            AppLogger.i(TAG, "Chapter analysis completed in ${duration}ms: " +
                    "${context.characters.size} characters, ${context.totalDialogs} dialogs")

            // Build result data
            val resultData = buildResultData(context)

            return TaskResult(
                success = true,
                taskId = taskId,
                durationMs = duration,
                resultData = resultData
            )

        } catch (e: Exception) {
            AppLogger.e(TAG, "Chapter analysis failed", e)
            return TaskResult(
                success = false,
                taskId = taskId,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun buildResultData(context: ChapterAnalysisContext): Map<String, Any> {
        // Serialize characters to JSON for cross-process compatibility
        val charactersJson = gson.toJson(context.characters.values.toList())

        return mapOf(
            KEY_BOOK_ID to bookId,
            KEY_CHAPTER_ID to chapterId,
            KEY_CHARACTERS to charactersJson,
            KEY_CHARACTER_COUNT to context.characters.size,
            KEY_DIALOG_COUNT to context.totalDialogs
        )
    }
}

