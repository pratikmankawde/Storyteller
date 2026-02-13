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
import com.dramebaz.app.ui.insights.ForeshadowingView
import com.dramebaz.app.ui.insights.PlotOutlineView
import com.dramebaz.app.ui.insights.SentimentDistributionView
import com.dramebaz.app.ui.test.insights.BaseInsightsDesignFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Design 2: Dashboard grid layout with compact widgets
 * Analytics-style dashboard with metrics tiles.
 */
class DashboardInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Book header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle

        // Statistics tiles
        view.findViewById<TextView>(R.id.stat_chapters)?.text = dummyChapters.toString()
        view.findViewById<TextView>(R.id.stat_characters)?.text = dummyCharacters.toString()
        view.findViewById<TextView>(R.id.stat_dialogs)?.text = dummyDialogs.toString()

        // Reading level tile
        view.findViewById<TextView>(R.id.reading_level_value)?.text = dummyReadingLevel.gradeDescription
        view.findViewById<TextView>(R.id.reading_ease_value)?.text = "${dummyReadingLevel.readingEaseScore.toInt()}/100"

        // Sentiment tile
        view.findViewById<SentimentDistributionView>(R.id.sentiment_view)?.setDistribution(dummySentiment)
        view.findViewById<TextView>(R.id.sentiment_tone)?.text = dummySentiment.dominantTone

        // Emotional Arc
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)

        // Plot Outline
        view.findViewById<PlotOutlineView>(R.id.plot_outline_view)?.setPlotPoints(dummyPlotPoints, dummyChapters)

        // Themes
        view.findViewById<ChipGroup>(R.id.themes_chip_group)?.apply {
            removeAllViews()
            dummyThemes.take(6).forEach { theme ->
                addView(Chip(requireContext()).apply {
                    text = theme
                    isClickable = false
                    chipMinHeight = 28f
                })
            }
        }

        // Vocabulary container - show actual words
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

        // Foreshadowing
        view.findViewById<ForeshadowingView>(R.id.foreshadowing_view)?.setData(dummyForeshadowing, dummyChapters)
    }
}

