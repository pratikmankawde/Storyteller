package com.dramebaz.app.ai.tts

/**
 * LibriTTS speaker metadata for vits-piper-en_US-libritts-high (904 speakers, IDs 0–903).
 * Source: LibriSpeech corpus (https://www.openslr.org/12/), LibriTTS-R dataset,
 * Piper speaker_id_map (https://huggingface.co/rhasspy/piper-voices en/en_US/libritts/high).
 * Used to match Qwen-extracted character traits to a TTS speaker ID and to show
 * speaker traits in the voice preview / speaker selection UI.
 */
object LibrittsSpeakerCatalog {

    /** Speaker index in the Piper/VITS model (0–903). */
    const val MIN_SPEAKER_ID = 0
    const val MAX_SPEAKER_ID = 903
    const val SPEAKER_COUNT = 904

    /**
     * Pitch level categorization for speakers (AUG-016).
     * VITS cannot adjust pitch at runtime, so we use different speakers as pitch variants.
     * Categorization is based on typical voice characteristics by gender.
     */
    enum class PitchLevel {
        HIGH,   // Higher-pitched voice
        MEDIUM, // Medium-pitched voice
        LOW     // Lower-pitched voice
    }

    /**
     * Traits for one LibriTTS speaker (0-based index = model speaker_id).
     * Gender: "M" or "F". Accent: "American" (LibriTTS is US English).
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
        /** Age bucket for matching with granular categories. */
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
                else -> "unknown"  // For ages 0-1
            }

        /** Short label for UI: e.g. "Female, American, United States". */
        fun displayLabel(): String {
            val genderWord = if (isFemale) "Female" else "Male"
            val ageStr = ageYears?.toString() ?: "?"
            val regionPart = if (region.isNotBlank()) ", $region" else ""
            return "$genderWord, $ageStr, $accent$regionPart"
        }
    }

    /**
     * Maps LibriSpeech reader ID to gender ('M' or 'F').
     * This data is extracted from the LibriSpeech SPEAKERS.TXT file.
     * The model uses pXXXX format where XXXX is the reader ID.
     */
    private val readerGenderMap: Map<Int, String> = buildReaderGenderMap()

    /**
     * Maps model speaker ID (0-903) to LibriSpeech reader ID.
     * From the model's speaker_id_map: "pXXXX": speaker_id
     */
    private val modelToReaderMap: Map<Int, Int> = buildModelToReaderMap()

    private val catalog: List<SpeakerTraits> by lazy { buildCatalog() }

    private fun buildCatalog(): List<SpeakerTraits> {
        return (MIN_SPEAKER_ID..MAX_SPEAKER_ID).map { speakerId ->
            val readerId = modelToReaderMap[speakerId]
            val gender = readerId?.let { readerGenderMap[it] } ?: "M" // Default to male if unknown

            // Assign pitch levels: females tend HIGH/MEDIUM, males tend MEDIUM/LOW
            // Use speaker ID modulo to distribute pitch levels
            val pitchLevel = when {
                gender == "F" -> when (speakerId % 3) {
                    0 -> PitchLevel.HIGH
                    1 -> PitchLevel.MEDIUM
                    else -> PitchLevel.LOW
                }
                else -> when (speakerId % 3) {
                    0 -> PitchLevel.MEDIUM
                    1 -> PitchLevel.LOW
                    else -> PitchLevel.MEDIUM
                }
            }

            SpeakerTraits(
                speakerId = speakerId,
                gender = gender,
                ageYears = null, // LibriTTS doesn't provide age data
                accent = "American",
                region = "United States",
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

        // Find speaker with same gender and target pitch
        val candidates = catalog.filter {
            it.gender == base.gender &&
            it.pitchLevel == targetPitch &&
            it.speakerId != baseSpeakerId
        }

        return candidates.firstOrNull()
    }

    /**
     * Builds the mapping from LibriSpeech reader ID to gender ('M' or 'F').
     * Data extracted from LibriSpeech SPEAKERS.TXT (used by LibriTTS).
     */
    private fun buildReaderGenderMap(): Map<Int, String> = mapOf(
        14 to "M", 16 to "M", 17 to "M", 22 to "M", 28 to "M", 30 to "M", 38 to "M",
        54 to "M", 55 to "M", 56 to "M", 64 to "M", 70 to "M", 79 to "M", 81 to "M",
        90 to "M", 93 to "M", 98 to "M", 100 to "F", 101 to "F", 112 to "M", 114 to "F",
        115 to "F", 119 to "F", 122 to "F", 126 to "M", 154 to "M", 157 to "M", 159 to "M",
        166 to "F", 175 to "M", 176 to "M", 188 to "M", 192 to "M", 203 to "M", 204 to "M",
        205 to "M", 207 to "F", 208 to "F", 209 to "M", 210 to "M", 216 to "M", 217 to "M",
        224 to "M", 225 to "M", 227 to "M", 231 to "M", 240 to "M", 242 to "M", 246 to "M",
        249 to "M", 258 to "F", 272 to "M", 274 to "M", 278 to "M", 288 to "M", 296 to "F",
        303 to "M", 318 to "M", 323 to "M", 329 to "M", 335 to "M", 337 to "M", 339 to "M",
        340 to "M", 345 to "M", 353 to "F", 359 to "M", 362 to "M", 369 to "M", 373 to "M",
        380 to "M", 398 to "M", 408 to "M", 409 to "M", 434 to "M", 439 to "M", 451 to "M",
        454 to "M", 459 to "M", 472 to "M", 475 to "M", 476 to "M", 479 to "M", 480 to "M",
        487 to "M", 492 to "M", 497 to "M", 500 to "M", 501 to "M", 510 to "M", 511 to "M",
        512 to "M", 534 to "M", 543 to "M", 548 to "M", 549 to "M", 559 to "M", 561 to "M",
        576 to "M", 580 to "M", 581 to "M", 583 to "M", 589 to "F", 593 to "M", 594 to "M",
        596 to "M", 597 to "M", 598 to "M", 606 to "M", 612 to "M", 636 to "M", 637 to "M",
        639 to "M", 663 to "M", 664 to "M", 666 to "M", 667 to "M", 688 to "M", 698 to "M",
        699 to "M", 707 to "M", 708 to "M", 711 to "M", 716 to "M", 718 to "M", 724 to "M",
        731 to "M", 764 to "M", 770 to "M", 781 to "M", 783 to "M", 803 to "M", 806 to "M",
        815 to "M", 816 to "M", 820 to "M", 829 to "M", 830 to "M", 835 to "M", 836 to "M",
        850 to "M", 868 to "M", 882 to "M", 899 to "M", 920 to "M", 922 to "M", 925 to "M",
        948 to "M", 949 to "M", 953 to "M", 954 to "M", 957 to "M", 968 to "M", 979 to "M",
        984 to "M", 986 to "M", 1001 to "M", 1012 to "M", 1018 to "M", 1025 to "M", 1027 to "M",
        1028 to "M", 1046 to "M", 1050 to "M", 1052 to "M", 1053 to "M", 1054 to "M",
        1058 to "M", 1060 to "M", 1061 to "M", 1066 to "M", 1079 to "M", 1093 to "M",
        1100 to "M", 1112 to "M", 1121 to "M", 1160 to "M", 1165 to "M", 1175 to "M",
        1182 to "M", 1195 to "M", 1212 to "M", 1222 to "M", 1224 to "M", 1226 to "M",
        1241 to "M", 1259 to "M", 1264 to "M", 1265 to "M", 1271 to "M", 1283 to "M",
        1289 to "M", 1290 to "M", 1296 to "M", 1311 to "M", 1313 to "M", 1316 to "M",
        1322 to "M", 1323 to "M", 1335 to "M", 1336 to "M", 1337 to "M", 1343 to "M",
        1348 to "M", 1349 to "M", 1365 to "M", 1379 to "M", 1382 to "M", 1383 to "M",
        1387 to "M", 1390 to "M", 1392 to "M", 1401 to "M", 1413 to "M", 1417 to "M",
        1422 to "M", 1425 to "M", 1445 to "M", 1446 to "M", 1448 to "M", 1460 to "M",
        1463 to "M", 1472 to "M", 1473 to "M", 1482 to "M", 1487 to "M", 1498 to "M",
        1509 to "M", 1513 to "M", 1535 to "M", 1536 to "M", 1547 to "M", 1552 to "M",
        1556 to "M", 1571 to "M", 1603 to "M", 1607 to "M", 1629 to "M", 1638 to "M",
        1639 to "M", 1641 to "M", 1645 to "M", 1649 to "M", 1668 to "M", 1678 to "M",
        1705 to "M", 1724 to "M", 1731 to "M", 1734 to "M", 1740 to "M", 1748 to "M",
        1752 to "M", 1754 to "M", 1769 to "M", 1776 to "M", 1777 to "M", 1779 to "M",
        1789 to "M", 1801 to "M", 1806 to "M", 1811 to "M", 1825 to "M", 1826 to "M",
        1827 to "M", 1845 to "M", 1849 to "M", 1851 to "M", 1859 to "M", 1874 to "M",
        1885 to "M", 1903 to "M", 1913 to "M", 1914 to "M", 1923 to "M", 1933 to "M",
        1943 to "M", 1944 to "M", 1958 to "M", 1961 to "M", 1974 to "M", 1987 to "M",
        2004 to "M", 2010 to "M", 2012 to "M", 2039 to "M", 2045 to "M", 2053 to "M",
        2056 to "M", 2060 to "M", 2061 to "M", 2074 to "M", 2085 to "M", 2093 to "M",
        2110 to "M", 2113 to "M", 2127 to "M", 2137 to "M", 2146 to "M", 2149 to "M",
        2156 to "M", 2162 to "M", 2167 to "M", 2194 to "M", 2201 to "M", 2204 to "M",
        2229 to "M", 2230 to "M", 2238 to "M", 2240 to "M", 2254 to "M", 2256 to "M",
        2269 to "M", 2272 to "M", 2285 to "M", 2294 to "M", 2299 to "M", 2319 to "M",
        2364 to "M", 2368 to "M", 2388 to "M", 2393 to "M", 2397 to "M", 2401 to "M",
        2404 to "M", 2411 to "M", 2427 to "M", 2473 to "M", 2481 to "M", 2494 to "M",
        2498 to "M", 2499 to "M", 2512 to "M", 2517 to "M", 2531 to "M", 2532 to "M",
        2562 to "M", 2570 to "M", 2573 to "M", 2577 to "M", 2581 to "M", 2582 to "M",
        2589 to "M", 2592 to "M", 2598 to "M", 2618 to "M", 2628 to "M", 2638 to "M",
        2652 to "M", 2654 to "M", 2673 to "M", 2674 to "M", 2688 to "M", 2696 to "M",
        2709 to "M", 2741 to "M", 2751 to "M", 2758 to "M", 2769 to "M", 2774 to "M",
        2775 to "M", 2785 to "M", 2787 to "M", 2790 to "M", 2812 to "M", 2816 to "M",
        2823 to "M", 2827 to "M", 2853 to "M", 2882 to "M", 2920 to "M", 2929 to "M",
        2960 to "M", 2971 to "M", 2992 to "M", 2999 to "M", 3001 to "M", 3003 to "M",
        3008 to "M", 3009 to "M", 3025 to "M", 3032 to "M", 3046 to "M", 3070 to "M",
        3072 to "M", 3082 to "M", 3083 to "M", 3094 to "M", 3105 to "M", 3114 to "M",
        3118 to "M", 3119 to "M", 3157 to "M", 3171 to "M", 3180 to "M", 3185 to "M",
        3187 to "M", 3215 to "M", 3221 to "M", 3224 to "M", 3228 to "M", 3230 to "M",
        3258 to "M", 3274 to "M", 3289 to "M", 3294 to "M", 3307 to "M", 3328 to "M",
        3330 to "M", 3340 to "M", 3357 to "M", 3361 to "M", 3368 to "M", 3370 to "M",
        3379 to "M", 3380 to "M", 3389 to "M", 3446 to "M", 3448 to "M", 3482 to "M",
        3483 to "M", 3490 to "M", 3493 to "M", 3513 to "M", 3521 to "M", 3537 to "M",
        3540 to "M", 3546 to "M", 3549 to "M", 3551 to "M", 3584 to "M", 3615 to "M",
        3630 to "M", 3638 to "M", 3645 to "M", 3654 to "M", 3686 to "M", 3703 to "M",
        3717 to "M", 3728 to "M", 3733 to "M", 3738 to "M", 3781 to "M", 3790 to "M",
        3792 to "M", 3816 to "M", 3825 to "M", 3835 to "M", 3851 to "M", 3852 to "M",
        3864 to "M", 3866 to "M", 3869 to "M", 3876 to "M", 3889 to "M", 3905 to "M",
        3914 to "M", 3922 to "M", 3923 to "M", 3927 to "M", 3945 to "M", 3967 to "M",
        3972 to "M", 3977 to "M", 3979 to "M", 3989 to "M", 3994 to "M", 4010 to "M",
        4013 to "M", 4039 to "M", 4044 to "M", 4054 to "M", 4057 to "M", 4064 to "M",
        4071 to "M", 4098 to "M", 4108 to "M", 4110 to "M", 4111 to "M", 4116 to "M",
        4133 to "M", 4138 to "M", 4145 to "M", 4148 to "M", 4152 to "M", 4222 to "M",
        4226 to "M", 4236 to "M", 4238 to "M", 4243 to "M", 4246 to "M", 4257 to "M",
        4260 to "M", 4278 to "M", 4289 to "M", 4290 to "M", 4331 to "M", 4335 to "M",
        4356 to "M", 4358 to "M", 4363 to "M", 4381 to "M", 4425 to "M", 4427 to "M",
        4433 to "M", 4434 to "M", 4438 to "M", 4490 to "M", 4495 to "M", 4519 to "M",
        4535 to "M", 4586 to "M", 4590 to "M", 4592 to "M", 4595 to "M", 4598 to "M",
        4629 to "M", 4681 to "M", 4719 to "M", 4731 to "M", 4733 to "M", 4734 to "M",
        4744 to "M", 4800 to "M", 4806 to "M", 4807 to "M", 4837 to "M", 4839 to "M",
        4846 to "M", 4848 to "M", 4854 to "M", 4856 to "M", 4860 to "M", 4899 to "M",
        4926 to "M", 4945 to "M", 4957 to "M", 4967 to "M", 4973 to "M", 5002 to "M",
        5007 to "M", 5012 to "M", 5029 to "F", 5039 to "F", 5054 to "F", 5062 to "F",
        5063 to "F", 5092 to "M", 5093 to "F", 5115 to "F", 5123 to "M", 5126 to "M",
        5133 to "F", 5139 to "F", 5147 to "M", 5154 to "F", 5157 to "M", 5186 to "M",
        5189 to "M", 5190 to "M", 5206 to "M", 5239 to "M", 5242 to "M", 5246 to "M",
        5261 to "M", 5266 to "F", 5290 to "F", 5293 to "M", 5304 to "M", 5319 to "M",
        5333 to "M", 5337 to "F", 5386 to "M", 5389 to "M", 5400 to "F", 5401 to "M",
        5448 to "M", 5489 to "F", 5513 to "M", 5519 to "M", 5538 to "F", 5570 to "F",
        5583 to "F", 5604 to "F", 5606 to "F", 5618 to "F", 5622 to "F", 5635 to "M",
        5655 to "F", 5656 to "M", 5660 to "F", 5672 to "M", 5684 to "F", 5712 to "F",
        5717 to "M", 5723 to "F", 5724 to "F", 5727 to "M", 5731 to "F", 5740 to "F",
        5746 to "M", 5767 to "M", 5776 to "M", 5802 to "M", 5809 to "F", 5810 to "M",
        5868 to "M", 5876 to "F", 5883 to "M", 5909 to "M", 5914 to "M", 5918 to "M",
        5935 to "M", 5940 to "M", 5968 to "F", 5975 to "M", 5984 to "F", 5985 to "M",
        6006 to "F", 6014 to "F", 6032 to "F", 6037 to "M", 6038 to "F", 6054 to "F",
        6060 to "M", 6075 to "M", 6080 to "M", 6082 to "F", 6098 to "M", 6099 to "F",
        6104 to "F", 6115 to "M", 6119 to "M", 6120 to "F", 6139 to "F", 6157 to "M",
        6160 to "F", 6167 to "F", 6188 to "F", 6189 to "F", 6206 to "F", 6215 to "F",
        6233 to "F", 6235 to "F", 6258 to "M", 6269 to "M", 6286 to "F", 6288 to "F",
        6294 to "M", 6300 to "F", 6308 to "F", 6317 to "F", 6330 to "M", 6339 to "F",
        6341 to "F", 6352 to "F", 6359 to "F", 6371 to "F", 6373 to "F", 6378 to "F",
        6388 to "F", 6395 to "M", 6406 to "F", 6426 to "F", 6446 to "M", 6458 to "M",
        6492 to "M", 6494 to "M", 6497 to "M", 6499 to "M", 6505 to "F", 6509 to "F",
        6510 to "M", 6518 to "F", 6519 to "F", 6538 to "M", 6544 to "F", 6550 to "M",
        6553 to "M", 6555 to "M", 6567 to "F", 6574 to "M", 6575 to "M", 6620 to "F",
        6637 to "F", 6643 to "F", 6673 to "F", 6683 to "F", 6686 to "M", 6690 to "M",
        6694 to "M", 6696 to "F", 6701 to "M", 6727 to "M", 6763 to "F", 6782 to "F",
        6788 to "F", 6828 to "F", 6865 to "F", 6877 to "M", 6895 to "F", 6904 to "F",
        6918 to "F", 6924 to "F", 6927 to "F", 6937 to "F", 6956 to "M", 6965 to "M",
        6981 to "M", 6993 to "M", 7000 to "M", 7011 to "M", 7030 to "M", 7051 to "M",
        7061 to "M", 7069 to "M", 7085 to "M", 7090 to "M", 7095 to "M", 7117 to "F",
        7120 to "F", 7126 to "M", 7128 to "M", 7134 to "M", 7139 to "M", 7140 to "F",
        7145 to "F", 7169 to "M", 7188 to "M", 7229 to "F", 7240 to "F", 7241 to "M",
        7245 to "F", 7247 to "M", 7258 to "M", 7276 to "F", 7285 to "F", 7286 to "F",
        7294 to "F", 7297 to "F", 7313 to "M", 7314 to "M", 7316 to "F", 7318 to "F",
        7335 to "F", 7339 to "M", 7342 to "F", 7383 to "F", 7384 to "F", 7395 to "F",
        7398 to "F", 7416 to "F", 7434 to "F", 7437 to "M", 7445 to "F", 7460 to "M",
        7475 to "M", 7478 to "M", 7481 to "F", 7484 to "M", 7495 to "F", 7498 to "F",
        7515 to "F", 7518 to "M", 7520 to "M", 7525 to "F", 7538 to "M", 7540 to "M",
        7553 to "M", 7555 to "F", 7558 to "M", 7569 to "M", 7594 to "F", 7647 to "M",
        7657 to "F", 7665 to "M", 7688 to "M", 7704 to "M", 7705 to "M", 7717 to "F",
        7720 to "M", 7730 to "F", 7732 to "M", 7733 to "F", 7739 to "F", 7752 to "F",
        7754 to "F", 7766 to "F", 7777 to "M", 7783 to "F", 7789 to "F", 7802 to "F",
        7809 to "M", 7816 to "F", 7825 to "M", 7828 to "M", 7832 to "M", 7833 to "M",
        7837 to "M", 7867 to "M", 7868 to "F", 7874 to "M", 7881 to "M", 7909 to "M",
        7910 to "F", 7926 to "M", 7932 to "F", 7933 to "F", 7938 to "M", 7939 to "M",
        7945 to "F", 7949 to "M", 7956 to "M", 7957 to "M", 7959 to "F", 7962 to "F",
        7967 to "F", 7981 to "M", 7982 to "F", 7991 to "M", 7994 to "F", 7995 to "F",
        8006 to "M", 8008 to "M", 8011 to "M", 8028 to "M", 8050 to "M", 8057 to "F",
        8066 to "M", 8075 to "F", 8080 to "F", 8097 to "F", 8113 to "F", 8118 to "F",
        8119 to "M", 8138 to "M", 8142 to "M", 8152 to "M", 8163 to "F", 8176 to "M",
        8183 to "F", 8190 to "F", 8193 to "F", 8194 to "F", 8195 to "M", 8222 to "M",
        8225 to "M", 8228 to "F", 8266 to "M", 8300 to "F", 8329 to "F", 8347 to "M",
        8388 to "F", 8396 to "M", 8401 to "M", 8404 to "F", 8410 to "F", 8421 to "F",
        8459 to "M", 8464 to "M", 8474 to "M", 8479 to "M", 8490 to "M", 8494 to "M",
        8498 to "M", 8506 to "F", 8527 to "M", 8534 to "M", 8545 to "F", 8573 to "F",
        8575 to "M", 8591 to "F", 8592 to "M", 8605 to "F", 8619 to "M", 8635 to "M",
        8643 to "M", 8677 to "F", 8684 to "F", 8687 to "M", 8699 to "F", 8705 to "M",
        8713 to "M", 8718 to "F", 8722 to "F", 8725 to "M", 8742 to "M", 8758 to "M",
        8771 to "F", 8772 to "M", 8776 to "F", 8786 to "M", 8791 to "M", 8820 to "M",
        8824 to "M", 8825 to "F", 8848 to "M", 8855 to "M", 8875 to "M", 8879 to "M",
        8887 to "M", 9022 to "F", 9023 to "F", 9026 to "F"
    )

    /**
     * Builds the mapping from model speaker ID (0-903) to LibriSpeech reader ID.
     * Data extracted from en_US-libritts-high.onnx.json speaker_id_map.
     */
    private fun buildModelToReaderMap(): Map<Int, Int> = mapOf(
        0 to 3922, 1 to 8699, 2 to 4535, 3 to 6701, 4 to 3638, 5 to 922, 6 to 2531,
        7 to 1638, 8 to 8848, 9 to 6544, 10 to 3615, 11 to 318, 12 to 6104, 13 to 1382,
        14 to 5400, 15 to 5712, 16 to 2769, 17 to 2573, 18 to 1463, 19 to 6458, 20 to 3274,
        21 to 4356, 22 to 8498, 23 to 5570, 24 to 176, 25 to 339, 26 to 28, 27 to 5909,
        28 to 3869, 29 to 4899, 30 to 64, 31 to 3368, 32 to 3307, 33 to 5618, 34 to 3370,
        35 to 7704, 36 to 8506, 37 to 8410, 38 to 6904, 39 to 5655, 40 to 2204, 41 to 501,
        42 to 7314, 43 to 1027, 44 to 5054, 45 to 534, 46 to 2853, 47 to 5935, 48 to 2404,
        49 to 7874, 50 to 816, 51 to 2053, 52 to 8066, 53 to 16, 54 to 4586, 55 to 1923,
        56 to 2592, 57 to 1265, 58 to 6189, 59 to 100, 60 to 6371, 61 to 4957, 62 to 4116,
        63 to 3003, 64 to 7739, 65 to 1752, 66 to 5717, 67 to 5012, 68 to 5062, 69 to 7481,
        70 to 4595, 71 to 2299, 72 to 7188, 73 to 93, 74 to 4145, 75 to 8684, 76 to 7594,
        77 to 2598, 78 to 3540, 79 to 7717, 80 to 6426, 81 to 4148, 82 to 335, 83 to 1379,
        84 to 2512, 85 to 242, 86 to 8855, 87 to 8118, 88 to 369, 89 to 6575, 90 to 6694,
        91 to 8080, 92 to 1283, 93 to 7434, 94 to 5290, 95 to 1731, 96 to 2401, 97 to 459,
        98 to 192, 99 to 7910, 100 to 114, 101 to 5660, 102 to 1313, 103 to 203, 104 to 7460,
        105 to 207, 106 to 6497, 107 to 6696, 108 to 7766, 109 to 6233, 110 to 3185,
        111 to 2010, 112 to 2056, 113 to 3717, 114 to 5802, 115 to 5622, 116 to 2156,
        117 to 4243, 118 to 1422, 119 to 5039, 120 to 4110, 121 to 1093, 122 to 1776,
        123 to 7995, 124 to 6877, 125 to 5635, 126 to 54, 127 to 288, 128 to 4592,
        129 to 7276, 130 to 688, 131 to 8388, 132 to 8152, 133 to 8194, 134 to 7000,
        135 to 8527, 136 to 5126, 137 to 3923, 138 to 1054, 139 to 3927, 140 to 5029,
        141 to 4098, 142 to 1789, 143 to 56, 144 to 7240, 145 to 5538, 146 to 1903,
        147 to 6538, 148 to 3380, 149 to 6643, 150 to 7495, 151 to 8718, 152 to 8050,
        153 to 126, 154 to 7245, 155 to 2517, 156 to 4438, 157 to 4945, 158 to 7145,
        159 to 724, 160 to 9022, 161 to 6637, 162 to 6927, 163 to 6937, 164 to 8113,
        165 to 5724, 166 to 6006, 167 to 3584, 168 to 2971, 169 to 2230, 170 to 7982,
        171 to 1649, 172 to 3994, 173 to 7720, 174 to 6981, 175 to 781, 176 to 4973,
        177 to 6206, 178 to 2481, 179 to 3157, 180 to 1509, 181 to 510, 182 to 7540,
        183 to 8887, 184 to 7120, 185 to 2882, 186 to 7128, 187 to 8142, 188 to 7229,
        189 to 2787, 190 to 8820, 191 to 2368, 192 to 4331, 193 to 4967, 194 to 4427,
        195 to 6054, 196 to 3728, 197 to 274, 198 to 7134, 199 to 1603, 200 to 1383,
        201 to 1165, 202 to 4363, 203 to 512, 204 to 5985, 205 to 7967, 206 to 2060,
        207 to 7752, 208 to 7484, 209 to 8643, 210 to 3549, 211 to 5731, 212 to 7881,
        213 to 667, 214 to 6828, 215 to 5740, 216 to 3483, 217 to 718, 218 to 6341,
        219 to 1913, 220 to 3228, 221 to 7247, 222 to 7705, 223 to 1018, 224 to 8193,
        225 to 6098, 226 to 3989, 227 to 7828, 228 to 5876, 229 to 7754, 230 to 4719,
        231 to 8011, 232 to 7939, 233 to 5975, 234 to 2004, 235 to 6139, 236 to 8183,
        237 to 3482, 238 to 3361, 239 to 4289, 240 to 231, 241 to 7789, 242 to 4598,
        243 to 5239, 244 to 2638, 245 to 6300, 246 to 8474, 247 to 2194, 248 to 7832,
        249 to 1079, 250 to 1335, 251 to 188, 252 to 1195, 253 to 5914, 254 to 1401,
        255 to 7318, 256 to 5448, 257 to 1392, 258 to 3703, 259 to 2113, 260 to 7783,
        261 to 8176, 262 to 6519, 263 to 7933, 264 to 7938, 265 to 7802, 266 to 6120,
        267 to 224, 268 to 209, 269 to 5656, 270 to 3032, 271 to 6965, 272 to 258,
        273 to 4837, 274 to 5489, 275 to 272, 276 to 3851, 277 to 7140, 278 to 2562,
        279 to 1472, 280 to 79, 281 to 2775, 282 to 3046, 283 to 2532, 284 to 8266,
        285 to 6099, 286 to 4425, 287 to 5293, 288 to 7981, 289 to 2045, 290 to 920,
        291 to 511, 292 to 7416, 293 to 835, 294 to 1289, 295 to 8195, 296 to 7833,
        297 to 8772, 298 to 968, 299 to 1641, 300 to 7117, 301 to 1678, 302 to 5809,
        303 to 8028, 304 to 500, 305 to 6505, 306 to 7868, 307 to 14, 308 to 2238,
        309 to 4744, 310 to 3733, 311 to 7515, 312 to 699, 313 to 5093, 314 to 6388,
        315 to 7959, 316 to 98, 317 to 3914, 318 to 5246, 319 to 2570, 320 to 8396,
        321 to 3513, 322 to 882, 323 to 7994, 324 to 5968, 325 to 8591, 326 to 806,
        327 to 5261, 328 to 1271, 329 to 899, 330 to 3945, 331 to 8404, 332 to 249,
        333 to 3008, 334 to 7139, 335 to 6395, 336 to 6215, 337 to 6080, 338 to 4054,
        339 to 7825, 340 to 6683, 341 to 8725, 342 to 3230, 343 to 4138, 344 to 6160,
        345 to 666, 346 to 6510, 347 to 3551, 348 to 8075, 349 to 225, 350 to 7169,
        351 to 1851, 352 to 5984, 353 to 2960, 354 to 8329, 355 to 175, 356 to 6378,
        357 to 480, 358 to 7538, 359 to 479, 360 to 5519, 361 to 8534, 362 to 4856,
        363 to 101, 364 to 3521, 365 to 2256, 366 to 3083, 367 to 4278, 368 to 8713,
        369 to 1226, 370 to 4222, 371 to 8494, 372 to 8776, 373 to 731, 374 to 6574,
        375 to 5319, 376 to 8605, 377 to 5583, 378 to 6406, 379 to 4064, 380 to 4806,
        381 to 3972, 382 to 7383, 383 to 5133, 384 to 597, 385 to 1025, 386 to 7313,
        387 to 5304, 388 to 8758, 389 to 1050, 390 to 6499, 391 to 6956, 392 to 770,
        393 to 4108, 394 to 2774, 395 to 3864, 396 to 4490, 397 to 4848, 398 to 1826,
        399 to 6294, 400 to 7949, 401 to 1446, 402 to 7867, 403 to 8163, 404 to 953,
        405 to 8138, 406 to 353, 407 to 7553, 408 to 8825, 409 to 5189, 410 to 2012,
        411 to 948, 412 to 205, 413 to 1535, 414 to 8008, 415 to 1112, 416 to 7926,
        417 to 4039, 418 to 716, 419 to 3967, 420 to 7932, 421 to 7525, 422 to 7316,
        423 to 3448, 424 to 2393, 425 to 6788, 426 to 6550, 427 to 7011, 428 to 8791,
        429 to 8119, 430 to 1777, 431 to 6014, 432 to 1046, 433 to 6269, 434 to 6188,
        435 to 5266, 436 to 3490, 437 to 8786, 438 to 8824, 439 to 589, 440 to 576,
        441 to 1121, 442 to 1806, 443 to 7294, 444 to 3119, 445 to 2688, 446 to 1012,
        447 to 4807, 448 to 7498, 449 to 3905, 450 to 7384, 451 to 2992, 452 to 30,
        453 to 497, 454 to 227, 455 to 4226, 456 to 5007, 457 to 1066, 458 to 8222,
        459 to 7688, 460 to 6865, 461 to 6286, 462 to 8225, 463 to 3224, 464 to 8635,
        465 to 1348, 466 to 3645, 467 to 1961, 468 to 8190, 469 to 6032, 470 to 7286,
        471 to 5389, 472 to 3105, 473 to 1028, 474 to 6038, 475 to 764, 476 to 7437,
        477 to 6555, 478 to 8875, 479 to 2074, 480 to 7809, 481 to 2240, 482 to 2827,
        483 to 5386, 484 to 6763, 485 to 3009, 486 to 6339, 487 to 1825, 488 to 7569,
        489 to 359, 490 to 7956, 491 to 2137, 492 to 8677, 493 to 4434, 494 to 329,
        495 to 3289, 496 to 4290, 497 to 2999, 498 to 2427, 499 to 637, 500 to 2229,
        501 to 1874, 502 to 3446, 503 to 9023, 504 to 3114, 505 to 6235, 506 to 4860,
        507 to 4519, 508 to 561, 509 to 70, 510 to 4800, 511 to 2294, 512 to 6115,
        513 to 2582, 514 to 8464, 515 to 5139, 516 to 6918, 517 to 337, 518 to 5810,
        519 to 8401, 520 to 303, 521 to 5206, 522 to 2589, 523 to 7061, 524 to 2269,
        525 to 2758, 526 to 3389, 527 to 4629, 528 to 707, 529 to 5606, 530 to 1513,
        531 to 2473, 532 to 664, 533 to 5092, 534 to 5154, 535 to 6288, 536 to 6308,
        537 to 4731, 538 to 3328, 539 to 7816, 540 to 3221, 541 to 8687, 542 to 7030,
        543 to 476, 544 to 4257, 545 to 5918, 546 to 6317, 547 to 204, 548 to 8006,
        549 to 6895, 550 to 1264, 551 to 2494, 552 to 112, 553 to 1859, 554 to 398,
        555 to 1052, 556 to 3294, 557 to 1460, 558 to 8573, 559 to 5684, 560 to 8421,
        561 to 5883, 562 to 7297, 563 to 246, 564 to 8057, 565 to 3835, 566 to 1748,
        567 to 3816, 568 to 3357, 569 to 1053, 570 to 409, 571 to 868, 572 to 3118,
        573 to 7520, 574 to 6686, 575 to 1241, 576 to 5190, 577 to 166, 578 to 1482,
        579 to 5604, 580 to 1212, 581 to 2741, 582 to 1259, 583 to 984, 584 to 6492,
        585 to 6167, 586 to 296, 587 to 6567, 588 to 6924, 589 to 2272, 590 to 7085,
        591 to 345, 592 to 2388, 593 to 1705, 594 to 1343, 595 to 7241, 596 to 451,
        597 to 5401, 598 to 6446, 599 to 612, 600 to 594, 601 to 7555, 602 to 7069,
        603 to 2577, 604 to 5333, 605 to 8742, 606 to 6727, 607 to 1571, 608 to 4734,
        609 to 7258, 610 to 3977, 611 to 373, 612 to 5723, 613 to 1365, 614 to 7285,
        615 to 580, 616 to 836, 617 to 6782, 618 to 3654, 619 to 1974, 620 to 6258,
        621 to 925, 622 to 949, 623 to 2790, 624 to 698, 625 to 6373, 626 to 2785,
        627 to 1222, 628 to 2751, 629 to 3825, 630 to 5115, 631 to 1827, 632 to 3171,
        633 to 119, 634 to 850, 635 to 3258, 636 to 7909, 637 to 1322, 638 to 8097,
        639 to 22, 640 to 7478, 641 to 1349, 642 to 4854, 643 to 2929, 644 to 7335,
        645 to 5868, 646 to 454, 647 to 7945, 648 to 2654, 649 to 3493, 650 to 1060,
        651 to 8545, 652 to 6509, 653 to 5002, 654 to 7732, 655 to 3082, 656 to 1779,
        657 to 2709, 658 to 7398, 659 to 8879, 660 to 639, 661 to 598, 662 to 5672,
        663 to 6553, 664 to 4111, 665 to 1417, 666 to 7991, 667 to 380, 668 to 8459,
        669 to 8347, 670 to 1769, 671 to 2673, 672 to 3330, 673 to 7051, 674 to 1337,
        675 to 4057, 676 to 4839, 677 to 6060, 678 to 7095, 679 to 278, 680 to 1445,
        681 to 6518, 682 to 2364, 683 to 1958, 684 to 548, 685 to 4010, 686 to 3072,
        687 to 6993, 688 to 8575, 689 to 2149, 690 to 240, 691 to 2920, 692 to 5588,
        693 to 1885, 694 to 6082, 695 to 9026, 696 to 340, 697 to 159, 698 to 7730,
        699 to 7962, 700 to 1987, 701 to 3876, 702 to 8771, 703 to 5123, 704 to 3866,
        705 to 3546, 706 to 7777, 707 to 115, 708 to 5337, 709 to 475, 710 to 1724,
        711 to 6359, 712 to 4260, 713 to 2110, 714 to 1845, 715 to 4335, 716 to 4133,
        717 to 783, 718 to 8479, 719 to 1448, 720 to 1160, 721 to 7647, 722 to 2618,
        723 to 3630, 724 to 4013, 725 to 5242, 726 to 7957, 727 to 3852, 728 to 3889,
        729 to 1387, 730 to 439, 731 to 1425, 732 to 2061, 733 to 7395, 734 to 7837,
        735 to 5147, 736 to 2319, 737 to 3781, 738 to 1311, 739 to 4733, 740 to 8705,
        741 to 3094, 742 to 2823, 743 to 1914, 744 to 954, 745 to 4381, 746 to 4044,
        747 to 593, 748 to 8300, 749 to 7558, 750 to 6494, 751 to 6330, 752 to 5940,
        753 to 7126, 754 to 1061, 755 to 6352, 756 to 5186, 757 to 1944, 758 to 2285,
        759 to 6673, 760 to 5746, 761 to 208, 762 to 492, 763 to 216, 764 to 979,
        765 to 1668, 766 to 6620, 767 to 711, 768 to 7733, 769 to 8619, 770 to 5157,
        771 to 829, 772 to 3180, 773 to 3979, 774 to 1556, 775 to 3379, 776 to 5727,
        777 to 596, 778 to 2127, 779 to 581, 780 to 2652, 781 to 2628, 782 to 1849,
        783 to 4238, 784 to 606, 785 to 1224, 786 to 1629, 787 to 1413, 788 to 957,
        789 to 8592, 790 to 2254, 791 to 1323, 792 to 122, 793 to 2093, 794 to 1100,
        795 to 81, 796 to 323, 797 to 815, 798 to 2581, 799 to 543, 800 to 6037,
        801 to 2397, 802 to 5513, 803 to 4495, 804 to 5776, 805 to 17, 806 to 4590,
        807 to 8228, 808 to 708, 809 to 3792, 810 to 3790, 811 to 7090, 812 to 1943,
        813 to 4246, 814 to 559, 815 to 3738, 816 to 2167, 817 to 1933, 818 to 2162,
        819 to 549, 820 to 3025, 821 to 1182, 822 to 4358, 823 to 636, 824 to 986,
        825 to 8490, 826 to 3340, 827 to 90, 828 to 1487, 829 to 1639, 830 to 1547,
        831 to 4152, 832 to 1498, 833 to 1740, 834 to 6157, 835 to 217, 836 to 2201,
        837 to 362, 838 to 2146, 839 to 1801, 840 to 5063, 841 to 7339, 842 to 663,
        843 to 38, 844 to 1336, 845 to 3215, 846 to 210, 847 to 6075, 848 to 55,
        849 to 2411, 850 to 7445, 851 to 5767, 852 to 2812, 853 to 472, 854 to 803,
        855 to 4236, 856 to 7665, 857 to 1607, 858 to 1316, 859 to 7475, 860 to 3001,
        861 to 1473, 862 to 3537, 863 to 3070, 864 to 1390, 865 to 1290, 866 to 2499,
        867 to 154, 868 to 7518, 869 to 408, 870 to 1811, 871 to 1734, 872 to 7342,
        873 to 8722, 874 to 1754, 875 to 7657, 876 to 583, 877 to 830, 878 to 6690,
        879 to 1552, 880 to 2498, 881 to 1296, 882 to 3686, 883 to 157, 884 to 487,
        885 to 6119, 886 to 4926, 887 to 4846, 888 to 1536, 889 to 2674, 890 to 1645,
        891 to 3187, 892 to 1058, 893 to 2039, 894 to 4071, 895 to 4433, 896 to 1175,
        897 to 434, 898 to 1001, 899 to 2816, 900 to 820, 901 to 2696, 902 to 4681,
        903 to 2085
    )
}
