package com.dramebaz.app.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.common.ShimmerProgressBar
import com.dramebaz.app.ui.common.ShimmerView

/**
 * Adapter for displaying books in a grid.
 *
 * UI-004: Updated to support shared element transitions by passing the cover ImageView
 * to the click callback for use with FragmentNavigator.Extras.
 * AUTO-ANALYSIS: Updated to show analysis progress and inactive state.
 */
class BookAdapter(
    private val onBookClick: (Book, ImageView) -> Unit,
    private val onBookLongClick: (Book) -> Boolean = { false }
) : ListAdapter<Book, BookAdapter.VH>(Diff) {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.book_cover)
        val title: TextView = itemView.findViewById(R.id.book_title)
        val metadata: TextView = itemView.findViewById(R.id.book_metadata)
        // AUTO-ANALYSIS: Progress tracking views
        val analysisStatus: TextView = itemView.findViewById(R.id.analysis_status)
        val analysisProgress: ShimmerProgressBar = itemView.findViewById(R.id.analysis_progress)
        val shimmerOverlay: ShimmerView = itemView.findViewById(R.id.shimmer_overlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = getItem(position)
        holder.title.text = b.title

        // Set metadata (format info)
        holder.metadata.text = b.format.uppercase()

        // AUTO-ANALYSIS: Bind analysis progress and state
        bindAnalysisState(holder, b)

        // COVER-001: Load appropriate book cover (embedded or placeholder)
        // COVER-SLIDESHOW: Enable slideshow for books being analyzed without genre
        BookCoverLoader.loadCoverInto(holder.cover, b, enableSlideshow = true)

        // UI-004: Set unique transitionName for shared element transitions
        val transitionName = "book_cover_${b.id}"
        ViewCompat.setTransitionName(holder.cover, transitionName)

        // UI-004: Pass the cover ImageView to click handler for shared element transition
        holder.itemView.setOnClickListener { onBookClick(b, holder.cover) }
        holder.itemView.setOnLongClickListener { onBookLongClick(b) }

        // Add animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(position * 50L)
            .start()
    }

    /**
     * AUTO-ANALYSIS: Bind analysis progress bar and shimmer overlay based on book state.
     * Uses shimmer effect for processing books instead of lock icon.
     */
    private fun bindAnalysisState(holder: VH, book: Book) {
        val state = book.getAnalysisState()

        when (state) {
            AnalysisState.PENDING -> {
                // Book is queued but not started - show shimmer overlay
                holder.analysisProgress.visibility = View.GONE
                holder.analysisStatus.visibility = View.VISIBLE
                holder.analysisStatus.text = "Waiting for analysis..."
                holder.shimmerOverlay.visibility = View.VISIBLE
                holder.cover.alpha = 1f
            }
            AnalysisState.ANALYZING -> {
                // Book is being analyzed - show progress and shimmer
                holder.analysisProgress.visibility = View.VISIBLE
                holder.analysisProgress.progress = book.analysisProgress / 100f // Convert 0-100 to 0.0-1.0
                holder.analysisStatus.visibility = View.VISIBLE
                holder.analysisStatus.text = book.analysisMessage
                    ?: "Analyzing ${book.analyzedChapterCount}/${book.totalChaptersToAnalyze}..."
                // Show shimmer overlay if no chapters analyzed yet
                if (book.analyzedChapterCount == 0) {
                    holder.shimmerOverlay.visibility = View.VISIBLE
                    holder.cover.alpha = 1f
                } else {
                    // Partially active - can read analyzed chapters
                    holder.shimmerOverlay.visibility = View.GONE
                    holder.cover.alpha = 1f
                }
            }
            AnalysisState.COMPLETED -> {
                // Book is fully active - hide all progress/overlay
                holder.analysisProgress.visibility = View.GONE
                holder.analysisStatus.visibility = View.GONE
                holder.shimmerOverlay.visibility = View.GONE
                holder.cover.alpha = 1f
            }
            AnalysisState.FAILED -> {
                // Analysis failed - show error status
                holder.analysisProgress.visibility = View.GONE
                holder.analysisStatus.visibility = View.VISIBLE
                holder.analysisStatus.text = "Analysis failed - tap to retry"
                holder.shimmerOverlay.visibility = View.GONE
                holder.cover.alpha = 0.8f
            }
            AnalysisState.CANCELLED -> {
                // Analysis cancelled - similar to failed
                holder.analysisProgress.visibility = View.GONE
                holder.analysisStatus.visibility = View.VISIBLE
                holder.analysisStatus.text = "Analysis cancelled"
                holder.shimmerOverlay.visibility = View.GONE
                holder.cover.alpha = 0.8f
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(a: Book, b: Book) = a.id == b.id
        override fun areContentsTheSame(a: Book, b: Book) = a == b
    }
}
