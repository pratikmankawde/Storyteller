package com.dramebaz.app.ui.test.insights.designs

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.dramebaz.app.R
import com.dramebaz.app.ui.insights.EmotionalArcView
import com.dramebaz.app.ui.insights.PlotOutlineView
import com.dramebaz.app.ui.insights.SentimentDistributionView
import com.dramebaz.app.ui.test.insights.BaseInsightsDesignFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Design 7: Dark Mode / OLED friendly
 * Dark theme with high contrast elements, perfect for OLED screens.
 */
class DarkInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_dark, container, false)
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

        // Reading level
        view.findViewById<Chip>(R.id.reading_level_chip)?.apply {
            text = dummyReadingLevel.gradeDescription
            setChipBackgroundColorResource(R.color.dark_accent)
            setTextColor(Color.BLACK)
        }
        view.findViewById<TextView>(R.id.reading_ease)?.text = "Ease: ${dummyReadingLevel.readingEaseScore.toInt()}"

        // Emotional Arc
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)

        // Sentiment
        view.findViewById<SentimentDistributionView>(R.id.sentiment_view)?.setDistribution(dummySentiment)
        view.findViewById<Chip>(R.id.sentiment_tone_chip)?.apply {
            text = dummySentiment.dominantTone
            setChipBackgroundColorResource(R.color.dark_accent)
            setTextColor(Color.BLACK)
        }

        // Plot Outline
        view.findViewById<PlotOutlineView>(R.id.plot_outline_view)?.setPlotPoints(dummyPlotPoints, dummyChapters)

        // Themes
        view.findViewById<ChipGroup>(R.id.themes_chip_group)?.apply {
            removeAllViews()
            dummyThemes.forEach { theme ->
                addView(Chip(requireContext()).apply {
                    text = theme
                    isClickable = false
                    setChipBackgroundColorResource(R.color.dark_card_bg)
                    setTextColor(Color.WHITE)
                    chipStrokeWidth = 1f
                    setChipStrokeColorResource(R.color.dark_accent)
                })
            }
        }

        // Vocabulary
        view.findViewById<LinearLayout>(R.id.vocabulary_container)?.apply {
            removeAllViews()
            dummyVocabulary.take(5).forEach { (word, definition) ->
                addView(TextView(requireContext()).apply {
                    text = "• $word: $definition"
                    setPadding(0, 12, 0, 12)
                    setTextColor(Color.parseColor("#E0E0E0"))
                })
            }
        }
    }
}

