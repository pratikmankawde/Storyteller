package com.dramebaz.app.ai.tts

import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.flow.first

/**
 * VOICE-002: Voice Consistency Check
 * Checks if assigned speaker IDs are still available in the TTS model.
 * Warns users about characters with missing/invalid voices and offers reassignment.
 * 
 * LibriTTS model supports speakers 0-903.
 */
class VoiceConsistencyChecker(private val characterDao: CharacterDao) {
    
    companion object {
        private const val TAG = "VoiceConsistencyChecker"
    }
    
    /**
     * Result of a voice consistency check.
     */
    data class ConsistencyResult(
        /** Characters with valid speaker IDs */
        val validCharacters: List<Character>,
        /** Characters with invalid/missing speaker IDs */
        val invalidCharacters: List<InvalidVoice>,
        /** Total characters checked */
        val totalChecked: Int
    ) {
        /** True if all characters have valid voices */
        val isConsistent: Boolean get() = invalidCharacters.isEmpty()
        
        /** Summary message for display */
        val summary: String get() = when {
            invalidCharacters.isEmpty() -> "All characters have valid voices"
            invalidCharacters.size == 1 -> "1 character has an invalid voice"
            else -> "${invalidCharacters.size} characters have invalid voices"
        }
    }
    
    /**
     * Represents a character with an invalid voice assignment.
     */
    data class InvalidVoice(
        val character: Character,
        val reason: InvalidReason,
        /** Suggested fallback speaker ID */
        val suggestedFallback: Int = LibrittsSpeakerCatalog.MIN_SPEAKER_ID
    )
    
    /**
     * Reason why a voice assignment is invalid.
     */
    enum class InvalidReason {
        /** Speaker ID is null */
        NOT_ASSIGNED,
        /** Speaker ID is out of range (not 0-903) */
        OUT_OF_RANGE,
        /** Speaker ID does not exist in the catalog */
        NOT_IN_CATALOG
    }
    
    /**
     * Check voice consistency for all characters in a book.
     * @param bookId Book ID to check
     * @return ConsistencyResult with lists of valid and invalid characters
     */
    suspend fun checkVoiceConsistency(bookId: Long): ConsistencyResult {
        val characters = characterDao.getByBookId(bookId).first()
        AppLogger.d(TAG, "Checking voice consistency for ${characters.size} characters in book $bookId")
        
        val validCharacters = mutableListOf<Character>()
        val invalidCharacters = mutableListOf<InvalidVoice>()
        
        for (character in characters) {
            val speakerId = character.speakerId
            
            when {
                speakerId == null -> {
                    // Speaker ID not assigned - suggest one based on traits
                    val fallback = suggestFallbackSpeaker(character)
                    invalidCharacters.add(InvalidVoice(character, InvalidReason.NOT_ASSIGNED, fallback))
                    AppLogger.w(TAG, "Character '${character.name}' has no speaker ID assigned")
                }
                speakerId < LibrittsSpeakerCatalog.MIN_SPEAKER_ID || speakerId > LibrittsSpeakerCatalog.MAX_SPEAKER_ID -> {
                    // Speaker ID out of range
                    val fallback = suggestFallbackSpeaker(character)
                    invalidCharacters.add(InvalidVoice(character, InvalidReason.OUT_OF_RANGE, fallback))
                    AppLogger.w(TAG, "Character '${character.name}' has out-of-range speaker ID: $speakerId")
                }
                !isValidSpeakerId(speakerId) -> {
                    // Speaker ID not in catalog (edge case for sparse catalogs)
                    val fallback = suggestFallbackSpeaker(character)
                    invalidCharacters.add(InvalidVoice(character, InvalidReason.NOT_IN_CATALOG, fallback))
                    AppLogger.w(TAG, "Character '${character.name}' has invalid speaker ID: $speakerId")
                }
                else -> {
                    // Valid speaker ID
                    validCharacters.add(character)
                }
            }
        }
        
        val result = ConsistencyResult(
            validCharacters = validCharacters,
            invalidCharacters = invalidCharacters,
            totalChecked = characters.size
        )
        
        AppLogger.i(TAG, "Voice consistency check: ${result.summary}")
        return result
    }
    
    /**
     * Check if a specific speaker ID is valid.
     */
    fun isValidSpeakerId(speakerId: Int): Boolean {
        return speakerId in LibrittsSpeakerCatalog.MIN_SPEAKER_ID..LibrittsSpeakerCatalog.MAX_SPEAKER_ID
    }
    
    /**
     * Suggest a fallback speaker ID based on character traits.
     */
    private fun suggestFallbackSpeaker(character: Character): Int {
        // Use SpeakerMatcher to find a suitable speaker based on traits
        return SpeakerMatcher.suggestSpeakerIdFromTraitList(
            traits = character.traits.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            personalitySummary = character.personalitySummary,
            name = character.name
        ) ?: LibrittsSpeakerCatalog.allSpeakers().random().speakerId
    }
    
    /**
     * Fix invalid voice assignments by applying fallback speakers.
     * @param bookId Book ID
     * @param invalidVoices List of invalid voice assignments to fix
     * @return Number of characters fixed
     */
    suspend fun fixInvalidVoices(bookId: Long, invalidVoices: List<InvalidVoice>): Int {
        var fixed = 0
        for (invalid in invalidVoices) {
            try {
                val updatedCharacter = invalid.character.copy(speakerId = invalid.suggestedFallback)
                characterDao.update(updatedCharacter)
                fixed++
                AppLogger.i(TAG, "Fixed voice for '${invalid.character.name}': speakerId=${invalid.suggestedFallback}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to fix voice for '${invalid.character.name}'", e)
            }
        }
        return fixed
    }
}

