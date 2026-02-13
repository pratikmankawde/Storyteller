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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Design 9: Magazine / Editorial
 * Print magazine-inspired layout with large typography and visual hierarchy.
 */
class MagazineInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_magazine, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hero header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle
        view.findViewById<TextView>(R.id.book_author)?.text = "by $dummyBookAuthor"

        // Feature stats with large typography
        view.findViewById<TextView>(R.id.feature_stat_value)?.text = dummyChapters.toString()
        view.findViewById<TextView>(R.id.feature_stat_label)?.text = "Chapters"

        // Secondary stats row
        view.findViewById<TextView>(R.id.stat_characters)?.text = "$dummyCharacters\nCharacters"
        view.findViewById<TextView>(R.id.stat_dialogs)?.text = "$dummyDialogs\nDialogs"
        view.findViewById<TextView>(R.id.stat_reading_level)?.text = "${dummyReadingLevel.gradeDescription}\nReading Level"

        // Emotional Journey section
        view.findViewById<TextView>(R.id.section_emotional_title)?.text = "THE EMOTIONAL JOURNEY"
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)

        // Sentiment section
        view.findViewById<TextView>(R.id.sentiment_headline)?.text = "Overall Tone: ${dummySentiment.dominantTone}"
        view.findViewById<SentimentDistributionView>(R.id.sentiment_view)?.setDistribution(dummySentiment)

        // Story arc section
        view.findViewById<TextView>(R.id.section_story_title)?.text = "STORY STRUCTURE"
        view.findViewById<PlotOutlineView>(R.id.plot_outline_view)?.setPlotPoints(dummyPlotPoints, dummyChapters)

        // Themes as pull quote
        view.findViewById<TextView>(R.id.themes_quote)?.text = "\"${dummyThemes.take(4).joinToString(" · ")}\""

        // Vocabulary builder section
        view.findViewById<TextView>(R.id.section_vocab_title)?.text = "VOCABULARY BUILDER"
        view.findViewById<LinearLayout>(R.id.vocabulary_container)?.apply {
            removeAllViews()
            dummyVocabulary.take(4).forEach { (word, definition) ->
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 16, 0, 16)
                    addView(TextView(requireContext()).apply {
                        text = word.uppercase()
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_primary))
                        textSize = 12f
                        paint.isFakeBoldText = true
                    })
                    addView(TextView(requireContext()).apply {
                        text = definition
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                        textSize = 14f
                    })
                })
            }
        }
    }
}

