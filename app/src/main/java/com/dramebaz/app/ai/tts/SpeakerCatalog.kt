package com.dramebaz.app.ai.tts

/**
 * Common interface for speaker catalogs (LibriTTS, VCTK, etc.).
 * Allows SpeakerMatcher to work with any TTS model's speaker set.
 */
interface SpeakerCatalog {

    /** Minimum valid speaker ID in this catalog. */
    val minSpeakerId: Int

    /** Maximum valid speaker ID in this catalog. */
    val maxSpeakerId: Int

    /** Total number of speakers in this catalog. */
    val speakerCount: Int

    /** Get traits for a specific speaker ID, or null if not found. */
    fun getTraits(speakerId: Int): SpeakerTraits?

    /** Get all speakers in this catalog. */
    fun allSpeakers(): List<SpeakerTraits>

    /** Get all female speaker IDs. */
    fun femaleSpeakerIds(): List<Int>

    /** Get all male speaker IDs. */
    fun maleSpeakerIds(): List<Int>

    /**
     * Pitch level categorization for speakers.
     * VITS cannot adjust pitch at runtime, so we use different speakers as pitch variants.
     */
    enum class PitchLevel {
        HIGH,   // Higher-pitched voice
        MEDIUM, // Medium-pitched voice
        LOW     // Lower-pitched voice
    }

    /**
     * Common traits for a speaker across all catalogs.
     * Adapters convert catalog-specific traits to this common format.
     */
    data class SpeakerTraits(
        val speakerId: Int,
        val gender: String,
        val ageYears: Int?,
        val accent: String,
        val region: String,
        val pitchLevel: PitchLevel = PitchLevel.MEDIUM
    ) {
        val isFemale: Boolean get() = gender.equals("F", ignoreCase = true)
        val isMale: Boolean get() = gender.equals("M", ignoreCase = true)

        /** Human-readable gender label for display. */
        val genderLabel: String get() = when {
            isFemale -> "Female"
            isMale -> "Male"
            else -> gender
        }

        /** Age bucket for matching. */
        val ageBucket: String
            get() = when (ageYears) {
                null -> "unknown"
                in 2..5 -> "toddler"
                in 6..10 -> "child"
                in 11..15 -> "preteen"
                in 16..19 -> "teen"
                in 20..24 -> "young"
                in 25..35 -> "young_adult"
                in 36..50 -> "middle_aged"
                in 51..65 -> "senior"
                in 66..Int.MAX_VALUE -> "elderly"
                else -> "unknown"
            }

        /** Short label for UI display. */
        fun displayLabel(): String {
            val genderWord = if (isFemale) "Female" else "Male"
            val ageStr = ageYears?.toString() ?: "?"
            val regionPart = if (region.isNotBlank()) ", $region" else ""
            return "$genderWord, $ageStr, $accent$regionPart"
        }
    }

    companion object {
        /**
         * Get the appropriate speaker catalog for the given TTS model ID.
         * Falls back to LibriTTS if model is unknown.
         */
        fun forModel(modelId: String?): SpeakerCatalog {
            return when {
                modelId == null -> LibrittsSpeakerCatalogAdapter
                modelId.contains("vctk", ignoreCase = true) -> VctkSpeakerCatalogAdapter
                modelId.contains("libritts", ignoreCase = true) -> LibrittsSpeakerCatalogAdapter
                else -> LibrittsSpeakerCatalogAdapter // Default fallback
            }
        }
    }
}

/**
 * Adapter to expose LibrittsSpeakerCatalog through the SpeakerCatalog interface.
 */
object LibrittsSpeakerCatalogAdapter : SpeakerCatalog {
    override val minSpeakerId: Int = LibrittsSpeakerCatalog.MIN_SPEAKER_ID
    override val maxSpeakerId: Int = LibrittsSpeakerCatalog.MAX_SPEAKER_ID
    override val speakerCount: Int = LibrittsSpeakerCatalog.SPEAKER_COUNT

    override fun getTraits(speakerId: Int): SpeakerCatalog.SpeakerTraits? {
        val t = LibrittsSpeakerCatalog.getTraits(speakerId) ?: return null
        return SpeakerCatalog.SpeakerTraits(
            speakerId = t.speakerId,
            gender = t.gender,
            ageYears = t.ageYears,
            accent = t.accent,
            region = t.region,
            pitchLevel = t.pitchLevel.toCommon()
        )
    }

    override fun allSpeakers(): List<SpeakerCatalog.SpeakerTraits> =
        LibrittsSpeakerCatalog.allSpeakers().map { it.toCommon() }

    override fun femaleSpeakerIds(): List<Int> = LibrittsSpeakerCatalog.femaleSpeakerIds()
    override fun maleSpeakerIds(): List<Int> = LibrittsSpeakerCatalog.maleSpeakerIds()

    private fun LibrittsSpeakerCatalog.SpeakerTraits.toCommon() = SpeakerCatalog.SpeakerTraits(
        speakerId, gender, ageYears, accent, region, pitchLevel.toCommon()
    )

    private fun LibrittsSpeakerCatalog.PitchLevel.toCommon() = when (this) {
        LibrittsSpeakerCatalog.PitchLevel.HIGH -> SpeakerCatalog.PitchLevel.HIGH
        LibrittsSpeakerCatalog.PitchLevel.MEDIUM -> SpeakerCatalog.PitchLevel.MEDIUM
        LibrittsSpeakerCatalog.PitchLevel.LOW -> SpeakerCatalog.PitchLevel.LOW
    }
}

/**
 * Adapter to expose VctkSpeakerCatalog through the SpeakerCatalog interface.
 */
object VctkSpeakerCatalogAdapter : SpeakerCatalog {
    override val minSpeakerId: Int = VctkSpeakerCatalog.MIN_SPEAKER_ID
    override val maxSpeakerId: Int = VctkSpeakerCatalog.MAX_SPEAKER_ID
    override val speakerCount: Int = VctkSpeakerCatalog.SPEAKER_COUNT

    override fun getTraits(speakerId: Int): SpeakerCatalog.SpeakerTraits? {
        val t = VctkSpeakerCatalog.getTraits(speakerId) ?: return null
        return SpeakerCatalog.SpeakerTraits(
            speakerId = t.speakerId,
            gender = t.gender,
            ageYears = t.ageYears,
            accent = t.accent,
            region = t.region,
            pitchLevel = t.pitchLevel.toCommon()
        )
    }

    override fun allSpeakers(): List<SpeakerCatalog.SpeakerTraits> =
        VctkSpeakerCatalog.allSpeakers().map { it.toCommon() }

    override fun femaleSpeakerIds(): List<Int> = VctkSpeakerCatalog.femaleSpeakerIds()
    override fun maleSpeakerIds(): List<Int> = VctkSpeakerCatalog.maleSpeakerIds()

    private fun VctkSpeakerCatalog.SpeakerTraits.toCommon() = SpeakerCatalog.SpeakerTraits(
        speakerId, gender, ageYears, accent, region, pitchLevel.toCommon()
    )

    private fun VctkSpeakerCatalog.PitchLevel.toCommon() = when (this) {
        VctkSpeakerCatalog.PitchLevel.HIGH -> SpeakerCatalog.PitchLevel.HIGH
        VctkSpeakerCatalog.PitchLevel.MEDIUM -> SpeakerCatalog.PitchLevel.MEDIUM
        VctkSpeakerCatalog.PitchLevel.LOW -> SpeakerCatalog.PitchLevel.LOW
    }
}
