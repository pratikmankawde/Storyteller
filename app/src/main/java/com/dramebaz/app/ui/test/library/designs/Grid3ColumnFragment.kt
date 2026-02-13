package com.dramebaz.app.ui.test.library.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.library.LibrarySection
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment
import com.dramebaz.app.ui.test.library.LibraryItem

/**
 * Design 2: Grid 3-Column Compact layout.
 * More compact cards with minimal info, shows more books at once.
 */
class Grid3ColumnFragment : BaseLibraryDesignFragment() {

    private var adapter: CompactGridAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = CompactGridAdapter(
            onBookClick = { book, cover -> onBookClick(book, cover) },
            onBookLongClick = { book -> onBookLongClick(book) },
            onSectionToggle = { section -> onSectionToggle(section) }
        )

        // 3-column grid with span for headers
        val layoutManager = GridLayoutManager(context, 3)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter?.getItemViewType(position)) {
                    VIEW_TYPE_HEADER -> 3 // Full width
                    else -> 1 // Single column
                }
            }
        }

        recyclerView?.layoutManager = layoutManager
        recyclerView?.adapter = adapter

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        val items = buildSectionedList(books)
        adapter?.submitList(ArrayList(items))
        emptyState?.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recyclerView?.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_BOOK = 1
    }

    private inner class CompactGridAdapter(
        private val onBookClick: (Book, ImageView) -> Unit,
        private val onBookLongClick: (Book) -> Boolean,
        private val onSectionToggle: (LibrarySection) -> Unit
    ) : ListAdapter<LibraryItem, RecyclerView.ViewHolder>(LibraryItemDiff) {

        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is LibraryItem.Header -> VIEW_TYPE_HEADER
            is LibraryItem.BookItem -> VIEW_TYPE_BOOK
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_library_section_header, parent, false))
                else -> CompactBookViewHolder(inflater.inflate(R.layout.item_book_grid_compact, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is LibraryItem.Header -> (holder as HeaderViewHolder).bind(item)
                is LibraryItem.BookItem -> (holder as CompactBookViewHolder).bind(item)
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
                arrow.rotation = if (header.isExpanded) 180f else 0f
                itemView.setOnClickListener { onSectionToggle(header.section) }
            }
        }

        inner class CompactBookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)

            fun bind(item: LibraryItem.BookItem) {
                val book = item.book
                title.text = book.title
                BookCoverLoader.loadCoverInto(cover, book)
                favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }
    }

    object LibraryItemDiff : DiffUtil.ItemCallback<LibraryItem>() {
        override fun areItemsTheSame(old: LibraryItem, new: LibraryItem) = when {
            old is LibraryItem.Header && new is LibraryItem.Header -> old.section == new.section
            old is LibraryItem.BookItem && new is LibraryItem.BookItem -> old.book.id == new.book.id && old.section == new.section
            else -> false
        }
        override fun areContentsTheSame(old: LibraryItem, new: LibraryItem) = old == new
    }
}

