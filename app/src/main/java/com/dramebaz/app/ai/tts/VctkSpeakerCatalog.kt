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

    /**
     * Mapping from Piper model speaker ID to VCTK corpus speaker ID (pXXX).
     * This is derived from the Piper en_GB-vctk-medium.onnx.json speaker_id_map.
     * The Piper model does NOT use sequential ordering - speaker ID 0 is p239, not p225!
     */
    private val piperSpeakerIdMap: Map<Int, String> = mapOf(
        0 to "p239", 1 to "p236", 2 to "p264", 3 to "p250", 4 to "p259",
        5 to "p247", 6 to "p261", 7 to "p263", 8 to "p283", 9 to "p286",
        10 to "p274", 11 to "p276", 12 to "p270", 13 to "p281", 14 to "p277",
        15 to "p231", 16 to "p271", 17 to "p238", 18 to "p257", 19 to "p273",
        20 to "p284", 21 to "p329", 22 to "p361", 23 to "p287", 24 to "p360",
        25 to "p374", 26 to "p376", 27 to "p310", 28 to "p304", 29 to "p334",
        30 to "p340", 31 to "p323", 32 to "p347", 33 to "p330", 34 to "p308",
        35 to "p314", 36 to "p317", 37 to "p339", 38 to "p311", 39 to "p294",
        40 to "p305", 41 to "p266", 42 to "p335", 43 to "p318", 44 to "p351",
        45 to "p333", 46 to "p313", 47 to "p316", 48 to "p244", 49 to "p307",
        50 to "p363", 51 to "p336", 52 to "p297", 53 to "p312", 54 to "p267",
        55 to "p275", 56 to "p295", 57 to "p258", 58 to "p288", 59 to "p301",
        60 to "p232", 61 to "p292", 62 to "p272", 63 to "p280", 64 to "p278",
        65 to "p341", 66 to "p268", 67 to "p298", 68 to "p299", 69 to "p279",
        70 to "p285", 71 to "p326", 72 to "p300", 73 to "s5", 74 to "p230",
        75 to "p345", 76 to "p254", 77 to "p269", 78 to "p293", 79 to "p252",
        80 to "p262", 81 to "p243", 82 to "p227", 83 to "p343", 84 to "p255",
        85 to "p229", 86 to "p240", 87 to "p248", 88 to "p253", 89 to "p233",
        90 to "p228", 91 to "p282", 92 to "p251", 93 to "p246", 94 to "p234",
        95 to "p226", 96 to "p260", 97 to "p245", 98 to "p241", 99 to "p303",
        100 to "p265", 101 to "p306", 102 to "p237", 103 to "p249", 104 to "p256",
        105 to "p302", 106 to "p364", 107 to "p225", 108 to "p362"
    )

    /**
     * VCTK corpus speaker metadata: maps pXXX speaker ID to (Gender, Age, Accent, Region).
     * Data from CSTR VCTK Corpus and NeuML vctk-vits-onnx README.
     */
    private val vctkSpeakerData: Map<String, SpeakerData> = mapOf(
        "p225" to SpeakerData("F", 23, "English", "Southern England"),
        "p226" to SpeakerData("M", 22, "English", "Surrey"),
        "p227" to SpeakerData("M", 38, "English", "Cumbria"),
        "p228" to SpeakerData("F", 22, "English", "Southern England"),
        "p229" to SpeakerData("F", 23, "English", "Southern England"),
        "p230" to SpeakerData("F", 22, "English", "Stockton-on-tees"),
        "p231" to SpeakerData("F", 23, "English", "Southern England"),
        "p232" to SpeakerData("M", 23, "English", "Southern England"),
        "p233" to SpeakerData("F", 23, "English", "Staffordshire"),
        "p234" to SpeakerData("F", 22, "Scottish", "West Dumfries"),
        "p236" to SpeakerData("F", 23, "English", "Manchester"),
        "p237" to SpeakerData("M", 22, "Scottish", "Fife"),
        "p238" to SpeakerData("F", 22, "Northern Irish", "Belfast"),
        "p239" to SpeakerData("F", 22, "English", "SW England"),
        "p240" to SpeakerData("F", 21, "English", "Southern England"),
        "p241" to SpeakerData("M", 21, "Scottish", "Perth"),
        "p243" to SpeakerData("M", 22, "English", "London"),
        "p244" to SpeakerData("F", 22, "English", "Manchester"),
        "p245" to SpeakerData("M", 25, "Irish", "Dublin"),
        "p246" to SpeakerData("M", 22, "Scottish", "Selkirk"),
        "p247" to SpeakerData("M", 22, "Scottish", "Argyll"),
        "p248" to SpeakerData("F", 23, "Indian", ""),
        "p249" to SpeakerData("F", 22, "Scottish", "Aberdeen"),
        "p250" to SpeakerData("F", 22, "English", "SE England"),
        "p251" to SpeakerData("M", 26, "Indian", ""),
        "p252" to SpeakerData("M", 22, "Scottish", "Edinburgh"),
        "p253" to SpeakerData("F", 22, "Welsh", "Cardiff"),
        "p254" to SpeakerData("M", 21, "English", "Surrey"),
        "p255" to SpeakerData("M", 19, "Scottish", "Galloway"),
        "p256" to SpeakerData("M", 24, "English", "Birmingham"),
        "p257" to SpeakerData("F", 24, "English", "Southern England"),
        "p258" to SpeakerData("M", 22, "English", "Southern England"),
        "p259" to SpeakerData("M", 23, "English", "Nottingham"),
        "p260" to SpeakerData("M", 21, "Scottish", "Orkney"),
        "p261" to SpeakerData("F", 26, "Northern Irish", "Belfast"),
        "p262" to SpeakerData("F", 23, "Scottish", "Edinburgh"),
        "p263" to SpeakerData("M", 22, "Scottish", "Aberdeen"),
        "p264" to SpeakerData("F", 23, "Scottish", "West Lothian"),
        "p265" to SpeakerData("F", 23, "Scottish", "Ross"),
        "p266" to SpeakerData("F", 22, "Irish", "Athlone"),
        "p267" to SpeakerData("F", 23, "English", "Yorkshire"),
        "p268" to SpeakerData("F", 23, "English", "Southern England"),
        "p269" to SpeakerData("F", 20, "English", "Newcastle"),
        "p270" to SpeakerData("M", 21, "English", "Yorkshire"),
        "p271" to SpeakerData("M", 19, "Scottish", "Fife"),
        "p272" to SpeakerData("M", 23, "Scottish", "Edinburgh"),
        "p273" to SpeakerData("M", 23, "English", "Suffolk"),
        "p274" to SpeakerData("M", 22, "English", "Essex"),
        "p275" to SpeakerData("M", 23, "Scottish", "Midlothian"),
        "p276" to SpeakerData("F", 24, "English", "Oxford"),
        "p277" to SpeakerData("F", 23, "English", "NE England"),
        "p278" to SpeakerData("M", 22, "English", "Cheshire"),
        "p279" to SpeakerData("M", 23, "English", "Leicester"),
        "p280" to SpeakerData("M", null, "Unknown", ""),  // Unknown speaker
        "p281" to SpeakerData("M", 29, "Scottish", "Edinburgh"),
        "p282" to SpeakerData("F", 23, "English", "Newcastle"),
        "p283" to SpeakerData("F", 24, "Irish", "Cork"),
        "p284" to SpeakerData("M", 20, "Scottish", "Fife"),
        "p285" to SpeakerData("M", 21, "Scottish", "Edinburgh"),
        "p286" to SpeakerData("M", 23, "English", "Newcastle"),
        "p287" to SpeakerData("M", 23, "English", "York"),
        "p288" to SpeakerData("F", 22, "Irish", "Dublin"),
        "p292" to SpeakerData("M", 23, "Northern Irish", "Belfast"),
        "p293" to SpeakerData("F", 22, "Northern Irish", "Belfast"),
        "p294" to SpeakerData("F", 33, "American", "San Francisco"),
        "p295" to SpeakerData("F", 23, "Irish", "Dublin"),
        "p297" to SpeakerData("F", 20, "American", "New York"),
        "p298" to SpeakerData("M", 19, "Irish", "Tipperary"),
        "p299" to SpeakerData("F", 25, "American", "California"),
        "p300" to SpeakerData("F", 23, "American", "California"),
        "p301" to SpeakerData("F", 23, "American", "North Carolina"),
        "p302" to SpeakerData("M", 20, "Canadian", "Montreal"),
        "p303" to SpeakerData("F", 24, "Canadian", "Toronto"),
        "p304" to SpeakerData("M", 22, "Northern Irish", "Belfast"),
        "p305" to SpeakerData("F", 19, "American", "Philadelphia"),
        "p306" to SpeakerData("F", 21, "American", "New York"),
        "p307" to SpeakerData("F", 23, "Canadian", "Ontario"),
        "p308" to SpeakerData("F", 18, "American", "Alabama"),
        "p310" to SpeakerData("F", 21, "American", "Tennessee"),
        "p311" to SpeakerData("M", 21, "American", "Iowa"),
        "p312" to SpeakerData("F", 19, "Canadian", "Hamilton"),
        "p313" to SpeakerData("F", 24, "Irish", "County Down"),
        "p314" to SpeakerData("F", 26, "South African", "Cape Town"),
        "p316" to SpeakerData("M", 20, "Canadian", "Alberta"),
        "p317" to SpeakerData("F", 23, "Canadian", "Hamilton"),
        "p318" to SpeakerData("F", 32, "American", "Napa"),
        "p323" to SpeakerData("F", 19, "South African", "Pretoria"),
        "p326" to SpeakerData("M", 26, "Australian", "Sydney"),
        "p329" to SpeakerData("F", 23, "American", ""),
        "p330" to SpeakerData("F", 26, "American", ""),
        "p333" to SpeakerData("F", 19, "American", "Indiana"),
        "p334" to SpeakerData("M", 18, "American", "Chicago"),
        "p335" to SpeakerData("F", 25, "New Zealand", ""),
        "p336" to SpeakerData("F", 18, "South African", "Johannesburg"),
        "p339" to SpeakerData("F", 21, "American", "Pennsylvania"),
        "p340" to SpeakerData("F", 18, "Irish", "Dublin"),
        "p341" to SpeakerData("F", 26, "American", "Ohio"),
        "p343" to SpeakerData("F", 27, "Canadian", "Alberta"),
        "p345" to SpeakerData("M", 22, "American", "Florida"),
        "p347" to SpeakerData("M", 26, "South African", "Johannesburg"),
        "p351" to SpeakerData("F", 21, "Northern Irish", "Derry"),
        "p360" to SpeakerData("M", 19, "American", "New Jersey"),
        "p361" to SpeakerData("F", 19, "American", "New Jersey"),
        "p362" to SpeakerData("F", 29, "American", ""),
        "p363" to SpeakerData("M", 22, "Canadian", "Toronto"),
        "p364" to SpeakerData("M", 23, "Irish", "Donegal"),
        "p374" to SpeakerData("M", 28, "Australian", ""),
        "p376" to SpeakerData("F", 22, "Welsh", "Cardiff"),  // Note: duplicate entry, using p376 data
        "s5" to SpeakerData("M", null, "Unknown", "")  // Unknown speaker s5
    )

    /** Helper data class for VCTK speaker data */
    private data class SpeakerData(
        val gender: String,
        val age: Int?,
        val accent: String,
        val region: String
    )

    private val catalog: List<SpeakerTraits> = buildCatalog()

    /**
     * Builds the catalog using the correct Piper model speaker_id_map.
     * Model speaker ID → VCTK speaker ID (pXXX) → VCTK corpus metadata.
     */
    private fun buildCatalog(): List<SpeakerTraits> {
        return (MIN_SPEAKER_ID..MAX_SPEAKER_ID).map { speakerId ->
            val vctkId = piperSpeakerIdMap[speakerId] ?: "p225"
            val data = vctkSpeakerData[vctkId] ?: SpeakerData("M", null, "Unknown", "")

            // AUG-016: Pitch levels assigned based on age/gender
            val pitchLevel = when {
                data.gender == "F" && (data.age ?: 25) < 22 -> PitchLevel.HIGH
                data.gender == "F" && (data.age ?: 25) > 28 -> PitchLevel.LOW
                data.gender == "F" -> PitchLevel.MEDIUM
                data.gender == "M" && (data.age ?: 25) > 30 -> PitchLevel.LOW
                else -> PitchLevel.MEDIUM
            }

            SpeakerTraits(
                speakerId = speakerId,
                gender = data.gender,
                ageYears = data.age,
                accent = data.accent,
                region = data.region,
                pitchLevel = pitchLevel
            )
        }
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
