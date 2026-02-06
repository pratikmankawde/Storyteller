package com.dramebaz.app.data.models

/**
 * INS-003: Sentiment Distribution Analysis
 * Data class representing the distribution of sentiment across a book.
 */
data class SentimentDistribution(
    val positive: Float,   // Percentage (0-100)
    val neutral: Float,    // Percentage (0-100)
    val negative: Float,   // Percentage (0-100)
    val dominantTone: String = ""  // "Uplifting", "Dark", "Balanced", etc.
) {
    companion object {
        // Map emotions to sentiment categories
        private val positiveEmotions = setOf(
            "joy", "happy", "happiness", "love", "romance", 
            "peace", "calm", "resolution", "excitement", "hope",
            "grateful", "content", "cheerful", "optimistic"
        )
        
        private val negativeEmotions = setOf(
            "sadness", "sad", "melancholy", "anger", "angry", "rage",
            "fear", "scared", "anxiety", "grief", "despair", "horror",
            "disgust", "frustration", "jealousy", "guilt", "shame"
        )
        
        // Neutral: surprise, curious, curiosity, tension, suspense, neutral, etc.
        
        /**
         * Classify an emotion string into sentiment category.
         * @return "positive", "neutral", or "negative"
         */
        fun classifySentiment(emotion: String): String {
            val lower = emotion.lowercase().trim()
            return when {
                positiveEmotions.any { lower.contains(it) } -> "positive"
                negativeEmotions.any { lower.contains(it) } -> "negative"
                else -> "neutral"
            }
        }
        
        /**
         * Calculate sentiment distribution from a list of emotion-intensity pairs.
         */
        fun fromEmotions(emotions: List<Pair<String, Float>>): SentimentDistribution {
            if (emotions.isEmpty()) {
                return SentimentDistribution(33.3f, 33.3f, 33.4f, "Unknown")
            }
            
            var positiveWeight = 0f
            var neutralWeight = 0f
            var negativeWeight = 0f
            var totalWeight = 0f
            
            emotions.forEach { (emotion, intensity) ->
                val weight = intensity.coerceIn(0f, 10f)
                totalWeight += weight
                when (classifySentiment(emotion)) {
                    "positive" -> positiveWeight += weight
                    "negative" -> negativeWeight += weight
                    else -> neutralWeight += weight
                }
            }
            
            if (totalWeight == 0f) {
                return SentimentDistribution(33.3f, 33.3f, 33.4f, "Unknown")
            }
            
            val posPercent = (positiveWeight / totalWeight * 100f)
            val negPercent = (negativeWeight / totalWeight * 100f)
            val neuPercent = (neutralWeight / totalWeight * 100f)
            
            // Determine dominant tone
            val tone = when {
                posPercent >= 60f -> "Uplifting"
                negPercent >= 60f -> "Dark"
                posPercent >= 45f && negPercent <= 25f -> "Optimistic"
                negPercent >= 45f && posPercent <= 25f -> "Somber"
                neuPercent >= 50f -> "Contemplative"
                posPercent >= 35f && negPercent >= 35f -> "Dramatic"
                else -> "Balanced"
            }
            
            return SentimentDistribution(
                positive = posPercent,
                neutral = neuPercent,
                negative = negPercent,
                dominantTone = tone
            )
        }
    }
    
    /**
     * Get color for the dominant tone badge.
     */
    fun getToneColor(): Int {
        return when (dominantTone.lowercase()) {
            "uplifting" -> android.graphics.Color.parseColor("#4CAF50")  // Green
            "dark" -> android.graphics.Color.parseColor("#5D4037")       // Brown
            "optimistic" -> android.graphics.Color.parseColor("#8BC34A") // Light Green
            "somber" -> android.graphics.Color.parseColor("#607D8B")     // Blue Grey
            "contemplative" -> android.graphics.Color.parseColor("#9C27B0") // Purple
            "dramatic" -> android.graphics.Color.parseColor("#FF5722")   // Deep Orange
            "balanced" -> android.graphics.Color.parseColor("#2196F3")   // Blue
            else -> android.graphics.Color.parseColor("#9E9E9E")         // Grey
        }
    }
}

