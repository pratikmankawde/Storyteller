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
import com.dramebaz.app.ui.insights.SentimentDistributionView
import com.dramebaz.app.ui.test.insights.BaseInsightsDesignFragment

/**
 * Design 8: Minimal / Clean
 * Simple, typography-focused design with lots of whitespace.
 */
class MinimalInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_minimal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Book header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle
        view.findViewById<TextView>(R.id.book_author)?.text = "by $dummyBookAuthor"

        // Statistics as simple text
        view.findViewById<TextView>(R.id.stats_text)?.text = 
            "$dummyChapters chapters · $dummyCharacters characters · $dummyDialogs dialogs"

        // Reading level as text
        view.findViewById<TextView>(R.id.reading_level_text)?.text =
            "${dummyReadingLevel.gradeDescription} reading level (Grade ${String.format("%.1f", dummyReadingLevel.gradeLevel)})"

        // Emotional Arc - minimal size
        view.findViewById<EmotionalArcView>(R.id.emotional_arc_view)?.setData(dummyEmotionalArc)

        // Sentiment as text
        view.findViewById<TextView>(R.id.sentiment_text)?.text = buildString {
            append("Tone: ${dummySentiment.dominantTone}\n")
            append("Positive ${dummySentiment.positive.toInt()}% · ")
            append("Neutral ${dummySentiment.neutral.toInt()}% · ")
            append("Negative ${dummySentiment.negative.toInt()}%")
        }

        // Themes as simple comma list
        view.findViewById<TextView>(R.id.themes_text)?.text = dummyThemes.joinToString(", ")

        // Key vocabulary
        view.findViewById<LinearLayout>(R.id.vocabulary_container)?.apply {
            removeAllViews()
            dummyVocabulary.take(4).forEach { (word, definition) ->
                addView(TextView(requireContext()).apply {
                    text = "$word — $definition"
                    setPadding(0, 16, 0, 16)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                    textSize = 14f
                })
            }
        }

        // Plot summary
        view.findViewById<TextView>(R.id.plot_summary)?.text = buildString {
            dummyPlotPoints.forEach { point ->
                append("${point.type.displayName}: ${point.description}\n\n")
            }
        }
    }
}

