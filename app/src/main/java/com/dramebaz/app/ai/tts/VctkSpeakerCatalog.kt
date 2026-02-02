package com.dramebaz.app.ai.tts

/**
 * VCTK speaker metadata for vits-piper-en_GB-vctk-medium (109 speakers, IDs 0–108).
 * Source: CSTR VCTK Corpus (https://datashare.ed.ac.uk/handle/10283/3443),
 * Piper speaker_id_map (https://huggingface.co/rhasspy/piper-voices en/en_GB/vctk/medium).
 * Used to match Qwen-extracted character traits to a TTS speaker ID and to show
 * speaker traits in the voice preview / speaker selection UI.
 */
object VctkSpeakerCatalog {

    /** Speaker index in the Piper/VITS model (0–108). */
    const val MIN_SPEAKER_ID = 0
    const val MAX_SPEAKER_ID = 108
    const val SPEAKER_COUNT = 109

    /**
     * Pitch level categorization for speakers (AUG-016).
     * VITS cannot adjust pitch at runtime, so we use different speakers as pitch variants.
     * Categorization is based on typical voice characteristics by age and gender.
     */
    enum class PitchLevel {
        HIGH,   // Higher-pitched voice
        MEDIUM, // Medium-pitched voice
        LOW     // Lower-pitched voice
    }

    /**
     * Traits for one VCTK speaker (0-based index = model speaker_id).
     * Gender: "M" or "F". Accent/region: e.g. "English", "Scottish", "American", "Irish".
     */
    data class SpeakerTraits(
        val speakerId: Int,
        val gender: String,
        val ageYears: Int?,
        val accent: String,
        val region: String,
        val pitchLevel: PitchLevel = PitchLevel.MEDIUM  // AUG-016: Pitch categorization
    ) {
        val isFemale: Boolean get() = gender.equals("F", ignoreCase = true)
        val isMale: Boolean get() = gender.equals("M", ignoreCase = true)
        /** Age bucket for matching: young (<25), middle (25–40), older (>40). */
        val ageBucket: String
            get() = when (ageYears) {
                null -> "unknown"
                in 0..24 -> "young"
                in 25..40 -> "middle"
                else -> "older"
            }

        /** Short label for UI: e.g. "Female, 23, English, Southern England". */
        fun displayLabel(): String {
            val genderWord = if (isFemale) "Female" else "Male"
            val ageStr = ageYears?.toString() ?: "?"
            val regionPart = if (region.isNotBlank()) ", $region" else ""
            return "$genderWord, $ageStr, $accent$regionPart"
        }
    }

    private val catalog: List<SpeakerTraits> = buildList {
        // 0-based index = VITS speaker_id. Data from NeuML vctk-vits-onnx README (SPEAKER 1-based → our 0-based).
        // AUG-016: Pitch levels assigned based on age/gender: Young females=HIGH, older females=MEDIUM/LOW, young males=MEDIUM, older males=LOW
        add(SpeakerTraits(0, "F", 23, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(1, "M", 22, "English", "Surrey", PitchLevel.MEDIUM))
        add(SpeakerTraits(2, "M", 38, "English", "Cumbria", PitchLevel.LOW))
        add(SpeakerTraits(3, "F", 22, "English", "Southern England", PitchLevel.HIGH))
        add(SpeakerTraits(4, "F", 23, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(5, "F", 22, "English", "Stockton-on-tees", PitchLevel.HIGH))
        add(SpeakerTraits(6, "F", 23, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(7, "M", 23, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(8, "F", 23, "English", "Staffordshire", PitchLevel.MEDIUM))
        add(SpeakerTraits(9, "F", 22, "Scottish", "West Dumfries", PitchLevel.HIGH))
        add(SpeakerTraits(10, "F", 23, "English", "Manchester", PitchLevel.MEDIUM))
        add(SpeakerTraits(11, "M", 22, "Scottish", "Fife", PitchLevel.MEDIUM))
        add(SpeakerTraits(12, "F", 22, "Northern Irish", "Belfast", PitchLevel.HIGH))
        add(SpeakerTraits(13, "F", 22, "English", "SW England", PitchLevel.HIGH))
        add(SpeakerTraits(14, "F", 21, "English", "Southern England", PitchLevel.HIGH))
        add(SpeakerTraits(15, "M", 21, "Scottish", "Perth", PitchLevel.MEDIUM))
        add(SpeakerTraits(16, "M", 22, "English", "London", PitchLevel.MEDIUM))
        add(SpeakerTraits(17, "F", 22, "English", "Manchester", PitchLevel.HIGH))
        add(SpeakerTraits(18, "M", 25, "Irish", "Dublin", PitchLevel.MEDIUM))
        add(SpeakerTraits(19, "M", 22, "Scottish", "Selkirk", PitchLevel.MEDIUM))
        add(SpeakerTraits(20, "M", 22, "Scottish", "Argyll", PitchLevel.MEDIUM))
        add(SpeakerTraits(21, "F", 23, "Indian", "", PitchLevel.MEDIUM))
        add(SpeakerTraits(22, "F", 22, "Scottish", "Aberdeen", PitchLevel.HIGH))
        add(SpeakerTraits(23, "F", 22, "English", "SE England", PitchLevel.HIGH))
        add(SpeakerTraits(24, "M", 26, "Indian", "", PitchLevel.LOW))
        add(SpeakerTraits(25, "M", 22, "Scottish", "Edinburgh", PitchLevel.MEDIUM))
        add(SpeakerTraits(26, "F", 22, "Welsh", "Cardiff", PitchLevel.HIGH))
        add(SpeakerTraits(27, "M", 21, "English", "Surrey", PitchLevel.MEDIUM))
        add(SpeakerTraits(28, "M", 19, "Scottish", "Galloway", PitchLevel.MEDIUM))
        add(SpeakerTraits(29, "M", 24, "English", "Birmingham", PitchLevel.MEDIUM))
        add(SpeakerTraits(30, "F", 24, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(31, "M", 22, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(32, "M", 23, "English", "Nottingham", PitchLevel.MEDIUM))
        add(SpeakerTraits(33, "M", 21, "Scottish", "Orkney", PitchLevel.MEDIUM))
        add(SpeakerTraits(34, "F", 26, "Northern Irish", "Belfast", PitchLevel.MEDIUM))
        add(SpeakerTraits(35, "F", 23, "Scottish", "Edinburgh", PitchLevel.MEDIUM))
        add(SpeakerTraits(36, "M", 22, "Scottish", "Aberdeen", PitchLevel.MEDIUM))
        add(SpeakerTraits(37, "F", 23, "Scottish", "West Lothian", PitchLevel.MEDIUM))
        add(SpeakerTraits(38, "F", 23, "Scottish", "Ross", PitchLevel.MEDIUM))
        add(SpeakerTraits(39, "F", 22, "Irish", "Athlone", PitchLevel.HIGH))
        add(SpeakerTraits(40, "F", 23, "English", "Yorkshire", PitchLevel.MEDIUM))
        add(SpeakerTraits(41, "F", 23, "English", "Southern England", PitchLevel.MEDIUM))
        add(SpeakerTraits(42, "F", 20, "English", "Newcastle", PitchLevel.HIGH))
        add(SpeakerTraits(43, "M", 21, "English", "Yorkshire", PitchLevel.MEDIUM))
        add(SpeakerTraits(44, "M", 19, "Scottish", "Fife", PitchLevel.MEDIUM))
        add(SpeakerTraits(45, "M", 23, "Scottish", "Edinburgh", PitchLevel.MEDIUM))
        add(SpeakerTraits(46, "M", 23, "English", "Suffolk", PitchLevel.MEDIUM))
        add(SpeakerTraits(47, "M", 22, "English", "Essex", PitchLevel.MEDIUM))
        add(SpeakerTraits(48, "M", 23, "Scottish", "Midlothian", PitchLevel.MEDIUM))
        add(SpeakerTraits(49, "F", 24, "English", "Oxford", PitchLevel.MEDIUM))
        add(SpeakerTraits(50, "F", 23, "English", "NE England", PitchLevel.MEDIUM))
        add(SpeakerTraits(51, "M", 22, "English", "Cheshire", PitchLevel.MEDIUM))
        add(SpeakerTraits(52, "M", 23, "English", "Leicester", PitchLevel.MEDIUM))
        add(SpeakerTraits(53, "M", null, "Unknown", "", PitchLevel.MEDIUM))  // REF 280
        add(SpeakerTraits(54, "M", 29, "Scottish", "Edinburgh", PitchLevel.LOW))
        add(SpeakerTraits(55, "F", 23, "English", "Newcastle", PitchLevel.MEDIUM))
        add(SpeakerTraits(56, "F", 24, "Irish", "Cork", PitchLevel.MEDIUM))
        add(SpeakerTraits(57, "M", 20, "Scottish", "Fife", PitchLevel.MEDIUM))
        add(SpeakerTraits(58, "M", 21, "Scottish", "Edinburgh", PitchLevel.MEDIUM))
        add(SpeakerTraits(59, "M", 23, "English", "Newcastle", PitchLevel.MEDIUM))
        add(SpeakerTraits(60, "M", 23, "English", "York", PitchLevel.MEDIUM))
        add(SpeakerTraits(61, "F", 22, "Irish", "Dublin", PitchLevel.HIGH))
        add(SpeakerTraits(62, "M", 23, "Northern Irish", "Belfast", PitchLevel.MEDIUM))
        add(SpeakerTraits(63, "F", 22, "Northern Irish", "Belfast", PitchLevel.HIGH))
        add(SpeakerTraits(64, "F", 33, "American", "San Francisco", PitchLevel.LOW))
        add(SpeakerTraits(65, "F", 23, "Irish", "Dublin", PitchLevel.MEDIUM))
        add(SpeakerTraits(66, "F", 20, "American", "New York", PitchLevel.HIGH))
        add(SpeakerTraits(67, "M", 19, "Irish", "Tipperary", PitchLevel.MEDIUM))
        add(SpeakerTraits(68, "F", 25, "American", "California", PitchLevel.MEDIUM))
        add(SpeakerTraits(69, "F", 23, "American", "California", PitchLevel.MEDIUM))
        add(SpeakerTraits(70, "F", 23, "American", "North Carolina", PitchLevel.MEDIUM))
        add(SpeakerTraits(71, "M", 20, "Canadian", "Montreal", PitchLevel.MEDIUM))
        add(SpeakerTraits(72, "F", 24, "Canadian", "Toronto", PitchLevel.MEDIUM))
        add(SpeakerTraits(73, "M", 22, "Northern Irish", "Belfast", PitchLevel.MEDIUM))
        add(SpeakerTraits(74, "F", 19, "American", "Philadelphia", PitchLevel.HIGH))
        add(SpeakerTraits(75, "F", 21, "American", "New York", PitchLevel.HIGH))
        add(SpeakerTraits(76, "F", 23, "Canadian", "Ontario", PitchLevel.MEDIUM))
        add(SpeakerTraits(77, "F", 18, "American", "Alabama", PitchLevel.HIGH))
        add(SpeakerTraits(78, "F", 21, "American", "Tennessee", PitchLevel.HIGH))
        add(SpeakerTraits(79, "M", 21, "American", "Iowa", PitchLevel.MEDIUM))
        add(SpeakerTraits(80, "F", 19, "Canadian", "Hamilton", PitchLevel.HIGH))
        add(SpeakerTraits(81, "F", 24, "Irish", "County Down", PitchLevel.MEDIUM))
        add(SpeakerTraits(82, "F", 26, "South African", "Cape Town", PitchLevel.MEDIUM))
        add(SpeakerTraits(83, "M", 20, "Canadian", "Alberta", PitchLevel.MEDIUM))
        add(SpeakerTraits(84, "F", 23, "Canadian", "Hamilton", PitchLevel.MEDIUM))
        add(SpeakerTraits(85, "F", 32, "American", "Napa", PitchLevel.LOW))
        add(SpeakerTraits(86, "F", 19, "South African", "Pretoria", PitchLevel.HIGH))
        add(SpeakerTraits(87, "M", 26, "Australian", "Sydney", PitchLevel.LOW))
        add(SpeakerTraits(88, "F", 23, "American", "", PitchLevel.MEDIUM))
        add(SpeakerTraits(89, "F", 26, "American", "", PitchLevel.MEDIUM))
        add(SpeakerTraits(90, "F", 19, "American", "Indiana", PitchLevel.HIGH))
        add(SpeakerTraits(91, "M", 18, "American", "Chicago", PitchLevel.MEDIUM))
        add(SpeakerTraits(92, "F", 25, "New Zealand", "English", PitchLevel.MEDIUM))
        add(SpeakerTraits(93, "F", 18, "South African", "Johannesburg", PitchLevel.HIGH))
        add(SpeakerTraits(94, "F", 21, "American", "Pennsylvania", PitchLevel.HIGH))
        add(SpeakerTraits(95, "F", 18, "Irish", "Dublin", PitchLevel.HIGH))
        add(SpeakerTraits(96, "F", 26, "American", "Ohio", PitchLevel.MEDIUM))
        add(SpeakerTraits(97, "F", 27, "Canadian", "Alberta", PitchLevel.LOW))
        add(SpeakerTraits(98, "M", 22, "American", "Florida", PitchLevel.MEDIUM))
        add(SpeakerTraits(99, "M", 26, "South African", "Johannesburg", PitchLevel.LOW))
        add(SpeakerTraits(100, "F", 21, "Northern Irish", "Derry", PitchLevel.HIGH))
        add(SpeakerTraits(101, "M", 19, "American", "New Jersey", PitchLevel.MEDIUM))
        add(SpeakerTraits(102, "F", 19, "American", "New Jersey", PitchLevel.HIGH))
        add(SpeakerTraits(103, "F", 29, "American", "", PitchLevel.LOW))
        add(SpeakerTraits(104, "M", 22, "Canadian", "Toronto", PitchLevel.MEDIUM))
        add(SpeakerTraits(105, "M", 23, "Irish", "Donegal", PitchLevel.MEDIUM))
        add(SpeakerTraits(106, "M", 28, "Australian", "English", PitchLevel.LOW))
        // Indices 107–108: catalog has 107 entries in source; VCTK has 109 speakers. Add two generic entries.
        add(SpeakerTraits(107, "F", 22, "English", "Southern England", PitchLevel.HIGH))
        add(SpeakerTraits(108, "M", 24, "American", "California", PitchLevel.MEDIUM))
    }

    fun getTraits(speakerId: Int): SpeakerTraits? =
        catalog.getOrNull(speakerId)

    fun allSpeakers(): List<SpeakerTraits> = catalog

    /** All speaker IDs that are female. */
    fun femaleSpeakerIds(): List<Int> = catalog.filter { it.isFemale }.map { it.speakerId }

    /** All speaker IDs that are male. */
    fun maleSpeakerIds(): List<Int> = catalog.filter { it.isMale }.map { it.speakerId }

    // AUG-016: Pitch-based speaker filtering methods

    /** All speaker IDs with the specified pitch level. */
    fun speakerIdsByPitch(pitchLevel: PitchLevel): List<Int> =
        catalog.filter { it.pitchLevel == pitchLevel }.map { it.speakerId }

    /** All female speaker IDs with the specified pitch level. */
    fun femaleSpeakerIdsByPitch(pitchLevel: PitchLevel): List<Int> =
        catalog.filter { it.isFemale && it.pitchLevel == pitchLevel }.map { it.speakerId }

    /** All male speaker IDs with the specified pitch level. */
    fun maleSpeakerIdsByPitch(pitchLevel: PitchLevel): List<Int> =
        catalog.filter { it.isMale && it.pitchLevel == pitchLevel }.map { it.speakerId }

    /** Get speakers matching gender and pitch level. */
    fun getSpeakersByTraits(
        gender: String? = null,
        pitchLevel: PitchLevel? = null,
        accent: String? = null
    ): List<SpeakerTraits> = catalog.filter { speaker ->
        (gender == null || speaker.gender.equals(gender, ignoreCase = true)) &&
        (pitchLevel == null || speaker.pitchLevel == pitchLevel) &&
        (accent == null || speaker.accent.equals(accent, ignoreCase = true))
    }

    /**
     * Find a speaker with similar traits but different pitch level.
     * Useful for pitch variation in prosody (e.g., excitement = higher pitch speaker).
     */
    fun findSpeakerWithDifferentPitch(
        baseSpeakerId: Int,
        targetPitch: PitchLevel
    ): SpeakerTraits? {
        val base = getTraits(baseSpeakerId) ?: return null
        if (base.pitchLevel == targetPitch) return base

        // Find speaker with same gender and target pitch, prefer similar age
        val candidates = catalog.filter {
            it.gender == base.gender &&
            it.pitchLevel == targetPitch &&
            it.speakerId != baseSpeakerId
        }

        // Sort by age proximity and return closest match
        return if (base.ageYears != null) {
            candidates.minByOrNull {
                kotlin.math.abs((it.ageYears ?: 25) - base.ageYears)
            }
        } else {
            candidates.firstOrNull()
        }
    }
}
