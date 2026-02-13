package com.dramebaz.app.ui.test.insights.designs

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dramebaz.app.R
import com.dramebaz.app.ui.insights.EmotionalArcView
import com.dramebaz.app.ui.insights.ForeshadowingView
import com.dramebaz.app.ui.insights.PlotOutlineView
import com.dramebaz.app.ui.insights.SentimentDistributionView
import com.dramebaz.app.ui.test.insights.BaseInsightsDesignFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Design 3: Vertical timeline layout
 * Story progression displayed as a timeline with insights at each point.
 */
class TimelineInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_timeline, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Book header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle
        view.findViewById<TextView>(R.id.book_author)?.text = dummyBookAuthor

        // Quick stats at top
        view.findViewById<TextView>(R.id.stat_chapters)?.text = "$dummyChapters chapters"
        view.findViewById<TextView>(R.id.stat_characters)?.text = "$dummyCharacters characters"
        view.findViewById<TextView>(R.id.stat_dialogs)?.text = "$dummyDialogs dialogs"

        // Reading level
        view.findViewById<TextView>(R.id.reading_level_value)?.text =
            "${dummyReadingLevel.gradeDescription} (Grade ${dummyReadingLevel.gradeLevel.toInt()})"

        // Sentiment Analysis
        view.findViewById<TextView>(R.id.sentiment_tone)?.text = "Tone: ${dummySentiment.dominantTone}"
        view.findViewById<SentimentDistributionView>(R.id.sentiment_view)?.setDistribution(dummySentiment)

        // Timeline section - Emotional Journey
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)

        // Plot Outline
        view.findViewById<PlotOutlineView>(R.id.plot_outline_view)?.setPlotPoints(dummyPlotPoints, dummyChapters)

        // Timeline section - Foreshadowing connections
        view.findViewById<ForeshadowingView>(R.id.foreshadowing_view)?.setData(dummyForeshadowing, dummyChapters)

        // Themes section
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

    private fun createTimelineItem(title: String, description: String): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 16, 16)
            addView(TextView(requireContext()).apply {
                text = title
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_primary))
                textSize = 14f
                paint.isFakeBoldText = true
            })
            addView(TextView(requireContext()).apply {
                text = description
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                textSize = 12f
            })
        }
    }
}

