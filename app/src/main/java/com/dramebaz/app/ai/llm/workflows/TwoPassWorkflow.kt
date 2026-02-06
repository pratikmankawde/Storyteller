package com.dramebaz.app.ai.llm.workflows

import android.content.Context
import com.dramebaz.app.ai.llm.models.LlmModel
import com.dramebaz.app.ai.llm.models.LiteRtLmEngineImpl
import com.dramebaz.app.ai.llm.StubFallbacks
import com.dramebaz.app.ai.llm.prompts.ExtractionPrompts
import com.dramebaz.app.ai.tts.SpeakerMatcher
import com.dramebaz.app.ai.tts.LibrittsSpeakerCatalog
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Two-Pass Character Analysis Workflow using LiteRT-LM model (Gemma).
 *
 * This workflow uses the pure inference approach - all pass-specific logic
 * (prompts, parsing) is contained here, and the engine is used only for
 * raw text generation via generateResponse().
 *
 * Workflow:
 * - Pass 1: Extract character names (~1024 tokens per segment)
 * - Pass 2: Extract dialogs with speaker attribution (~1024 tokens per segment)
 *
 * Design Pattern: Strategy Pattern - implements AnalysisWorkflow interface.
 */
class TwoPassWorkflow : AnalysisWorkflow {
    companion object {
        private const val TAG = "TwoPassWorkflow"
        private const val PASS1_MAX_SEGMENT_CHARS = 12_000
        private const val PASS2_MAX_SEGMENT_CHARS = 6_000
    }

    override val passCount: Int = 2
    override val workflowName: String = "LiteRT-LM 2-Pass Workflow"

    private var model: LlmModel? = null
    private var appContext: Context? = null
    private val gson = Gson()

    override suspend fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        model = LiteRtLmEngineImpl(context)
        val success = model?.loadModel() ?: false
        if (success) {
            AppLogger.i(TAG, "✅ $workflowName initialized: ${getExecutionProvider()}")
        } else {
            AppLogger.e(TAG, "❌ Failed to initialize $workflowName")
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

        val extractedCharacters = mutableMapOf<String, MutableCharacterData>()
        val chapterPrefix = "Chapter ${chapterIndex + 1}/$totalChapters"

        // ============ Pass 1: Extract characters ============
        onProgress?.invoke("$chapterPrefix: Pass 1 - Extracting characters...")
        AppLogger.i(TAG, "🔄 Starting Pass 1: Extract characters")

        val pass1Segments = segmentText(chapterText, PASS1_MAX_SEGMENT_CHARS)
        AppLogger.d(TAG, "   Text split into ${pass1Segments.size} segments for Pass 1")

        for ((idx, segment) in pass1Segments.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 1 - Segment ${idx + 1}/${pass1Segments.size}")

            val names = extractCharacterNames(llm, segment)

            for (name in names) {
                val key = name.lowercase()
                if (!extractedCharacters.containsKey(key)) {
                    val voiceProfile = createRandomVoiceProfile()
                    val speakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(emptyList(), null, name)
                        ?: (voiceProfile["speaker_id"] as? Int) ?: 45

                    extractedCharacters[key] = MutableCharacterData(
                        name = name,
                        traits = mutableListOf(),
                        voiceProfile = voiceProfile.toMutableMap().apply {
                            put("speaker_id", speakerId)
                        },
                        speakerId = speakerId
                    )
                    onCharacterProcessed?.invoke(name)
                    AppLogger.d(TAG, "   ✅ New character: $name (speaker_id=$speakerId)")
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

        val pass2Segments = segmentText(chapterText, PASS2_MAX_SEGMENT_CHARS)
        val characterNames = extractedCharacters.values.map { it.name }

        for ((idx, segment) in pass2Segments.withIndex()) {
            onProgress?.invoke("$chapterPrefix: Pass 2 - Segment ${idx + 1}/${pass2Segments.size}")

            val dialogs = extractDialogs(llm, segment, characterNames)

            for (dialog in dialogs) {
                val key = dialog.speaker.lowercase()
                extractedCharacters[key]?.dialogs?.add(dialog)
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

    // ==================== Pass 1: Character Extraction ====================

    private suspend fun extractCharacterNames(llm: LlmModel, segmentText: String): List<String> {
        val userPrompt = ExtractionPrompts.buildPass1ExtractNamesPrompt(segmentText)
        val response = llm.generateResponse(
            systemPrompt = ExtractionPrompts.PASS1_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = 256,
            temperature = 0.1f
        )
        return parsePass1Response(response)
    }

    private fun parsePass1Response(response: String): List<String> {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val characters = obj["characters"] as? List<*> ?: return emptyList()
            characters.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().takeIf { it.isNotBlank() }
                    else -> (entry as? Map<*, *>)?.get("name")?.toString()?.trim()?.takeIf { it.isNotBlank() }
                }
            }.distinctBy { it.lowercase() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-1: Failed to parse response", e)
            emptyList()
        }
    }

    // ==================== Pass 2: Dialog Extraction ====================

    private suspend fun extractDialogs(llm: LlmModel, segmentText: String, characterNames: List<String>): List<DialogEntry> {
        val userPrompt = ExtractionPrompts.buildPass2ExtractDialogsPrompt(segmentText, characterNames)
        val response = llm.generateResponse(
            systemPrompt = ExtractionPrompts.PASS2_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = 1024,
            temperature = 0.15f
        )
        return parsePass2Response(response)
    }

    private fun parsePass2Response(response: String): List<DialogEntry> {
        return try {
            val json = extractJsonFromResponse(response)
            // First try: standard dialogs array format
            val obj = gson.fromJson(json, Map::class.java) as? Map<*, *>
            if (obj != null && obj.containsKey("dialogs")) {
                @Suppress("UNCHECKED_CAST")
                val dialogs = obj["dialogs"] as? List<*> ?: return emptyList()
                return dialogs.mapNotNull { dialogObj ->
                    val dialogMap = dialogObj as? Map<*, *> ?: return@mapNotNull null
                    val speaker = (dialogMap["speaker"] as? String)?.trim() ?: return@mapNotNull null
                    val text = (dialogMap["text"] as? String)?.trim() ?: return@mapNotNull null
                    if (text.isBlank()) return@mapNotNull null
                    val emotion = (dialogMap["emotion"] as? String)?.trim()?.lowercase() ?: "neutral"
                    val intensity = (dialogMap["intensity"] as? Number)?.toFloat() ?: 0.5f
                    DialogEntry(speaker, text, emotion, intensity.coerceIn(0f, 1f))
                }
            }
            // Fallback: array of {speaker: text} objects
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson<List<Map<String, Any>>>(json, object : TypeToken<List<Map<String, Any>>>() {}.type)
                ?: return emptyList()
            list.mapNotNull { map ->
                if (map.size != 1) return@mapNotNull null
                val (speaker, text) = map.entries.firstOrNull() ?: return@mapNotNull null
                val speakerStr = speaker.trim()
                val textStr = (text as? String)?.trim() ?: text?.toString()?.trim() ?: return@mapNotNull null
                if (textStr.isBlank()) return@mapNotNull null
                DialogEntry(speakerStr, textStr, "neutral", 0.5f)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pass-2: Failed to parse response", e)
            emptyList()
        }
    }

    // ==================== Utility Methods ====================

    private fun extractJsonFromResponse(response: String): String {
        var json = response.trim()
        if (json.startsWith("```json")) json = json.removePrefix("```json").trim()
        if (json.startsWith("```")) json = json.removePrefix("```").trim()
        if (json.endsWith("```")) json = json.removeSuffix("```").trim()

        val objStart = json.indexOf('{')
        val objEnd = json.lastIndexOf('}')
        val arrStart = json.indexOf('[')
        val arrEnd = json.lastIndexOf(']')

        return when {
            objStart >= 0 && objEnd > objStart -> json.substring(objStart, objEnd + 1)
            arrStart >= 0 && arrEnd > arrStart -> json.substring(arrStart, arrEnd + 1)
            else -> json
        }
    }

    private fun createRandomVoiceProfile(): Map<String, Any> {
        val speakerId = (LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID).random()
        return mapOf(
            "pitch" to 1.0,
            "speed" to 1.0,
            "energy" to 0.7,
            "gender" to "neutral",
            "age" to "middle-aged",
            "tone" to "neutral",
            "accent" to "neutral",
            "speaker_id" to speakerId
        )
    }

    private fun segmentText(text: String, maxCharsPerSegment: Int): List<String> {
        if (text.length <= maxCharsPerSegment) {
            return listOf(text)
        }

        val segments = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxCharsPerSegment) {
                segments.add(remaining)
                break
            }

            var breakPoint = maxCharsPerSegment
            val searchStart = (maxCharsPerSegment * 0.8).toInt()

            val paragraphBreak = remaining.lastIndexOf("\n\n", maxCharsPerSegment)
            if (paragraphBreak > searchStart) {
                breakPoint = paragraphBreak + 2
            } else {
                val sentenceEnds = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
                for (end in sentenceEnds) {
                    val idx = remaining.lastIndexOf(end, maxCharsPerSegment)
                    if (idx > searchStart) {
                        breakPoint = idx + end.length
                        break
                    }
                }
            }

            segments.add(remaining.substring(0, breakPoint))
            remaining = remaining.substring(breakPoint)
        }

        return segments
    }

    private data class MutableCharacterData(
        val name: String,
        val traits: MutableList<String>,
        val voiceProfile: MutableMap<String, Any>,
        val dialogs: MutableList<DialogEntry> = mutableListOf(),
        val speakerId: Int = -1
    )
}

