package com.dramebaz.app.ui.test.library.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.AnalysisState
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment

/**
 * Design 6: Glassmorphism Style - Frosted glass cards with gradient background.
 * Modern iOS/macOS inspired translucent design.
 */
class GlassmorphismFragment : BaseLibraryDesignFragment() {

    private var adapter: GlassAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_glass, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = GlassAdapter(
            onBookClick = { book, cover -> onBookClick(book, cover) },
            onBookLongClick = { book -> onBookLongClick(book) }
        )

        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        adapter?.submitList(ArrayList(books))
        emptyState?.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recyclerView?.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class GlassAdapter(
        private val onBookClick: (Book, ImageView) -> Unit,
        private val onBookLongClick: (Book) -> Boolean
    ) : ListAdapter<Book, GlassAdapter.GlassViewHolder>(GlassBookDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GlassViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_glass, parent, false)
            return GlassViewHolder(view)
        }

        override fun onBindViewHolder(holder: GlassViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class GlassViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val format: TextView = itemView.findViewById(R.id.book_format)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)
            private val analysisStatus: TextView = itemView.findViewById(R.id.analysis_status)

            fun bind(book: Book) {
                title.text = book.title
                format.text = book.format.uppercase()
                BookCoverLoader.loadCoverInto(cover, book)
                
                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE
                
                // Show analysis status
                when (book.getAnalysisState()) {
                    AnalysisState.ANALYZING -> {
                        analysisStatus.visibility = View.VISIBLE
                        analysisStatus.text = "Analyzing ${book.analyzedChapterCount}/${book.totalChaptersToAnalyze}..."
                    }
                    AnalysisState.PENDING -> {
                        analysisStatus.visibility = View.VISIBLE
                        analysisStatus.text = "Pending analysis"
                    }
                    else -> analysisStatus.visibility = View.GONE
                }
                
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }

    }
}

private object GlassBookDiff : DiffUtil.ItemCallback<Book>() {
    override fun areItemsTheSame(old: Book, new: Book) = old.id == new.id
    override fun areContentsTheSame(old: Book, new: Book) = old == new
}

