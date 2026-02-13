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
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment

/**
 * Design 8: Minimalist/Clean Style - Simple list with minimal decoration.
 * Focus on typography and whitespace, no visual clutter.
 */
class MinimalistFragment : BaseLibraryDesignFragment() {

    private var adapter: MinimalAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_minimal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = MinimalAdapter(
            onBookClick = { book, cover -> onBookClick(book, cover) },
            onBookLongClick = { book -> onBookLongClick(book) }
        )

        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        // Sort alphabetically for clean minimal look
        val sortedBooks = books.sortedBy { it.title.lowercase() }
        adapter?.submitList(ArrayList(sortedBooks))
        emptyState?.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recyclerView?.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class MinimalAdapter(
        private val onBookClick: (Book, ImageView) -> Unit,
        private val onBookLongClick: (Book) -> Boolean
    ) : ListAdapter<Book, MinimalAdapter.MinimalViewHolder>(MinimalBookDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MinimalViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_minimal, parent, false)
            return MinimalViewHolder(view)
        }

        override fun onBindViewHolder(holder: MinimalViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class MinimalViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val meta: TextView = itemView.findViewById(R.id.book_meta)
            private val favoriteIndicator: View = itemView.findViewById(R.id.favorite_indicator)

            fun bind(book: Book) {
                title.text = book.title
                meta.text = book.format.uppercase()
                BookCoverLoader.loadCoverInto(cover, book)
                
                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE
                
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }

    }
}

private object MinimalBookDiff : DiffUtil.ItemCallback<Book>() {
    override fun areItemsTheSame(old: Book, new: Book) = old.id == new.id
    override fun areContentsTheSame(old: Book, new: Book) = old == new
}

