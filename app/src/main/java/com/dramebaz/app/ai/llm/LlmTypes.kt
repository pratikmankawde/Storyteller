package com.dramebaz.app.ai.llm

import com.dramebaz.app.data.models.ChapterSummary
import com.dramebaz.app.data.models.Dialog
import com.dramebaz.app.data.models.SoundCueModel
import com.google.gson.annotations.SerializedName

/**
 * Shared data types for LLM model responses.
 * Renamed from QwenTypes to be model-agnostic.
 */
data class ChapterAnalysisResponse(
    @SerializedName("chapter_summary") val chapterSummary: ChapterSummary?,
    @SerializedName("characters") val characters: List<CharacterStub>?,
    @SerializedName("dialogs") val dialogs: List<Dialog>?,
    @SerializedName("sound_cues") val soundCues: List<SoundCueModel>?
)

data class CharacterStub(
    val name: String,
    val traits: List<String>?,
    @SerializedName("voice_profile") val voiceProfile: Map<String, Any>?
)

/**
 * Type alias for backward compatibility.
 * @deprecated Use ChapterAnalysisResponse directly
 */
@Deprecated("Use ChapterAnalysisResponse", ReplaceWith("ChapterAnalysisResponse"))
typealias QwenChapterAnalysisResponse = ChapterAnalysisResponse

/**
 * Type alias for backward compatibility.
 * @deprecated Use CharacterStub directly
 */
@Deprecated("Use CharacterStub", ReplaceWith("CharacterStub"))
typealias QwenCharacterStub = CharacterStub

