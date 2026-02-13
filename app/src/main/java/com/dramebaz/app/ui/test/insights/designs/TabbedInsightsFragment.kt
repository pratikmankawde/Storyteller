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
import com.google.android.material.tabs.TabLayout

/**
 * Design 4: Tabbed sections
 * Organizes insights into navigable tabs for a cleaner experience.
 */
class TabbedInsightsFragment : BaseInsightsDesignFragment() {

    private var overviewSection: View? = null
    private var emotionSection: View? = null
    private var storySection: View? = null
    private var learningSection: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_tabbed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Book header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle

        // Find sections
        overviewSection = view.findViewById(R.id.overview_content)
        emotionSection = view.findViewById(R.id.emotions_content)
        storySection = view.findViewById(R.id.story_content)
        learningSection = view.findViewById(R.id.vocabulary_content)

        // Setup tabs
        view.findViewById<TabLayout>(R.id.tab_layout)?.apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.position)
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }

        // Populate Overview
        view.findViewById<TextView>(R.id.stat_chapters)?.text = dummyChapters.toString()
        view.findViewById<TextView>(R.id.stat_characters)?.text = dummyCharacters.toString()
        view.findViewById<TextView>(R.id.stat_dialogs)?.text = dummyDialogs.toString()
        view.findViewById<TextView>(R.id.reading_level_value)?.text = dummyReadingLevel.gradeDescription

        // Populate Emotion
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)
        view.findViewById<SentimentDistributionView>(R.id.sentiment_view)?.setDistribution(dummySentiment)
        view.findViewById<TextView>(R.id.sentiment_tone)?.text = "Tone: ${dummySentiment.dominantTone}"

        // Populate Story
        view.findViewById<PlotOutlineView>(R.id.plot_outline_view)?.setPlotPoints(dummyPlotPoints, dummyChapters)
        view.findViewById<ChipGroup>(R.id.themes_chip_group)?.apply {
            removeAllViews()
            dummyThemes.forEach { theme ->
                addView(Chip(requireContext()).apply {
                    text = theme
                    isClickable = false
                })
            }
        }
        view.findViewById<ForeshadowingView>(R.id.foreshadowing_view)?.setData(dummyForeshadowing, dummyChapters)

        // Populate Learning
        view.findViewById<LinearLayout>(R.id.vocabulary_container)?.apply {
            removeAllViews()
            dummyVocabulary.forEach { (word, definition) ->
                addView(TextView(requireContext()).apply {
                    text = "• $word: $definition"
                    setPadding(0, 12, 0, 12)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                })
            }
        }

        showSection(0)
    }

    private fun showSection(position: Int) {
        overviewSection?.visibility = if (position == 0) View.VISIBLE else View.GONE
        emotionSection?.visibility = if (position == 1) View.VISIBLE else View.GONE
        storySection?.visibility = if (position == 2) View.VISIBLE else View.GONE
        learningSection?.visibility = if (position == 3) View.VISIBLE else View.GONE
    }
}

