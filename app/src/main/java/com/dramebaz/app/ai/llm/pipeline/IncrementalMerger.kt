package com.dramebaz.app.ai.llm.pipeline

import com.dramebaz.app.ai.llm.pipeline.matching.CharacterNameMatcher
import com.dramebaz.app.ai.llm.pipeline.matching.FuzzyCharacterNameMatcher
import com.dramebaz.app.ai.llm.pipeline.merging.PreferDetailedVoiceProfileMerger
import com.dramebaz.app.ai.llm.pipeline.merging.VoiceProfileMerger
import com.dramebaz.app.ai.llm.prompts.BatchedAnalysisOutput
import com.dramebaz.app.ai.llm.prompts.ExtractedCharacterData
import com.dramebaz.app.ai.llm.prompts.ExtractedVoiceProfile
import com.dramebaz.app.utils.AppLogger

/**
 * Incrementally merges batch results into accumulated character data.
 *
 * Key features:
 * - Deduplicates character names (case-insensitive matching)
 * - Resolves name variants (full names vs last names, nicknames)
 * - Merges traits without duplicates
 * - Accumulates dialogs in order
 * - Keeps the most detailed voice profile
 *
 * Uses Strategy Pattern for:
 * - CharacterNameMatcher: Pluggable name matching algorithms
 * - VoiceProfileMerger: Pluggable voice profile merging strategies
 */
object IncrementalMerger {
    private const val TAG = "IncrementalMerger"

    // Pluggable strategies (defaults can be overridden)
    var nameMatcher: CharacterNameMatcher = FuzzyCharacterNameMatcher()
    var voiceProfileMerger: VoiceProfileMerger = PreferDetailedVoiceProfileMerger()

    /**
     * Accumulated character data across multiple batches.
     */
    data class MergedCharacterData(
        val name: String,
        val canonicalName: String,  // Normalized for matching
        val dialogs: MutableList<String> = mutableListOf(),
        val traits: MutableSet<String> = mutableSetOf(),
        var voiceProfile: ExtractedVoiceProfile? = null,
        val nameVariants: MutableSet<String> = mutableSetOf()  // All observed name forms
    )

    /**
     * Merge new batch results into existing accumulated data.
     *
     * @param existing Current accumulated character data (keyed by canonical name)
     * @param newBatch Results from the latest batch
     * @return Updated accumulated data
     */
    fun merge(
        existing: MutableMap<String, MergedCharacterData>,
        newBatch: BatchedAnalysisOutput
    ): MutableMap<String, MergedCharacterData> {
        for (character in newBatch.characters) {
            val canonicalName = nameMatcher.canonicalize(character.name)

            // Find existing character by canonical name or name variant
            val existingChar = findExistingCharacter(existing, character.name, canonicalName)

            if (existingChar != null) {
                // Merge into existing character
                mergeIntoExisting(existingChar, character)
                AppLogger.d(TAG, "Merged '${character.name}' into existing '${existingChar.name}'")
            } else {
                // Create new character entry
                val newChar = MergedCharacterData(
                    name = character.name,
                    canonicalName = canonicalName,
                    dialogs = character.dialogs.toMutableList(),
                    traits = character.traits.toMutableSet(),
                    voiceProfile = character.voiceProfile
                )
                newChar.nameVariants.add(character.name)
                existing[canonicalName] = newChar
                AppLogger.d(TAG, "Added new character '${character.name}' (canonical: $canonicalName)")
            }
        }

        return existing
    }

    /**
     * Find existing character by canonical name or name variant.
     */
    private fun findExistingCharacter(
        existing: Map<String, MergedCharacterData>,
        name: String,
        canonicalName: String
    ): MergedCharacterData? {
        // Direct match on canonical name
        existing[canonicalName]?.let { return it }

        // Check if this name is a variant of an existing character
        for ((_, charData) in existing) {
            if (nameMatcher.isVariant(name, charData.name, charData.nameVariants)) {
                return charData
            }
        }

        return null
    }

    /**
     * Merge new character data into existing accumulated data.
     */
    private fun mergeIntoExisting(existing: MergedCharacterData, new: ExtractedCharacterData) {
        // Add name variant
        existing.nameVariants.add(new.name)

        // Prefer longer name as canonical display name
        if (new.name.length > existing.name.length) {
            // Keep the longer, more complete name but preserve canonical key
            AppLogger.d(TAG, "Updating display name from '${existing.name}' to '${new.name}'")
        }

        // Accumulate dialogs (preserve order)
        existing.dialogs.addAll(new.dialogs)

        // Merge traits (deduplicate using case-insensitive comparison)
        for (trait in new.traits) {
            val traitLower = trait.lowercase()
            if (existing.traits.none { it.lowercase() == traitLower }) {
                existing.traits.add(trait)
            }
        }

        // Merge voice profiles using the pluggable merger
        existing.voiceProfile = voiceProfileMerger.merge(existing.voiceProfile, new.voiceProfile)
    }

    /**
     * Convert merged data to list format for final output.
     */
    fun toList(merged: Map<String, MergedCharacterData>): List<MergedCharacterData> {
        return merged.values.toList().sortedByDescending { it.dialogs.size }
    }
}

