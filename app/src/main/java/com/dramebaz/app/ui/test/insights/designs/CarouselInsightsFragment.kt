package com.dramebaz.app.ui.test.insights.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dramebaz.app.R
import com.dramebaz.app.ui.insights.EmotionalArcView
import com.dramebaz.app.ui.insights.PlotOutlineView
import com.dramebaz.app.ui.insights.SentimentDistributionView
import com.dramebaz.app.ui.test.insights.BaseInsightsDesignFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Design 5: Carousel cards
 * Horizontally swipeable insight cards in a carousel.
 */
class CarouselInsightsFragment : BaseInsightsDesignFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_insights_design_carousel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Book header
        view.findViewById<TextView>(R.id.book_title)?.text = dummyBookTitle
        view.findViewById<TextView>(R.id.book_author)?.text = dummyBookAuthor

        // Setup carousel ViewPager2
        val carouselPager = view.findViewById<ViewPager2>(R.id.carousel_pager)
        val indicator = view.findViewById<TabLayout>(R.id.carousel_indicator)

        carouselPager?.apply {
            adapter = CarouselAdapter()
            offscreenPageLimit = 1

            // Add page transformer for carousel effect
            setPageTransformer { page, position ->
                val scale = 1 - 0.1f * kotlin.math.abs(position)
                page.scaleY = scale
            }
        }

        // Connect indicator to ViewPager2
        if (carouselPager != null && indicator != null) {
            TabLayoutMediator(indicator, carouselPager) { _, _ -> }.attach()
        }
    }

    private inner class CarouselAdapter : RecyclerView.Adapter<CarouselAdapter.CardViewHolder>() {

        private val cardTypes = listOf("emotional", "sentiment", "plot", "themes", "vocabulary")

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_insights_carousel_card, parent, false)
            return CardViewHolder(view)
        }

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            holder.bind(cardTypes[position])
        }

        override fun getItemCount() = cardTypes.size

        inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title = itemView.findViewById<TextView>(R.id.carousel_card_title)
            private val emotionalView = itemView.findViewById<EmotionalArcView>(R.id.carousel_emotional_arc)
            private val sentimentView = itemView.findViewById<SentimentDistributionView>(R.id.carousel_sentiment_view)
            private val plotView = itemView.findViewById<PlotOutlineView>(R.id.carousel_plot_view)
            private val chipGroup = itemView.findViewById<ChipGroup>(R.id.carousel_chip_group)
            private val vocabContainer = itemView.findViewById<LinearLayout>(R.id.carousel_vocabulary_container)

            fun bind(type: String) {
                hideAll()
                when (type) {
                    "emotional" -> {
                        title?.text = "Emotional Journey"
                        emotionalView?.visibility = View.VISIBLE
                        emotionalView?.setData(dummyEmotionalArc)
                    }
                    "sentiment" -> {
                        title?.text = "Sentiment Analysis"
                        sentimentView?.visibility = View.VISIBLE
                        sentimentView?.setDistribution(dummySentiment)
                    }
                    "plot" -> {
                        title?.text = "Story Arc"
                        plotView?.visibility = View.VISIBLE
                        plotView?.setPlotPoints(dummyPlotPoints, dummyChapters)
                    }
                    "themes" -> {
                        title?.text = "Major Themes"
                        chipGroup?.visibility = View.VISIBLE
                        chipGroup?.removeAllViews()
                        dummyThemes.forEach { theme ->
                            chipGroup?.addView(Chip(requireContext()).apply { text = theme })
                        }
                    }
                    "vocabulary" -> {
                        title?.text = "Vocabulary Builder"
                        vocabContainer?.visibility = View.VISIBLE
                        vocabContainer?.removeAllViews()
                        dummyVocabulary.take(5).forEach { (word, def) ->
                            vocabContainer?.addView(TextView(requireContext()).apply {
                                text = "• $word: $def"
                                setPadding(0, 8, 0, 8)
                            })
                        }
                    }
                }
            }

            private fun hideAll() {
                emotionalView?.visibility = View.GONE
                sentimentView?.visibility = View.GONE
                plotView?.visibility = View.GONE
                chipGroup?.visibility = View.GONE
                vocabContainer?.visibility = View.GONE
            }
        }
    }
}

