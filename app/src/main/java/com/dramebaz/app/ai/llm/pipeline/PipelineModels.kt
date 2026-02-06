package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.prompts.ExtractedDialogData
import com.dramebaz.app.ai.llm.prompts.VoiceProfileData

/**
 * Base interface for pipeline context.
 * Context is passed between steps and accumulates results.
 */
interface PipelineContext {
    /** Hash of the content being processed (for checkpoint validation) */
    val contentHash: Int
}

/**
 * A single step in the pipeline.
 * Each step transforms the context and returns the updated context.
 */
interface PipelineStep<T : PipelineContext> {
    /** Name of this step for logging and progress */
    val name: String
    
    /** Execute this step */
    suspend fun execute(model: LlmModel, context: T, config: PassConfig): T
}

/**
 * Progress information for pipeline execution.
 */
data class PipelineProgress(
    val pipelineId: String,
    val currentStep: Int,
    val totalSteps: Int,
    val stepName: String,
    val message: String,
    val subProgress: Float = 0f  // 0.0 to 1.0 for sub-step progress
)

/**
 * Result of pipeline execution.
 */
data class PipelineResult<T : PipelineContext>(
    val success: Boolean,
    val context: T,
    val completedSteps: Int,
    val totalSteps: Int,
    val error: String? = null,
    val durationMs: Long = 0
)

/**
 * Checkpoint for resuming interrupted pipelines.
 */
data class PipelineCheckpoint(
    val bookId: Long,
    val chapterId: Long,
    val pipelineId: String,
    val stepIndex: Int,
    val contentHash: Int,
    val context: PipelineContext,
    val timestamp: Long
)

// ==================== Chapter Analysis Context ====================

/**
 * Context for chapter analysis pipeline.
 * Accumulates results from each pass.
 */
data class ChapterAnalysisContext(
    override val contentHash: Int,
    val bookId: Long,
    val chapterId: Long,
    val cleanedPages: List<String>,
    val characters: MutableMap<String, AccumulatedCharacterData> = mutableMapOf(),
    val totalDialogs: Int = 0,
    val pagesProcessed: Int = 0
) : PipelineContext

/**
 * Accumulated data for a character during analysis.
 */
data class AccumulatedCharacterData(
    val name: String,
    val pagesAppearing: MutableSet<Int> = mutableSetOf(),
    val dialogs: MutableList<DialogWithPage> = mutableListOf(),
    var traits: List<String> = emptyList(),
    var voiceProfile: VoiceProfileData? = null,
    var speakerId: Int? = null
)

/**
 * Dialog with page number for tracking.
 */
data class DialogWithPage(
    val pageNumber: Int,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)

// ==================== Pre-built Pipeline Steps ====================

/**
 * Step 1: Character Extraction
 * Runs character extraction on each page and accumulates unique names.
 */
class CharacterExtractionStep : PipelineStep<ChapterAnalysisContext> {
    override val name: String = "Character Extraction"
    
    override suspend fun execute(
        model: LlmModel,
        context: ChapterAnalysisContext,
        config: PassConfig
    ): ChapterAnalysisContext {
        val pass = com.dramebaz.app.ai.llm.pipeline.passes.CharacterExtractionPassV2()
        
        for ((pageIndex, pageText) in context.cleanedPages.withIndex()) {
            if (pageText.length < 50) continue
            
            val input = com.dramebaz.app.ai.llm.prompts.CharacterExtractionPromptInput(
                text = pageText,
                pageNumber = pageIndex + 1
            )
            
            val output = pass.execute(model, input, config)
            
            // Accumulate characters
            for (name in output.characterNames) {
                val normalizedName = name.trim()
                if (normalizedName.length < 2) continue
                
                val key = normalizedName.lowercase()
                val existing = context.characters[key]
                if (existing != null) {
                    existing.pagesAppearing.add(pageIndex + 1)
                } else {
                    context.characters[key] = AccumulatedCharacterData(
                        name = normalizedName,
                        pagesAppearing = mutableSetOf(pageIndex + 1)
                    )
                }
            }
        }
        
        return context.copy(pagesProcessed = context.cleanedPages.size)
    }
}

/**
 * Step 2: Dialog Extraction
 * Runs dialog extraction on each page for characters appearing on that page.
 */
class DialogExtractionStep : PipelineStep<ChapterAnalysisContext> {
    override val name: String = "Dialog Extraction"
    
    override suspend fun execute(
        model: LlmModel,
        context: ChapterAnalysisContext,
        config: PassConfig
    ): ChapterAnalysisContext {
        val pass = com.dramebaz.app.ai.llm.pipeline.passes.DialogExtractionPassV2()
        var totalDialogs = 0
        
        for ((pageIndex, pageText) in context.cleanedPages.withIndex()) {
            val pageNum = pageIndex + 1
            
            // Get characters appearing on this page
            val charactersOnPage = context.characters.values
                .filter { it.pagesAppearing.contains(pageNum) }
                .map { it.name }
            
            if (charactersOnPage.isEmpty()) continue
            
            val input = com.dramebaz.app.ai.llm.prompts.DialogExtractionPromptInput(
                text = pageText,
                characterNames = charactersOnPage,
                pageNumber = pageNum
            )
            
            val output = pass.execute(model, input, config)
            
            // Add dialogs to characters
            for (dialog in output.dialogs) {
                val charKey = dialog.speaker.lowercase()
                context.characters[charKey]?.dialogs?.add(DialogWithPage(
                    pageNumber = pageNum,
                    text = dialog.text,
                    emotion = dialog.emotion,
                    intensity = dialog.intensity
                ))
                totalDialogs++
            }
        }

        return context.copy(totalDialogs = totalDialogs)
    }
}

/**
 * Step 3: Voice Profile Suggestion
 * Runs voice profile suggestion for characters in batches.
 */
class VoiceProfileStep(
    private val batchSize: Int = 4
) : PipelineStep<ChapterAnalysisContext> {
    override val name: String = "Voice Profile Suggestion"

    override suspend fun execute(
        model: LlmModel,
        context: ChapterAnalysisContext,
        config: PassConfig
    ): ChapterAnalysisContext {
        val pass = com.dramebaz.app.ai.llm.pipeline.passes.VoiceProfilePassV2()
        val speakerMatcher = com.dramebaz.app.ai.tts.SpeakerMatcher

        val charactersToProcess = context.characters.values
            .filter { it.voiceProfile == null }
            .toList()

        val batches = charactersToProcess.chunked(batchSize)

        for (batch in batches) {
            val characterNames = batch.map { it.name }

            // Build dialog context
            val dialogContext = batch.joinToString("\n\n") { char ->
                val dialogs = char.dialogs.take(5).joinToString("\n") { d ->
                    "${char.name}: \"${d.text}\""
                }
                "Character: ${char.name}\nDialogs:\n$dialogs"
            }

            val input = com.dramebaz.app.ai.llm.prompts.VoiceProfilePromptInput(
                characterNames = characterNames,
                dialogContext = dialogContext
            )

            val output = pass.execute(model, input, config)

            // Update characters with profiles
            for (profile in output.profiles) {
                val charKey = profile.characterName.lowercase()
                val charData = context.characters[charKey]
                if (charData != null) {
                    charData.voiceProfile = profile

                    // Assign speaker ID
                    charData.speakerId = speakerMatcher.suggestSpeakerId(
                        traits = "${profile.gender}, ${profile.age}, ${profile.tone}",
                        personalitySummary = null,
                        name = charData.name
                    )
                }
            }
        }

        return context
    }
}

