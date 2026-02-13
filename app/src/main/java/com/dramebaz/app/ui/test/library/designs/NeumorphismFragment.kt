package com.dramebaz.app.ui.test.library.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment

/**
 * Design 7: Neumorphism Style - Soft UI with raised/pressed effects.
 * Clean, minimalist design with subtle shadows creating depth.
 */
class NeumorphismFragment : BaseLibraryDesignFragment() {

    private var adapter: NeumorphicAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_neumorphic, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = NeumorphicAdapter(
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

    private inner class NeumorphicAdapter(
        private val onBookClick: (Book, ImageView) -> Unit,
        private val onBookLongClick: (Book) -> Boolean
    ) : ListAdapter<Book, NeumorphicAdapter.NeumorphicViewHolder>(NeumorphicBookDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NeumorphicViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_neumorphic, parent, false)
            return NeumorphicViewHolder(view)
        }

        override fun onBindViewHolder(holder: NeumorphicViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class NeumorphicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val format: TextView = itemView.findViewById(R.id.book_format)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)
            private val progressContainer: View = itemView.findViewById(R.id.progress_container)
            private val readingProgress: ProgressBar = itemView.findViewById(R.id.reading_progress)

            fun bind(book: Book) {
                title.text = book.title
                format.text = "${book.format.uppercase()} • ${book.detectedGenre ?: "Unknown"}"
                // COVER-SLIDESHOW: Enable slideshow for books being analyzed without genre
                BookCoverLoader.loadCoverInto(cover, book, enableSlideshow = true)

                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE
                
                // Show reading progress if available
                val progressPercent = (book.readingProgress * 100).toInt()
                if (progressPercent > 0 && !book.isFinished) {
                    progressContainer.visibility = View.VISIBLE
                    readingProgress.progress = progressPercent
                } else {
                    progressContainer.visibility = View.GONE
                }
                
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }

    }
}

private object NeumorphicBookDiff : DiffUtil.ItemCallback<Book>() {
    override fun areItemsTheSame(old: Book, new: Book) = old.id == new.id
    override fun areContentsTheSame(old: Book, new: Book) = old == new
}

