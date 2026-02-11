package com.dramebaz.app.domain.theme

/**
 * COVER-001: Maps book genres to placeholder cover asset paths.
 *
 * This is a pure mapping utility with no Android dependencies.
 * The returned paths are relative to the Android assets root.
 *
 * Example: "fantasy" -> "images/bookcovers/Fantasy.png"
 */
object GenreCoverMapper {

    private const val BASE_PATH = "images/bookcovers/"

    // Asset filenames (must match files under app/src/main/assets/images/bookcovers)
    private const val COVER_BIOGRAPHIES = BASE_PATH + "Biographies.png"
    private const val COVER_CHILDRENS = BASE_PATH + "Childrens.png"
    private const val COVER_COMEDY = BASE_PATH + "Comedy.png"
    private const val COVER_FANTASY = BASE_PATH + "Fantasy.png"
    private const val COVER_HISTORY = BASE_PATH + "History.png"
    private const val COVER_HORROR = BASE_PATH + "Horror.png"
    private const val COVER_LITERATURE = BASE_PATH + "Literature.png"
    private const val COVER_MYSTERY = BASE_PATH + "Mystery.png"
    private const val COVER_NONFICTION = BASE_PATH + "NonFiction.png"
    private const val COVER_ROMANCE = BASE_PATH + "Romance.png"
    private const val COVER_SCIFI = BASE_PATH + "Sci-Fi.png"
    private const val COVER_SPIRITUAL = BASE_PATH + "Spiritual.png"

    /** Default cover used when genre is null or unknown. */
    private const val DEFAULT_COVER = COVER_LITERATURE

    /**
     * Map a raw genre string (from ThemeAnalysisPass or heuristics) to
     * an asset-relative placeholder cover path.
     */
    fun mapGenreToCoverPath(rawGenre: String?): String {
        val key = normalize(rawGenre) ?: return DEFAULT_COVER

        return when {
            // Fantasy
            key in setOf("fantasy", "epicfantasy", "urbanfantasy", "highfantasy") ->
                COVER_FANTASY

            // Sci-fi
            key in setOf("scifi", "sciencefiction", "spacesaga", "dystopian") ->
                COVER_SCIFI

            // Romance
            key in setOf("romance", "romanticfiction", "romcom") ->
                COVER_ROMANCE

            // Mystery / thriller / crime
            key in setOf("mystery", "thriller", "crime", "suspense", "detective", "noir") ->
                COVER_MYSTERY

            // Horror
            key in setOf("horror", "ghoststory", "supernaturalhorror") ->
                COVER_HORROR

            // Comedy / humor
            key in setOf("comedy", "humor", "humour") ->
                COVER_COMEDY

            // Children's / YA
            key in setOf("childrens", "children", "kids", "middlegrade", "youngadult", "ya") ->
                COVER_CHILDRENS

            // History / historical
            key in setOf("history", "historical", "historicalfiction") ->
                COVER_HISTORY

            // Biographies / memoir
            key in setOf("biography", "biographies", "memoir") ->
                COVER_BIOGRAPHIES

            // Spiritual / religious
            key in setOf("spiritual", "religion", "faith", "inspirational") ->
                COVER_SPIRITUAL

            // General non-fiction
            key in setOf("nonfiction", "essays", "business", "science") ->
                COVER_NONFICTION

            // Classic / modern literature (ThemeAnalysis defaults)
            key in setOf("classicliterature", "modernfiction", "literary", "literature") ->
                COVER_LITERATURE

            else -> DEFAULT_COVER
        }
    }

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sb = StringBuilder()
        for (ch in raw.lowercase().trim()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}

