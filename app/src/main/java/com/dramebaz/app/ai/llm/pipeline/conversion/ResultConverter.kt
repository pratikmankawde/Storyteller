package com.dramebaz.app.ai.llm.pipeline.conversion

import com.dramebaz.app.ai.llm.pipeline.IncrementalMerger
import com.dramebaz.app.ai.tts.SpeakerMatcher

/**
 * Adapter interface for converting between different result formats.
 * Allows the batched pipeline results to be compatible with various consumers.
 */
interface ResultConverter<TInput, TOutput> {
    /**
     * Convert from input format to output format.
     */
    fun convert(input: TInput): TOutput
}

/**
 * Converts MergedCharacterData to the accumulated character data format
 * used by BookAnalysisWorkflow.
 */
class MergedToAccumulatedConverter : ResultConverter<IncrementalMerger.MergedCharacterData, Map<String, Any?>> {

    override fun convert(input: IncrementalMerger.MergedCharacterData): Map<String, Any?> {
        // Build traits list from voice profile and extracted traits for speaker matching
        val traitsForMatching = buildTraitsForMatching(input)

        // Compute speaker ID using SpeakerMatcher
        val speakerId = SpeakerMatcher.suggestSpeakerIdFromTraitList(
            traitsForMatching,
            null,
            input.name
        ) ?: 0  // Default to speaker 0 if no match found

        return mapOf(
            "name" to input.name,
            "dialogs" to input.dialogs.mapIndexed { index, dialog ->
                mapOf(
                    "pageNumber" to index,  // Approximation since batched doesn't track page numbers
                    "text" to dialog,
                    "emotion" to "neutral",
                    "intensity" to 0.5f
                )
            },
            "traits" to input.traits.toList(),
            "voiceProfile" to input.voiceProfile?.let { vp ->
                mapOf(
                    "pitch" to vp.pitch,
                    "speed" to vp.speed,
                    "energy" to 1.0f,
                    "gender" to vp.gender,
                    "age" to vp.age,
                    "tone" to "",
                    "accent" to vp.accent
                )
            },
            "speakerId" to speakerId,
            "pagesAppearing" to emptyList<Int>()
        )
    }

    /**
     * Build a combined traits list from voice profile and extracted traits
     * for speaker matching. Prioritizes voice profile data (gender, age, accent).
     */
    private fun buildTraitsForMatching(input: IncrementalMerger.MergedCharacterData): List<String> {
        val traits = mutableListOf<String>()

        // Add voice profile traits if available (these are most reliable for matching)
        input.voiceProfile?.let { vp ->
            if (vp.gender.isNotBlank()) traits.add(vp.gender)
            if (vp.age.isNotBlank()) traits.add(vp.age)
            if (vp.accent.isNotBlank() && vp.accent != "neutral") traits.add(vp.accent)
        }

        // Add extracted traits
        traits.addAll(input.traits)

        return traits
    }
}

/**
 * Converts a list of MergedCharacterData to a list of accumulated character data maps.
 */
class BatchResultConverter : ResultConverter<List<IncrementalMerger.MergedCharacterData>, List<Map<String, Any?>>> {
    
    private val singleConverter = MergedToAccumulatedConverter()
    
    override fun convert(input: List<IncrementalMerger.MergedCharacterData>): List<Map<String, Any?>> {
        return input.map { singleConverter.convert(it) }
    }
}

/**
 * Utility object for common conversion operations.
 */
object ResultConverterUtil {
    
    private val batchConverter = BatchResultConverter()
    
    /**
     * Convert merged character data to accumulated format for JSON serialization.
     */
    fun toAccumulatedFormat(characters: List<IncrementalMerger.MergedCharacterData>): List<Map<String, Any?>> {
        return batchConverter.convert(characters)
    }
    
    /**
     * Extract statistics from result data.
     */
    fun extractStats(characters: List<IncrementalMerger.MergedCharacterData>): ResultStats {
        return ResultStats(
            characterCount = characters.size,
            dialogCount = characters.sumOf { it.dialogs.size },
            traitCount = characters.sumOf { it.traits.size },
            charactersWithVoice = characters.count { it.voiceProfile != null }
        )
    }
}

/**
 * Statistics about analysis results.
 */
data class ResultStats(
    val characterCount: Int,
    val dialogCount: Int,
    val traitCount: Int,
    val charactersWithVoice: Int
)

