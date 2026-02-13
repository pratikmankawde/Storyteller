package com.dramebaz.app.ui.test.insights.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dramebaz.app.R
import com.dramebaz.app.ui.insights.EmotionalArcView
import com.dramebaz.app.ui.insights.PlotOutlineView
import com.dramebaz.app.ui.insights.SentimentDistributionView
import com.dramebaz.app.ui.test.insights.BaseInsightsDesignFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Design 1: Card-based sections with Material Design cards
 * Clean, organized layout with distinct sections in cards.
 */
class CardsInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_cards, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Book header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle
        view.findViewById<TextView>(R.id.book_author)?.text = dummyBookAuthor

        // Statistics
        view.findViewById<TextView>(R.id.stat_chapters)?.text = dummyChapters.toString()
        view.findViewById<TextView>(R.id.stat_characters)?.text = dummyCharacters.toString()
        view.findViewById<TextView>(R.id.stat_dialogs)?.text = dummyDialogs.toString()

        // Reading Level
        view.findViewById<Chip>(R.id.reading_level_chip)?.apply {
            text = dummyReadingLevel.gradeDescription
            chipBackgroundColor = ContextCompat.getColorStateList(requireContext(), R.color.md_theme_primaryContainer)
        }
        view.findViewById<TextView>(R.id.reading_ease)?.text = "${dummyReadingLevel.readingEaseScore.toInt()}"
        view.findViewById<TextView>(R.id.avg_sentence)?.text = String.format("%.1f", dummyReadingLevel.avgSentenceLength)
        view.findViewById<TextView>(R.id.vocab_complexity)?.text = "${dummyReadingLevel.vocabularyComplexity.toInt()}%"

        // Sentiment
        view.findViewById<SentimentDistributionView>(R.id.sentiment_view)?.setDistribution(dummySentiment)
        view.findViewById<Chip>(R.id.sentiment_tone_chip)?.text = dummySentiment.dominantTone

        // Emotional Arc
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)

        // Plot Outline
        view.findViewById<PlotOutlineView>(R.id.plot_outline_view)?.setPlotPoints(dummyPlotPoints, dummyChapters)

        // Themes
        view.findViewById<ChipGroup>(R.id.themes_chip_group)?.apply {
            removeAllViews()
            dummyThemes.forEach { theme ->
                addView(Chip(requireContext()).apply {
                    text = theme
                    isClickable = false
                })
            }
        }

        // Vocabulary
        view.findViewById<LinearLayout>(R.id.vocabulary_container)?.apply {
            removeAllViews()
            dummyVocabulary.take(5).forEach { (word, definition) ->
                addView(TextView(requireContext()).apply {
                    text = "• $word: $definition"
                    setPadding(0, 8, 0, 8)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                })
            }
        }
    }
}

