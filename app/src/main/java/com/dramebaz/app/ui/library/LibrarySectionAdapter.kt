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
 * LIBRARY-001: Library sections with collapsible headers.
 */
enum class LibrarySection(val titleResId: Int, val iconResId: Int) {
    FAVOURITES(R.string.section_favourites, android.R.drawable.star_on),
    LAST_READ(R.string.section_last_read, android.R.drawable.ic_menu_recent_history),
    FINISHED(R.string.section_finished, android.R.drawable.checkbox_on_background),
    RECENTLY_ADDED(R.string.section_recently_added, android.R.drawable.ic_menu_add),
    UNREAD(R.string.section_unread, android.R.drawable.ic_menu_gallery)
}

/**
 * LIBRARY-001: Items for the sectioned library list.
 */
sealed class LibraryItem {
    data class Header(
        val section: LibrarySection,
        val count: Int,
        val isExpanded: Boolean = true
    ) : LibraryItem()
    
    data class BookItem(
        val book: Book,
        val section: LibrarySection
    ) : LibraryItem()
}

/**
 * LIBRARY-001: Adapter for sectioned library with collapsible headers.
 */
class LibrarySectionAdapter(
    private val onBookClick: (Book, ImageView) -> Unit,
    private val onBookLongClick: (Book) -> Boolean = { false },
    private val onSectionToggle: (LibrarySection) -> Unit
) : ListAdapter<LibraryItem, RecyclerView.ViewHolder>(LibraryItemDiff) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_BOOK = 1
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LibraryItem.Header -> VIEW_TYPE_HEADER
        is LibraryItem.BookItem -> VIEW_TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_library_section_header, parent, false)
            )
            else -> BookViewHolder(
                inflater.inflate(R.layout.item_book_card, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LibraryItem.Header -> (holder as HeaderViewHolder).bind(item)
            is LibraryItem.BookItem -> (holder as BookViewHolder).bind(item, position)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.section_icon)
        private val title: TextView = itemView.findViewById(R.id.section_title)
        private val count: TextView = itemView.findViewById(R.id.section_count)
        private val arrow: ImageView = itemView.findViewById(R.id.section_arrow)

        fun bind(header: LibraryItem.Header) {
            icon.setImageResource(header.section.iconResId)
            title.setText(header.section.titleResId)
            count.text = header.count.toString()
            count.visibility = if (header.count > 0) View.VISIBLE else View.GONE
            
            // Rotate arrow based on expanded state
            arrow.rotation = if (header.isExpanded) 180f else 0f
            
            itemView.setOnClickListener {
                onSectionToggle(header.section)
            }
        }
    }

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.book_cover)
        val title: TextView = itemView.findViewById(R.id.book_title)
        val metadata: TextView = itemView.findViewById(R.id.book_metadata)
        val analysisStatus: TextView = itemView.findViewById(R.id.analysis_status)
        val analysisProgress: ShimmerProgressBar = itemView.findViewById(R.id.analysis_progress)
        val shimmerOverlay: ShimmerView = itemView.findViewById(R.id.shimmer_overlay)

        fun bind(item: LibraryItem.BookItem, position: Int) {
            val book = item.book
            title.text = book.title
            metadata.text = book.format.uppercase()
            
            bindAnalysisState(book)
            
            val transitionName = "book_cover_${book.id}"
            ViewCompat.setTransitionName(cover, transitionName)
            
            itemView.setOnClickListener { onBookClick(book, cover) }
            itemView.setOnLongClickListener { onBookLongClick(book) }
            
            // Add animation
            itemView.alpha = 0f
            itemView.animate().alpha(1f).setDuration(300).setStartDelay(position * 30L).start()
        }
        
        private fun bindAnalysisState(book: Book) {
            when (book.getAnalysisState()) {
                AnalysisState.PENDING -> {
                    analysisProgress.visibility = View.GONE
                    analysisStatus.visibility = View.VISIBLE
                    analysisStatus.text = "Waiting for analysis..."
                    shimmerOverlay.visibility = View.VISIBLE
                    cover.alpha = 1f
                }
                AnalysisState.ANALYZING -> {
                    analysisProgress.visibility = View.VISIBLE
                    analysisProgress.progress = book.analysisProgress / 100f // Convert 0-100 to 0.0-1.0
                    analysisStatus.visibility = View.VISIBLE
                    analysisStatus.text = book.analysisMessage
                        ?: "Analyzing ${book.analyzedChapterCount}/${book.totalChaptersToAnalyze}..."
                    shimmerOverlay.visibility = if (book.analyzedChapterCount == 0) View.VISIBLE else View.GONE
                    cover.alpha = 1f
                }
                AnalysisState.COMPLETED -> {
                    analysisProgress.visibility = View.GONE
                    analysisStatus.visibility = View.GONE
                    shimmerOverlay.visibility = View.GONE
                    cover.alpha = 1f
                }
                AnalysisState.FAILED, AnalysisState.CANCELLED -> {
                    analysisProgress.visibility = View.GONE
                    analysisStatus.visibility = View.VISIBLE
                    analysisStatus.text = if (book.getAnalysisState() == AnalysisState.FAILED)
                        "Analysis failed" else "Analysis cancelled"
                    shimmerOverlay.visibility = View.GONE
                    cover.alpha = 0.8f
                }
            }
        }
    }
}

/**
 * LIBRARY-001: DiffUtil for library items.
 */
object LibraryItemDiff : DiffUtil.ItemCallback<LibraryItem>() {
    override fun areItemsTheSame(oldItem: LibraryItem, newItem: LibraryItem): Boolean {
        return when {
            oldItem is LibraryItem.Header && newItem is LibraryItem.Header ->
                oldItem.section == newItem.section
            oldItem is LibraryItem.BookItem && newItem is LibraryItem.BookItem ->
                oldItem.book.id == newItem.book.id && oldItem.section == newItem.section
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: LibraryItem, newItem: LibraryItem): Boolean {
        return oldItem == newItem
    }
}

