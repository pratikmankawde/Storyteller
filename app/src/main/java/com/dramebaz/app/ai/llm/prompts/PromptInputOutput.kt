package com.dramebaz.app.ai.llm.prompts

/**
 * Input/Output data classes for prompt definitions.
 * These are separate from pass-specific I/O to allow prompts to be reused.
 */

// ==================== Character Extraction ====================

/** Input for character extraction prompts */
data class CharacterExtractionPromptInput(
    val text: String,
    val pageNumber: Int = 0
)

/** Output from character extraction prompts */
data class CharacterExtractionPromptOutput(
    val characterNames: List<String>
)

// ==================== Dialog Extraction ====================

/** Input for dialog extraction prompts */
data class DialogExtractionPromptInput(
    val text: String,
    val characterNames: List<String>,
    val pageNumber: Int = 0
)

/** Output from dialog extraction prompts */
data class DialogExtractionPromptOutput(
    val dialogs: List<ExtractedDialogData>
)

/** Single extracted dialog */
data class ExtractedDialogData(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)

// ==================== Traits Extraction ====================

/** Input for traits extraction prompts */
data class TraitsExtractionPromptInput(
    val characterName: String,
    val contextText: String
)

/** Output from traits extraction prompts */
data class TraitsExtractionPromptOutput(
    val characterName: String,
    val traits: List<String>
)

// ==================== Voice Profile ====================

/** Input for voice profile prompts */
data class VoiceProfilePromptInput(
    val characterNames: List<String>,
    val dialogContext: String
)

/** Output from voice profile prompts */
data class VoiceProfilePromptOutput(
    val profiles: List<VoiceProfileData>
)

/** Voice profile data for a character */
data class VoiceProfileData(
    val characterName: String,
    val gender: String = "male",
    val age: String = "adult",
    val tone: String = "",
    val accent: String = "neutral",
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
    val energy: Float = 1.0f,
    val emotionBias: Map<String, Float> = emptyMap()
)

// ==================== Key Moments ====================

/** Input for key moments extraction prompts */
data class KeyMomentsPromptInput(
    val characterName: String,
    val chapterText: String,
    val chapterTitle: String
)

/** Output from key moments extraction prompts */
data class KeyMomentsPromptOutput(
    val characterName: String,
    val moments: List<KeyMomentData>
)

/** Single key moment */
data class KeyMomentData(
    val chapter: String,
    val moment: String,
    val significance: String
)

// ==================== Relationships ====================

/** Input for relationships extraction prompts */
data class RelationshipsPromptInput(
    val characterName: String,
    val chapterText: String,
    val otherCharacters: List<String>
)

/** Output from relationships extraction prompts */
data class RelationshipsPromptOutput(
    val characterName: String,
    val relationships: List<RelationshipData>
)

/** Single relationship */
data class RelationshipData(
    val character: String,
    val relationship: String,
    val nature: String
)

// ==================== Story Generation ====================

/** Input for story generation prompts */
data class StoryGenerationPromptInput(
    val userPrompt: String,
    val sourceStory: String? = null  // For remix
)

/** Output from story generation prompts */
data class StoryGenerationPromptOutput(
    val storyText: String
)

// ==================== Scene Prompt ====================

/** Input for scene prompt generation */
data class ScenePromptPromptInput(
    val sceneText: String,
    val mood: String? = null,
    val characters: List<String> = emptyList()
)

/** Output from scene prompt generation */
data class ScenePromptPromptOutput(
    val imagePrompt: String,
    val style: String = "",
    val mood: String = ""
)

