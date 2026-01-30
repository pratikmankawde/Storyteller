package com.dramebaz.app.ai.tts

/**
 * VCTK speaker metadata for the Sherpa VITS vits-vctk model (109 speakers, IDs 0–108).
 * Source: CSTR VCTK Corpus (https://datashare.ed.ac.uk/handle/10283/3443),
 * NeuML vctk-vits-onnx README (https://huggingface.co/NeuML/vctk-vits-onnx).
 * Used to match Qwen-extracted character traits (gender, age, accent) to a TTS speaker ID.
 */
object VctkSpeakerCatalog {

    /** Speaker index in the VITS model (0–108). */
    const val MIN_SPEAKER_ID = 0
    const val MAX_SPEAKER_ID = 108
    const val SPEAKER_COUNT = 109

    /**
     * Traits for one VCTK speaker (0-based index = VITS speaker_id).
     * Gender: "M" or "F". Accent/region: e.g. "English", "Scottish", "American", "Irish".
     */
    data class SpeakerTraits(
        val speakerId: Int,
        val gender: String,
        val ageYears: Int?,
        val accent: String,
        val region: String
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
    }

    private val catalog: List<SpeakerTraits> = buildList {
        // 0-based index = VITS speaker_id. Data from NeuML vctk-vits-onnx README (SPEAKER 1-based → our 0-based).
        add(SpeakerTraits(0, "F", 23, "English", "Southern England"))
        add(SpeakerTraits(1, "M", 22, "English", "Surrey"))
        add(SpeakerTraits(2, "M", 38, "English", "Cumbria"))
        add(SpeakerTraits(3, "F", 22, "English", "Southern England"))
        add(SpeakerTraits(4, "F", 23, "English", "Southern England"))
        add(SpeakerTraits(5, "F", 22, "English", "Stockton-on-tees"))
        add(SpeakerTraits(6, "F", 23, "English", "Southern England"))
        add(SpeakerTraits(7, "M", 23, "English", "Southern England"))
        add(SpeakerTraits(8, "F", 23, "English", "Staffordshire"))
        add(SpeakerTraits(9, "F", 22, "Scottish", "West Dumfries"))
        add(SpeakerTraits(10, "F", 23, "English", "Manchester"))
        add(SpeakerTraits(11, "M", 22, "Scottish", "Fife"))
        add(SpeakerTraits(12, "F", 22, "Northern Irish", "Belfast"))
        add(SpeakerTraits(13, "F", 22, "English", "SW England"))
        add(SpeakerTraits(14, "F", 21, "English", "Southern England"))
        add(SpeakerTraits(15, "M", 21, "Scottish", "Perth"))
        add(SpeakerTraits(16, "M", 22, "English", "London"))
        add(SpeakerTraits(17, "F", 22, "English", "Manchester"))
        add(SpeakerTraits(18, "M", 25, "Irish", "Dublin"))
        add(SpeakerTraits(19, "M", 22, "Scottish", "Selkirk"))
        add(SpeakerTraits(20, "M", 22, "Scottish", "Argyll"))
        add(SpeakerTraits(21, "F", 23, "Indian", ""))
        add(SpeakerTraits(22, "F", 22, "Scottish", "Aberdeen"))
        add(SpeakerTraits(23, "F", 22, "English", "SE England"))
        add(SpeakerTraits(24, "M", 26, "Indian", ""))
        add(SpeakerTraits(25, "M", 22, "Scottish", "Edinburgh"))
        add(SpeakerTraits(26, "F", 22, "Welsh", "Cardiff"))
        add(SpeakerTraits(27, "M", 21, "English", "Surrey"))
        add(SpeakerTraits(28, "M", 19, "Scottish", "Galloway"))
        add(SpeakerTraits(29, "M", 24, "English", "Birmingham"))
        add(SpeakerTraits(30, "F", 24, "English", "Southern England"))
        add(SpeakerTraits(31, "M", 22, "English", "Southern England"))
        add(SpeakerTraits(32, "M", 23, "English", "Nottingham"))
        add(SpeakerTraits(33, "M", 21, "Scottish", "Orkney"))
        add(SpeakerTraits(34, "F", 26, "Northern Irish", "Belfast"))
        add(SpeakerTraits(35, "F", 23, "Scottish", "Edinburgh"))
        add(SpeakerTraits(36, "M", 22, "Scottish", "Aberdeen"))
        add(SpeakerTraits(37, "F", 23, "Scottish", "West Lothian"))
        add(SpeakerTraits(38, "F", 23, "Scottish", "Ross"))
        add(SpeakerTraits(39, "F", 22, "Irish", "Athlone"))
        add(SpeakerTraits(40, "F", 23, "English", "Yorkshire"))
        add(SpeakerTraits(41, "F", 23, "English", "Southern England"))
        add(SpeakerTraits(42, "F", 20, "English", "Newcastle"))
        add(SpeakerTraits(43, "M", 21, "English", "Yorkshire"))
        add(SpeakerTraits(44, "M", 19, "Scottish", "Fife"))
        add(SpeakerTraits(45, "M", 23, "Scottish", "Edinburgh"))
        add(SpeakerTraits(46, "M", 23, "English", "Suffolk"))
        add(SpeakerTraits(47, "M", 22, "English", "Essex"))
        add(SpeakerTraits(48, "M", 23, "Scottish", "Midlothian"))
        add(SpeakerTraits(49, "F", 24, "English", "Oxford"))
        add(SpeakerTraits(50, "F", 23, "English", "NE England"))
        add(SpeakerTraits(51, "M", 22, "English", "Cheshire"))
        add(SpeakerTraits(52, "M", 23, "English", "Leicester"))
        add(SpeakerTraits(53, "M", null, "Unknown", ""))  // REF 280
        add(SpeakerTraits(54, "M", 29, "Scottish", "Edinburgh"))
        add(SpeakerTraits(55, "F", 23, "English", "Newcastle"))
        add(SpeakerTraits(56, "F", 24, "Irish", "Cork"))
        add(SpeakerTraits(57, "M", 20, "Scottish", "Fife"))
        add(SpeakerTraits(58, "M", 21, "Scottish", "Edinburgh"))
        add(SpeakerTraits(59, "M", 23, "English", "Newcastle"))
        add(SpeakerTraits(60, "M", 23, "English", "York"))
        add(SpeakerTraits(61, "F", 22, "Irish", "Dublin"))
        add(SpeakerTraits(62, "M", 23, "Northern Irish", "Belfast"))
        add(SpeakerTraits(63, "F", 22, "Northern Irish", "Belfast"))
        add(SpeakerTraits(64, "F", 33, "American", "San Francisco"))
        add(SpeakerTraits(65, "F", 23, "Irish", "Dublin"))
        add(SpeakerTraits(66, "F", 20, "American", "New York"))
        add(SpeakerTraits(67, "M", 19, "Irish", "Tipperary"))
        add(SpeakerTraits(68, "F", 25, "American", "California"))
        add(SpeakerTraits(69, "F", 23, "American", "California"))
        add(SpeakerTraits(70, "F", 23, "American", "North Carolina"))
        add(SpeakerTraits(71, "M", 20, "Canadian", "Montreal"))
        add(SpeakerTraits(72, "F", 24, "Canadian", "Toronto"))
        add(SpeakerTraits(73, "M", 22, "Northern Irish", "Belfast"))
        add(SpeakerTraits(74, "F", 19, "American", "Philadelphia"))
        add(SpeakerTraits(75, "F", 21, "American", "New York"))
        add(SpeakerTraits(76, "F", 23, "Canadian", "Ontario"))
        add(SpeakerTraits(77, "F", 18, "American", "Alabama"))
        add(SpeakerTraits(78, "F", 21, "American", "Tennessee"))
        add(SpeakerTraits(79, "M", 21, "American", "Iowa"))
        add(SpeakerTraits(80, "F", 19, "Canadian", "Hamilton"))
        add(SpeakerTraits(81, "F", 24, "Irish", "County Down"))
        add(SpeakerTraits(82, "F", 26, "South African", "Cape Town"))
        add(SpeakerTraits(83, "M", 20, "Canadian", "Alberta"))
        add(SpeakerTraits(84, "F", 23, "Canadian", "Hamilton"))
        add(SpeakerTraits(85, "F", 32, "American", "Napa"))
        add(SpeakerTraits(86, "F", 19, "South African", "Pretoria"))
        add(SpeakerTraits(87, "M", 26, "Australian", "Sydney"))
        add(SpeakerTraits(88, "F", 23, "American", ""))
        add(SpeakerTraits(89, "F", 26, "American", ""))
        add(SpeakerTraits(90, "F", 19, "American", "Indiana"))
        add(SpeakerTraits(91, "M", 18, "American", "Chicago"))
        add(SpeakerTraits(92, "F", 25, "New Zealand", "English"))
        add(SpeakerTraits(93, "F", 18, "South African", "Johannesburg"))
        add(SpeakerTraits(94, "F", 21, "American", "Pennsylvania"))
        add(SpeakerTraits(95, "F", 18, "Irish", "Dublin"))
        add(SpeakerTraits(96, "F", 26, "American", "Ohio"))
        add(SpeakerTraits(97, "F", 27, "Canadian", "Alberta"))
        add(SpeakerTraits(98, "M", 22, "American", "Florida"))
        add(SpeakerTraits(99, "M", 26, "South African", "Johannesburg"))
        add(SpeakerTraits(100, "F", 21, "Northern Irish", "Derry"))
        add(SpeakerTraits(101, "M", 19, "American", "New Jersey"))
        add(SpeakerTraits(102, "F", 19, "American", "New Jersey"))
        add(SpeakerTraits(103, "F", 29, "American", ""))
        add(SpeakerTraits(104, "M", 22, "Canadian", "Toronto"))
        add(SpeakerTraits(105, "M", 23, "Irish", "Donegal"))
        add(SpeakerTraits(106, "M", 28, "Australian", "English"))
        // Indices 107–108: catalog has 107 entries in source; VCTK has 109 speakers. Add two generic entries.
        add(SpeakerTraits(107, "F", 22, "English", "Southern England"))
        add(SpeakerTraits(108, "M", 24, "American", "California"))
    }

    fun getTraits(speakerId: Int): SpeakerTraits? =
        catalog.getOrNull(speakerId)

    fun allSpeakers(): List<SpeakerTraits> = catalog

    /** All speaker IDs that are female. */
    fun femaleSpeakerIds(): List<Int> = catalog.filter { it.isFemale }.map { it.speakerId }

    /** All speaker IDs that are male. */
    fun maleSpeakerIds(): List<Int> = catalog.filter { it.isMale }.map { it.speakerId }
}
