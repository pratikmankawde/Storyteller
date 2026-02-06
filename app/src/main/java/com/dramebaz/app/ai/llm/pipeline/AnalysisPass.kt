package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.models.LlmModel

/**
 * Base interface for individual analysis passes in a multi-pass workflow.
 * 
 * Design Pattern: Strategy Pattern - each pass encapsulates a specific analysis step
 * that can be executed independently of the underlying LLM model.
 * 
 * Architecture:
 * - Pass defines WHAT to analyze (prompts, parsing logic)
 * - LlmModel defines HOW to generate (inference)
 * - PassConfig defines model-specific tuning (tokens, temperature)
 * 
 * This separation allows any pass to work with any LLM model.
 */
interface AnalysisPass<I, O> {
    
    /**
     * Unique identifier for this pass (e.g., "character_extraction", "dialog_extraction")
     */
    val passId: String
    
    /**
     * Human-readable name for logging and UI
     */
    val displayName: String
    
    /**
     * Execute this pass using the provided LLM model.
     * 
     * @param model The LLM model to use for inference
     * @param input Input data for this pass
     * @param config Configuration for this pass (tokens, temperature, etc.)
     * @return Output data from this pass
     */
    suspend fun execute(
        model: LlmModel,
        input: I,
        config: PassConfig
    ): O
}

/**
 * Configuration for a single pass execution.
 * Allows model-specific tuning without changing pass logic.
 */
data class PassConfig(
    /** Maximum tokens for LLM response */
    val maxTokens: Int = 1024,
    
    /** Temperature for LLM generation (0.0 = deterministic, 2.0 = creative) */
    val temperature: Float = 0.15f,
    
    /** Maximum input characters per segment */
    val maxSegmentChars: Int = 10_000,
    
    /** Number of retry attempts on failure */
    val maxRetries: Int = 3,
    
    /** Tokens to reduce on each retry after token limit error */
    val tokenReductionOnRetry: Int = 500
) {
    companion object {
        /** Default config for character extraction (Pass 1) */
        val PASS1_CHARACTER_EXTRACTION = PassConfig(
            maxTokens = 256,
            temperature = 0.1f,
            maxSegmentChars = 12_000
        )

        /** Default config for dialog extraction (Pass 2) */
        val PASS2_DIALOG_EXTRACTION = PassConfig(
            maxTokens = 1024,
            temperature = 0.15f,
            maxSegmentChars = 6_000
        )

        /** Default config for traits and voice profile (Pass 3) */
        val PASS3_TRAITS_EXTRACTION = PassConfig(
            maxTokens = 384,
            temperature = 0.1f,
            maxSegmentChars = 10_000
        )

        /** Config for chapter analysis (summary, characters, dialogs, sound cues) */
        val CHAPTER_ANALYSIS = PassConfig(
            maxTokens = 768,
            temperature = 0.15f,
            maxSegmentChars = 10_000
        )

        /** Config for extended analysis (themes, symbols, foreshadowing, vocabulary) */
        val EXTENDED_ANALYSIS = PassConfig(
            maxTokens = 1024,
            temperature = 0.15f,
            maxSegmentChars = 10_000
        )

        /** Config for key moments extraction */
        val KEY_MOMENTS_EXTRACTION = PassConfig(
            maxTokens = 512,
            temperature = 0.2f,
            maxSegmentChars = 10_000
        )

        /** Config for relationships extraction */
        val RELATIONSHIPS_EXTRACTION = PassConfig(
            maxTokens = 512,
            temperature = 0.2f,
            maxSegmentChars = 10_000
        )

        /** Config for voice profile suggestion */
        val VOICE_PROFILE_SUGGESTION = PassConfig(
            maxTokens = 512,
            temperature = 0.2f
        )

        /** Config for scene prompt generation (VIS-001) */
        val SCENE_PROMPT_GENERATION = PassConfig(
            maxTokens = 512,
            temperature = 0.3f,
            maxSegmentChars = 2000
        )

        /** Config for story generation */
        val STORY_GENERATION = PassConfig(
            maxTokens = 2048,
            temperature = 0.7f
        )

        /** Config for story remix */
        val STORY_REMIX = PassConfig(
            maxTokens = 2048,
            temperature = 0.7f,
            maxSegmentChars = 5000
        )

        /** Config for character detection */
        val CHARACTER_DETECTION = PassConfig(
            maxTokens = 256,
            temperature = 0.1f,
            maxSegmentChars = 10_000
        )

        /** Config for trait inference */
        val INFER_TRAITS = PassConfig(
            maxTokens = 256,
            temperature = 0.15f,
            maxSegmentChars = 5000
        )

        /** Config for personality inference */
        val PERSONALITY_INFERENCE = PassConfig(
            maxTokens = 256,
            temperature = 0.15f
        )
    }
}

/**
 * Input for character extraction pass.
 */
data class CharacterExtractionInput(
    val text: String,
    val segmentIndex: Int = 0,
    val totalSegments: Int = 1
)

/**
 * Output from character extraction pass.
 */
data class CharacterExtractionOutput(
    val characterNames: List<String>
)

/**
 * Input for dialog extraction pass.
 */
data class DialogExtractionInput(
    val text: String,
    val characterNames: List<String>,
    val segmentIndex: Int = 0,
    val totalSegments: Int = 1
)

/**
 * Output from dialog extraction pass.
 */
data class DialogExtractionOutput(
    val dialogs: List<ExtractedDialog>
)

/**
 * Extracted dialog with speaker attribution.
 */
data class ExtractedDialog(
    val speaker: String,
    val text: String,
    val emotion: String = "neutral",
    val intensity: Float = 0.5f
)

/**
 * Input for traits extraction pass.
 */
data class TraitsExtractionInput(
    val characterName: String,
    val contextText: String
)

/**
 * Output from traits extraction pass.
 */
data class TraitsExtractionOutput(
    val characterName: String,
    val traits: List<String>,
    val voiceProfile: Map<String, Any>
)

