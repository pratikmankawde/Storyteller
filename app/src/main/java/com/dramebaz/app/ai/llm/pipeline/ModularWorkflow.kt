package com.dramebaz.app.ai.llm.pipeline

import android.content.Context
import com.dramebaz.app.ai.llm.StubFallbacks
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LlmModelFactory
import com.dramebaz.app.ai.llm.workflows.AnalysisWorkflow
import com.dramebaz.app.ai.llm.workflows.CharacterAnalysisResult
import com.dramebaz.app.ai.llm.workflows.DialogEntry
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.utils.AppLogger

/**
 * Modular Analysis Workflow - Model-agnostic multi-pass character analysis.
 * 
 * This workflow decouples the analysis pipeline from specific LLM models.
 * Any LlmModel implementation can be used with any workflow configuration.
 * 
 * Architecture:
 * - WorkflowConfig defines WHICH passes to run and their settings
 * - AnalysisPass implementations define HOW to prompt and parse
 * - LlmModel defines HOW to generate text (inference)
 * 
 * Design Pattern: Strategy + Template Method
 * - Strategy: Different pass configurations can be swapped
 * - Template: The overall workflow structure is fixed
 */
class ModularWorkflow(
    private val config: WorkflowConfig = WorkflowConfig.THREE_PASS
) : AnalysisWorkflow {
    
    companion object {
        private const val TAG = "ModularWorkflow"
    }
    
    override val passCount: Int = config.passCount
    override val workflowName: String = config.name
    
    private var model: LlmModel? = null
    private var appContext: Context? = null
    
    // Pass instances (lazy initialized)
    private val characterPass = CharacterExtractionPass()
    private val dialogPass = DialogExtractionPass()
    private val traitsPass = TraitsExtractionPass()
    
    override suspend fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        model = LlmModelFactory.createDefaultModel(context)
        val success = model?.loadModel() ?: false
        if (success) {
            AppLogger.i(TAG, "✅ $workflowName initialized: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "❌ Failed to initialize $workflowName")
        }
        return success
    }
    
    /**
     * Initialize with a specific LlmModel instance.
     * This allows using any model with any workflow configuration.
     */
    suspend fun initializeWithModel(context: Context, llmModel: LlmModel): Boolean {
        appContext = context.applicationContext
        model = llmModel
        val success = model?.loadModel() ?: false
        if (success) {
            AppLogger.i(TAG, "✅ $workflowName initialized with custom model: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "❌ Failed to initialize $workflowName with custom model")
        }
        return success
    }
    
    override fun isReady(): Boolean = model?.isModelLoaded() == true
    
    override fun release() {
        model?.release()
        model = null
    }
    
    override fun getExecutionProvider(): String = model?.getExecutionProvider() ?: "unknown"
    
    override suspend fun analyzeChapter(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int,
        totalChapters: Int,
        onProgress: ((String) -> Unit)?,
        onCharacterProcessed: ((String) -> Unit)?
    ): List<CharacterAnalysisResult> {
        val llm = model ?: return emptyList()
        val chapterPrefix = "Chapter ${chapterIndex + 1}/$totalChapters"
        
        // Accumulated data
        val characterPages = mutableMapOf<String, MutableList<String>>()
        val characterDialogs = mutableMapOf<String, MutableList<DialogEntry>>()
        val allCharacterNames = mutableSetOf<String>()
        
        // ============ Pass 1: Character Extraction ============
        if (config.runCharacterExtraction) {
            onProgress?.invoke("$chapterPrefix: Pass 1 - Extracting characters...")
            AppLogger.i(TAG, "🔄 Starting Pass 1: Character Extraction")
            
            val segments = segmentText(chapterText, config.segmentSizePass1)
            
            for ((idx, segment) in segments.withIndex()) {
                onProgress?.invoke("$chapterPrefix: Pass 1 - Segment ${idx + 1}/${segments.size}")
                
                val input = CharacterExtractionInput(segment, idx, segments.size)
                val output = characterPass.execute(llm, input, config.pass1Config)
                
                for (name in output.characterNames) {
                    val key = name.lowercase()
                    allCharacterNames.add(name)
                    if (!characterPages.containsKey(key)) {
                        characterPages[key] = mutableListOf()
                    }
                    characterPages[key]?.add(segment)
                }
            }
            
            // Fallback if no characters found
            if (allCharacterNames.isEmpty()) {
                AppLogger.w(TAG, "⚠️ No characters in Pass 1, using stub fallback")
                val stubNames = StubFallbacks.detectCharactersOnPage(chapterText)
                allCharacterNames.addAll(stubNames)
                for (name in stubNames) {
                    characterPages[name.lowercase()] = mutableListOf(chapterText.take(config.maxContextPerCharacter))
                }
            }
            
            AppLogger.i(TAG, "   Pass 1 complete: ${allCharacterNames.size} characters")
        }
        
        // ============ Pass 2: Dialog Extraction ============
        if (config.runDialogExtraction && allCharacterNames.isNotEmpty()) {
            onProgress?.invoke("$chapterPrefix: Pass 2 - Extracting dialogs...")
            AppLogger.i(TAG, "🔄 Starting Pass 2: Dialog Extraction")
            
            val segments = segmentText(chapterText, config.segmentSizePass2)
            val characterNamesList = allCharacterNames.toList()
            
            for ((idx, segment) in segments.withIndex()) {
                onProgress?.invoke("$chapterPrefix: Pass 2 - Segment ${idx + 1}/${segments.size}")
                
                val input = DialogExtractionInput(segment, characterNamesList, idx, segments.size)
                val output = dialogPass.execute(llm, input, config.pass2Config)
                
                for (dialog in output.dialogs) {
                    val key = dialog.speaker.lowercase()
                    if (!characterDialogs.containsKey(key)) {
                        characterDialogs[key] = mutableListOf()
                    }
                    characterDialogs[key]?.add(DialogEntry(dialog.speaker, dialog.text, dialog.emotion, dialog.intensity))
                }
            }
            
            AppLogger.i(TAG, "   Pass 2 complete: dialogs extracted")
        }
        
        // Build results
        return buildResults(llm, allCharacterNames, characterPages, characterDialogs, chapterPrefix, onProgress, onCharacterProcessed)
    }

    /**
     * Build final results with optional Pass 3 (traits extraction).
     */
    private suspend fun buildResults(
        llm: LlmModel,
        allCharacterNames: Set<String>,
        characterPages: Map<String, MutableList<String>>,
        characterDialogs: Map<String, MutableList<DialogEntry>>,
        chapterPrefix: String,
        onProgress: ((String) -> Unit)?,
        onCharacterProcessed: ((String) -> Unit)?
    ): List<CharacterAnalysisResult> {
        val results = mutableListOf<CharacterAnalysisResult>()

        for ((idx, name) in allCharacterNames.withIndex()) {
            val key = name.lowercase()
            val dialogs = characterDialogs[key] ?: emptyList()

            var traits: List<String> = emptyList()
            var voiceProfile: Map<String, Any> = emptyMap()

            // ============ Pass 3: Traits Extraction (if enabled) ============
            if (config.runTraitsExtraction) {
                onProgress?.invoke("$chapterPrefix: Pass 3 - Character ${idx + 1}/${allCharacterNames.size}")
                AppLogger.d(TAG, "🔄 Pass 3 for '$name'")

                // Aggregate context from pages where character appears
                val contextPages = characterPages[key] ?: emptyList()
                val aggregatedContext = contextPages
                    .joinToString("\n\n---\n\n")
                    .take(config.maxContextPerCharacter)

                if (aggregatedContext.isNotEmpty()) {
                    val input = TraitsExtractionInput(name, aggregatedContext)
                    val output = traitsPass.execute(llm, input, config.pass3Config)
                    traits = output.traits
                    voiceProfile = output.voiceProfile
                }
            }

            // If no voice profile from Pass 3, create a random one
            if (voiceProfile.isEmpty()) {
                voiceProfile = createRandomVoiceProfile()
            }

            // Match to LibriTTS speaker using SpeakerMatcher
            val speakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(traits, null, name) ?: 0

            results.add(CharacterAnalysisResult(
                name = name,
                traits = traits,
                voiceProfile = voiceProfile,
                dialogs = dialogs,
                speakerId = speakerId
            ))

            onCharacterProcessed?.invoke(name)
            AppLogger.d(TAG, "   ✓ '$name': ${traits.size} traits, ${dialogs.size} dialogs, speaker=$speakerId")
        }

        AppLogger.i(TAG, "✅ $workflowName complete: ${results.size} characters analyzed")
        return results
    }

    /**
     * Segment text into chunks for processing.
     * Tries to break at sentence/paragraph boundaries when possible.
     */
    private fun segmentText(text: String, maxCharsPerSegment: Int): List<String> {
        if (text.length <= maxCharsPerSegment) return listOf(text)

        val segments = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxCharsPerSegment) {
                segments.add(remaining)
                break
            }

            // Find a good break point
            val chunk = remaining.take(maxCharsPerSegment)
            val breakPoint = findBreakPoint(chunk)

            segments.add(remaining.take(breakPoint))
            remaining = remaining.drop(breakPoint).trimStart()
        }

        return segments
    }

    /**
     * Find a good break point (paragraph, sentence, or word boundary).
     */
    private fun findBreakPoint(text: String): Int {
        // Try paragraph break first
        val paragraphBreak = text.lastIndexOf("\n\n")
        if (paragraphBreak > text.length * 0.5) return paragraphBreak + 2

        // Try sentence break
        val sentenceBreak = maxOf(
            text.lastIndexOf(". "),
            text.lastIndexOf("! "),
            text.lastIndexOf("? ")
        )
        if (sentenceBreak > text.length * 0.5) return sentenceBreak + 2

        // Try newline
        val newlineBreak = text.lastIndexOf('\n')
        if (newlineBreak > text.length * 0.5) return newlineBreak + 1

        // Fallback to word boundary
        val wordBreak = text.lastIndexOf(' ')
        if (wordBreak > text.length * 0.5) return wordBreak + 1

        // Last resort: just cut at max
        return text.length
    }

    /**
     * Create a random voice profile for characters without Pass 3 analysis.
     */
    private fun createRandomVoiceProfile(): Map<String, Any> {
        val gender = if (Math.random() > 0.5) "male" else "female"
        val ages = listOf("young", "middle-aged", "elderly")
        val age = ages.random()

        return mapOf(
            "gender" to gender,
            "age" to age,
            "pitch" to (0.9 + Math.random() * 0.2),  // 0.9-1.1
            "speed" to (0.95 + Math.random() * 0.1), // 0.95-1.05
            "energy" to (0.6 + Math.random() * 0.3), // 0.6-0.9
            "tone" to "neutral"
        )
    }
}

