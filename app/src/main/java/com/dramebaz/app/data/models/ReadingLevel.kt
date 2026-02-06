package com.dramebaz.app.data.models

/**
 * INS-004: Reading Level Analysis
 * Data class representing text reading complexity metrics.
 */
data class ReadingLevel(
    /** Flesch-Kincaid grade level (e.g., 8.5 = Grade 8-9) */
    val gradeLevel: Float,
    /** Human-readable grade description (e.g., "Grade 8", "Adult", "Young Adult") */
    val gradeDescription: String,
    /** Average words per sentence */
    val avgSentenceLength: Float,
    /** Average syllables per word */
    val avgSyllablesPerWord: Float,
    /** Vocabulary complexity score (0-100) */
    val vocabularyComplexity: Float,
    /** Flesch Reading Ease score (0-100, higher = easier) */
    val readingEaseScore: Float
) {
    companion object {
        /**
         * Analyze text and calculate reading level using Flesch-Kincaid formulas.
         * 
         * Flesch-Kincaid Grade Level = 0.39 × (words/sentences) + 11.8 × (syllables/words) − 15.59
         * Flesch Reading Ease = 206.835 − 1.015 × (words/sentences) − 84.6 × (syllables/words)
         */
        fun analyze(text: String): ReadingLevel {
            if (text.isBlank()) {
                return ReadingLevel(
                    gradeLevel = 0f,
                    gradeDescription = "N/A",
                    avgSentenceLength = 0f,
                    avgSyllablesPerWord = 0f,
                    vocabularyComplexity = 0f,
                    readingEaseScore = 100f
                )
            }

            // Count sentences (end with . ! ?)
            val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }
            val sentenceCount = sentences.size.coerceAtLeast(1)

            // Count words
            val words = text.split(Regex("\\s+")).filter { it.any { c -> c.isLetter() } }
            val wordCount = words.size.coerceAtLeast(1)

            // Count syllables (simplified: vowel groups)
            var totalSyllables = 0
            var complexWords = 0 // Words with 3+ syllables
            val uniqueWords = mutableSetOf<String>()
            
            for (word in words) {
                val cleanWord = word.lowercase().filter { it.isLetter() }
                uniqueWords.add(cleanWord)
                val syllables = countSyllables(cleanWord)
                totalSyllables += syllables
                if (syllables >= 3) complexWords++
            }

            val avgSentenceLength = wordCount.toFloat() / sentenceCount
            val avgSyllablesPerWord = totalSyllables.toFloat() / wordCount

            // Flesch-Kincaid Grade Level
            val gradeLevel = (0.39f * avgSentenceLength + 11.8f * avgSyllablesPerWord - 15.59f)
                .coerceIn(0f, 18f) // Cap at graduate level

            // Flesch Reading Ease (0-100, higher = easier)
            val readingEase = (206.835f - 1.015f * avgSentenceLength - 84.6f * avgSyllablesPerWord)
                .coerceIn(0f, 100f)

            // Vocabulary complexity: % of complex words + uniqueness factor
            val complexWordRatio = complexWords.toFloat() / wordCount
            val uniquenessRatio = uniqueWords.size.toFloat() / wordCount
            val vocabularyComplexity = ((complexWordRatio * 70f + uniquenessRatio * 30f) * 100f)
                .coerceIn(0f, 100f)

            // Grade description
            val gradeDescription = when {
                gradeLevel < 1f -> "Pre-K"
                gradeLevel < 6f -> "Grade ${gradeLevel.toInt()}"
                gradeLevel < 9f -> "Middle School"
                gradeLevel < 12f -> "High School"
                gradeLevel < 14f -> "Young Adult"
                gradeLevel < 16f -> "Adult"
                else -> "Advanced"
            }

            return ReadingLevel(
                gradeLevel = gradeLevel,
                gradeDescription = gradeDescription,
                avgSentenceLength = avgSentenceLength,
                avgSyllablesPerWord = avgSyllablesPerWord,
                vocabularyComplexity = vocabularyComplexity,
                readingEaseScore = readingEase
            )
        }

        /**
         * Count syllables in a word using a simple vowel-counting heuristic.
         * This is an approximation that works well for English.
         */
        private fun countSyllables(word: String): Int {
            if (word.isEmpty()) return 0
            if (word.length <= 3) return 1

            val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y')
            var count = 0
            var prevWasVowel = false

            for (c in word) {
                val isVowel = c in vowels
                if (isVowel && !prevWasVowel) {
                    count++
                }
                prevWasVowel = isVowel
            }

            // Handle silent 'e' at end
            if (word.endsWith("e") && count > 1) {
                count--
            }

            // Handle special suffixes
            if (word.endsWith("le") && word.length > 2 && word[word.length - 3] !in vowels) {
                count++
            }

            return count.coerceAtLeast(1)
        }
    }
}

