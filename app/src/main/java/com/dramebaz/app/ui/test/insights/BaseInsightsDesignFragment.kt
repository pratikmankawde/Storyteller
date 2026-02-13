package com.dramebaz.app.ui.test.insights

import androidx.fragment.app.Fragment
import com.dramebaz.app.data.models.EmotionalSegment
import com.dramebaz.app.data.models.Foreshadowing
import com.dramebaz.app.data.models.PlotPoint
import com.dramebaz.app.data.models.PlotPointType
import com.dramebaz.app.data.models.ReadingLevel
import com.dramebaz.app.data.models.SentimentDistribution
import com.dramebaz.app.ui.insights.EmotionalArcView

/**
 * Base fragment for Insights design variations.
 * Provides dummy data for all insights components.
 */
abstract class BaseInsightsDesignFragment : Fragment() {

    // Dummy book info
    protected val dummyBookTitle = "Harry Potter and the Sorcerer's Stone"
    protected val dummyBookAuthor = "J.K. Rowling"

    // Dummy statistics
    protected val dummyChapters = 17
    protected val dummyCharacters = 24
    protected val dummyDialogs = 312

    // Dummy reading level
    protected val dummyReadingLevel = ReadingLevel(
        gradeLevel = 6.5f,
        gradeDescription = "Middle School",
        avgSentenceLength = 14.2f,
        avgSyllablesPerWord = 1.42f,
        vocabularyComplexity = 35.5f,
        readingEaseScore = 72.3f
    )

    // Dummy sentiment distribution
    protected val dummySentiment = SentimentDistribution(
        positive = 45f,
        neutral = 35f,
        negative = 20f,
        dominantTone = "Optimistic"
    )

    // Dummy themes
    protected val dummyThemes = listOf(
        "Friendship", "Good vs Evil", "Coming of Age", "Magic",
        "Family", "Bravery", "Sacrifice", "Identity"
    )

    // Dummy vocabulary
    protected val dummyVocabulary = listOf(
        "Muggle" to "Non-magical person",
        "Quidditch" to "Wizard sport played on broomsticks",
        "Horcrux" to "Object containing part of a soul",
        "Patronus" to "Guardian spell against Dementors",
        "Parseltongue" to "Language of snakes",
        "Animagus" to "Wizard who can transform into animal",
        "Apparate" to "Teleportation spell",
        "Legilimency" to "Mind-reading magic"
    )

    // Dummy emotional arc data points
    protected val dummyEmotionalArc: List<EmotionalArcView.EmotionalDataPoint> by lazy {
        listOf(
            EmotionalArcView.EmotionalDataPoint(0, "Ch 1: The Boy Who Lived", "curiosity", 6f, listOf("mystery")),
            EmotionalArcView.EmotionalDataPoint(1, "Ch 2: The Vanishing Glass", "sadness", 4f, listOf("frustration")),
            EmotionalArcView.EmotionalDataPoint(2, "Ch 3: Letters from No One", "curiosity", 7f, listOf("excitement")),
            EmotionalArcView.EmotionalDataPoint(3, "Ch 4: The Keeper of Keys", "joy", 8f, listOf("surprise")),
            EmotionalArcView.EmotionalDataPoint(4, "Ch 5: Diagon Alley", "joy", 9f, listOf("excitement", "wonder")),
            EmotionalArcView.EmotionalDataPoint(5, "Ch 6: Platform 9¾", "anxiety", 5f, listOf("excitement")),
            EmotionalArcView.EmotionalDataPoint(6, "Ch 7: The Sorting Hat", "tension", 7f, listOf("hope")),
            EmotionalArcView.EmotionalDataPoint(7, "Ch 8: Potions Master", "anger", 6f, listOf("frustration")),
            EmotionalArcView.EmotionalDataPoint(8, "Ch 9: Midnight Duel", "fear", 7f, listOf("tension")),
            EmotionalArcView.EmotionalDataPoint(9, "Ch 10: Halloween", "fear", 8f, listOf("danger")),
            EmotionalArcView.EmotionalDataPoint(10, "Ch 11: Quidditch", "joy", 9f, listOf("excitement")),
            EmotionalArcView.EmotionalDataPoint(11, "Ch 12: Mirror of Erised", "sadness", 6f, listOf("longing")),
            EmotionalArcView.EmotionalDataPoint(12, "Ch 13: Nicolas Flamel", "curiosity", 7f, listOf("discovery")),
            EmotionalArcView.EmotionalDataPoint(13, "Ch 14: Dragon", "tension", 8f, listOf("fear")),
            EmotionalArcView.EmotionalDataPoint(14, "Ch 15: Forbidden Forest", "fear", 9f, listOf("danger")),
            EmotionalArcView.EmotionalDataPoint(15, "Ch 16: Through Trapdoor", "tension", 10f, listOf("fear", "bravery")),
            EmotionalArcView.EmotionalDataPoint(16, "Ch 17: The Man with Two Faces", "resolution", 8f, listOf("joy", "relief"))
        )
    }

    // Dummy plot points
    protected val dummyPlotPoints: List<PlotPoint> by lazy {
        listOf(
            PlotPoint(1, PlotPointType.EXPOSITION, 0, "Harry lives with the Dursleys, unaware of his magical heritage"),
            PlotPoint(1, PlotPointType.INCITING_INCIDENT, 3, "Hagrid reveals Harry is a wizard"),
            PlotPoint(1, PlotPointType.RISING_ACTION, 6, "Harry begins Hogwarts and discovers mysterious events"),
            PlotPoint(1, PlotPointType.MIDPOINT, 10, "The troll attack bonds Harry, Ron, and Hermione"),
            PlotPoint(1, PlotPointType.CLIMAX, 15, "Harry faces Quirrell/Voldemort in the dungeon"),
            PlotPoint(1, PlotPointType.FALLING_ACTION, 16, "Harry wakes in hospital, learns the truth"),
            PlotPoint(1, PlotPointType.RESOLUTION, 16, "Harry returns home, changed forever")
        )
    }

    // Dummy foreshadowing
    protected val dummyForeshadowing: List<Foreshadowing> by lazy {
        listOf(
            Foreshadowing(1, 1, 0, "Harry's scar hurts when near Quirrell", 15, "Voldemort is on the back of Quirrell's head", "fate", 0.9f),
            Foreshadowing(2, 1, 4, "Hagrid mentions vault 713 at Gringotts", 9, "The Sorcerer's Stone was moved", "mystery", 0.85f),
            Foreshadowing(3, 1, 6, "Dumbledore warns about the 3rd floor corridor", 15, "The trio ventures through the trapdoor", "danger", 0.9f),
            Foreshadowing(4, 1, 11, "Harry sees his parents in the Mirror of Erised", 16, "Lily's love protects Harry from Voldemort", "love", 0.95f)
        )
    }

    // Dummy emotional segments for chapters
    protected val dummyEmotionalSegments: List<EmotionalSegment> = listOf(
        EmotionalSegment("Beginning", "curiosity", 0.7f),
        EmotionalSegment("Middle", "tension", 0.8f),
        EmotionalSegment("End", "resolution", 0.9f)
    )
}

