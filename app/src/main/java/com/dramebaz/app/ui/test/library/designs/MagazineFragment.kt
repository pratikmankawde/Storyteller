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
 * Design 9: Magazine-Style Large Cards - Full-width hero cards with large imagery.
 * Editorial/news-style layout with prominent visuals and typography.
 */
class MagazineFragment : BaseLibraryDesignFragment() {

    private var adapter: MagazineAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = MagazineAdapter(
            onBookClick = { book, cover -> onBookClick(book, cover) },
            onBookLongClick = { book -> onBookLongClick(book) }
        )

        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        // Sort by most recently added for magazine feel
        val sortedBooks = books.sortedByDescending { it.createdAt }
        adapter?.submitList(ArrayList(sortedBooks))
        emptyState?.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recyclerView?.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class MagazineAdapter(
        private val onBookClick: (Book, ImageView) -> Unit,
        private val onBookLongClick: (Book) -> Boolean
    ) : ListAdapter<Book, MagazineAdapter.MagazineViewHolder>(MagazineBookDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MagazineViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_magazine, parent, false)
            return MagazineViewHolder(view)
        }

        override fun onBindViewHolder(holder: MagazineViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class MagazineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val format: TextView = itemView.findViewById(R.id.book_format)
            private val genre: TextView = itemView.findViewById(R.id.book_genre)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)
            private val progressContainer: View = itemView.findViewById(R.id.progress_container)
            private val readingProgress: ProgressBar = itemView.findViewById(R.id.reading_progress)
            private val progressText: TextView = itemView.findViewById(R.id.progress_text)

            fun bind(book: Book) {
                title.text = book.title
                format.text = book.format.uppercase()
                genre.text = book.detectedGenre ?: "Unknown genre"
                BookCoverLoader.loadCoverInto(cover, book)
                
                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE
                
                // Show reading progress
                val progressPercent = (book.readingProgress * 100).toInt()
                if (progressPercent > 0 && !book.isFinished) {
                    progressContainer.visibility = View.VISIBLE
                    readingProgress.progress = progressPercent
                    progressText.text = "${progressPercent}%"
                } else {
                    progressContainer.visibility = View.GONE
                }
                
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }

    }
}

private object MagazineBookDiff : DiffUtil.ItemCallback<Book>() {
    override fun areItemsTheSame(old: Book, new: Book) = old.id == new.id
    override fun areContentsTheSame(old: Book, new: Book) = old == new
}

