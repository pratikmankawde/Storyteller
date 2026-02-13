package com.dramebaz.app.ui.test.library.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment

/**
 * Design 5: Masonry/Staggered Grid - Pinterest style.
 * Variable height cards in staggered grid layout.
 */
class MasonryFragment : BaseLibraryDesignFragment() {

    private var adapter: MasonryAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = MasonryAdapter(
            onBookClick = { book, cover -> onBookClick(book, cover) },
            onBookLongClick = { book -> onBookLongClick(book) }
        )

        // Staggered grid with 2 columns
        val layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
        recyclerView?.layoutManager = layoutManager
        recyclerView?.adapter = adapter

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        // Flat list for masonry - no sections, just all books
        adapter?.submitList(ArrayList(books))
        emptyState?.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recyclerView?.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class MasonryAdapter(
        private val onBookClick: (Book, ImageView) -> Unit,
        private val onBookLongClick: (Book) -> Boolean
    ) : ListAdapter<Book, MasonryAdapter.MasonryViewHolder>(MasonryBookDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MasonryViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_masonry, parent, false)
            return MasonryViewHolder(view)
        }

        override fun onBindViewHolder(holder: MasonryViewHolder, position: Int) {
            holder.bind(getItem(position), position)
        }

        inner class MasonryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val format: TextView = itemView.findViewById(R.id.book_format)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)

            fun bind(book: Book, position: Int) {
                title.text = book.title
                format.text = book.format.uppercase()
                // COVER-SLIDESHOW: Enable slideshow for books being analyzed without genre
                BookCoverLoader.loadCoverInto(cover, book, enableSlideshow = true)

                // Variable height based on position for staggered effect
                val heightMultiplier = if (position % 3 == 0) 1.3f else if (position % 3 == 1) 1.0f else 1.15f
                val baseHeight = (180 * resources.displayMetrics.density).toInt()
                cover.layoutParams.height = (baseHeight * heightMultiplier).toInt()
                
                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE
                
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }

    }
}

private object MasonryBookDiff : DiffUtil.ItemCallback<Book>() {
    override fun areItemsTheSame(old: Book, new: Book) = old.id == new.id
    override fun areContentsTheSame(old: Book, new: Book) = old == new
}

