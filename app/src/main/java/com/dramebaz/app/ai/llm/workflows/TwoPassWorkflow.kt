package com.dramebaz.app.ai.llm.workflows

import android.content.Context
import com.dramebaz.app.ai.llm.models.Gemma3nModel
import com.dramebaz.app.ai.llm.StubFallbacks
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.utils.AppLogger

/**
 * Two-Pass Character Analysis Workflow using Gemma 3n E2B Lite model.
 * 
 * Workflow:
 * - Pass 1: Extract character names + complete voice profiles (~3000 tokens per segment)
 * - Pass 2: Extract dialogs with speaker attribution (~1500 tokens per segment)
 * 
 * Features:
 * - Uses LiteRT-LM for on-device Gemma model inference
 * - GPU acceleration with CPU fallback
 * - Text segmentation with boundary-aware splitting
 * - Token limit error handling with retry logic
 * - SpeakerMatcher integration for speaker ID assignment
 * 
 * Design Pattern: Strategy Pattern - implements AnalysisWorkflow interface.
 */
class TwoPassWorkflow : AnalysisWorkflow {
    companion object {
        private const val TAG = "TwoPassWorkflow"
    }

    override val passCount: Int = 2
    override val workflowName: String = "Gemma 2-Pass Workflow"

    private var gemmaModel: Gemma3nModel? = null
    private var appContext: Context? = null

    override suspend fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        gemmaModel = Gemma3nModel(context)
        val success = gemmaModel?.loadModel() ?: false
        if (success) {
            AppLogger.i(TAG, "✅ $workflowName initialized: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "❌ Failed to initialize $workflowName")
        }
        return success
    }

    override fun isReady(): Boolean = gemmaModel?.isModelLoaded() == true

    override fun release() {
        gemmaModel?.release()
        gemmaModel = null
    }

    override fun getExecutionProvider(): String = gemmaModel?.getExecutionProvider() ?: "unknown"

    override suspend fun analyzeChapter(
        bookId: Long,
        chapterText: String,
        chapterIndex: Int,
        totalChapters: Int,
        onProgress: ((String) -> Unit)?,
        onCharacterProcessed: ((String) -> Unit)?
    ): List<CharacterAnalysisResult> {
        val model = gemmaModel ?: return emptyList()
        val engine = model.getUnderlyingEngine()
        
        val extractedCharacters = mutableMapOf<String, MutableCharacterData>()
        val chapterPrefix = "Chapter ${chapterIndex + 1}/$totalChapters"
        
        // ============ Pass 1: Extract characters and voice profiles ============
        onProgress?.invoke("$chapterPrefix: Pass 1 - Extracting characters...")
        AppLogger.i(TAG, "🔄 Starting Pass 1: Extract characters and voice profiles")
        
        val pass1Segments = engine.segmentTextForPass1(chapterText)
        AppLogger.d(TAG, "   Text split into ${pass1Segments.size} segments for Pass 1")
        
        for ((idx, segment) in pass1Segments.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 1 - Segment ${idx + 1}/${pass1Segments.size}")
            
            val characters = engine.pass1ExtractCharactersAndVoiceProfiles(segment)
            
            for (charResult in characters) {
                val key = charResult.name.lowercase()
                if (!extractedCharacters.containsKey(key)) {
                    // Assign speaker ID using SpeakerMatcher
                    val speakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(charResult.traits, null, charResult.name) ?: 45

                    extractedCharacters[key] = MutableCharacterData(
                        name = charResult.name,
                        traits = charResult.traits.toMutableList(),
                        voiceProfile = charResult.voiceProfile.toMutableMap().apply {
                            put("speaker_id", speakerId)
                        },
                        speakerId = speakerId
                    )
                    onCharacterProcessed?.invoke(charResult.name)
                    AppLogger.d(TAG, "   ✅ New character: ${charResult.name} (speaker_id=$speakerId)")
                }
            }
        }
        
        if (extractedCharacters.isEmpty()) {
            AppLogger.w(TAG, "⚠️ No characters found in Pass 1, using stub fallback")
            val stubNames = StubFallbacks.detectCharactersOnPage(chapterText)
            for (name in stubNames) {
                val key = name.lowercase()
                val (traits, profile) = StubFallbacks.singleCharacterTraitsAndProfile(name)
                extractedCharacters[key] = MutableCharacterData(
                    name = name,
                    traits = traits.toMutableList(),
                    voiceProfile = profile.toMutableMap(),
                    speakerId = (profile["speaker_id"] as? Int) ?: -1
                )
            }
        }
        
        AppLogger.i(TAG, "   Pass 1 complete: ${extractedCharacters.size} characters extracted")
        
        // ============ Pass 2: Extract dialogs ============
        onProgress?.invoke("$chapterPrefix: Pass 2 - Extracting dialogs...")
        AppLogger.i(TAG, "🔄 Starting Pass 2: Extract dialogs")
        
        val pass2Segments = engine.segmentTextForPass2(chapterText)
        val characterNames = extractedCharacters.values.map { it.name }
        
        for ((idx, segment) in pass2Segments.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 2 - Segment ${idx + 1}/${pass2Segments.size}")
            
            val dialogs = engine.pass2ExtractDialogs(segment, characterNames)
            
            for (dialog in dialogs) {
                val key = dialog.speaker.lowercase()
                extractedCharacters[key]?.dialogs?.add(
                    DialogEntry(dialog.speaker, dialog.text, dialog.emotion, dialog.intensity)
                )
            }
        }
        
        AppLogger.i(TAG, "   Pass 2 complete: dialogs extracted")
        
        // Build final results
        onProgress?.invoke("$chapterPrefix: Analysis complete")
        return extractedCharacters.values.map { data ->
            CharacterAnalysisResult(
                name = data.name,
                traits = data.traits,
                voiceProfile = data.voiceProfile,
                dialogs = data.dialogs,
                speakerId = data.speakerId
            )
        }
    }

    private data class MutableCharacterData(
        val name: String,
        val traits: MutableList<String>,
        val voiceProfile: MutableMap<String, Any>,
        val dialogs: MutableList<DialogEntry> = mutableListOf(),
        val speakerId: Int = -1
    )
}

