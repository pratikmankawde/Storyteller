package com.dramebaz.app.ui.test.library.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.library.LibrarySection
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment

/**
 * Design 3: Horizontal Sections - Netflix/Spotify style.
 * Vertical scroll with horizontal book carousels per section.
 */
class HorizontalSectionsFragment : BaseLibraryDesignFragment() {

    private var sectionsContainer: LinearLayout? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_horizontal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sectionsContainer = view.findViewById(R.id.sections_container)
        emptyState = view.findViewById(R.id.empty_state)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        sectionsContainer?.removeAllViews()
        
        if (books.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            return
        }
        emptyState?.visibility = View.GONE

        val sectionData = mapOf(
            LibrarySection.FAVOURITES to books.filter { it.isFavorite },
            LibrarySection.LAST_READ to books.filter { it.lastReadAt != null && !it.isFinished }.sortedByDescending { it.lastReadAt },
            LibrarySection.FINISHED to books.filter { it.isFinished }.sortedByDescending { it.lastReadAt },
            LibrarySection.RECENTLY_ADDED to books.sortedByDescending { it.createdAt },
            LibrarySection.UNREAD to books.filter { it.lastReadAt == null && !it.isFinished }
        )

        sectionData.forEach { (section, sectionBooks) ->
            if (sectionBooks.isNotEmpty()) {
                addSection(section, sectionBooks)
            }
        }
    }

    private fun addSection(section: LibrarySection, books: List<Book>) {
        val sectionView = LayoutInflater.from(context).inflate(R.layout.item_horizontal_section, sectionsContainer, false)
        
        sectionView.findViewById<ImageView>(R.id.section_icon).setImageResource(section.iconResId)
        sectionView.findViewById<TextView>(R.id.section_title).setText(section.titleResId)
        sectionView.findViewById<TextView>(R.id.section_count).text = books.size.toString()

        val recycler = sectionView.findViewById<RecyclerView>(R.id.books_recycler)
        recycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recycler.adapter = HorizontalBookAdapter(books)

        sectionsContainer?.addView(sectionView)
    }

    private inner class HorizontalBookAdapter(private val books: List<Book>) : 
        RecyclerView.Adapter<HorizontalBookAdapter.BookViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_horizontal, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            holder.bind(books[position])
        }

        override fun getItemCount() = books.size

        inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)

            fun bind(book: Book) {
                title.text = book.title
                // COVER-SLIDESHOW: Enable slideshow for books being analyzed without genre
                BookCoverLoader.loadCoverInto(cover, book, enableSlideshow = true)
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
            }
        }
    }
}

